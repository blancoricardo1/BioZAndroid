package com.example.bioz

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.bioz.model.BioZPacket
import com.example.bioz.model.BleConnectionState
import com.example.bioz.model.BleDevice
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class BleManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _deviceMap = MutableStateFlow<Map<String, BleDevice>>(emptyMap())
    val scannedDevices = _deviceMap
        .map { it.values.sortedBy { device -> device.name ?: device.address } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 200)
    val logs = _logs.asSharedFlow()

    private val _packets = MutableSharedFlow<BioZPacket>(extraBufferCapacity = 500)
    val packets = _packets.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var shouldReconnect: Boolean = false

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            scanGranted && connectGranted
        } else {
            val locationGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            locationGranted
        }
    }

    fun scanDevices() = callbackFlow {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { addDevice(it) }
                trySend(_deviceMap.value.values.toList())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { addDevice(it) }
                trySend(_deviceMap.value.values.toList())
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(emptyList<ScanFilter>(), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    private fun addDevice(result: ScanResult) {
        val device = result.device
        val record = BleDevice(
            name = device.name ?: result.scanRecord?.deviceName,
            address = device.address,
            rssi = result.rssi
        )
        _deviceMap.value = _deviceMap.value + (record.address to record)
    }

    fun connect(device: BleDevice) {
        val adapter = bluetoothAdapter ?: return
        val target: BluetoothDevice = adapter.getRemoteDevice(device.address)
        shouldReconnect = true
        _connectionState.value = BleConnectionState.Connecting
        bluetoothGatt?.close()
        bluetoothGatt = target.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect(userInitiated: Boolean = false) {
        shouldReconnect = !userInitiated
        bluetoothGatt?.let { gatt ->
            sendStop()
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        notifyCharacteristic = null
        _connectionState.value = BleConnectionState.Disconnected
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _logs.tryEmit("Connected, discovering services")
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _logs.tryEmit("Disconnected (status=$status)")
                _connectionState.value = BleConnectionState.Disconnected
                notifyCharacteristic = null
                if (shouldReconnect) {
                    _logs.tryEmit("Reconnecting…")
                    gatt?.connect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
                return
            }
            val characteristic = gatt.services
                .flatMap { it.characteristics }
                .firstOrNull { characteristic ->
                    val props = characteristic.properties
                    val canWrite = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    canWrite && canNotify
                }

            if (characteristic == null) {
                _connectionState.value = BleConnectionState.Error("No writable/notifiable characteristic found")
                _logs.tryEmit("No suitable characteristic")
                return
            }
            notifyCharacteristic = characteristic
            enableNotifications(gatt, characteristic)
            _connectionState.value =
                BleConnectionState.Connected(gatt.device.name, gatt.device.address)
            sendStart()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            value: ByteArray?
        ) {
            val bytes = value ?: characteristic?.value ?: return
            val text = bytes.toString(Charset.forName("UTF-8")).trim()
            _logs.tryEmit("RX: $text")
            parsePacket(text)?.let { packet ->
                _packets.tryEmit(packet)
            }
        }
    }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CONFIG))
        descriptor?.let {
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(it)
        }
    }

    private fun parsePacket(text: String): BioZPacket? {
        val parts = text.split(',')
        if (parts.size < 4) return null
        val timestamp = parts.getOrNull(0)?.ifBlank { "" } ?: ""
        val q = parts.getOrNull(1)?.toFloatOrNull() ?: return null
        val i = parts.getOrNull(2)?.toFloatOrNull() ?: return null
        val freq = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
        return BioZPacket(timestamp = timestamp, q = q, i = i, frequency = freq, raw = text)
    }

    fun sendStart() {
        val now = LocalDateTime.now()
        val datePart = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val timePart = now.format(DateTimeFormatter.ofPattern("HHmmss"))
        sendCommand("start@$datePart@$timePart")
    }

    fun sendStop() {
        sendCommand("stop")
    }

    fun sendCommand(command: String) {
        val characteristic = notifyCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = command.toByteArray()
        val success = gatt.writeCharacteristic(characteristic)
        if (!success) {
            _logs.tryEmit("Write failed: $command")
        } else {
            _logs.tryEmit("TX: $command")
        }
    }

    fun close() {
        shouldReconnect = false
        disconnect(userInitiated = true)
        scope.cancel()
    }

    companion object {
        private const val CLIENT_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
