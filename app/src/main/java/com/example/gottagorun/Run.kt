package com.example.gottagorun

import com.google.firebase.firestore.Exclude

data class Run(
    @get:Exclude var documentId: String = "",
    val timestamp: Long = 0,
    val distanceInMeters: Float = 0f,
    val timeInMillis: Long = 0,
    val totalElevationGain: Double? = null, // Changed to nullable
    val pathPoints: List<Map<String, Double>> = emptyList()
)