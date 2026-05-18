package com.shimtraveling.features.profile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Photo
import com.shimtraveling.databinding.ActivityMyPhotosBinding
import com.shimtraveling.features.photo.PhotoDetailActivity
import com.shimtraveling.features.share.PublishPhotoActivity
import com.shimtraveling.ui.adapter.PhotoAdapter
import com.shimtraveling.ui.viewmodel.PhotoViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MyPhotosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyPhotosBinding
    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var photoViewModel: PhotoViewModel
    private var currentUserId: String? = null
    private var allPhotos: List<Photo> = emptyList()

    private val photoDetailLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val photoId = data?.getStringExtra("photoId")
            val isLiked = data?.getBooleanExtra("isLiked", false) ?: false
            val likesCount = data?.getIntExtra("likesCount", 0) ?: 0
            if (photoId != null) {
                photoAdapter.updatePhoto(photoId, isLiked, likesCount)

                allPhotos = allPhotos.map {
                    if (it.id == photoId) it.copy(isLiked = isLiked, likes = likesCount) else it
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyPhotosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoViewModel = ViewModelProvider(this, PhotoViewModel.Factory(application))[PhotoViewModel::class.java]

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupFab()
        loadMyPhotos()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupRecyclerView() {
        photoAdapter = PhotoAdapter(
            onPhotoClick = { photo -> onPhotoClick(photo) },
            onLikeClick = { photo ->
                val result = photoViewModel.toggleLike(photo)
                photoAdapter.updatePhoto(photo.id, result.first, result.second)
            }
        )
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@MyPhotosActivity, 2)
            adapter = photoAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupSearch() {
        binding.searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                filterPhotos(v.text?.toString() ?: "")
                true
            } else false
        }
        binding.searchInput.doOnTextChanged { text, _, _, _ ->
            filterPhotos(text?.toString() ?: "")
        }
    }

    private fun setupFab() {
        binding.publishButton.setOnClickListener {
            val intent = Intent(this, PublishPhotoActivity::class.java)
            startActivity(intent)
        }
    }

    private fun filterPhotos(query: String) {
        val filtered = if (query.isBlank()) {
            allPhotos
        } else {
            val terms = query.split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }

            if (terms.isEmpty()) {
                allPhotos
            } else {
                allPhotos.filter { photo ->
                    terms.all { term ->
                        photo.placeName.lowercase().contains(term) ||
                        photo.tags.any { it.lowercase().contains(term) }
                    }
                }
            }
        }
        displayPhotos(filtered)
    }

    private fun setupTagChips(tags: List<String>) {
        binding.filterChips.removeAllViews()

        val allChip = Chip(this).apply {
            text = "Tout"
            isCheckable = true
            isChecked = true
            setOnClickListener {
                filterPhotos(binding.searchInput.text?.toString() ?: "")
            }
        }
        binding.filterChips.addView(allChip)

        tags.forEach { tag ->
            val chip = Chip(this).apply {
                text = tag.replaceFirstChar { it.uppercase() }
                isCheckable = true
                setOnClickListener {
                    filterByTag(tag)
                }
            }
            binding.filterChips.addView(chip)
        }
    }

    private fun filterByTag(tag: String) {
        val filtered = allPhotos.filter { it.tags.contains(tag) }
        displayPhotos(filtered)
    }

    private fun reloadPhotos() {
        val userId = currentUserId ?: return
        lifecycleScope.launch {
            val result = TravelingApp.getInstance().photoRepository
                .getPhotosByAuthorWithLikeStatus(userId, userId).first()
            result.onSuccess { photos ->
                allPhotos = photos
                photoAdapter.submitList(photos)
                setupTagChips(extractTags(photos))
            }
        }
    }

    private fun extractTags(photos: List<Photo>): List<String> {
        return photos.flatMap { it.tags }.distinct().sorted()
    }

    private fun onPhotoClick(photo: Photo) {
        val intent = Intent(this, PhotoDetailActivity::class.java)
        intent.putExtra("photo", photo)
        photoDetailLauncher.launch(intent)
    }

    private fun loadMyPhotos() {
        lifecycleScope.launch {
            val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
            userResult.onSuccess { user ->
                currentUserId = user?.id
                if (user != null) {
                    val photosResult = TravelingApp.getInstance().photoRepository
                        .getPhotosByAuthorWithLikeStatus(user.id, user.id).first()
                    photosResult.onSuccess { photos ->
                        allPhotos = photos
                        displayPhotos(photos)
                        setupTagChips(extractTags(photos))
                    }
                } else {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                }
            }
        }
    }

    private fun displayPhotos(photos: List<Photo>) {
        photoAdapter.submitList(photos)
        if (photos.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
