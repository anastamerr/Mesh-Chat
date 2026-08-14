package chat.mesh.probe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

@SuppressLint("MissingPermission")
internal class BleProbe(
    context: Context,
    private val durationMillis: Long = DEFAULT_DURATION_MILLIS,
    private val onComplete: (BleRuntimeResult) -> Unit,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable(::finish)
    private val failures = linkedSetOf<String>()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var clientGatt: BluetoothGatt? = null
    private var startedAtMillis = 0L
    private var scanStarted = false
    private var advertisingRequested = false
    private var advertisingStarted = false
    private var gattServerPublished = false
    private var peerDiscovered = false
    private var gattClientConnected = false
    private var peerServiceDiscovered = false
    private var negotiatedMtu: Int? = null
    private var gattClientWriteRequested = false
    private var gattClientWriteConfirmed = false
    private var gattServerReceivedWrite = false
    private var finished = false

    fun start() {
        check(!finished && startedAtMillis == 0L) { "Probe instances are single-use" }
        startedAtMillis = SystemClock.elapsedRealtime()

        if (!permissionsGranted(context)) {
            failures += "permissions:not-granted"
            finish()
            return
        }

        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null) {
            failures += "adapter:unavailable"
            finish()
            return
        }
        if (!adapter.isEnabled) {
            failures += "adapter:disabled"
            finish()
            return
        }

        try {
            openGattServer(manager)
            startScan(adapter)
            startAdvertising(adapter)
            handler.postDelayed(finishRunnable, durationMillis)
        } catch (_: SecurityException) {
            failures += "permissions:revoked"
            finish()
        }
    }

    fun close() {
        if (!finished && startedAtMillis != 0L) {
            stop(notify = false)
        }
    }

    private fun openGattServer(manager: BluetoothManager) {
        gattServer = manager.openGattServer(context, gattServerCallback)
        val server = gattServer
        if (server == null) {
            failures += "gatt-server:unavailable"
            return
        }

        val characteristic = BluetoothGattCharacteristic(
            PROBE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(
            PROBE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            addCharacteristic(characteristic)
        }
        if (!server.addService(service)) {
            failures += "gatt-server:add-service-rejected"
        }
    }

    private fun startScan(adapter: BluetoothAdapter) {
        val availableScanner = adapter.bluetoothLeScanner
        scanner = availableScanner
        if (availableScanner == null) {
            failures += "scan:unavailable"
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(PROBE_SERVICE_PARCEL_UUID)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        availableScanner.startScan(listOf(filter), settings, scanCallback)
        scanStarted = true
    }

    private fun startAdvertising(adapter: BluetoothAdapter) {
        val availableAdvertiser = adapter.bluetoothLeAdvertiser
        advertiser = availableAdvertiser
        if (availableAdvertiser == null) {
            failures += "advertising:unavailable"
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(PROBE_SERVICE_PARCEL_UUID)
            .build()
        advertisingRequested = true
        availableAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun connectToPeer(result: ScanResult) {
        if (clientGatt != null || finished) return
        peerDiscovered = true
        clientGatt = result.device.connectGatt(
            context,
            false,
            gattClientCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
        if (clientGatt == null) {
            failures += "gatt-client:unavailable"
        }
    }

    private fun finish(): Unit = stop(notify = true)

    private fun stop(notify: Boolean) {
        if (finished) return
        finished = true
        handler.removeCallbacks(finishRunnable)

        cleanUp { scanner?.takeIf { scanStarted }?.stopScan(scanCallback) }
        cleanUp { advertiser?.takeIf { advertisingRequested }?.stopAdvertising(advertiseCallback) }
        cleanUp { clientGatt?.close() }
        cleanUp { gattServer?.clearServices() }
        cleanUp { gattServer?.close() }

        if (notify) onComplete(
            BleRuntimeResult(
                durationMillis = SystemClock.elapsedRealtime() - startedAtMillis,
                scanStarted = scanStarted,
                advertisingStarted = advertisingStarted,
                gattServerPublished = gattServerPublished,
                peerDiscovered = peerDiscovered,
                gattClientConnected = gattClientConnected,
                peerServiceDiscovered = peerServiceDiscovered,
                negotiatedMtu = negotiatedMtu,
                gattClientWriteConfirmed = gattClientWriteConfirmed,
                gattServerReceivedWrite = gattServerReceivedWrite,
                failures = failures.toList(),
            ),
        )
    }

    private fun cleanUp(operation: () -> Unit) {
        try {
            operation()
        } catch (_: SecurityException) {
            failures += "permissions:revoked"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { connectToPeer(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanStarted = false
                failures += "scan:$errorCode"
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            handler.post { advertisingStarted = true }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post { failures += "advertising:$errorCode" }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gattServerPublished = true
                } else {
                    failures += "gatt-server:$status"
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val accepted = characteristic.uuid == PROBE_CHARACTERISTIC_UUID &&
                !preparedWrite && offset == 0 && value.contentEquals(PROBE_WRITE_VALUE)
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset,
                    null,
                )
            }
            handler.post {
                if (accepted) {
                    gattServerReceivedWrite = true
                } else {
                    failures += "gatt-server:invalid-write"
                }
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    gattClientConnected = true
                    if (!gatt.discoverServices()) {
                        failures += "gatt-client:discovery-rejected"
                    }
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    failures += "gatt-client:$status"
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    gatt.getService(PROBE_SERVICE_UUID)?.getCharacteristic(PROBE_CHARACTERISTIC_UUID) != null
                ) {
                    peerServiceDiscovered = true
                    if (!gatt.requestMtu(MAX_REQUESTED_MTU)) {
                        failures += "gatt-client:mtu-request-rejected"
                        writeProbeValue(gatt)
                    }
                } else {
                    failures += "gatt-client:service-$status"
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                } else {
                    failures += "gatt-client:mtu-$status"
                }
                writeProbeValue(gatt)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handler.post { recordWriteResult(characteristic, status) }
        }
    }

    private fun writeProbeValue(gatt: BluetoothGatt) {
        if (gattClientWriteRequested || finished) return
        val characteristic = gatt.getService(PROBE_SERVICE_UUID)
            ?.getCharacteristic(PROBE_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            failures += "gatt-client:characteristic-missing"
            return
        }
        gattClientWriteRequested = true
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                PROBE_WRITE_VALUE.copyOf(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            writeLegacy(gatt, characteristic)
        }
        if (!accepted) failures += "gatt-client:write-rejected"
    }

    private fun recordWriteResult(characteristic: BluetoothGattCharacteristic, status: Int) {
        if (finished || characteristic.uuid != PROBE_CHARACTERISTIC_UUID) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            gattClientWriteConfirmed = true
        } else {
            failures += "gatt-client:write-$status"
        }
    }

    internal companion object {
        private const val DEFAULT_DURATION_MILLIS = 12_000L
        private const val MAX_REQUESTED_MTU = 517
        private val PROBE_SERVICE_UUID: UUID = UUID.fromString("a3a3d7f0-4985-4f80-a938-2c833d65b001")
        private val PROBE_CHARACTERISTIC_UUID: UUID = UUID.fromString("a3a3d7f0-4985-4f80-a938-2c833d65b002")
        private val PROBE_SERVICE_PARCEL_UUID = ParcelUuid(PROBE_SERVICE_UUID)
        private val PROBE_WRITE_VALUE = byteArrayOf(0x4d)

        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun permissionsGranted(context: Context): Boolean = requiredPermissions().all { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

        fun readCapabilities(context: Context): BleProbeReport {
            val manager = context.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            val permissionsGranted = permissionsGranted(context)
            val readableAdapter = adapter?.takeIf { permissionsGranted }

            return BleProbeReport(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdk = Build.VERSION.SDK_INT,
                permissionsGranted = permissionsGranted,
                bleFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                adapterPresent = adapter != null,
                adapterEnabled = readableAdapter?.isEnabled,
                scannerAvailable = readableAdapter?.takeIf { it.isEnabled }?.let { it.bluetoothLeScanner != null },
                advertiserAvailable = readableAdapter?.takeIf { it.isEnabled }?.let { it.bluetoothLeAdvertiser != null },
                multipleAdvertisementSupported = readableAdapter?.isMultipleAdvertisementSupported,
                offloadedFilteringSupported = readableAdapter?.isOffloadedFilteringSupported,
                offloadedBatchingSupported = readableAdapter?.isOffloadedScanBatchingSupported,
                le2mPhySupported = readableAdapter?.isLe2MPhySupported,
                leCodedPhySupported = readableAdapter?.isLeCodedPhySupported,
                extendedAdvertisingSupported = readableAdapter?.isLeExtendedAdvertisingSupported,
                periodicAdvertisingSupported = readableAdapter?.isLePeriodicAdvertisingSupported,
                maximumAdvertisingDataLength = readableAdapter?.leMaximumAdvertisingDataLength,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = PROBE_WRITE_VALUE
        return gatt.writeCharacteristic(characteristic)
    }
}
