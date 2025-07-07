package com.example.gottagorun

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.gottagorun.databinding.ActivityRunDetailBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RunDetailActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityRunDetailBinding
    private var googleMap: GoogleMap? = null
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var currentRun: Run? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRunDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Run Details"

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        binding.mapViewDetail.onCreate(savedInstanceState)
        binding.mapViewDetail.getMapAsync(this)

        binding.buttonShare.setOnClickListener {
            shareRun()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun shareRun() {
        googleMap?.snapshot { mapBitmap ->
            if (mapBitmap != null && currentRun != null) {
                val shareableBitmap = createShareableImage(mapBitmap, currentRun!!)
                val imageUri = saveBitmapAndGetUri(shareableBitmap)
                if (imageUri != null) {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, imageUri)
                        type = "image/png"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Run"))
                }
            } else {
                Toast.makeText(this, "Failed to capture map or run data.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createShareableImage(mapBitmap: Bitmap, run: Run): Bitmap {
        val statsHeight = 200
        val bitmapConfig = mapBitmap.config ?: Bitmap.Config.ARGB_8888
        val resultBitmap = Bitmap.createBitmap(mapBitmap.width, mapBitmap.height + statsHeight, bitmapConfig)
        val canvas = Canvas(resultBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(mapBitmap, 0f, 0f, null)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
            style = Paint.Style.FILL
            isFakeBoldText = true
        }
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 40f
            style = Paint.Style.FILL
        }
        val distanceInKm = run.distanceInMeters / 1000f
        val distanceText = String.format("%.2f km", distanceInKm)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(run.timeInMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(run.timeInMillis) % 60
        val timeText = String.format("%02d:%02d", minutes, seconds)
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val dateText = sdf.format(Date(run.timestamp))
        val yPos = mapBitmap.height + 80f
        canvas.drawText("GottaGoRun", 40f, yPos, titlePaint)
        canvas.drawText(dateText, 40f, yPos + 60, textPaint)
        val xPosStats = mapBitmap.width - 40f
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(distanceText, xPosStats, yPos, textPaint)
        canvas.drawText(timeText, xPosStats, yPos + 60, textPaint)
        return resultBitmap
    }

    private fun saveBitmapAndGetUri(bitmap: Bitmap): Uri? {
        return try {
            val imagesFolder = File(cacheDir, "images")
            imagesFolder.mkdirs()
            val file = File(imagesFolder, "shared_run.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, stream)
            stream.flush()
            stream.close()
            FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun fetchRunDetails(runId: String) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).collection("runs").document(runId)
            .get()
            .addOnSuccessListener { document ->
                currentRun = document.toObject(Run::class.java)
                if (currentRun != null) {
                    currentRun!!.documentId = document.id // Store the document ID
                    updateUI(currentRun!!)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load run details.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateUI(run: Run) {
        val distanceInKm = run.distanceInMeters / 1000f
        binding.textViewDistanceDetail.text = String.format("Dist: %.2f km", distanceInKm)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(run.timeInMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(run.timeInMillis) % 60
        binding.textViewTimeDetail.text = String.format("Time: %02d:%02d", minutes, seconds)
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
            val boundsBuilder = LatLngBounds.Builder()
            for (point in pathPoints) {
                boundsBuilder.include(point)
            }
            val bounds = boundsBuilder.build()
            googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
            googleMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark))
        }
        val runId = intent.getStringExtra("RUN_ID")
        if (runId != null) {
            fetchRunDetails(runId)
        }
    }

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
