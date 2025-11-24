package com.example.bioz.model

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Connecting : BleConnectionState()
    data class Connected(val deviceName: String?, val address: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}
