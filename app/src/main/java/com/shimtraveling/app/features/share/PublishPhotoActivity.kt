package com.shimtraveling.features.share

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.shimtraveling.R
import com.shimtraveling.core.ImageLabelingHelper
import com.shimtraveling.data.model.LocationPrecision
import com.shimtraveling.data.model.PhotoVisibility
import com.shimtraveling.data.model.Place
import com.shimtraveling.data.model.TimeOfDay
import com.shimtraveling.databinding.ActivityPublishPhotoBinding
import com.shimtraveling.ui.common.openGuide
import com.shimtraveling.ui.viewmodel.PublishPhotoViewModel
import com.shimtraveling.ui.viewmodel.PublishState
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class PublishPhotoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPublishPhotoBinding
    private lateinit var viewModel: PublishPhotoViewModel

    private val CAMERA_PERMISSION_CODE = 100
    private val AUDIO_PERMISSION_CODE = 101

    private var selectedImageUri: Uri? = null
    private var selectedVisibility = PhotoVisibility.PUBLIC
    private var selectedPlace: Place? = null
    private var selectedGroupId: String? = null
    private var selectedTimeOfDay: TimeOfDay? = null
    private var tempPhotoFile: File? = null
    private var selectedAudioUri: Uri? = null
    private var recordedAudioFile: File? = null
    private var audioRecorder: MediaRecorder? = null
    private var audioPreviewPlayer: MediaPlayer? = null
    private var isRecordingAudio = false

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = com.shimtraveling.ui.common.VoiceSearch.extractFirstResult(result.data)
            if (!spokenText.isNullOrBlank()) {
                val currentText = binding.descriptionInput.text.toString()
                val newText = if (currentText.isBlank()) spokenText else "$currentText $spokenText"
                binding.descriptionInput.setText(newText)
            }
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (!success) return@registerForActivityResult
        tempPhotoFile?.takeIf { it.exists() }?.let {
            val bitmap = BitmapFactory.decodeFile(it.absolutePath)
            Glide.with(this).load(bitmap).centerCrop().into(binding.photoPreview)
            selectedImageUri = null
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        selectedImageUri = uri
        Glide.with(this).load(uri).centerCrop().into(binding.photoPreview)
        tempPhotoFile = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPublishPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this, PublishPhotoViewModel.Factory(application)
        )[PublishPhotoViewModel::class.java]

        setupToolbar()
        setupButtons()
        setupVisibilityToggle()
        updateAudioControls()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.publish_title)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.userGroups.collect { groups ->
                binding.btnGroup.isEnabled = groups.isNotEmpty()
                binding.btnGroup.alpha = if (groups.isNotEmpty()) 1f else 0.5f
            }
        }

        lifecycleScope.launch {
            viewModel.publishState.collect { state ->
                when (state) {
                    is PublishState.Loading -> {
                        binding.publishButton.isEnabled = false
                        binding.publishButton.text = getString(R.string.publish_loading)
                    }
                    is PublishState.Success -> {
                        Toast.makeText(this@PublishPhotoActivity, getString(R.string.publish_success), Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                    is PublishState.Error -> {
                        binding.publishButton.isEnabled = true
                        binding.publishButton.text = getString(R.string.publish_title)
                        Toast.makeText(this@PublishPhotoActivity, state.message, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is PublishState.Idle -> {
                        binding.publishButton.isEnabled = true
                        binding.publishButton.text = getString(R.string.publish_title)
                    }
                }
            }
        }
    }

    private fun setupButtons() {
        binding.takePhotoButton.setOnClickListener { checkCameraPermissionAndOpenCamera() }
        binding.chooseGalleryButton.setOnClickListener { openGallery() }
        binding.locationInput.setOnClickListener { showPlacePicker() }
        binding.publishButton.setOnClickListener { triggerPublish() }
        binding.attachAudioButton.setOnClickListener { toggleAudioRecording() }
        binding.previewAudioButton.setOnClickListener { toggleAudioPreview() }
        binding.removeAudioButton.setOnClickListener { clearRecordedAudio() }

        binding.descriptionLayout.setEndIconOnClickListener { startVoiceToText() }

        binding.tagsLayout.setEndIconOnClickListener { suggestTags() }

    }

    private fun toggleAudioRecording() {
        if (isRecordingAudio) {
            stopAudioRecording()
        } else {
            checkAudioPermissionAndStartRecording()
        }
    }

    private fun checkAudioPermissionAndStartRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_CODE)
        } else {
            startAudioRecording()
        }
    }

    private fun startAudioRecording() {
        if (isRecordingAudio) return

        val outputFile = File(cacheDir, "voice_note_${System.currentTimeMillis()}.m4a")
        runCatching {
            releaseAudioPreview()
            recordedAudioFile?.takeIf { it.exists() }?.delete()
            recordedAudioFile = outputFile
            audioRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            isRecordingAudio = true
            selectedAudioUri = null
        }.onSuccess {
            binding.audioAttachmentStatus.visibility = View.VISIBLE
            binding.audioAttachmentStatus.text = getString(R.string.publish_voice_recording_in_progress)
            binding.attachAudioButton.text = getString(R.string.publish_stop_voice_recording)
            updateAudioControls()
        }.onFailure {
            audioRecorder?.release()
            audioRecorder = null
            recordedAudioFile = null
            isRecordingAudio = false
            updateAudioControls()
            Toast.makeText(this, getString(R.string.publish_voice_record_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAudioRecording() {
        if (!isRecordingAudio) return

        runCatching {
            audioRecorder?.stop()
        }.onSuccess {
            selectedAudioUri = recordedAudioFile?.let(Uri::fromFile)
            binding.audioAttachmentStatus.visibility = View.VISIBLE
            binding.audioAttachmentStatus.text = getString(R.string.publish_voice_recorded)
        }.onFailure {
            recordedAudioFile?.takeIf { it.exists() }?.delete()
            recordedAudioFile = null
            selectedAudioUri = null
            binding.audioAttachmentStatus.visibility = View.VISIBLE
            binding.audioAttachmentStatus.text = getString(R.string.publish_voice_record_error)
            Toast.makeText(this, getString(R.string.publish_voice_record_error), Toast.LENGTH_SHORT).show()
        }

        audioRecorder?.release()
        audioRecorder = null
        isRecordingAudio = false
        updateAudioControls()
    }

    private fun toggleAudioPreview() {
        val audioUri = selectedAudioUri ?: return

        try {
            val existingPlayer = audioPreviewPlayer
            if (existingPlayer?.isPlaying == true) {
                existingPlayer.pause()
                binding.previewAudioButton.text = getString(R.string.photo_play_voice)
                return
            }
            if (existingPlayer != null) {
                existingPlayer.start()
                binding.previewAudioButton.text = getString(R.string.photo_playing_pause)
                return
            }

            audioPreviewPlayer = MediaPlayer().apply {
                setDataSource(this@PublishPhotoActivity, audioUri)
                setOnPreparedListener { player ->
                    player.start()
                    binding.previewAudioButton.text = getString(R.string.photo_playing_pause)
                }
                setOnCompletionListener {
                    binding.previewAudioButton.text = getString(R.string.photo_play_voice)
                    releaseAudioPreview()
                    updateAudioControls()
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            releaseAudioPreview()
            binding.previewAudioButton.text = getString(R.string.photo_play_voice)
            Toast.makeText(this, getString(R.string.photo_audio_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearRecordedAudio() {
        releaseAudioPreview()
        if (isRecordingAudio) {
            runCatching { audioRecorder?.stop() }
            audioRecorder?.release()
            audioRecorder = null
            isRecordingAudio = false
        }
        recordedAudioFile?.takeIf { it.exists() }?.delete()
        recordedAudioFile = null
        selectedAudioUri = null
        binding.audioAttachmentStatus.visibility = View.GONE
        binding.audioAttachmentStatus.text = ""
        updateAudioControls()
    }

    private fun releaseAudioPreview() {
        audioPreviewPlayer?.release()
        audioPreviewPlayer = null
    }

    private fun updateAudioControls() {
        binding.attachAudioButton.text = when {
            isRecordingAudio -> getString(R.string.publish_stop_voice_recording)
            selectedAudioUri != null -> getString(R.string.publish_rerecord_voice)
            else -> getString(R.string.publish_attach_voice)
        }
        binding.audioActionsRow.visibility = if (!isRecordingAudio && selectedAudioUri != null) View.VISIBLE else View.GONE
        binding.previewAudioButton.text = if (audioPreviewPlayer?.isPlaying == true) {
            getString(R.string.photo_playing_pause)
        } else {
            getString(R.string.photo_play_voice)
        }
    }

    private fun setupVisibilityToggle() {
        binding.visibilityToggleGroup.check(R.id.btn_public)
        binding.visibilityToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_public -> { selectedVisibility = PhotoVisibility.PUBLIC; selectedGroupId = null }
                R.id.btn_group -> showGroupPicker()
                R.id.btn_private -> { selectedVisibility = PhotoVisibility.PRIVATE; selectedGroupId = null }
            }
        }

        binding.timeOfDayToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedTimeOfDay = when (checkedId) {
                R.id.btn_morning -> TimeOfDay.MORNING
                R.id.btn_afternoon -> TimeOfDay.AFTERNOON
                R.id.btn_evening -> TimeOfDay.EVENING
                else -> null
            }
        }
    }

    private fun showGroupPicker() {
        val groups = viewModel.userGroups.value
        if (groups.isEmpty()) {
            Toast.makeText(this, getString(R.string.publish_no_group), Toast.LENGTH_SHORT).show()
            binding.visibilityToggleGroup.check(R.id.btn_public)
            selectedVisibility = PhotoVisibility.PUBLIC
            return
        }
        val names = groups.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.publish_select_group))
            .setItems(names) { _, which ->
                selectedVisibility = PhotoVisibility.GROUP
                selectedGroupId = groups[which].id
                Toast.makeText(this, getString(R.string.publish_group_selected, groups[which].name), Toast.LENGTH_SHORT).show()
            }
            .setOnCancelListener {
                binding.visibilityToggleGroup.check(R.id.btn_public)
                selectedVisibility = PhotoVisibility.PUBLIC; selectedGroupId = null
            }
            .setNegativeButton(getString(R.string.common_cancel)) { _, _ ->
                binding.visibilityToggleGroup.check(R.id.btn_public)
                selectedVisibility = PhotoVisibility.PUBLIC; selectedGroupId = null
            }.show()
    }

    private fun startVoiceToText() {
        val intent = com.shimtraveling.ui.common.VoiceSearch.buildIntent(
            prompt = getString(R.string.publish_voice_prompt),
            locale = Locale.getDefault()
        )
        try { speechLauncher.launch(intent) }
        catch (e: Exception) { Toast.makeText(this, getString(R.string.publish_voice_unavailable), Toast.LENGTH_SHORT).show() }
    }


    private fun suggestTags() {
        val bitmap = currentBitmap()
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.publish_tags_image_required), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.publish_tags_processing), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val fromImage = ImageLabelingHelper.suggestTags(bitmap)
            val merged = fromImage.toMutableList()

            selectedPlace?.let { place ->
                val typeTag = place.type.getDisplayName().lowercase(Locale.getDefault())
                if (merged.none { it.equals(typeTag, ignoreCase = true) }) merged.add(typeTag)
            }

            if (merged.isEmpty()) {
                Toast.makeText(this@PublishPhotoActivity, getString(R.string.publish_tags_no_labels), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val currentTags = binding.tagsInput.text.toString()
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            merged.forEach { tag -> if (currentTags.none { it.equals(tag, ignoreCase = true) }) currentTags.add(tag) }

            binding.tagsInput.setText(currentTags.joinToString(", "))
            Toast.makeText(this@PublishPhotoActivity, getString(R.string.publish_tags_done), Toast.LENGTH_SHORT).show()
        }
    }


    private fun currentBitmap(): android.graphics.Bitmap? = when {
        tempPhotoFile?.exists() == true -> BitmapFactory.decodeFile(tempPhotoFile!!.absolutePath)
        selectedImageUri != null -> contentResolver.openInputStream(selectedImageUri!!)?.use {
            BitmapFactory.decodeStream(it)
        }
        else -> null
    }

    private fun showPlacePicker() {
        val places = viewModel.places.value
        if (places.isEmpty()) {
            Toast.makeText(this, getString(R.string.publish_places_loading), Toast.LENGTH_SHORT).show()
            return
        }
        val names = places.map { "${it.name} (${it.type.getDisplayName()})" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.publish_select_place_title))
            .setItems(names) { _, which ->
                selectedPlace = places[which]
                binding.locationInput.setText(places[which].name)
            }
            .setNeutralButton(getString(R.string.publish_new_place)) { _, _ ->
                startActivity(Intent(this, com.shimtraveling.features.profile.AddPlaceActivity::class.java))
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun triggerPublish() {
        val place = selectedPlace ?: run {
            Toast.makeText(this, getString(R.string.publish_place_required), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = when {
            tempPhotoFile?.exists() == true -> Uri.fromFile(tempPhotoFile)
            selectedImageUri != null -> selectedImageUri
            else -> {
                Toast.makeText(this, getString(R.string.publish_image_required), Toast.LENGTH_SHORT).show()
                return
            }
        }

        val description = binding.descriptionInput.text.toString()
        val tags = binding.tagsInput.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val howToGo = binding.howToGoInput?.text?.toString()?.takeIf { it.isNotBlank() }
        val price = binding.priceInput?.text?.toString()?.toDoubleOrNull()

        if (price != null && selectedTimeOfDay == null) {
            Toast.makeText(this, getString(R.string.publish_time_of_day_required), Toast.LENGTH_SHORT).show()
            return
        }

        val locPrec = if (binding.locationApproximateSwitch.isChecked) {
            LocationPrecision.APPROXIMATE
        } else {
            LocationPrecision.EXACT
        }

        viewModel.publish(
            imageUri = uri,
            audioUri = selectedAudioUri,
            description = description,
            tags = tags,
            howToGo = howToGo,
            price = price,
            timeOfDay = selectedTimeOfDay,
            selectedPlace = place,
            visibility = selectedVisibility,
            groupId = selectedGroupId,
            locationPrecision = locPrec
        )
    }

    private fun checkCameraPermissionAndOpenCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            openCamera()
        }
    }

    private fun openCamera() {
        tempPhotoFile = File(cacheDir, "temp_photo_${System.currentTimeMillis()}.jpg")
        val cameraUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tempPhotoFile!!)
        cameraLauncher.launch(cameraUri)
    }

    private fun openGallery() { galleryLauncher.launch("image/*") }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                Toast.makeText(this, getString(R.string.publish_camera_permission_required), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (requestCode == AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudioRecording()
            } else {
                Toast.makeText(this, getString(R.string.publish_audio_permission_required), Toast.LENGTH_SHORT).show()
            }
        }
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

    override fun onDestroy() {
        if (isRecordingAudio) {
            runCatching { audioRecorder?.stop() }
        }
        audioRecorder?.release()
        audioRecorder = null
        releaseAudioPreview()
        isRecordingAudio = false
        super.onDestroy()
    }

}
