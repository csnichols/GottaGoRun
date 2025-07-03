package com.example.gottagorun

import com.google.firebase.firestore.Exclude

data class PlannedRun(
    val name: String = "",
    val distanceInMeters: Long = 0,
    val pathPoints: List<Map<String, Double>> = emptyList(),
    val timestamp: Long = 0,
    @get:Exclude var documentId: String = ""
)