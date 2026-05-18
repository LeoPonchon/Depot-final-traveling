package com.shimtraveling.features.place

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shimtraveling.R
import com.shimtraveling.core.NavigationHelper
import com.shimtraveling.core.CityResolver
import com.shimtraveling.ui.main.MainActivity
import com.shimtraveling.data.model.Place
import com.shimtraveling.databinding.ActivityPlaceDetailBinding
import com.shimtraveling.features.photo.PhotoDetailActivity
import com.shimtraveling.ui.common.openGuide
import com.shimtraveling.ui.adapter.CommentAdapter
import com.shimtraveling.ui.adapter.PhotoAdapter
import com.shimtraveling.ui.viewmodel.PlaceDetailViewModel
import kotlinx.coroutines.launch
import java.util.Locale

class PlaceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaceDetailBinding
    private lateinit var navigationHelper: NavigationHelper
    private lateinit var commentAdapter: CommentAdapter
    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var viewModel: PlaceDetailViewModel

    private val photoDetailLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val photoId = data?.getStringExtra("photoId") ?: return@registerForActivityResult
            val isLiked = data.getBooleanExtra("isLiked", false)
            val likesCount = data.getIntExtra("likesCount", 0)
            photoAdapter.updatePhoto(photoId, isLiked, likesCount)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navigationHelper = NavigationHelper(this)
        viewModel = ViewModelProvider(this, PlaceDetailViewModel.Factory(application))[PlaceDetailViewModel::class.java]

        setupToolbar()
        setupCommentsRecyclerView()
        setupPhotosRecyclerView()
        setupButtons()

        @Suppress("DEPRECATION")
        val place = intent.getParcelableExtra<Place>("place")
        if (place != null) {
            bindPlaceUI(place)
            viewModel.initialize(place)
        }

        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupCommentsRecyclerView() {
        commentAdapter = CommentAdapter()
        binding.commentsRecycler.apply {
            layoutManager = LinearLayoutManager(this@PlaceDetailActivity)
            adapter = commentAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupPhotosRecyclerView() {
        photoAdapter = PhotoAdapter(
            onPhotoClick = { photo ->
                val intent = Intent(this, PhotoDetailActivity::class.java)
                intent.putExtra("photo", photo)
                photoDetailLauncher.launch(intent)
            },
            onLikeClick = { photo -> viewModel.togglePhotoLike(photo) }
        )
        binding.photosRecycler.apply {
            layoutManager = GridLayoutManager(this@PlaceDetailActivity, 2)
            adapter = photoAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun bindPlaceUI(place: Place) {
        Glide.with(this).load(place.imageUrl).centerCrop().into(binding.placeImage)
        binding.placeName.text = place.name
        binding.placeDescription.text = place.description
        binding.placeLocation.text = place.address ?: "Lieu non spécifié"
        if (!place.openingHours.isNullOrBlank()) {
            binding.openingHoursLabel.visibility = View.VISIBLE
            binding.placeOpeningHours.visibility = View.VISIBLE
            binding.placeOpeningHours.text = place.openingHours
        } else {
            binding.openingHoursLabel.visibility = View.GONE
            binding.placeOpeningHours.visibility = View.GONE
        }
        binding.placeType.text = place.type.getDisplayName()
        binding.likesCount.text = place.likes.toString()
        binding.authorName.text = String.format("Par %s", place.authorName)
        updateLikeButton(place.isLiked)

        binding.priceLabel.visibility = View.GONE
        binding.placePrice.visibility = View.GONE
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.place.collect { place ->
                place ?: return@collect
                binding.likesCount.text = place.likes.toString()
                updateLikeButton(place.isLiked)

                val isLoggedIn = viewModel.getCurrentUserId() != null
                binding.commentInputContainer.visibility =
                    if (isLoggedIn) View.VISIBLE else View.GONE

                if (place.price != null) {
                    binding.placePrice.text = String.format(Locale.getDefault(), "%.2f €", place.price)
                    binding.priceLabel.visibility = View.VISIBLE
                    binding.placePrice.visibility = View.VISIBLE
                } else {
                    binding.priceLabel.visibility = View.GONE
                    binding.placePrice.visibility = View.GONE
                }

                if (!place.openingHours.isNullOrBlank()) {
                    binding.openingHoursLabel.visibility = View.VISIBLE
                    binding.placeOpeningHours.visibility = View.VISIBLE
                    binding.placeOpeningHours.text = place.openingHours
                } else {
                    binding.openingHoursLabel.visibility = View.GONE
                    binding.placeOpeningHours.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.photos.collect { photos ->
                if (photos.isEmpty()) {
                    binding.photosRecycler.visibility = View.GONE
                    binding.noPhotosText.visibility = View.VISIBLE
                } else {
                    binding.photosRecycler.visibility = View.VISIBLE
                    binding.noPhotosText.visibility = View.GONE
                    photoAdapter.submitList(photos)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.comments.collect { comments ->
                commentAdapter.submitList(comments)
            }
        }

        lifecycleScope.launch {
            viewModel.commentError.collect { error ->
                if (error != null) {
                    android.widget.Toast.makeText(this@PlaceDetailActivity, error, android.widget.Toast.LENGTH_SHORT).show()
                    viewModel.clearCommentError()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.reportStatus.collect { success ->
                if (success != null) {
                    val msg = if (success) "Signalement envoyé" else "Erreur lors du signalement"
                    android.widget.Toast.makeText(this@PlaceDetailActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    viewModel.clearReportStatus()
                }
            }
        }
    }

    private fun setupButtons() {
        binding.likeButton.setOnClickListener { viewModel.toggleLike() }

        binding.directionsButton.setOnClickListener {
            viewModel.place.value?.let { p ->
                lifecycleScope.launch {
                    val location = com.shimtraveling.TravelingApp.getInstance().locationService.getCurrentLocation()
                    if (location != null) {
                        navigationHelper.openDirectionsToPlace(
                            p.latitude, p.longitude, p.name,
                            location.latitude, location.longitude
                        )
                    } else {
                        navigationHelper.openNavigationToPlace(p.latitude, p.longitude, p.name)
                    }
                }
            }
        }

        binding.shareButton.setOnClickListener {
            viewModel.place.value?.let { p ->
                navigationHelper.sharePlace(p.latitude, p.longitude, p.name)
            }
        }

        binding.pathFromPlaceButton.setOnClickListener {
            viewModel.place.value?.let { p ->
                val city = suggestCity(p.address, p.name)
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_PATH, true)
                    putExtra(MainActivity.EXTRA_PREFILL_CITY, city)
                })
            }
        }

        binding.reportButton.setOnClickListener { showReportDialog() }

        binding.sendCommentButton.setOnClickListener {
            val content = binding.commentInput.text.toString().trim()
            viewModel.addComment(content)
            binding.commentInput.text?.clear()
        }
    }

    private fun suggestCity(address: String?, name: String): String =
        CityResolver.guessCity(address, name)

    private fun showReportDialog() {
        val reasons = arrayOf(
            "Contenu inapproprié", "Spam ou arnaque",
            "Informations incorrectes", "Contenu offensant", "Autre"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Signaler ce lieu")
            .setItems(reasons) { _, which -> viewModel.reportPlace(reasons[which]) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun updateLikeButton(isLiked: Boolean) {
        val iconRes = if (isLiked) R.drawable.ic_liked else R.drawable.ic_like
        binding.likeButton.setIconResource(iconRes)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.help_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                openGuide()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
