package com.shimtraveling.features.profile

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Place
import com.shimtraveling.data.model.Photo
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.databinding.ActivityFavoritesBinding
import com.shimtraveling.features.path.PathDetailActivity
import com.shimtraveling.features.photo.PhotoDetailActivity
import com.shimtraveling.features.place.PlaceDetailActivity
import com.shimtraveling.ui.adapter.PhotoAdapter
import com.shimtraveling.ui.adapter.PathAdapter
import com.shimtraveling.ui.adapter.PlaceAdapter
import com.shimtraveling.ui.viewmodel.PhotoViewModel
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var placeAdapter: PlaceAdapter
    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var pathAdapter: PathAdapter
    private lateinit var photoViewModel: PhotoViewModel
    private var currentTabIndex = 0
    private var currentUserId: String? = null

    private val photoDetailLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val photoId = data?.getStringExtra("photoId")
            val isLiked = data?.getBooleanExtra("isLiked", false) ?: false
            val likesCount = data?.getIntExtra("likesCount", 0) ?: 0
            if (photoId != null) {
                if (!isLiked) {
                    val currentList = photoAdapter.currentList.toMutableList()
                    currentList.removeAll { it.id == photoId }
                    photoAdapter.submitList(currentList)
                    if (currentList.isEmpty()) {
                        binding.emptyView.visibility = android.view.View.VISIBLE
                        binding.emptyView.text = "Aucune photo favorite"
                    }
                } else {
                    photoAdapter.updatePhoto(photoId, isLiked, likesCount)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoViewModel = ViewModelProvider(this, PhotoViewModel.Factory(application))[PhotoViewModel::class.java]

        setupToolbar()
        setupAdapters()
        setupTabs()
        loadPlaceFavorites()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Favoris"
    }

    private fun setupAdapters() {
        placeAdapter = PlaceAdapter(
            onItemClick = { place -> onPlaceClick(place) },
            onLikeClick = { place ->
                lifecycleScope.launch {
                    val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
                    userResult.onSuccess { user ->
                        if (user != null) {
                            val isLiked = TravelingApp.getInstance().placeRepository.isPlaceLiked(place.id, user.id)
                            if (isLiked) {
                                TravelingApp.getInstance().placeRepository.unlikePlace(place.id, user.id)
                                TravelingApp.getInstance().userRepository.removePlaceFavoriteSuspend(place.id)

                                val currentList = placeAdapter.currentList.toMutableList()
                                currentList.removeAll { it.id == place.id }
                                placeAdapter.submitList(currentList)
                                if (currentList.isEmpty()) {
                                    binding.emptyView.visibility = View.VISIBLE
                                    binding.emptyView.text = "Aucun lieu favori"
                                }
                            } else {
                                TravelingApp.getInstance().placeRepository.likePlace(place.id, user.id)
                                TravelingApp.getInstance().userRepository.addPlaceFavoriteSuspend(place.id)
                            }
                            val updatedPlaces = placeAdapter.currentList.toMutableList()
                            val index = updatedPlaces.indexOfFirst { it.id == place.id }
                            if (index != -1) {
                                updatedPlaces[index] = place.copy(
                                    isLiked = !isLiked,
                                    likes = if (isLiked) place.likes - 1 else place.likes + 1
                                )
                                placeAdapter.submitList(updatedPlaces)
                            }
                        } else {
                            android.widget.Toast.makeText(
                                this@FavoritesActivity,
                                "Connectez-vous pour aimer ce lieu",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        )

        photoAdapter = PhotoAdapter(
            onPhotoClick = { photo -> onPhotoClick(photo) },
            onLikeClick = { photo ->
                val result = photoViewModel.toggleLike(photo)
                photoAdapter.updatePhoto(photo.id, result.first, result.second)
            }
        )

        pathAdapter = PathAdapter(
            onPathClick = { path -> onPathClick(path) }
        )
    }

    private fun reloadPhotoFavorites() {
        val userId = currentUserId ?: return
        lifecycleScope.launch {
            val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
            userResult.onSuccess { user ->
                if (user != null && user.photoFavorites.isNotEmpty()) {
                    val photos = mutableListOf<Photo>()
                    for (photoId in user.photoFavorites) {
                        val photoResult = TravelingApp.getInstance().photoRepository.getPhotoByIdWithLikeStatus(photoId, user.id)
                        photoResult.getOrNull()?.let { photos.add(it) }
                    }
                    photoAdapter.submitList(photos)
                }
            }
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabIndex = tab?.position ?: 0
                when (currentTabIndex) {
                    0 -> loadPlaceFavorites()
                    1 -> loadPhotoFavorites()
                    2 -> loadPathLikes()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadPlaceFavorites() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = placeAdapter

        lifecycleScope.launch {
            TravelingApp.getInstance().userRepository.getCurrentUser().collect { result ->
                result.onSuccess { user ->
                    if (user != null && user.placeFavorites.isNotEmpty()) {
                        val places = mutableListOf<Place>()
                        for (placeId in user.placeFavorites) {
                            val placeResult = TravelingApp.getInstance().firestoreRepository.getPlaceById(placeId)
                            placeResult.getOrNull()?.let { place ->
                                val isLiked = TravelingApp.getInstance().placeRepository.isPlaceLiked(place.id, user.id)
                                places.add(place.copy(isLiked = isLiked))
                            }
                        }
                        placeAdapter.submitList(places)
                        binding.emptyView.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
                        binding.emptyView.text = "Aucun lieu favori"
                    } else {
                        placeAdapter.submitList(emptyList())
                        binding.emptyView.visibility = View.VISIBLE
                        binding.emptyView.text = "Aucun lieu favori"
                    }
                }
            }
        }
    }

    private fun loadPathLikes() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = pathAdapter

        lifecycleScope.launch {
            val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
            val user = userResult.getOrNull()
            if (user == null) {
                pathAdapter.submitList(emptyList())
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = "Connectez-vous pour voir vos parcours likés"
                return@launch
            }

            val likedResult = TravelingApp.getInstance().firestoreRepository.getLikedPathsByUser(user.id)
            likedResult.onSuccess { paths ->
                pathAdapter.submitList(paths)
                binding.emptyView.visibility = if (paths.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyView.text = "Aucun parcours liké"
            }
            likedResult.onFailure { error ->
                pathAdapter.submitList(emptyList())
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = error.message ?: "Aucun parcours liké"
            }
        }
    }

    private fun loadPhotoFavorites() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = photoAdapter

        lifecycleScope.launch {
            TravelingApp.getInstance().userRepository.getCurrentUser().collect { result ->
                result.onSuccess { user ->
                    currentUserId = user?.id
                    if (user != null && user.photoFavorites.isNotEmpty()) {
                        val photos = mutableListOf<Photo>()
                        for (photoId in user.photoFavorites) {
                            val photoResult = TravelingApp.getInstance().photoRepository.getPhotoByIdWithLikeStatus(photoId, user.id)
                            photoResult.getOrNull()?.let { photos.add(it) }
                        }
                        photoAdapter.submitList(photos)
                        binding.emptyView.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                        binding.emptyView.text = "Aucune photo favorite"
                    } else {
                        photoAdapter.submitList(emptyList())
                        binding.emptyView.visibility = View.VISIBLE
                        binding.emptyView.text = "Aucune photo favorite"
                    }
                }
            }
        }
    }

    private fun onPlaceClick(place: Place) {
        val intent = Intent(this, PlaceDetailActivity::class.java)
        intent.putExtra("place", place)
        startActivity(intent)
    }

    private fun onPhotoClick(photo: Photo) {
        val intent = Intent(this, PhotoDetailActivity::class.java)
        intent.putExtra("photo", photo)
        photoDetailLauncher.launch(intent)
    }

    private fun onPathClick(path: TravelPath) {
        val intent = Intent(this, PathDetailActivity::class.java)
        intent.putExtra("path", path)
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

}
