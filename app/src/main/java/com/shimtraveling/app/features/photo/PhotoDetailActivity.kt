package com.shimtraveling.features.photo

import android.content.Intent
import android.os.Parcelable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.CityResolver
import com.shimtraveling.core.NavigationHelper
import com.shimtraveling.features.place.PlaceDetailActivity
import com.shimtraveling.ui.main.MainActivity
import com.shimtraveling.data.model.LocationPrecision
import com.shimtraveling.data.model.Photo
import com.shimtraveling.databinding.ActivityPhotoDetailBinding
import com.shimtraveling.databinding.BottomSheetSimilarPhotosBinding
import com.shimtraveling.ui.common.openGuide
import com.shimtraveling.ui.adapter.CommentAdapter
import com.shimtraveling.ui.adapter.PhotoAdapter
import com.shimtraveling.ui.viewmodel.PhotoDetailViewModel
import com.shimtraveling.ui.viewmodel.PhotoViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class PhotoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoDetailBinding
    private lateinit var navigationHelper: NavigationHelper
    private lateinit var commentAdapter: CommentAdapter
    private lateinit var viewModel: PhotoDetailViewModel
    private val similarPhotosViewModel by lazy {
        ViewModelProvider(this, PhotoViewModel.Factory(application, autoLoad = false))[PhotoViewModel::class.java]
    }
    private var voicePlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navigationHelper = NavigationHelper(this)
        viewModel = ViewModelProvider(
            this, PhotoDetailViewModel.Factory(application)
        )[PhotoDetailViewModel::class.java]

        setupToolbar()
        setupCommentsRecyclerView()
        setupButtons()

        @Suppress("DEPRECATION")
        val photo = intent.getParcelableExtra<Photo>("photo")
        val photoIdExtra = intent.getStringExtra(EXTRA_PHOTO_ID)
        if (photo != null) {
            bindPhotoUI(photo)
            viewModel.initialize(photo)
        } else if (!photoIdExtra.isNullOrBlank()) {
            lifecycleScope.launch {
                val likeId = com.shimtraveling.core.SessionIdentity.getLikeUserId(this@PhotoDetailActivity)
                val loaded = TravelingApp.getInstance().photoRepository.getPhotoByIdWithLikeStatus(photoIdExtra, likeId)
                val p = loaded.getOrNull()
                if (p != null) {
                    bindPhotoUI(p)
                    viewModel.initialize(p)
                } else {
                    finish()
                }
            }
        }

        observeViewModel()
    }

    companion object {
        const val EXTRA_PHOTO_ID = "PHOTO_ID"
    }

    private fun openFeedForAuthor(photo: Photo) {
        startActivity(android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_FILTER_AUTHOR_ID, photo.authorId)
        })
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupCommentsRecyclerView() {
        commentAdapter = CommentAdapter()
        binding.commentsRecycler.apply {
            layoutManager = LinearLayoutManager(this@PhotoDetailActivity)
            adapter = commentAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun bindPhotoUI(photo: Photo) {
        Glide.with(this).load(photo.url).centerCrop().into(binding.photoImage)
        binding.photoPlaceName.text = photo.placeName.ifBlank { "Lieu non spécifié" }

        val visibilityText = when (photo.visibility) {
            com.shimtraveling.data.model.PhotoVisibility.PUBLIC  -> "Public"
            com.shimtraveling.data.model.PhotoVisibility.GROUP   -> "Groupe"
            com.shimtraveling.data.model.PhotoVisibility.PRIVATE -> "Privé"
        }
        binding.photoVisibility.text = visibilityText
        binding.photoDescription.text = photo.description ?: "Aucune description"

        if (photo.price != null) {
            binding.photoPrice.text = String.format(Locale.getDefault(), "Prix: %.2f €", photo.price)
            binding.photoPrice.visibility = android.view.View.VISIBLE
        } else {
            binding.photoPrice.visibility = android.view.View.GONE
        }

        if (!photo.address.isNullOrBlank()) {
            binding.photoAddress.text = photo.address
            binding.photoAddress.visibility = android.view.View.VISIBLE
        } else {
            binding.photoAddress.visibility = android.view.View.GONE
        }

        val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        binding.photoDate.text = buildString {
            photo.takenAt?.let { append(getString(R.string.photo_taken_prefix, dateFormat.format(it))) }
            if (photo.takenAt != null) append("\n")
            append(getString(R.string.photo_added_prefix, dateFormat.format(photo.createdAt)))
        }.trim()
        binding.authorName.text = photo.authorName
        binding.authorName.isClickable = true
        binding.authorName.isFocusable = true
        binding.authorName.setOnClickListener { openFeedForAuthor(photo) }
        binding.authorAvatar.isClickable = true
        binding.authorAvatar.setOnClickListener { openFeedForAuthor(photo) }
        binding.authorProfileButton.setOnClickListener { openFeedForAuthor(photo) }

        if (!photo.authorAvatar.isNullOrBlank()) {
            Glide.with(this).load(photo.authorAvatar).circleCrop()
                .placeholder(R.drawable.ic_person).into(binding.authorAvatar)
        }

        if (!photo.howToGo.isNullOrBlank()) {
            binding.photoHowToGo.text = photo.howToGo
            binding.howToGoContainer.visibility = android.view.View.VISIBLE
        } else {
            binding.howToGoContainer.visibility = android.view.View.GONE
        }

        if (photo.latitude != 0.0 && photo.longitude != 0.0) {
            val approx = photo.locationPrecision == LocationPrecision.APPROXIMATE
            val lat = if (approx) String.format(Locale.getDefault(), "%.2f", photo.latitude) else String.format(Locale.getDefault(), "%.4f", photo.latitude)
            val lng = if (approx) String.format(Locale.getDefault(), "%.2f", photo.longitude) else String.format(Locale.getDefault(), "%.4f", photo.longitude)
            binding.photoCoordinates.text = if (approx) {
                getString(R.string.photo_coordinates_approx, lat, lng)
            } else {
                getString(R.string.photo_coordinates_exact, lat, lng)
            }
            binding.coordinatesContainer.visibility = android.view.View.VISIBLE
        } else {
            binding.coordinatesContainer.visibility = android.view.View.GONE
        }

        setupVoiceSection(photo)
    }

    private fun setupVoiceSection(photo: Photo) {
        val url = photo.audioUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            binding.audioCard.visibility = android.view.View.GONE
            return
        }
        binding.audioCard.visibility = android.view.View.VISIBLE
        binding.playAudioButton.text = getString(R.string.photo_play_voice)
        binding.playAudioButton.setOnClickListener { toggleVoicePlayback(url) }
    }

    private fun toggleVoicePlayback(url: String) {
        try {
            if (voicePlayer?.isPlaying == true) {
                voicePlayer?.pause()
                binding.playAudioButton.text = getString(R.string.photo_play_voice)
                return
            }
            voicePlayer?.release()
            voicePlayer = MediaPlayer().apply {
                setDataSource(this@PhotoDetailActivity, Uri.parse(url))
                setOnPreparedListener { mp ->
                    mp.start()
                    binding.playAudioButton.text = getString(R.string.photo_playing_pause)
                }
                setOnCompletionListener {
                    binding.playAudioButton.text = getString(R.string.photo_play_voice)
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, getString(R.string.photo_audio_error), android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.photo.collect { photo ->
                photo ?: return@collect
                binding.likesCount.text = photo.likes.toString()
                updateLikeButton(photo.isLiked)

                val isLoggedIn = viewModel.getCurrentUserId() != null
                binding.commentInputContainer.visibility =
                    if (isLoggedIn) android.view.View.VISIBLE else android.view.View.GONE

                val isAuthor = viewModel.getCurrentUserId() == photo.authorId
                binding.deleteButton.visibility =
                    if (isAuthor) android.view.View.VISIBLE else android.view.View.GONE

                setupVoiceSection(photo)
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
                    android.widget.Toast.makeText(
                        this@PhotoDetailActivity, error, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    viewModel.clearCommentError()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.deleteStatus.collect { success ->
                if (success != null) {
                    if (success) {
                        android.widget.Toast.makeText(
                            this@PhotoDetailActivity, "Photo supprimée", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    } else {
                        android.widget.Toast.makeText(
                            this@PhotoDetailActivity,
                            "Erreur lors de la suppression",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        viewModel.clearDeleteStatus()
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.reportStatus.collect { status ->
                if (status != null) {
                    val msg = if (status) getString(R.string.photo_report_sent)
                              else getString(R.string.photo_report_failed)
                    android.widget.Toast.makeText(
                        this@PhotoDetailActivity, msg, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    viewModel.clearReportStatus()
                }
            }
        }
    }

    private fun setupButtons() {
        binding.likeButton.setOnClickListener { viewModel.toggleLike() }

        binding.pathFromPhotoButton.setOnClickListener {
            viewModel.photo.value?.let { p ->
                val city = suggestCity(p.address, p.placeName.ifBlank { p.placeId })
                startActivity(android.content.Intent(this, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_PATH, true)
                    putExtra(MainActivity.EXTRA_PREFILL_CITY, city)
                })
            }
        }

        binding.openPlaceButton.setOnClickListener {
            viewModel.photo.value?.let { p ->
                lifecycleScope.launch {
                    val place = TravelingApp.getInstance().placeRepository.getPlaceById(p.placeId).first().getOrNull()
                    if (place != null) {
                        startActivity(Intent(this@PhotoDetailActivity, PlaceDetailActivity::class.java).apply {
                            putExtra("place", place as Parcelable)
                        })
                    } else {
                        android.widget.Toast.makeText(
                            this@PhotoDetailActivity,
                            getString(R.string.no_results),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        binding.addMustVisitButton.setOnClickListener {
            viewModel.photo.value?.let { p ->
                val city = suggestCity(p.address, p.placeName.ifBlank { p.placeId })
                val token = p.placeName.ifBlank { p.placeId }
                startActivity(android.content.Intent(this, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_PATH, true)
                    putExtra(MainActivity.EXTRA_PREFILL_CITY, city)
                    putExtra(MainActivity.EXTRA_MUST_VISIT_TOKEN, token)
                })
            }
        }

        binding.similarButton.setOnClickListener {
            viewModel.photo.value?.let { p -> showSimilarPhotosBottomSheet(p.id) }
        }

        binding.directionsButton.setOnClickListener {
            viewModel.photo.value?.let { p ->
                if (p.latitude != 0.0 && p.longitude != 0.0) {
                    lifecycleScope.launch {
                        val location = TravelingApp.getInstance().locationService.getCurrentLocation()
                        navigationHelper.openDirectionsToPlace(
                            p.latitude, p.longitude, p.placeName,
                            location?.latitude, location?.longitude
                        )
                    }
                } else {
                    android.widget.Toast.makeText(
                        this, "Coordonnées non disponibles", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.shareButton.setOnClickListener {
            viewModel.photo.value?.let { p ->
                navigationHelper.sharePlace(p.latitude, p.longitude, p.placeName)
            }
        }

        binding.reportButton.setOnClickListener { showReportDialog() }
        binding.deleteButton.setOnClickListener { showDeleteConfirmationDialog() }

        binding.sendCommentButton.setOnClickListener {
            val content = binding.commentInput.text.toString().trim()
            viewModel.addComment(content)
            binding.commentInput.text?.clear()
        }
    }

    private fun showSimilarPhotosBottomSheet(refPhotoId: String) {
        val dialog = BottomSheetDialog(this)
        val sheet = BottomSheetSimilarPhotosBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        lateinit var sheetAdapter: PhotoAdapter
        sheetAdapter = PhotoAdapter(
            onPhotoClick = { photo ->
                startActivity(Intent(this, PhotoDetailActivity::class.java).putExtra("photo", photo))
                dialog.dismiss()
            },
            onLikeClick = { photo ->
                val (liked, likes) = similarPhotosViewModel.toggleLike(photo)
                sheetAdapter.updatePhoto(photo.id, liked, likes)
            }
        )
        sheet.recycler.layoutManager = GridLayoutManager(this, 2)
        sheet.recycler.adapter = sheetAdapter

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        dialog.setOnDismissListener { scope.cancel() }

        scope.launch {
            similarPhotosViewModel.isLoading.collect { loading ->
                sheet.progress.visibility =
                    if (loading) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        scope.launch {
            similarPhotosViewModel.photos.collect { result ->
                result.onSuccess { list ->
                    sheetAdapter.submitList(list)
                    sheet.empty.visibility =
                        if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
                result.onFailure {
                    sheetAdapter.submitList(emptyList())
                    sheet.empty.visibility = android.view.View.VISIBLE
                }
            }
        }

        dialog.show()
        similarPhotosViewModel.getSimilarPhotos(refPhotoId)
    }

    private fun suggestCity(address: String?, name: String): String =
        CityResolver.guessCity(address, name)

    private fun showReportDialog() {
        val reasons = arrayOf(
            "Contenu inapproprié", "Spam ou arnaque",
            "Informations incorrectes", "Contenu offensant", "Autre"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Signaler cette photo")
            .setItems(reasons) { _, which -> viewModel.reportPhoto(reasons[which]) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showDeleteConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer la photo")
            .setMessage("Êtes-vous sûr de vouloir supprimer cette photo ? Cette action est irréversible.")
            .setPositiveButton("Supprimer") { _, _ -> viewModel.deletePhoto() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun updateLikeButton(isLiked: Boolean) {
        val iconRes = if (isLiked) R.drawable.ic_liked else R.drawable.ic_like
        binding.likeButton.setIconResource(iconRes)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { returnResultAndFinish() }

    override fun onSupportNavigateUp(): Boolean { returnResultAndFinish(); return true }

    private fun returnResultAndFinish() {
        val photo = viewModel.photo.value
        val resultIntent = android.content.Intent().apply {
            putExtra("photoId", photo?.id)
            putExtra("isLiked", photo?.isLiked ?: false)
            putExtra("likesCount", photo?.likes ?: 0)
        }
        setResult(android.app.Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onDestroy() {
        voicePlayer?.release()
        voicePlayer = null
        super.onDestroy()
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
