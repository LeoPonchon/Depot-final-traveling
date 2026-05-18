package com.shimtraveling.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.databinding.ItemPathDetailHeaderBinding

class PathDetailHeaderAdapter : RecyclerView.Adapter<PathDetailHeaderAdapter.VH>() {

    private var path: TravelPath? = null
    private var weatherText: String? = null

    fun setPath(value: TravelPath) {
        path = value
        notifyDataSetChanged()
    }

    fun setWeather(text: String?) {
        weatherText = text
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPathDetailHeaderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = 1

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(path, weatherText)
    }

    class VH(private val binding: ItemPathDetailHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(path: TravelPath?, weather: String?) {
            if (path == null) return
            binding.pathDescription.text = path.description
            binding.totalDuration.text = path.formattedDuration
            binding.totalCost.text = if (path.hasCompletePricing && path.totalCost != null) {
                String.format("%.2f €", path.totalCost)
            } else {
                "Prix indisponible"
            }
            binding.totalEffort.text = path.totalEffort.getDisplayName()
            binding.totalDistance.text = String.format("%.1f km", path.distanceKm)

            val w = weather?.trim().orEmpty()
            if (w.isNotEmpty()) {
                binding.weatherBanner.visibility = View.VISIBLE
                binding.weatherBanner.text = w
            } else {
                binding.weatherBanner.visibility = View.GONE
                binding.weatherBanner.text = ""
            }
        }
    }
}
