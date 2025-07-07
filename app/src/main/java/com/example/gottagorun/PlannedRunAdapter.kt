package com.example.gottagorun

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gottagorun.databinding.ItemPlannedRunBinding

class PlannedRunAdapter(
    private val plannedRuns: List<PlannedRun>,
    private val onItemClicked: (PlannedRun) -> Unit // Add this listener
) : RecyclerView.Adapter<PlannedRunAdapter.PlannedRunViewHolder>() {

    class PlannedRunViewHolder(val binding: ItemPlannedRunBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlannedRunViewHolder {
        val binding = ItemPlannedRunBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlannedRunViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlannedRunViewHolder, position: Int) {
        val plannedRun = plannedRuns[position]
        holder.binding.textViewRouteName.text = plannedRun.name


        val distanceInKm = plannedRun.distanceInMeters / 1000.0
        holder.binding.textViewRouteDistance.text = String.format("Distance: %.2f km", distanceInKm)

        // Set the click listener on the item view
        holder.itemView.setOnClickListener {
            onItemClicked(plannedRun)
        }
    }

    override fun getItemCount() = plannedRuns.size
}
