package com.example.gottagorun

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gottagorun.databinding.ActivityRunHistoryBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class RunHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRunHistoryBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var runAdapter: RunAdapter
    private val runsList = mutableListOf<Run>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRunHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupRecyclerView()
        fetchRuns()
    }

    private fun setupRecyclerView() {
        runAdapter = RunAdapter(runsList)
        binding.recyclerViewRuns.apply {
            adapter = runAdapter
            layoutManager = LinearLayoutManager(this@RunHistoryActivity)
        }
    }

    private fun fetchRuns() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            db.collection("users").document(userId).collection("runs")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener { documents ->
                    runsList.clear()
                    for (document in documents) {
                        val run = document.toObject(Run::class.java)
                        run.documentId = document.id // <-- ADD THIS LINE to store the ID
                        runsList.add(run)
                    }
                    runAdapter.notifyDataSetChanged()
                }
                .addOnFailureListener { exception ->
                    Toast.makeText(this, "Error getting runs: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
