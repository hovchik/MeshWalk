package com.meshwalk.app.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.meshwalk.app.R
import com.meshwalk.app.domain.model.IdentityType
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import com.meshwalk.app.transport.api.NodeAdvertisement
import com.meshwalk.app.transport.manager.TransportManager
import com.meshwalk.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps the mesh network alive.
 *
 * Android requires a visible notification for foreground services.
 * This service maintains:
 * - Transport layer advertising & discovery
 * - Routing engine processing
 * - Background relay forwarding
 *
 * The service uses foregroundServiceType="connectedDevice" (Android 14+).
 * It will be stopped when the user explicitly turns off the mesh or closes the app.
 */
@AndroidEntryPoint
class MeshForegroundService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "meshwalk_mesh"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.meshwalk.app.START_MESH"
        const val ACTION_STOP = "com.meshwalk.app.STOP_MESH"

        fun startIntent(context: Context): Intent {
            return Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_START
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    @Inject lateinit var transportManager: TransportManager
    @Inject lateinit var routingEngine: MeshRoutingEngine
    @Inject lateinit var identityRepository: IdentityRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> startMesh()
            ACTION_STOP -> stopMesh()
        }

        return START_STICKY
    }

    private fun startMesh() {
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to mesh..."))

        lifecycleScope.launch {
            try {
                val identity = identityRepository.getActiveIdentity()
                if (identity == null) {
                    Timber.w("No active identity, cannot start mesh")
                    updateNotification("No identity configured")
                    return@launch
                }

                val advertisement = NodeAdvertisement(
                    nodeId = identity.nodeId,
                    displayName = if (identity.identityType != IdentityType.ANONYMOUS) {
                        identity.displayName
                    } else null,
                    publicExchangeKey = identity.publicExchangeKey
                )

                transportManager.startMesh(advertisement)
                routingEngine.start(identity.nodeId)

                val peerCount = transportManager.getConnectedNodeIds().size
                updateNotification("Mesh active • $peerCount peers nearby")

                Timber.i("Mesh started for node ${identity.nodeId}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start mesh")
                updateNotification("Mesh error: ${e.message}")
            }
        }
    }

    private fun stopMesh() {
        lifecycleScope.launch {
            routingEngine.stop()
            transportManager.stopMesh()
            Timber.i("Mesh stopped")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lifecycleScope.launch {
            routingEngine.stop()
            transportManager.stopMesh()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mesh Network",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows mesh network status"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshWalk")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
