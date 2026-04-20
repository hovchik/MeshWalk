package com.meshwalk.app

import android.Manifest
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.meshwalk.app.billing.BillingManager
import com.meshwalk.app.domain.model.DarkModeSetting
import com.meshwalk.app.domain.repository.SettingsRepository
import com.meshwalk.app.mesh.service.MeshForegroundService
import com.meshwalk.app.ui.MeshWalkApp
import com.meshwalk.app.ui.lock.BiometricLockScreen
import com.meshwalk.app.ui.theme.MeshWalkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var billingManager: BillingManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Transports handle missing permissions gracefully; startup respects the background toggle
        observeBackgroundWorkPreference()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()
        initializeBilling()

        setContent {
            val darkModeSetting by remember {
                settingsRepository.observeSettings().map { it.darkMode }
            }.collectAsState(initial = DarkModeSetting.SYSTEM)

            MeshWalkTheme(darkModeSetting = darkModeSetting) {
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
            observeBackgroundWorkPreference()
        }
    }

    private var backgroundWorkObserverStarted = false

    private fun observeBackgroundWorkPreference() {
        if (backgroundWorkObserverStarted) return
        backgroundWorkObserverStarted = true
        lifecycleScope.launch {
            settingsRepository.observeSettings()
                .map { it.autoStartMesh }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) startMeshService() else stopMeshService()
                }
        }
    }

    private fun initializeBilling() {
        lifecycleScope.launch {
            billingManager.observeStateChanges(lifecycleScope)
            billingManager.initialize()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh purchase state to detect external changes
        // (e.g., subscription cancelled via Play Store)
        lifecycleScope.launch {
            billingManager.refreshPurchases()
        }
    }

    private fun startMeshService() {
        startForegroundService(MeshForegroundService.startIntent(this))
    }

    private fun stopMeshService() {
        stopService(Intent(this, MeshForegroundService::class.java))
    }
}
