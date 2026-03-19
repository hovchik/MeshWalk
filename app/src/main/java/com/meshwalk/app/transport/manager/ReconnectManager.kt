package com.meshwalk.app.transport.manager

import com.meshwalk.app.transport.api.TransportEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages automatic reconnection to peers when connections are lost.
 *
 * Strategy:
 * - When a peer disconnects, schedule a reconnection attempt with exponential backoff
 * - Reconnection restarts discovery/advertising to re-establish connections
 * - Backoff: 2s, 4s, 8s, 16s, 32s (capped)
 * - Max attempts per peer: 5 (then gives up until the peer is re-discovered)
 * - When a peer reconnects, its backoff state is cleared
 * - When mesh stops, all pending reconnect jobs are cancelled
 */
@Singleton
class ReconnectManager @Inject constructor() {

    companion object {
        private const val INITIAL_DELAY_MS = 2_000L
        private const val MAX_DELAY_MS = 32_000L
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_MULTIPLIER = 2.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tracks backoff state for each disconnected peer (keyed by nodeId). */
    private val peerBackoffState = ConcurrentHashMap<String, BackoffState>()

    /** Active reconnect jobs keyed by nodeId. */
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    private val _reconnectEvents = MutableSharedFlow<ReconnectEvent>(extraBufferCapacity = 32)
    val reconnectEvents: SharedFlow<ReconnectEvent> = _reconnectEvents

    /** Callback invoked to trigger discovery restart. Set by TransportManager. */
    var onReconnectAttempt: (suspend () -> Unit)? = null

    /**
     * Called when a peer disconnects. Schedules reconnection attempts.
     */
    fun onPeerDisconnected(nodeId: String) {
        // Don't schedule if already tracking this peer
        if (reconnectJobs.containsKey(nodeId)) {
            Timber.d("Reconnect already scheduled for ${nodeId.take(8)}")
            return
        }

        val state = peerBackoffState.getOrPut(nodeId) { BackoffState() }

        if (state.attemptCount >= MAX_ATTEMPTS) {
            Timber.d("Max reconnect attempts reached for ${nodeId.take(8)}, giving up")
            _reconnectEvents.tryEmit(ReconnectEvent.GaveUp(nodeId, MAX_ATTEMPTS))
            peerBackoffState.remove(nodeId)
            return
        }

        scheduleReconnect(nodeId, state)
    }

    /**
     * Called when a peer connects (or reconnects). Clears any pending backoff state.
     */
    fun onPeerConnected(nodeId: String) {
        val hadPending = reconnectJobs.remove(nodeId)?.let { job ->
            job.cancel()
            true
        } ?: false

        val hadBackoff = peerBackoffState.remove(nodeId) != null

        if (hadPending || hadBackoff) {
            Timber.d("Reconnected to ${nodeId.take(8)}, cleared backoff state")
            _reconnectEvents.tryEmit(ReconnectEvent.Reconnected(nodeId))
        }
    }

    /**
     * Stop all reconnection attempts. Called when mesh is stopped.
     */
    fun cancelAll() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        peerBackoffState.clear()
        Timber.d("All reconnect attempts cancelled")
    }

    /**
     * Get the number of peers currently awaiting reconnection.
     */
    fun pendingReconnectCount(): Int = reconnectJobs.size

    private fun scheduleReconnect(nodeId: String, state: BackoffState) {
        val delayMs = calculateDelay(state.attemptCount)
        state.attemptCount++

        Timber.d("Scheduling reconnect for ${nodeId.take(8)} in ${delayMs}ms (attempt ${state.attemptCount}/$MAX_ATTEMPTS)")
        _reconnectEvents.tryEmit(
            ReconnectEvent.Scheduled(nodeId, state.attemptCount, delayMs)
        )

        val job = scope.launch {
            delay(delayMs)

            Timber.d("Attempting reconnect for ${nodeId.take(8)} (attempt ${state.attemptCount}/$MAX_ATTEMPTS)")
            _reconnectEvents.tryEmit(
                ReconnectEvent.Attempting(nodeId, state.attemptCount)
            )

            try {
                onReconnectAttempt?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Reconnect attempt failed for ${nodeId.take(8)}")
            }

            // Remove the completed job
            reconnectJobs.remove(nodeId)

            // If still not connected after attempt, schedule next one
            if (peerBackoffState.containsKey(nodeId) && state.attemptCount < MAX_ATTEMPTS) {
                scheduleReconnect(nodeId, state)
            } else if (state.attemptCount >= MAX_ATTEMPTS) {
                Timber.d("Max reconnect attempts reached for ${nodeId.take(8)}")
                _reconnectEvents.tryEmit(ReconnectEvent.GaveUp(nodeId, MAX_ATTEMPTS))
                peerBackoffState.remove(nodeId)
            }
        }

        reconnectJobs[nodeId] = job
    }

    private fun calculateDelay(attemptCount: Int): Long {
        val multiplier = Math.pow(BACKOFF_MULTIPLIER, attemptCount.toDouble())
        // Clamp before multiplying to avoid Long overflow
        if (multiplier > MAX_DELAY_MS.toDouble() / INITIAL_DELAY_MS) {
            return MAX_DELAY_MS
        }
        return (INITIAL_DELAY_MS * multiplier.toLong()).coerceAtMost(MAX_DELAY_MS)
    }

    private data class BackoffState(
        var attemptCount: Int = 0
    )
}

/**
 * Events emitted by the ReconnectManager for observability.
 */
sealed interface ReconnectEvent {
    /** A reconnect attempt has been scheduled. */
    data class Scheduled(val nodeId: String, val attempt: Int, val delayMs: Long) : ReconnectEvent

    /** A reconnect attempt is being made now. */
    data class Attempting(val nodeId: String, val attempt: Int) : ReconnectEvent

    /** The peer has reconnected successfully. */
    data class Reconnected(val nodeId: String) : ReconnectEvent

    /** All reconnect attempts exhausted for this peer. */
    data class GaveUp(val nodeId: String, val totalAttempts: Int) : ReconnectEvent
}
