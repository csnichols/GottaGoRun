package com.example.gottagorun

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.gottagorun.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.PolyUtil
import com.google.maps.model.Distance
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var googleMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geoApiContext: GeoApiContext

    // State Management
    private enum class AppState { IDLE, TRACKING, PLANNING }
    private var currentState = AppState.IDLE

    // Planning variables
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var plannedRoutePolyline: Polyline? = null
    private var plannedDistance: Distance? = null

    // Run tracking variables
    private var pathPoints = mutableListOf<LatLng>()
    private lateinit var locationCallback: LocationCallback
    private var totalDistance = 0f
    private var startTime = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val millis = System.currentTimeMillis() - startTime
            val seconds = (millis / 1000).toInt()
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            binding.textViewTime.text = String.format("Time: %02d:%02d", minutes, remainingSeconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val plannedRouteResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val runId = result.data?.getStringExtra("SELECTED_RUN_ID")
            if (runId != null) {
                fetchAndDisplayPlannedRoute(runId)
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        geoApiContext = GeoApiContext.Builder()
            .apiKey(getString(R.string.google_maps_key))
            .build()

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)

        setupLocationCallback()
        setupClickListeners()
        updateUIForState(AppState.IDLE)
    }

    private fun setupClickListeners() {
        binding.buttonLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        binding.buttonHistory.setOnClickListener {
            val intent = Intent(this, RunHistoryActivity::class.java)
            startActivity(intent)
        }

        binding.buttonPlannedRoutes.setOnClickListener {
            val intent = Intent(this, PlannedRoutesActivity::class.java)
            plannedRouteResultLauncher.launch(intent)
        }

        binding.buttonAction.setOnClickListener {
            when (currentState) {
                AppState.IDLE -> startTracking()
                AppState.TRACKING -> stopTracking()
                AppState.PLANNING -> { /* TODO: Logic for starting a planned run */ }
            }
        }

        binding.buttonPlanRoute.setOnClickListener {
            val newState = if (currentState == AppState.PLANNING) AppState.IDLE else AppState.PLANNING
            updateUIForState(newState)
        }

        binding.buttonSavePlan.setOnClickListener {
            showSaveRouteDialog()
        }
    }

    private fun updateUIForState(state: AppState) {
        currentState = state
        when (state) {
            AppState.IDLE -> {
                binding.statsContainer.visibility = View.INVISIBLE
                binding.planningContainer.visibility = View.GONE
                binding.topButtonsContainer.visibility = View.VISIBLE
                binding.buttonAction.visibility = View.VISIBLE
                binding.buttonAction.text = "Start Run"
                binding.buttonAction.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_media_play)
                binding.buttonPlanRoute.text = "Plan Route"
                clearMap()
            }
            AppState.TRACKING -> {
                binding.statsContainer.visibility = View.VISIBLE
                binding.planningContainer.visibility = View.GONE
                binding.topButtonsContainer.visibility = View.GONE
                binding.buttonAction.visibility = View.VISIBLE
                binding.buttonAction.text = "Stop Run"
                binding.buttonAction.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_media_pause)
            }
            AppState.PLANNING -> {
                binding.statsContainer.visibility = View.INVISIBLE
                binding.planningContainer.visibility = View.VISIBLE
                binding.topButtonsContainer.visibility = View.VISIBLE
                binding.buttonAction.visibility = View.GONE
                binding.buttonPlanRoute.text = "Cancel Planning"
                binding.textViewPlanningInstructions.text = "Tap on the map to set a start point."
                binding.buttonSavePlan.visibility = View.GONE
                clearMap()
            }
        }
    }

    private fun handleMapClick(latLng: LatLng) {
        if (currentState == AppState.PLANNING) {
            if (startMarker == null) {
                startMarker = googleMap?.addMarker(MarkerOptions().position(latLng).title("Start"))
                binding.textViewPlanningInstructions.text = "Tap on the map to set an end point."
            } else if (endMarker == null) {
                endMarker = googleMap?.addMarker(MarkerOptions().position(latLng).title("End"))
                binding.textViewPlanningInstructions.text = "Calculating route..."
                getDirections(startMarker!!.position, endMarker!!.position)
            } else {
                clearMap()
                binding.textViewPlanningInstructions.text = "Tap on the map to set a start point."
            }
        }
    }

    private fun getDirections(origin: LatLng, destination: LatLng) {
        Thread {
            try {
                val directionsResult = DirectionsApi.newRequest(geoApiContext)
                    .origin(com.google.maps.model.LatLng(origin.latitude, origin.longitude))
                    .destination(com.google.maps.model.LatLng(destination.latitude, destination.longitude))
                    .await()

                Handler(Looper.getMainLooper()).post {
                    if (directionsResult.routes.isNotEmpty()) {
                        val route = directionsResult.routes[0]
                        val decodedPath = PolyUtil.decode(route.overviewPolyline.encodedPath)
                        plannedRoutePolyline = googleMap?.addPolyline(PolylineOptions().addAll(decodedPath).color(Color.MAGENTA).width(12f))

                        plannedDistance = route.legs[0].distance
                        binding.textViewPlanningInstructions.text = "Planned Distance: ${plannedDistance?.humanReadable}"
                        binding.buttonSavePlan.visibility = View.VISIBLE
                    } else {
                        binding.textViewPlanningInstructions.text = "No route found. Tap to reset."
                    }
                }
            } catch (e: Exception) {
                Log.e("DirectionsAPI", "Error getting directions", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Directions API Error: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.textViewPlanningInstructions.text = "Error finding route. Tap to reset."
                }
            }
        }.start()
    }

    private fun showSaveRouteDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Save Route")

        val input = EditText(this)
        input.hint = "Enter route name (e.g., Park Loop)"
        builder.setView(input)

        builder.setPositiveButton("Save") { _, _ ->
            val routeName = input.text.toString()
            if (routeName.isNotEmpty()) {
                savePlannedRouteToFirebase(routeName)
            } else {
                Toast.makeText(this, "Please enter a name for the route.", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        builder.show()
    }

    private fun savePlannedRouteToFirebase(routeName: String) {
        val userId = auth.currentUser?.uid
        val start = startMarker?.position
        val end = endMarker?.position
        val polyline = plannedRoutePolyline

        if (userId != null && start != null && end != null && polyline != null && plannedDistance != null) {
            val routeData = hashMapOf(
                "name" to routeName,
                "distanceInMeters" to plannedDistance!!.inMeters,
                "pathPoints" to polyline.points.map { mapOf("latitude" to it.latitude, "longitude" to it.longitude) },
                "startPoint" to mapOf("latitude" to start.latitude, "longitude" to start.longitude),
                "endPoint" to mapOf("latitude" to end.latitude, "longitude" to end.longitude),
                "timestamp" to System.currentTimeMillis()
            )

            db.collection("users").document(userId).collection("planned_routes")
                .add(routeData)
                .addOnSuccessListener {
                    Toast.makeText(this, "Planned route saved!", Toast.LENGTH_SHORT).show()
                    updateUIForState(AppState.IDLE)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to save planned route: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "Error: Route data is incomplete.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchAndDisplayPlannedRoute(runId: String) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("planned_routes").document(runId)
            .get()
            .addOnSuccessListener { document ->
                val plannedRun = document.toObject(PlannedRun::class.java)
                if (plannedRun != null) {
                    updateUIForState(AppState.PLANNING)
                    val pathPoints = plannedRun.pathPoints.map { LatLng(it["latitude"]!!, it["longitude"]!!) }
                    if (pathPoints.isNotEmpty()) {
                        val polylineOptions = PolylineOptions().color(Color.MAGENTA).width(12f).addAll(pathPoints)
                        plannedRoutePolyline = googleMap?.addPolyline(polylineOptions)

                        val boundsBuilder = LatLngBounds.Builder()
                        for (point in pathPoints) {
                            boundsBuilder.include(point)
                        }
                        val bounds = boundsBuilder.build()
                        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))

                        binding.textViewPlanningInstructions.text = "Selected Plan: ${plannedRun.name}"
                        binding.buttonAction.visibility = View.VISIBLE
                        binding.buttonAction.text = "Start Planned Run"
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load planned route.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun clearMap() {
        startMarker?.remove()
        endMarker?.remove()
        plannedRoutePolyline?.remove()
        startMarker = null
        endMarker = null
        plannedRoutePolyline = null
        plannedDistance = null
        googleMap?.clear()
    }

    private fun startTracking() {
        pathPoints.clear()
        totalDistance = 0f
        binding.textViewDistance.text = "Dist: 0.00 km"
        binding.textViewTime.text = "Time: 00:00"
        updateUIForState(AppState.TRACKING)
        startTime = System.currentTimeMillis()
        timerHandler.postDelayed(timerRunnable, 1000)
        startLocationUpdates()
    }

    private fun stopTracking() {
        updateUIForState(AppState.IDLE)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        timerHandler.removeCallbacks(timerRunnable)
        saveRunToFirebase()
    }

    private fun saveRunToFirebase() {
        val userId = auth.currentUser?.uid
        if (userId != null && pathPoints.isNotEmpty()) {
            val runData = hashMapOf(
                "timestamp" to System.currentTimeMillis(),
                "distanceInMeters" to totalDistance,
                "timeInMillis" to (System.currentTimeMillis() - startTime),
                "pathPoints" to pathPoints.map { mapOf("latitude" to it.latitude, "longitude" to it.longitude) }
            )
            db.collection("users").document(userId).collection("runs")
                .add(runData)
                .addOnSuccessListener { Toast.makeText(this, "Run saved successfully!", Toast.LENGTH_SHORT).show() }
                .addOnFailureListener { e -> Toast.makeText(this, "Failed to save run: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                if (currentState == AppState.TRACKING) {
                    locationResult.locations.forEach { location ->
                        val newPoint = LatLng(location.latitude, location.longitude)
                        if (pathPoints.isNotEmpty()) {
                            totalDistance += calculateDistance(pathPoints.last(), newPoint)
                        }
                        pathPoints.add(newPoint)
                        drawLiveRoute()
                        updateStats()
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = TimeUnit.SECONDS.toMillis(5)
            fastestInterval = TimeUnit.SECONDS.toMillis(2)
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, results)
        return results[0]
    }

    private fun drawLiveRoute() {
        googleMap?.clear()
        val polylineOptions = PolylineOptions().color(Color.BLUE).width(10f).addAll(pathPoints)
        googleMap?.addPolyline(polylineOptions)
    }

    private fun updateStats() {
        val distanceInKm = totalDistance / 1000
        binding.textViewDistance.text = String.format("Dist: %.2f km", distanceInKm)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.setOnMapClickListener { latLng -> handleMapClick(latLng) }

        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
            googleMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark))
        }

        checkLocationPermission()
    }

    private fun checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            getDeviceLocation()
        }
    }

    private fun getDeviceLocation() {
        try {
            googleMap?.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                }
            }
        } catch (e: SecurityException) {
            // Should not happen
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getDeviceLocation()
            } else {
                Toast.makeText(this, "Location permission is required to show your position on the map.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Lifecycle methods for MapView
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onStop() { super.onStop(); binding.mapView.onStop() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDestroy()
        geoApiContext.shutdown() // Important to release resources
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}

