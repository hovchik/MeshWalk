package com.meshwalk.app.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.meshwalk.app.MainActivity
import com.meshwalk.app.R
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import com.meshwalk.app.transport.api.NodeAdvertisement
import com.meshwalk.app.transport.manager.TransportManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MeshForegroundService : Service() {

    @Inject lateinit var transportManager: TransportManager
    @Inject lateinit var routingEngine: MeshRoutingEngine
    @Inject lateinit var identityRepository: IdentityRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_ID = "mesh_service_channel"
        const val NOTIFICATION_ID = 1

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun startIntent(context: Context): Intent {
            return Intent(context, MeshForegroundService::class.java)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        _isRunning.value = true

        serviceScope.launch {
            val identity = identityRepository.getActiveIdentity()
            if (identity == null) {
                Timber.w("No active identity found, mesh cannot start")
                return@launch
            }

            val advertisement = NodeAdvertisement(
                nodeId = identity.nodeId,
                displayName = identity.displayName,
                publicExchangeKey = identity.publicExchangeKey,
                capabilities = setOf("relay", "store-forward"),
                protocolVersion = 1
            )

            routingEngine.start(identity.nodeId)
            transportManager.startMesh(advertisement)
            Timber.d("Mesh started for node ${identity.nodeId}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.launch {
            transportManager.stopMesh()
            routingEngine.stop()
            Timber.d("Mesh stopped")
        }
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MeshWalk Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the mesh network active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshWalk")
            .setContentText("Mesh network is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
