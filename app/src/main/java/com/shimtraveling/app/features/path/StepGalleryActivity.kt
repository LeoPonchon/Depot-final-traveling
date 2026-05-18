package com.shimtraveling.features.path

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.SessionIdentity
import com.shimtraveling.data.model.Photo
import com.shimtraveling.databinding.ActivityStepGalleryBinding
import com.shimtraveling.features.common.VideoPlayerActivity
import com.shimtraveling.features.photo.PhotoDetailActivity
import com.shimtraveling.ui.adapter.PhotoAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class StepGalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStepGalleryBinding
    private lateinit var photoAdapter: PhotoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStepGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()

        val placeId = intent.getStringExtra(EXTRA_PLACE_ID)
        val placeName = intent.getStringExtra(EXTRA_PLACE_NAME) ?: "Etape"
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)?.trim().orEmpty()

        supportActionBar?.title = placeName

        if (videoUrl.isNotEmpty()) {
            binding.videoCard.visibility = android.view.View.VISIBLE
            binding.openVideoButton.setOnClickListener {
                startActivity(VideoPlayerActivity.createIntent(this, videoUrl, placeName))
            }
        } else {
            binding.videoCard.visibility = android.view.View.GONE
        }

        placeId?.let(::loadPhotos)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupRecyclerView() {
        photoAdapter = PhotoAdapter(
            onPhotoClick = ::openPhotoDetail,
            onLikeClick = ::toggleLike
        )
        binding.photosRecycler.apply {
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            adapter = photoAdapter
        }
    }

    private fun loadPhotos(placeId: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.emptyText.visibility = android.view.View.GONE

        lifecycleScope.launch {
            val user = TravelingApp.getInstance().userRepository.getCurrentUser().first().getOrNull()
            TravelingApp.getInstance().placeRepository
                .getPhotosByPlace(placeId, user?.id, user?.groups.orEmpty())
                .collectLatest { result ->
                    binding.progressBar.visibility = android.view.View.GONE

                    result.onSuccess { photos ->
                        if (photos.isEmpty()) {
                            binding.emptyText.visibility = android.view.View.VISIBLE
                            photoAdapter.submitList(emptyList())
                        } else {
                            binding.emptyText.visibility = android.view.View.GONE
                            photoAdapter.submitList(photos)
                        }
                    }

                    result.onFailure { error ->
                        binding.emptyText.visibility = android.view.View.VISIBLE
                        Toast.makeText(
                            this@StepGalleryActivity,
                            "Erreur: ${error.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
        }
    }

    private fun openPhotoDetail(photo: Photo) {
        startActivity(Intent(this, PhotoDetailActivity::class.java).putExtra("photo", photo))
    }

    private fun toggleLike(photo: Photo) {
        val likeId = SessionIdentity.getLikeUserId(this)

        lifecycleScope.launch {
            val result = if (photo.isLiked) {
                TravelingApp.getInstance().photoRepository.unlikePhoto(photo.id, likeId)
            } else {
                TravelingApp.getInstance().photoRepository.likePhoto(photo.id, likeId)
            }

            result.onSuccess {
                val newIsLiked = !photo.isLiked
                val newLikes = if (newIsLiked) photo.likes + 1 else photo.likes - 1
                photoAdapter.updatePhoto(photo.id, newIsLiked, newLikes)
            }

            result.onFailure { error ->
                Toast.makeText(
                    this@StepGalleryActivity,
                    "Erreur: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
        const val EXTRA_PLACE_NAME = "place_name"
        const val EXTRA_VIDEO_URL = "video_url"
    }
}
