package com.example.biozandroid

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.biozandroid.ui.theme.BioZAndroidTheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothManager: BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val scanResults = mutableStateListOf<ScanResult>()
    private var isScanning by mutableStateOf(false)
    private var connectionState by mutableStateOf("Disconnected")
    private var selectedDevice by mutableStateOf<ScanResult?>(null)
    private var selectedCharacteristic by mutableStateOf<BluetoothGattCharacteristic?>(null)
    private val availableCharacteristics = mutableStateListOf<BluetoothGattCharacteristic>()
    private val notifications = mutableStateListOf<String>()
    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scan ->
                val alreadyKnown = scanResults.any { it.device.address == scan.device.address }
                if (!alreadyKnown) {
                    runOnUiThread {
                        scanResults.add(scan)
                    }
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                connectionState = "Scan failed: $errorCode"
                isScanning = false
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { connectionState = "Connection error: $status" }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { connectionState = "Discovering services" }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    connectionState = "Disconnected"
                    availableCharacteristics.clear()
                    selectedCharacteristic = null
                    selectedDevice = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val characteristics = gatt.services.flatMap(BluetoothGattService::getCharacteristics)
                runOnUiThread {
                    connectionState = "Connected"
                    availableCharacteristics.clear()
                    availableCharacteristics.addAll(characteristics)
                }
            } else {
                runOnUiThread { connectionState = "Service discovery failed: $status" }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleNotification(characteristic, characteristic.value ?: ByteArray(0))
        }
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val message = buildString {
            append("From ")
            append(characteristic.uuid)
            append(": ")
            append(value.joinToString(separator = " ") { byte ->
                String.format("%02X", byte)
            })
        }
        runOnUiThread { notifications.add(0, message) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        enableEdgeToEdge()
        setContent {
            BioZAndroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val permissionLauncher =
                        rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.RequestMultiplePermissions()
                        ) { }
                    val hasPermissions = hasBlePermissions()
                    BleScreen(
                        isScanning = isScanning,
                        scanResults = scanResults,
                        connectionState = connectionState,
                        availableCharacteristics = availableCharacteristics,
                        notifications = notifications,
                        selectedDevice = selectedDevice,
                        selectedCharacteristic = selectedCharacteristic,
                        hasPermissions = hasPermissions,
                        onRequestPermissions = { permissionLauncher.launch(blePermissions) },
                        onToggleScan = { toggleScan() },
                        onConnect = { device -> connectToDevice(device.device) },
                        onSelectCharacteristic = { characteristic -> selectedCharacteristic = characteristic },
                        onEnableNotifications = { enableNotifications() },
                        onWrite = { payload -> writeValue(payload) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        gatt?.close()
    }

    private fun hasBlePermissions(): Boolean = blePermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun toggleScan() {
        if (!hasBlePermissions()) {
            connectionState = "Grant Bluetooth permissions to start"
            return
        }
        if (isScanning) {
            stopScan()
        } else {
            startScan()
        }
    }

    private fun startScan() {
        if (isScanning) return
        val adapter = bluetoothAdapter
        if (adapter == null) {
            connectionState = "Bluetooth unavailable"
            return
        }
        if (!adapter.isEnabled) {
            connectionState = "Enable Bluetooth to start scanning"
            return
        }
        val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner
        if (scanner == null) {
            connectionState = "Bluetooth scanner unavailable"
            return
        }
        scanResults.clear()
        isScanning = true
        connectionState = "Scanning..."
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed({
            if (isScanning) {
                stopScan()
                if (scanResults.isEmpty()) {
                    connectionState = "No devices found"
                }
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!isScanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        if (connectionState == "Scanning...") {
            connectionState = "Scan stopped"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBlePermissions()) {
            connectionState = "Bluetooth permissions required"
            return
        }
        stopScan()
        connectionState = "Connecting to ${device.address}" 
        selectedDevice = scanResults.firstOrNull { it.device.address == device.address }
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private fun enableNotifications() {
        val characteristic = selectedCharacteristic ?: return
        if (!hasBlePermissions()) {
            connectionState = "Bluetooth permissions required"
            return
        }
        val bluetoothGatt = gatt ?: return
        bluetoothGatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            bluetoothGatt.writeDescriptor(descriptor)
            connectionState = "Notifications enabled"
        } else {
            connectionState = "No descriptor for notifications"
        }
    }

    private fun writeValue(payload: String) {
        val characteristic = selectedCharacteristic ?: return
        val bluetoothGatt = gatt ?: return
        val bytes = payload.toByteArray()
        characteristic.value = bytes
        val success = bluetoothGatt.writeCharacteristic(characteristic)
        connectionState = if (success) "Write sent" else "Write failed"
    }

    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 10_000L
    }
}

@Composable
private fun BleScreen(
    isScanning: Boolean,
    scanResults: List<ScanResult>,
    connectionState: String,
    availableCharacteristics: List<BluetoothGattCharacteristic>,
    notifications: List<String>,
    selectedDevice: ScanResult?,
    selectedCharacteristic: BluetoothGattCharacteristic?,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onConnect: (ScanResult) -> Unit,
    onSelectCharacteristic: (BluetoothGattCharacteristic) -> Unit,
    onEnableNotifications: () -> Unit,
    onWrite: (String) -> Unit
) {
    var writeInput by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "BLE Scanner",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(text = "Status: $connectionState")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onToggleScan) {
                Text(if (isScanning) "Stop Scan" else "Start Scan")
            }
            Button(onClick = onRequestPermissions, enabled = !hasPermissions) {
                Text("Request Permissions")
            }
        }
        DeviceList(scanResults = scanResults, onConnect = onConnect)
        SelectedDeviceInfo(selectedDevice)
        CharacteristicSelector(
            characteristics = availableCharacteristics,
            selected = selectedCharacteristic,
            onSelect = onSelectCharacteristic,
            onEnableNotifications = onEnableNotifications
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = writeInput,
                onValueChange = { writeInput = it },
                modifier = Modifier.weight(1f),
                label = { Text("Write payload") },
                maxLines = 2
            )
            Button(onClick = { onWrite(writeInput) }, enabled = selectedCharacteristic != null) {
                Text("Write")
            }
        }
        NotificationLog(notifications = notifications)
    }
}

