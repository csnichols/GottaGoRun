package com.example.gottagorun

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.gottagorun.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import com.google.maps.DirectionsApi
import com.google.maps.ElevationApi
import com.google.maps.GeoApiContext
import com.google.maps.android.PolyUtil
import com.google.maps.model.Distance
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var googleMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geoApiContext: GeoApiContext

    // State Management
    private enum class AppState { IDLE, TRACKING, PAUSED, PLANNING }
    private var currentState = AppState.IDLE

    // Planning variables
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var plannedRoutePolyline: Polyline? = null
    private var plannedDistance: Distance? = null

    // Run tracking variables
    private var pathPoints = mutableListOf<LatLng>()
    private var liveRoutePolyline: Polyline? = null
    private lateinit var locationCallback: LocationCallback
    private var totalDistance = 0f
    private var timeRunInMillis = 0L
    private var timerJob: Job? = null

    private val plannedRouteResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("SELECTED_RUN_ID")?.let { fetchAndDisplayPlannedRoute(it) }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val ELEVATION_API_CHUNK_SIZE = 500
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_home)
        supportActionBar?.title = "Map"

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
        binding.buttonAction.setOnClickListener {
            when (currentState) {
                AppState.IDLE, AppState.PLANNING -> startTracking()
                AppState.TRACKING -> pauseTracking()
                else -> {}
            }
        }
        binding.buttonResume.setOnClickListener { resumeTracking() }
        binding.buttonEnd.setOnClickListener { stopTracking() }
        binding.buttonSavePlan.setOnClickListener { showSaveRouteDialog() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish() // This correctly handles the "up" button (home icon)
                true
            }
            R.id.action_plan_route -> {
                val newState = if (currentState == AppState.PLANNING) AppState.IDLE else AppState.PLANNING
                updateUIForState(newState)
                true
            }
            R.id.action_history -> {
                startActivity(Intent(this, RunHistoryActivity::class.java))
                true
            }
            R.id.action_planned_routes -> {
                plannedRouteResultLauncher.launch(Intent(this, PlannedRoutesActivity::class.java))
                true
            }
            R.id.action_logout -> {
                auth.signOut()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateUIForState(state: AppState) {
        currentState = state
        when (state) {
            AppState.IDLE -> {
                binding.statsContainer.visibility = View.INVISIBLE
                binding.planningContainer.visibility = View.GONE
                binding.pauseContainer.visibility = View.GONE
                binding.buttonAction.visibility = View.VISIBLE
                binding.buttonAction.text = "Start Run"
                binding.buttonAction.setIconResource(android.R.drawable.ic_media_play)
                clearMap()
            }
            AppState.TRACKING -> {
                binding.statsContainer.visibility = View.VISIBLE
                binding.planningContainer.visibility = View.GONE
                binding.pauseContainer.visibility = View.GONE
                binding.buttonAction.visibility = View.VISIBLE
                binding.buttonAction.text = "Pause"
                binding.buttonAction.setIconResource(android.R.drawable.ic_media_pause)
            }
            AppState.PAUSED -> {
                binding.statsContainer.visibility = View.VISIBLE
                binding.planningContainer.visibility = View.GONE
                binding.pauseContainer.visibility = View.VISIBLE
                binding.buttonAction.visibility = View.GONE
            }
            AppState.PLANNING -> {
                binding.statsContainer.visibility = View.INVISIBLE
                binding.planningContainer.visibility = View.VISIBLE
                binding.buttonAction.visibility = View.GONE
                binding.textViewPlanningInstructions.text = "Tap on the map to set a start point."
                binding.buttonSavePlan.visibility = View.GONE
                clearMap()
            }
        }
    }

    private fun startTracking() {
        clearMap()
        pathPoints.clear()
        totalDistance = 0f
        timeRunInMillis = 0L
        binding.textViewDistance.text = "Dist: 0.00 km"
        binding.textViewTime.text = "Time: 00:00:00"

        updateUIForState(AppState.TRACKING)
        startTimer()
        startLocationUpdates()
    }

    private fun pauseTracking() {
        if (currentState != AppState.TRACKING) return
        updateUIForState(AppState.PAUSED)
        timerJob?.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun resumeTracking() {
        if (currentState != AppState.PAUSED) return
        updateUIForState(AppState.TRACKING)
        startTimer()
        startLocationUpdates()
    }

    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        timerJob?.cancel()
        if (pathPoints.size > 1) {
            calculateElevationAndSaveRun()
        }
        updateUIForState(AppState.IDLE)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            var lastTimestamp = System.currentTimeMillis()
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                timeRunInMillis += now - lastTimestamp
                lastTimestamp = now

                val seconds = (timeRunInMillis / 1000).toInt()
                val minutes = seconds / 60
                val hours = minutes / 60
                binding.textViewTime.text = String.format("Time: %02d:%02d:%02d", hours, minutes % 60, seconds % 60)
            }
        }
    }

    private fun calculateElevationAndSaveRun() = lifecycleScope.launch {
        if (pathPoints.size < 2) {
            saveRunToFirebase(0.0)
            return@launch
        }
        try {
            val allElevationResults = withContext(Dispatchers.IO) {
                val locations = pathPoints.map { com.google.maps.model.LatLng(it.latitude, it.longitude) }
                val aggregatedResults = mutableListOf<com.google.maps.model.ElevationResult>()
                locations.chunked(ELEVATION_API_CHUNK_SIZE).forEach { chunk ->
                    val chunkResult = ElevationApi.getByPoints(geoApiContext, *chunk.toTypedArray()).await()
                    aggregatedResults.addAll(chunkResult)
                }
                aggregatedResults
            }

            var totalElevationGain = 0.0
            if (allElevationResults.isNotEmpty()) {
                for (i in 0 until allElevationResults.size - 1) {
                    val elevationDiff = allElevationResults[i + 1].elevation - allElevationResults[i].elevation
                    if (elevationDiff > 0) {
                        totalElevationGain += elevationDiff
                    }
                }
            }
            saveRunToFirebase(totalElevationGain)

        } catch (e: com.google.maps.errors.ApiException) {
            Log.e("ElevationAPI", "Google Maps API Error: ${e.message}", e)
            Toast.makeText(this@MainActivity, "API Error: Could not retrieve elevation. ${e.message}", Toast.LENGTH_LONG).show()
            saveRunToFirebase(0.0)
        } catch (e: IOException) {
            Log.e("ElevationAPI", "Network Error getting elevation data", e)
            Toast.makeText(this@MainActivity, "Network error. Saving run without elevation data.", Toast.LENGTH_LONG).show()
            saveRunToFirebase(0.0)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("ElevationAPI", "Unknown error getting elevation data", e)
            Toast.makeText(this@MainActivity, "Could not fetch elevation data. Saving run without it.", Toast.LENGTH_LONG).show()
            saveRunToFirebase(0.0)
        }
    }

    private fun saveRunToFirebase(elevationGain: Double) {
        val userId = auth.currentUser?.uid?: return
        if (pathPoints.isNotEmpty()) {
            val runData = hashMapOf(
                "timestamp" to System.currentTimeMillis(),
                "distanceInMeters" to totalDistance,
                "timeInMillis" to timeRunInMillis,
                "totalElevationGain" to elevationGain,
                "pathPoints" to pathPoints.map { mapOf("latitude" to it.latitude, "longitude" to it.longitude) }
            )
            db.collection("users").document(userId).collection("runs")
                .add(runData)
                .addOnSuccessListener { Toast.makeText(this, "Run saved successfully!", Toast.LENGTH_SHORT).show() }
                .addOnFailureListener { e -> Toast.makeText(this, "Failed to save run: ${e.message}", Toast.LENGTH_SHORT).show() }
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
                updateUIForState(AppState.PLANNING)
                binding.textViewPlanningInstructions.text = "Tap on the map to set a start point."
            }
        }
    }

    private fun getDirections(origin: LatLng, destination: LatLng) = lifecycleScope.launch {
        try {
            val directionsResult = withContext(Dispatchers.IO) {
                val request = DirectionsApi.newRequest(geoApiContext)
                    .origin(com.google.maps.model.LatLng(origin.latitude, origin.longitude))
                    .destination(com.google.maps.model.LatLng(destination.latitude, destination.longitude))
                request.await()
            }

            if (directionsResult.routes.isNotEmpty()) {
                val route = directionsResult.routes[0]
                val decodedPath = PolyUtil.decode(route.overviewPolyline.encodedPath)
                plannedRoutePolyline = googleMap?.addPolyline(PolylineOptions().addAll(decodedPath).color(Color.MAGENTA).width(12f))

                if (route.legs.isNotEmpty()) {
                    plannedDistance = route.legs[0].distance
                    binding.textViewPlanningInstructions.text = "Planned Distance: ${plannedDistance?.humanReadable}"
                    binding.buttonSavePlan.visibility = View.VISIBLE
                }
            } else {
                binding.textViewPlanningInstructions.text = "No route found. Tap to reset."
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("DirectionsAPI", "Error getting directions", e)
            Toast.makeText(this@MainActivity, "Directions API Error: ${e.message}", Toast.LENGTH_LONG).show()
            binding.textViewPlanningInstructions.text = "Error finding route. Tap to reset."
        }
    }

    private fun showSaveRouteDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Save Route")

        val input = EditText(this)
        input.hint = "Enter route name (e.g., Park Loop)"
        builder.setView(input)

        builder.setPositiveButton("Save") { _, _ ->
            val routeName = input.text.toString()
            if (routeName.isNotBlank()) {
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

        if (userId!= null && start!= null && end!= null && polyline!= null && plannedDistance!= null) {
            val routeData = hashMapOf(
                "name" to routeName,
                "distanceInMeters" to plannedDistance!!.inMeters,
                "pathPoints" to polyline.points.map { mapOf("latitude" to it.latitude, "longitude" to it.longitude) },
                "startPoint" to mapOf("latitude" to start.latitude, "longitude" to start.longitude),
                "endPoint" to mapOf("latitude" to end.latitude, "longitude" to end.longitude),
                "timestamp" to System.currentTimeMillis()
            )

            db.collection("users").document(userId).collection("planned_routes").add(routeData)
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
        val userId = auth.currentUser?.uid?: return
        db.collection("users").document(userId).collection("planned_routes").document(runId)
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    Toast.makeText(this, "Planned route not found.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val plannedRun = document.toObject<PlannedRun>()
                if (plannedRun!= null) {
                    updateUIForState(AppState.PLANNING)
                    val pathPoints = plannedRun.pathPoints.map { LatLng(it["latitude"]!!, it["longitude"]!!) }
                    if (pathPoints.isNotEmpty()) {
                        clearMap()
                        plannedRoutePolyline = googleMap?.addPolyline(PolylineOptions().color(Color.MAGENTA).width(12f).addAll(pathPoints))

                        val bounds = LatLngBounds.builder().apply {
                            pathPoints.forEach { include(it) }
                        }.build()
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
        googleMap?.clear()
        startMarker = null
        endMarker = null
        plannedRoutePolyline = null
        liveRoutePolyline = null
        plannedDistance = null
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (currentState!= AppState.TRACKING) return
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
        liveRoutePolyline?.remove()
        val polylineOptions = PolylineOptions().color(Color.BLUE).width(10f).addAll(pathPoints)
        liveRoutePolyline = googleMap?.addPolyline(polylineOptions)
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)!= PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            getDeviceLocation()
        }
    }

    private fun getDeviceLocation() {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )!= PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )!= PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            googleMap?.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location!= null) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                }
            }
        } catch (e: SecurityException) {
            Log.e("LocationPermission", "Lost location permission.", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getDeviceLocation()
            } else {
                Toast.makeText(this, "Location permission is required for core functionality.", Toast.LENGTH_LONG).show()
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
        geoApiContext.shutdown()
        timerJob?.cancel()
        binding.mapView.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}