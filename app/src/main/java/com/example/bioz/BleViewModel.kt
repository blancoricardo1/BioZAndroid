package com.example.bioz

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bioz.model.BioZPacket
import com.example.bioz.model.BleConnectionState
import com.example.bioz.model.BleDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)
    private var scanJob: Job? = null

    val connectionState = bleManager.connectionState
    val scannedDevices = bleManager.scannedDevices

    val logs = bleManager.logs
        .runningFold(emptyList<String>()) { acc, value ->
            (acc + value).takeLast(200)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val packets = bleManager.packets
        .sample(50.milliseconds)
        .runningFold(emptyList<BioZPacket>()) { acc, value ->
            (acc + value).takeLast(200)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val latestPacket = packets.map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            bleManager.scanDevices().collect()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun connect(device: BleDevice) {
        bleManager.connect(device)
    }

    fun disconnect() {
        stopScan()
        bleManager.disconnect(userInitiated = true)
    }

    fun sendStart() {
        bleManager.sendStart()
    }

    fun sendStop() {
        bleManager.sendStop()
    }

    fun sendCommand(text: String) {
        bleManager.sendCommand(text)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.close()
    }
}
