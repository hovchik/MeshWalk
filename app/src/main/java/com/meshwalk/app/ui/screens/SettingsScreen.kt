package com.meshwalk.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTrigger++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Permissions",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "MeshWalk needs these permissions for mesh networking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        val permissions = buildMeshPermissions()

        permissions.forEach { perm ->
            // Use refreshTrigger to force recomposition after permission result
            val granted = remember(refreshTrigger) {
                if (perm.minSdk != null && Build.VERSION.SDK_INT < perm.minSdk) {
                    true // Not applicable on this API level
                } else {
                    ContextCompat.checkSelfPermission(
                        context, perm.manifestPermission
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }

            PermissionRow(
                label = perm.label,
                description = perm.description,
                granted = granted,
                onRequest = {
                    permissionLauncher.launch(arrayOf(perm.manifestPermission))
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { openAppSettings(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open App Settings")
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!granted) onRequest() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (granted) "Granted" else "Denied",
            tint = if (granted) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider()
}

private data class MeshPermission(
    val manifestPermission: String,
    val label: String,
    val description: String,
    val minSdk: Int? = null
)

private fun buildMeshPermissions(): List<MeshPermission> {
    val list = mutableListOf<MeshPermission>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list += MeshPermission(
            Manifest.permission.BLUETOOTH_SCAN,
            "Bluetooth Scan",
            "Discover nearby BLE devices",
            minSdk = 31
        )
        list += MeshPermission(
            Manifest.permission.BLUETOOTH_CONNECT,
            "Bluetooth Connect",
            "Establish Bluetooth connections",
            minSdk = 31
        )
        list += MeshPermission(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            "Bluetooth Advertise",
            "Make this node discoverable",
            minSdk = 31
        )
    }
    list += MeshPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
        "Fine Location",
        "Required for BLE scanning"
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += MeshPermission(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            "Nearby Wi-Fi Devices",
            "Wi-Fi Direct discovery",
            minSdk = 33
        )
        list += MeshPermission(
            Manifest.permission.POST_NOTIFICATIONS,
            "Notifications",
            "Foreground service notification",
            minSdk = 33
        )
    }
    return list
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}
