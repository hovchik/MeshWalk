package com.meshwalk.app.routing.queue

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles queued messages by periodically attempting to reconnect and resend
 * using Fibonacci-sequence delays between attempts.
 *
 * Fibonacci delay sequence (seconds): 1, 1, 2, 3, 5, 8, 13, 21, 34, 55
 *
 * When messages are queued (no peers available to deliver):
 * 1. Triggers a reconnect attempt (discovery/advertising restart)
 * 2. Attempts to flush the offline queue to any available peers
 * 3. If messages remain, schedules the next attempt at the next Fibonacci delay
 * 4. Stops when the queue is empty or max attempts are exhausted
 *
 * This complements the per-peer ReconnectManager by focusing on draining
 * the message queue rather than tracking individual peer connections.
 */
@Singleton
class QueuedMessageHandler @Inject constructor(
    private val offlineQueue: OfflineQueue
) {

    companion object {
        private const val MAX_ATTEMPTS = 10
        private const val BASE_DELAY_MS = 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null
    private var isFlushActive = false

    private val _events = MutableSharedFlow<QueueFlushEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<QueueFlushEvent> = _events

    /** Callback to restart discovery/advertising. Set by the routing engine. */
    var onReconnectForQueue: (suspend () -> Unit)? = null

    /** Callback to attempt flushing queued messages. Set by the routing engine. */
    var onFlushQueue: (suspend () -> Int)? = null

    /**
     * Called when a message is queued because no peers are available.
     * Starts the Fibonacci retry cycle if not already running.
     */
    fun onMessageQueued() {
        if (flushJob?.isFlushActive == true) {
            Timber.d("Queue flush cycle already running")
            return
        }
        startFlushCycle()
    }

    /**
     * Called when a peer connects. Triggers an immediate flush attempt
     * and resets the Fibonacci cycle since connectivity changed.
     */
    fun onConnectivityRestored() {
        // Cancel current cycle — connectivity change may resolve everything
        flushJob?.cancel()
        flushJob = null

        if (offlineQueue.size() == 0) return

        Timber.d("Connectivity restored, attempting immediate queue flush")
        scope.launch {
            _events.emit(QueueFlushEvent.ConnectivityRestored(offlineQueue.size()))
            val remaining = tryFlush()
            if (remaining > 0) {
                // Still messages left — restart cycle from beginning
                startFlushCycle()
            }
        }
    }

    /**
     * Stop all pending flush attempts. Called when mesh is stopped.
     */
    fun cancel() {
        flushJob?.cancel()
        flushJob = null
        isFlushActive = false
        Timber.d("Queued message handler cancelled")
    }

    private fun startFlushCycle() {
        isFlushActive = true
        var attemptIndex = 0

        flushJob = scope.launch {
            while (isFlushActive && attemptIndex < MAX_ATTEMPTS && offlineQueue.size() > 0) {
                val delayMs = fibonacciDelay(attemptIndex)
                val queueSize = offlineQueue.size()

                Timber.d("Queue flush attempt ${attemptIndex + 1}/$MAX_ATTEMPTS in ${delayMs}ms ($queueSize messages queued)")
                _events.emit(
                    QueueFlushEvent.FlushScheduled(
                        attempt = attemptIndex + 1,
                        maxAttempts = MAX_ATTEMPTS,
                        delayMs = delayMs,
                        queuedCount = queueSize
                    )
                )

                delay(delayMs)

                // Step 1: Trigger reconnect to find peers
                Timber.d("Queue flush: triggering reconnect (attempt ${attemptIndex + 1})")
                _events.emit(
                    QueueFlushEvent.ReconnectAttempting(attemptIndex + 1, offlineQueue.size())
                )
                try {
                    onReconnectForQueue?.invoke()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Queue flush reconnect failed")
                }

                // Brief pause for discovery to find peers
                delay(2_000)

                // Step 2: Attempt to flush queued messages
                val remaining = tryFlush()

                _events.emit(
                    QueueFlushEvent.FlushCompleted(
                        attempt = attemptIndex + 1,
                        sentCount = queueSize - remaining,
                        remainingCount = remaining
                    )
                )

                if (remaining == 0) {
                    Timber.d("Queue fully drained after ${attemptIndex + 1} attempts")
                    _events.emit(QueueFlushEvent.QueueDrained(attemptIndex + 1))
                    break
                }

                attemptIndex++
            }

            if (offlineQueue.size() > 0 && attemptIndex >= MAX_ATTEMPTS) {
                Timber.w("Queue flush gave up after $MAX_ATTEMPTS attempts, ${offlineQueue.size()} messages remaining")
                _events.emit(
                    QueueFlushEvent.GaveUp(MAX_ATTEMPTS, offlineQueue.size())
                )
            }

            isFlushActive = false
            flushJob = null
        }
    }

    private suspend fun tryFlush(): Int {
        return try {
            onFlushQueue?.invoke() ?: offlineQueue.size()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Queue flush attempt failed")
            offlineQueue.size()
        }
    }

    /**
     * Compute the Fibonacci delay for a given attempt index.
     * Sequence: 1, 1, 2, 3, 5, 8, 13, 21, 34, 55 (in seconds)
     */
    private fun fibonacciDelay(attemptIndex: Int): Long {
        val fibSeconds = fibonacci(attemptIndex)
        return fibSeconds * BASE_DELAY_MS
    }

    private fun fibonacci(n: Int): Long {
        if (n <= 1) return 1L
        var a = 1L
        var b = 1L
        for (i in 2..n) {
            val next = a + b
            a = b
            b = next
        }
        return b
    }
}

/**
 * Events emitted by the QueuedMessageHandler for observability.
 */
sealed interface QueueFlushEvent {
    /** A flush attempt has been scheduled. */
    data class FlushScheduled(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val queuedCount: Int
    ) : QueueFlushEvent

    /** A reconnect is being attempted to find peers for queued messages. */
    data class ReconnectAttempting(val attempt: Int, val queuedCount: Int) : QueueFlushEvent

    /** A flush attempt completed. */
    data class FlushCompleted(
        val attempt: Int,
        val sentCount: Int,
        val remainingCount: Int
    ) : QueueFlushEvent

    /** Connectivity was restored, triggering an immediate flush. */
    data class ConnectivityRestored(val queuedCount: Int) : QueueFlushEvent

    /** All queued messages were successfully sent. */
    data class QueueDrained(val totalAttempts: Int) : QueueFlushEvent

    /** All flush attempts exhausted, messages still remain. */
    data class GaveUp(val totalAttempts: Int, val remainingCount: Int) : QueueFlushEvent
}
