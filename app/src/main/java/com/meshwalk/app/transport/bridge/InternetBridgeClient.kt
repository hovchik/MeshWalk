package com.meshwalk.app.transport.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.meshwalk.app.domain.model.MeshPacket
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.routing.queue.OfflineQueue
import com.meshwalk.app.transport.manager.TransportManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional opportunistic internet bridge.
 *
 * When enabled (Settings) and this node has internet, it turns "mesh-only"
 * into "mesh-first": undeliverable packets sitting in the offline queue are
 * POSTed to a relay server, and packets the server holds for this node are
 * pulled down and re-injected into the local routing/transport stack exactly
 * as if they'd arrived over BLE/Nearby.
 *
 * The relay only ever sees the same encrypted MeshPackets that travel the
 * mesh — it cannot read message contents. It's a store-and-forward postbox,
 * not a trusted party.
 *
 * Wire protocol (JSON over HTTPS), relative to the configured base URL:
 *   POST {base}/v1/relay      body: {"packets":["<base64 packet>", ...]}
 *   GET  {base}/v1/inbox/{nodeId}  -> {"packets":["<base64 packet>", ...]}
 *
 * The whole client no-ops when disabled or when no base URL is set, so the
 * app is fully functional offline and this stays a pure enhancement.
 */
@Singleton
class InternetBridgeClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val offlineQueue: OfflineQueue,
    private val transportManager: TransportManager
) {
    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_PACKETS_PER_PUSH = 50
    }

    private var pollJob: Job? = null
    private var selfNodeId: String? = null

    /** Callback to inject a pulled packet into routing, wired by the routing engine. */
    var onPacketFromBridge: (suspend (MeshPacket) -> Unit)? = null

    fun start(nodeId: String, scope: CoroutineScope) {
        selfNodeId = nodeId
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    syncOnce()
                } catch (e: Exception) {
                    Timber.w(e, "Internet bridge sync failed")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Timber.d("Internet bridge client started for $nodeId")
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** One push+pull cycle. Skipped when disabled, unconfigured, or offline. */
    suspend fun syncOnce() {
        val settings = settingsRepository.getSettings()
        if (!settings.internetBridgeEnabled) return
        val base = settings.internetBridgeUrl.trim().trimEnd('/')
        if (base.isEmpty()) return
        val nodeId = selfNodeId ?: return
        if (!hasInternet()) {
            Timber.d("Internet bridge: no connectivity, skipping cycle")
            return
        }

        pushQueued(base)
        pullInbox(base, nodeId)
    }

    /** Upload undeliverable queued packets to the relay. */
    private suspend fun pushQueued(base: String) {
        val queued = offlineQueue.getAll().take(MAX_PACKETS_PER_PUSH)
        if (queued.isEmpty()) return

        val body = JSONObject().apply {
            put("packets", JSONArray().apply {
                queued.forEach { put(transportManager.serializePacket(it).b64()) }
            })
        }
        val ok = withContext(Dispatchers.IO) {
            postJson("$base/v1/relay", body.toString())
        }
        if (ok) {
            // The relay is now responsible for these; drop them locally so we
            // don't keep re-uploading. Mesh delivery, if it happens, is deduped
            // on the receiver by packetId.
            queued.forEach { offlineQueue.remove(it.packetId) }
            Timber.d("Internet bridge: relayed ${queued.size} queued packet(s)")
        }
    }

    /** Download packets the relay is holding for us and inject them locally. */
    private suspend fun pullInbox(base: String, nodeId: String) {
        val response = withContext(Dispatchers.IO) {
            getString("$base/v1/inbox/$nodeId")
        } ?: return

        val packetsJson = try {
            JSONObject(response).optJSONArray("packets") ?: return
        } catch (e: Exception) {
            Timber.w(e, "Internet bridge: malformed inbox response")
            return
        }

        val inject = onPacketFromBridge ?: return
        var count = 0
        for (i in 0 until packetsJson.length()) {
            val bytes = try {
                Base64.decode(packetsJson.getString(i), Base64.NO_WRAP)
            } catch (e: Exception) {
                continue
            }
            val packet = transportManager.deserializePacket(bytes) ?: continue
            inject(packet)
            count++
        }
        if (count > 0) Timber.d("Internet bridge: pulled $count packet(s) from inbox")
    }

    // -- HTTP (HttpURLConnection, no extra deps) --

    private fun postJson(urlStr: String, json: String): Boolean {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.getOrElse {
            Timber.w(it, "Internet bridge POST failed: $urlStr")
            false
        }
    }

    private fun getString(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val result = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
            conn.disconnect()
            result
        }.getOrElse {
            Timber.w(it, "Internet bridge GET failed: $urlStr")
            null
        }
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)
}
