package com.shimtraveling.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shimtraveling.R
import com.shimtraveling.data.model.Place
import com.shimtraveling.databinding.ItemPlaceBinding

class PlaceAdapter(
    private val onItemClick: (Place) -> Unit,
    private val onLikeClick: (Place) -> Unit
) : ListAdapter<Place, PlaceAdapter.PlaceViewHolder>(PlaceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaceViewHolder(private val binding: ItemPlaceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(place: Place) {
            binding.apply {
                placeName.text = place.name
                placeLocation.text = place.address ?: "Lieu non spécifié"
                placeType.text = place.type.getDisplayName()
                likesCount.text = place.likes.toString()

                Glide.with(itemView.context)
                    .load(place.imageUrl)
                    .placeholder(R.drawable.placeholder_image)
                    .centerCrop()
                    .into(placeImage)

                likeButton.setImageResource(
                    if (place.isLiked) R.drawable.ic_liked else R.drawable.ic_like
                )

                root.setOnClickListener { onItemClick(place) }
                likeButton.setOnClickListener { onLikeClick(place) }
            }
        }
    }

    class PlaceDiffCallback : DiffUtil.ItemCallback<Place>() {
        override fun areItemsTheSame(oldItem: Place, newItem: Place): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Place, newItem: Place): Boolean {
            return oldItem == newItem
        }
    }
}
