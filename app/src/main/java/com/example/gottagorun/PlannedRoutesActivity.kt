package com.example.gottagorun

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gottagorun.databinding.ActivityPlannedRoutesBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class PlannedRoutesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlannedRoutesBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var plannedRunAdapter: PlannedRunAdapter
    private val plannedRunsList = mutableListOf<PlannedRun>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlannedRoutesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupRecyclerView()
        fetchPlannedRuns()
    }

    private fun setupRecyclerView() {
        plannedRunAdapter = PlannedRunAdapter(plannedRunsList) { selectedRun ->
            // This code runs when a user clicks an item in the list
            val resultIntent = Intent()
            resultIntent.putExtra("SELECTED_RUN_ID", selectedRun.documentId)
            setResult(Activity.RESULT_OK, resultIntent)
            finish() // Close this screen and return to MainActivity
        }
        binding.recyclerViewPlannedRuns.apply {
            adapter = plannedRunAdapter
            layoutManager = LinearLayoutManager(this@PlannedRoutesActivity)
        }
    }

    private fun fetchPlannedRuns() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            db.collection("users").document(userId).collection("planned_routes")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener { documents ->
                    plannedRunsList.clear()
                    for (document in documents) {
                        val plannedRun = document.toObject(PlannedRun::class.java)
                        plannedRun.documentId = document.id
                        plannedRunsList.add(plannedRun)
                    }
                    plannedRunAdapter.notifyDataSetChanged()
                }
                .addOnFailureListener { exception ->
                    Toast.makeText(this, "Error getting planned routes: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}