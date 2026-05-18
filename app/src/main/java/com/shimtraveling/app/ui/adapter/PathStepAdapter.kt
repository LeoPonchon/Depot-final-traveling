package com.shimtraveling.ui.adapter

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shimtraveling.R
import com.shimtraveling.data.model.PathStep
import com.shimtraveling.data.model.Photo
import com.shimtraveling.databinding.ItemPathStepBinding
import com.shimtraveling.features.common.VideoPlayerActivity
import com.shimtraveling.features.photo.PhotoDetailActivity
import kotlin.math.roundToInt

class PathStepAdapter(
    private val onStepClick: (PathStep) -> Unit,
    private val onGalleryClick: ((PathStep) -> Unit)? = null,
    private val onSharePhotoClick: ((Photo) -> Unit)? = null
) : ListAdapter<PathStep, PathStepAdapter.StepViewHolder>(StepDiffCallback()) {

    private var photosByPlaceId: Map<String, List<Photo>> = emptyMap()

    fun updateSharePhotos(map: Map<String, List<Photo>>) {
        photosByPlaceId = map
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val binding = ItemPathStepBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StepViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StepViewHolder(private val binding: ItemPathStepBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(step: PathStep) {
            binding.stepNumber.text = step.order.toString()
            binding.stepName.text = step.placeName
            binding.stepDuration.text = "${step.estimatedDurationMinutes} min"
            binding.stepCost.text = step.estimatedCost?.let { String.format("%.2f €", it) } ?: "Prix indisponible"
            binding.stepActivityType.text = step.activityType.getDisplayName()
            val hours = step.openingHours?.trim().orEmpty()
            if (hours.isNotEmpty()) {
                binding.stepOpeningHours.visibility = View.VISIBLE
                binding.stepOpeningHours.text = itemView.context.getString(
                    R.string.path_step_opening_hours,
                    hours
                )
            } else {
                binding.stepOpeningHours.visibility = View.GONE
            }
            val el = step.elevationMeters
            if (el != null) {
                binding.stepElevation.visibility = View.VISIBLE
                binding.stepElevation.text = itemView.context.getString(R.string.path_step_elevation, el)
            } else {
                binding.stepElevation.visibility = View.GONE
            }
            binding.stepTimeSlot.text = when (step.timeOfDay) {
                com.shimtraveling.data.model.TimeOfDay.MORNING -> "Matin"
                com.shimtraveling.data.model.TimeOfDay.AFTERNOON -> "Après-midi"
                com.shimtraveling.data.model.TimeOfDay.EVENING -> "Soir"
                null -> "Créneau inconnu"
            }

            Glide.with(itemView.context)
                .load(step.placeImageUrl)
                .placeholder(R.drawable.placeholder_image)
                .centerCrop()
                .into(binding.stepImage)

            val sharePhotos = photosByPlaceId[step.placeId].orEmpty()
            if (sharePhotos.isEmpty()) {
                binding.stepSharePhotosLabel.visibility = View.GONE
                binding.stepPhotoStrip.visibility = View.GONE
            } else {
                binding.stepSharePhotosLabel.visibility = View.VISIBLE
                binding.stepPhotoStrip.visibility = View.VISIBLE
                binding.stepPhotoStripInner.removeAllViews()
                val dp = binding.root.resources.displayMetrics.density
                val size = (88 * dp).roundToInt()
                val margin = (6 * dp).roundToInt()
                for (p in sharePhotos.take(12)) {
                    val iv = ImageView(itemView.context).apply {
                        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setOnClickListener {
                            onSharePhotoClick?.invoke(p) ?: run {
                                itemView.context.startActivity(
                                    Intent(itemView.context, PhotoDetailActivity::class.java)
                                        .putExtra("photo", p)
                                )
                            }
                        }
                    }
                    Glide.with(itemView.context).load(p.url).centerCrop().into(iv)
                    binding.stepPhotoStripInner.addView(iv)
                }
            }

            val video = step.videoUrl?.trim().orEmpty()
            if (video.isNotEmpty()) {
                binding.videoButton.visibility = View.VISIBLE
                binding.videoButton.setOnClickListener {
                    itemView.context.startActivity(
                        VideoPlayerActivity.createIntent(itemView.context, video, step.placeName)
                    )
                }
            } else {
                binding.videoButton.visibility = View.GONE
                binding.videoButton.setOnClickListener(null)
            }

            binding.root.setOnClickListener { onStepClick(step) }
            binding.galleryButton.setOnClickListener {
                onGalleryClick?.invoke(step)
            }
        }
    }

    class StepDiffCallback : DiffUtil.ItemCallback<PathStep>() {
        override fun areItemsTheSame(oldItem: PathStep, newItem: PathStep): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PathStep, newItem: PathStep): Boolean {
            return oldItem == newItem
        }
    }
}
