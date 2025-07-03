package com.example.gottagorun

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.gottagorun.databinding.ActivityRunDetailBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

class RunDetailActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityRunDetailBinding
    private var googleMap: GoogleMap? = null
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRunDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        binding.mapViewDetail.onCreate(savedInstanceState)
        binding.mapViewDetail.getMapAsync(this)
    }

    private fun fetchRunDetails(runId: String) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("runs").document(runId)
            .get()
            .addOnSuccessListener { document ->
                val run = document.toObject(Run::class.java)
                if (run != null) {
                    updateUI(run)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load run details.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateUI(run: Run) {
        // Update stats
        val distanceInKm = run.distanceInMeters / 1000
        binding.textViewDistanceDetail.text = String.format("Dist: %.2f km", distanceInKm)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(run.timeInMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(run.timeInMillis) % 60
        binding.textViewTimeDetail.text = String.format("Time: %02d:%02d", minutes, seconds)

        // Draw route on map
        drawRouteOnMap(run.pathPoints)
    }

    private fun drawRouteOnMap(pathPointsData: List<Map<String, Double>>) {
        val pathPoints = pathPointsData.map { LatLng(it["latitude"]!!, it["longitude"]!!) }

        if (pathPoints.isNotEmpty()) {
            val polylineOptions = PolylineOptions()
                .color(Color.BLUE)
                .width(10f)
                .addAll(pathPoints)
            googleMap?.addPolyline(polylineOptions)

            // Zoom map to fit the entire route
            val boundsBuilder = LatLngBounds.Builder()
            for (point in pathPoints) {
                boundsBuilder.include(point)
            }
            val bounds = boundsBuilder.build()
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100)) // 100 is padding
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Check for the system's night mode setting
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
            // If it's night mode, apply our dark style
            googleMap?.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(
                    this, R.raw.map_style_dark
                )
            )
        }

        // Fetch the run details now that the map is ready
        val runId = intent.getStringExtra("RUN_ID")
        if (runId != null) {
            fetchRunDetails(runId)
        }
    }

    // Lifecycle methods for MapView
    override fun onResume() { super.onResume(); binding.mapViewDetail.onResume() }
    override fun onStart() { super.onStart(); binding.mapViewDetail.onStart() }
    override fun onStop() { super.onStop(); binding.mapViewDetail.onStop() }
    override fun onPause() { super.onPause(); binding.mapViewDetail.onPause() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapViewDetail.onLowMemory() }
    override fun onDestroy() { super.onDestroy(); binding.mapViewDetail.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapViewDetail.onSaveInstanceState(outState)
    }
}