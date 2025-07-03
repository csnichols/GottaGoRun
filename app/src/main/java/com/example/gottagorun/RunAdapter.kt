package com.example.gottagorun

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gottagorun.databinding.ItemRunBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RunAdapter(private val runs: List<Run>) : RecyclerView.Adapter<RunAdapter.RunViewHolder>() {

    class RunViewHolder(val binding: ItemRunBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RunViewHolder {
        val binding = ItemRunBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RunViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RunViewHolder, position: Int) {
        val run = runs[position]
        val context = holder.itemView.context

        // Format Date
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        holder.binding.textViewRunDate.text = "Date: ${sdf.format(Date(run.timestamp))}"

        // Format Distance
        val distanceInKm = run.distanceInMeters / 1000
        holder.binding.textViewRunDistance.text = String.format("Distance: %.2f km", distanceInKm)

        // Format Time
        val minutes = TimeUnit.MILLISECONDS.toMinutes(run.timeInMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(run.timeInMillis) % 60
        holder.binding.textViewRunTime.text = String.format("Time: %02d:%02d", minutes, seconds)

        // Add this click listener
        holder.itemView.setOnClickListener {
            val intent = Intent(context, RunDetailActivity::class.java).apply {
                putExtra("RUN_ID", run.documentId)
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount() = runs.size
}