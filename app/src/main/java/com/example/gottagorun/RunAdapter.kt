package com.example.gottagorun

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gottagorun.databinding.ItemRunBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class RunAdapter(
    private val runs: List<Run>,
    private val onItemClicked: (Run) -> Unit // Add this click listener parameter
) : RecyclerView.Adapter<RunAdapter.RunViewHolder>() {

    class RunViewHolder(val binding: ItemRunBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RunViewHolder {
        val binding = ItemRunBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RunViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RunViewHolder, position: Int) {
        val run = runs[position]

        // Format Date
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        holder.binding.textViewRunDate.text = "Date: ${sdf.format(Date(run.timestamp))}"

        // Format Distance
        val distanceInKm = run.distanceInMeters / 1000f
        holder.binding.textViewRunDistance.text = String.format("Distance: %.2f km", distanceInKm)

        // Format Time
        val minutes = TimeUnit.MILLISECONDS.toMinutes(run.timeInMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(run.timeInMillis) % 60
        holder.binding.textViewRunTime.text = String.format("Time: %02d:%02d", minutes, seconds)

        // Set the click listener on the item view
        holder.itemView.setOnClickListener {
            onItemClicked(run)
        }
    }

    override fun getItemCount() = runs.size
}