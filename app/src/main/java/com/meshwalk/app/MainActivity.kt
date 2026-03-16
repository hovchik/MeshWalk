package com.meshwalk.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.mesh.service.MeshForegroundService
import com.meshwalk.app.ui.MeshWalkApp
import com.meshwalk.app.ui.lock.BiometricLockScreen
import com.meshwalk.app.ui.theme.MeshWalkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Start service regardless — transports handle missing permissions gracefully
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        setContent {
            MeshWalkTheme {
                val fingerprintEnabled by remember {
                    settingsRepository.observeSettings().map { it.fingerprintLockEnabled }
                }.collectAsState(initial = false)

                var isUnlocked by remember { mutableStateOf(false) }

                // Re-lock when the app goes to background
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, fingerprintEnabled) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP && fingerprintEnabled) {
                            isUnlocked = false
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                if (fingerprintEnabled && !isUnlocked) {
                    BiometricLockScreen(
                        onAuthenticated = { isUnlocked = true }
                    )
                } else {
                    MeshWalkApp()
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startMeshService()
        }
    }

    private fun startMeshService() {
        startForegroundService(MeshForegroundService.startIntent(this))
    }
}
