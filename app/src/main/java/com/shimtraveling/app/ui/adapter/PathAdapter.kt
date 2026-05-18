package com.shimtraveling.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shimtraveling.R
import com.shimtraveling.data.model.PathType
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.databinding.ItemPathBinding

class PathAdapter(
    private val onPathClick: (TravelPath) -> Unit
) : ListAdapter<TravelPath, PathAdapter.PathViewHolder>(PathDiffCallback()) {

    companion object {
        const val PLACEHOLDER_ID = "__placeholder_path__"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PathViewHolder {
        val binding = ItemPathBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PathViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PathViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PathViewHolder(private val binding: ItemPathBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(path: TravelPath) {
            binding.apply {
                val isPlaceholder = path.id == PLACEHOLDER_ID
                pathName.text = path.name
                pathType.text = path.type.getDisplayName()
                pathDuration.text = path.formattedDuration
                pathCost.text = if (path.hasCompletePricing && path.totalCost != null) {
                    String.format("%.2f €", path.totalCost)
                } else {
                    "Prix indisponible"
                }
                pathDistance.text = String.format("%.1f km", path.distanceKm)
                pathEffort.text = path.totalEffort.getDisplayName()

                pathHint.text = path.description.orEmpty()
                pathHint.visibility = if (isPlaceholder && pathHint.text.isNotBlank()) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }

                val typeColor = when (path.type) {
                    PathType.ECONOMIC -> R.color.accent
                    PathType.BALANCED -> R.color.primary
                    PathType.COMFORT -> R.color.secondary
                }

                pathType.setTextColor(root.context.getColor(typeColor))

                root.setOnClickListener {
                    if (!isPlaceholder) onPathClick(path)
                }
            }
        }
    }

    class PathDiffCallback : DiffUtil.ItemCallback<TravelPath>() {
        override fun areItemsTheSame(oldItem: TravelPath, newItem: TravelPath): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TravelPath, newItem: TravelPath): Boolean {
            return oldItem == newItem
        }
    }
}
