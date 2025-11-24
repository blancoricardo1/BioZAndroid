package com.example.bioz.model

data class BioZPacket(
    val timestamp: String,
    val q: Float,
    val i: Float,
    val frequency: Float,
    val raw: String
)
