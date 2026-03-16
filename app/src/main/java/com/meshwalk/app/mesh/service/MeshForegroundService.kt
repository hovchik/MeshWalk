package com.meshwalk.app.mesh.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meshwalk.app.MainActivity
import com.meshwalk.app.R
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.mesh.inbox.MeshInbox
import com.meshwalk.app.mesh.outbox.MessageResendManager
import com.meshwalk.app.routing.engine.MeshRoutingEngine
import com.meshwalk.app.transport.api.NodeAdvertisement
import com.meshwalk.app.transport.manager.TransportManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MeshForegroundService : Service() {

    @Inject lateinit var transportManager: TransportManager
    @Inject lateinit var routingEngine: MeshRoutingEngine
    @Inject lateinit var identityRepository: IdentityRepository
    @Inject lateinit var meshInbox: MeshInbox
    @Inject lateinit var messageResendManager: MessageResendManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var meshStarted = false

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

        if (!meshStarted) {
            serviceScope.launch {
                // Check minimum permissions before starting mesh.
                // Each transport also checks its own permissions before calling
                // platform APIs, so partial permission grants are handled gracefully.
                if (!hasMinimumPermissions()) {
                    Timber.w("Mesh service started without required permissions; transports will check individually")
                }

                // Wait for identity to become available (user may still be in onboarding)
                Timber.d("Waiting for active identity...")
                val identity = identityRepository.observeActiveIdentity()
                    .filterNotNull()
                    .first()

                Timber.d("Identity available: ${identity.nodeId}, starting mesh")

                val advertisement = NodeAdvertisement(
                    nodeId = identity.nodeId,
                    displayName = identity.displayName,
                    publicExchangeKey = identity.publicExchangeKey,
                    capabilities = setOf("relay", "store-forward"),
                    protocolVersion = 1
                )

                routingEngine.start(identity.nodeId)
                meshInbox.start(identity.nodeId, serviceScope)
                messageResendManager.start(identity.nodeId, serviceScope)
                transportManager.startMesh(advertisement)
                meshStarted = true
                Timber.d("Mesh started for node ${identity.nodeId}")

                // Observe identity changes to re-advertise when the user renames
                identityRepository.observeActiveIdentity()
                    .filterNotNull()
                    .drop(1) // Skip the initial emission we already used above
                    .collect { updatedIdentity ->
                        val updatedAd = NodeAdvertisement(
                            nodeId = updatedIdentity.nodeId,
                            displayName = updatedIdentity.displayName,
                            publicExchangeKey = updatedIdentity.publicExchangeKey,
                            capabilities = setOf("relay", "store-forward"),
                            protocolVersion = 1
                        )
                        transportManager.updateAdvertisement(updatedAd)
                        Timber.d("Re-advertised after profile update: name=${updatedIdentity.displayName}")
                    }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (meshStarted) {
            // Use withTimeoutOrNull to avoid blocking the main thread indefinitely.
            // If cleanup takes longer than 3 seconds, the scope cancellation below
            // will still tear everything down.
            runBlocking {
                withTimeoutOrNull(3_000) {
                    transportManager.stopMesh()
                    routingEngine.stop()
                }
            }
            meshStarted = false
            Timber.d("Mesh stopped")
        }
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    private fun hasMinimumPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
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