@Composable
private fun DeviceList(scanResults: List<ScanResult>, onConnect: (ScanResult) -> Unit) {
    Text(text = "Devices", style = MaterialTheme.typography.titleMedium)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        items(scanResults) { scanResult ->
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(text = scanResult.device.name ?: "Unnamed", fontWeight = FontWeight.SemiBold)
                    Text(text = scanResult.device.address)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "RSSI: ${scanResult.rssi}")
                        val uuids = scanResult.scanRecord?.serviceUuids ?: emptyList<ParcelUuid>()
                        if (uuids.isNotEmpty()) {
                            Text(
                                text = uuids.joinToString { it.uuid.toString() },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { onConnect(scanResult) }) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedDeviceInfo(selectedDevice: ScanResult?) {
    if (selectedDevice == null) {
        Text("No device selected")
    } else {
        Text(
            text = "Connected to ${selectedDevice.device.name ?: "Unnamed"} (${selectedDevice.device.address})",
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CharacteristicSelector(
    characteristics: List<BluetoothGattCharacteristic>,
    selected: BluetoothGattCharacteristic?,
    onSelect: (BluetoothGattCharacteristic) -> Unit,
    onEnableNotifications: () -> Unit
) {
    Text(text = "Characteristics", style = MaterialTheme.typography.titleMedium)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        items(characteristics) { characteristic ->
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(text = "UUID: ${characteristic.uuid}")
                    Text(text = "Properties: ${characteristic.properties}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSelect(characteristic) }) {
                            Text(
                                text = if (selected?.uuid == characteristic.uuid) "Selected" else "Select"
                            )
                        }
                        Button(onClick = onEnableNotifications, enabled = selected?.uuid == characteristic.uuid) {
                            Text("Enable Notifications")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationLog(notifications: List<String>) {
    Text(text = "Notifications", style = MaterialTheme.typography.titleMedium)
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        items(notifications) { note ->
            Card(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)) {
                Text(text = note, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BioZAndroidTheme {
        BleScreen(
            isScanning = false,
            scanResults = emptyList(),
            connectionState = "Disconnected",
            availableCharacteristics = emptyList(),
            notifications = emptyList(),
            selectedDevice = null,
            selectedCharacteristic = null,
            hasPermissions = false,
            onRequestPermissions = {},
            onToggleScan = {},
            onConnect = {},
            onSelectCharacteristic = {},
            onEnableNotifications = {},
            onWrite = {}
        )
    }
}
