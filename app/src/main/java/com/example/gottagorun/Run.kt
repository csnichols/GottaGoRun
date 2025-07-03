package com.example.gottagorun

import com.google.firebase.firestore.Exclude

data class Run(
    val timestamp: Long = 0,
    val distanceInMeters: Float = 0f,
    val timeInMillis: Long = 0,
    val pathPoints: List<Map<String, Double>> = emptyList(), // Add this line
    @get:Exclude var documentId: String = "" // Add this line
)