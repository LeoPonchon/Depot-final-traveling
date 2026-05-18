package com.shimtraveling.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shimtraveling.R
import com.shimtraveling.data.model.Photo
import com.shimtraveling.databinding.ItemPhotoBinding
import java.text.SimpleDateFormat
import java.util.*

class PhotoAdapter(
    private val onPhotoClick: (Photo) -> Unit,
    private val onLikeClick: ((Photo) -> Unit)? = null
) : ListAdapter<Photo, PhotoAdapter.PhotoViewHolder>(PhotoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun updatePhoto(photoId: String, isLiked: Boolean, likes: Int) {
        val currentList = currentList.toMutableList()
        val index = currentList.indexOfFirst { it.id == photoId }
        if (index != -1) {
            val photo = currentList[index]
            currentList[index] = photo.copy(isLiked = isLiked, likes = likes)
            submitList(currentList)
        }
    }

    inner class PhotoViewHolder(private val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPhotoClick(getItem(position))
                }
            }

            binding.likeButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && onLikeClick != null) {
                    val photo = getItem(position)
                    onLikeClick(photo)
                }
            }
        }

        fun bind(photo: Photo) {
            binding.photoTitle.text = photo.placeName.ifBlank { "Lieu non spécifié" }
            binding.photoDate.text = dateFormat.format(photo.createdAt)
            binding.photoLikes.text = photo.likes.toString()

            val likeIcon = if (photo.isLiked) R.drawable.ic_liked else R.drawable.ic_like
            binding.likeButton.setImageResource(likeIcon)

            Glide.with(binding.root.context)
                .load(photo.url)
                .placeholder(R.drawable.ic_placeholder)
                .centerCrop()
                .into(binding.photoImage)

            if (!photo.description.isNullOrBlank()) {
                binding.photoDescription.text = photo.description
                binding.photoDescription.visibility = android.view.View.VISIBLE
            } else {
                binding.photoDescription.visibility = android.view.View.GONE
            }

            binding.photoAuthor.text = photo.authorName

            if (!photo.authorAvatar.isNullOrBlank()) {
                Glide.with(binding.root.context)
                    .load(photo.authorAvatar)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .into(binding.authorAvatar)
            } else {
                binding.authorAvatar.setImageResource(R.drawable.ic_person)
            }

            if (!photo.address.isNullOrBlank()) {
                binding.photoLocation.text = photo.address
                binding.photoLocation.visibility = android.view.View.VISIBLE
            } else {
                binding.photoLocation.visibility = android.view.View.GONE
            }
        }
    }

    class PhotoDiffCallback : DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean {
            return oldItem == newItem
        }
    }
}
