package com.example.bioz.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bioz.BleViewModel
import com.example.bioz.model.BioZPacket
import com.example.bioz.model.BleConnectionState
import com.example.bioz.model.BleDevice
import com.example.bioz.ui.components.BioZChart

@Composable
fun BleApp(
    viewModel: BleViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val devices by viewModel.scannedDevices.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val packets by viewModel.packets.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(title = { Text("BioZ BLE Client") })
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (connectionState) {
                is BleConnectionState.Connected -> ConnectedScreen(
                    state = connectionState as BleConnectionState.Connected,
                    viewModel = viewModel,
                    logs = logs,
                    packets = packets
                )
                BleConnectionState.Connecting -> ConnectingScreen()
                is BleConnectionState.Error -> ErrorScreen((connectionState as BleConnectionState.Error).message)
                else -> DeviceListScreen(
                    devices = devices,
                    permissionsGranted = permissionsGranted,
                    onScanClick = {
                        if (permissionsGranted) viewModel.startScan() else onRequestPermissions()
                    },
                    onDeviceClick = { viewModel.connect(it) }
                )
            }
        }
    }
}

@Composable
fun DeviceListScreen(
    devices: List<BleDevice>,
    permissionsGranted: Boolean,
    onScanClick: () -> Unit,
    onDeviceClick: (BleDevice) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onScanClick) {
                Icon(Icons.Default.Bluetooth, contentDescription = "Scan")
                Spacer(modifier = Modifier.padding(4.dp))
                Text(if (permissionsGranted) "Scan" else "Grant BLE perms")
            }
            Text(text = "Found: ${devices.size}")
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (!permissionsGranted) {
            Text(
                text = "Bluetooth permissions are required to scan.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Divider()
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(devices) { device ->
                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = device.name ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "${device.rssi} dBm")
                            IconButton(onClick = { onDeviceClick(device) }) {
                                Icon(Icons.Default.BluetoothConnected, contentDescription = "Connect")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Connecting to device…")
    }
}

@Composable
fun ErrorScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Error: $message")
    }
}

@Composable
fun ConnectedScreen(state: BleConnectionState.Connected, viewModel: BleViewModel, logs: List<String>, packets: List<BioZPacket>) {
    var commandText by remember { mutableStateOf("") }

    DisposableEffect(state.address) {
        onDispose { viewModel.sendStop() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Connected to ${state.deviceName ?: "Unknown"}", style = MaterialTheme.typography.titleMedium)
        Text(state.address, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.sendStart() }) { Text("Start") }
            OutlinedButton(onClick = { viewModel.sendStop() }) { Text("Stop") }
            OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = commandText,
            onValueChange = { commandText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Custom command") },
            trailingIcon = {
                IconButton(onClick = {
                    viewModel.sendCommand(commandText)
                    commandText = ""
                }) { Icon(Icons.Default.Send, contentDescription = "Send") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                viewModel.sendCommand(commandText)
                commandText = ""
            })
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("BioZ stream", style = MaterialTheme.typography.titleMedium)
        BioZChart(packets = packets)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Logs", style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            logs.takeLast(200).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
