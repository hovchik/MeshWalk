package com.meshwalk.app.transport.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.meshwalk.app.domain.model.ConnectionType
import com.meshwalk.app.transport.api.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE-based transport for lightweight node discovery and small payload exchange.
 *
 * This is supplementary to Nearby Connections:
 * - BLE advertising makes this node visible to other MeshWalk nodes
 * - BLE scanning discovers nearby nodes even before full Nearby connection
 * - Useful for passive presence detection and mesh topology awareness
 *
 * Limitations:
 * - Small payloads only (~20-512 bytes per characteristic)
 * - Not suitable for message transfer (use Nearby Connections for that)
 * - Android 12+ requires BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT
 * - Background scanning is restricted on Android 8+
 */
@Singleton
class BleDiscoveryTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : MeshTransport {

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val TAG = "BleDiscovery"
    }

    override val transportType = ConnectionType.BLE

    override val isAvailable: Boolean
        get() {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return btManager?.adapter?.isEnabled == true &&
                    btManager.adapter.bluetoothLeAdvertiser != null
        }

    private val _events = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val discoveredEndpoints: SharedFlow<TransportEvent> = _events

    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var isScanning = false
    private var isAdvertising = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val serviceData = result.scanRecord
                ?.getServiceData(ParcelUuid(MESH_SERVICE_UUID))

            if (serviceData != null) {
                val advert = NodeAdvertisement.deserialize(serviceData)
                _events.tryEmit(
                    TransportEvent.EndpointDiscovered(
                        endpointId = "ble_${device.address}",
                        endpointName = advert?.displayName ?: "Unknown",
                        nodeId = advert?.nodeId,
                        transportType = ConnectionType.BLE,
                        signalStrength = result.rssi
                    )
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed: $errorCode")
            _events.tryEmit(
                TransportEvent.Error(null, TransportError.DiscoveryFailed("BLE scan error: $errorCode"))
            )
        }
    }

    override suspend fun startAdvertising(nodeInfo: NodeAdvertisement): Result<Unit> {
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bleAdvertiser = btManager.adapter.bluetoothLeAdvertiser

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(false)
                .setTimeout(0) // Advertise indefinitely
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                .addServiceData(ParcelUuid(MESH_SERVICE_UUID), nodeInfo.serialize().take(24).toByteArray())
                .build()

            bleAdvertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    isAdvertising = true
                    Timber.d("BLE advertising started")
                }

                override fun onStartFailure(errorCode: Int) {
                    Timber.e("BLE advertising failed: $errorCode")
                }
            })
            Result.success(Unit)
        } catch (e: SecurityException) {
            Timber.e(e, "BLE advertising permission denied")
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stopAdvertising() {
        try {
            bleAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
            isAdvertising = false
        } catch (e: SecurityException) {
            Timber.w(e, "Permission denied stopping BLE advertising")
        }
    }

    override suspend fun startDiscovery(): Result<Unit> {
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bleScanner = btManager.adapter.bluetoothLeScanner

            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                    .build()
            )

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(0)
                .build()

            bleScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
            Timber.d("BLE discovery started")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Timber.e(e, "BLE scan permission denied")
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stopDiscovery() {
        try {
            bleScanner?.stopScan(scanCallback)
            isScanning = false
            Timber.d("BLE discovery stopped")
        } catch (e: SecurityException) {
            Timber.w(e, "Permission denied stopping BLE scan")
        }
    }

    // BLE transport doesn't support direct data sending (use Nearby for that)
    override suspend fun sendData(endpointId: String, data: ByteArray): Result<Unit> {
        return Result.failure(UnsupportedOperationException("BLE is discovery-only. Use Nearby Connections for data transfer."))
    }

    override suspend fun acceptConnection(endpointId: String) = Result.success(Unit)
    override suspend fun rejectConnection(endpointId: String) {}
    override suspend fun disconnect(endpointId: String) {}
    override suspend fun disconnectAll() {
        stopDiscovery()
        stopAdvertising()
    }

    override fun getConnectedEndpoints(): Set<String> = emptySet()
}
