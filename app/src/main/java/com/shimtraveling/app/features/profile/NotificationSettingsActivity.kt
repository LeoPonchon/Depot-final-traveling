package com.shimtraveling.features.profile

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.LocationCache
import com.shimtraveling.data.model.NotificationSettings
import com.shimtraveling.databinding.ActivityNotificationSettingsBinding
import com.shimtraveling.features.share.ShareMapActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationSettingsBinding
    private var currentSettings: NotificationSettings? = null
    private var placeLabelToId: Map<String, String> = emptyMap()
    private var userLabelToToken: Map<String, String> = emptyMap()

    private val nearbyCenterPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val lat = result.data?.getDoubleExtra(ShareMapActivity.EXTRA_PICKED_LAT, Double.NaN) ?: Double.NaN
            val lng = result.data?.getDoubleExtra(ShareMapActivity.EXTRA_PICKED_LNG, Double.NaN) ?: Double.NaN
            if (!lat.isNaN() && !lng.isNaN()) {
                val updated = (currentSettings ?: baseFromUi()).copy(
                    nearbyCenterLat = lat,
                    nearbyCenterLng = lng
                )
                currentSettings = updated
                updateNearbyCenterUi()
                saveSettings()
                return@registerForActivityResult
            }
        }
        Toast.makeText(this, "Aucun centre sélectionné", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupListeners()
        setupDropdowns()
        loadDropdownSuggestions()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Paramètres de notification"
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            binding.progressBar.visibility = android.view.View.VISIBLE
            val result = TravelingApp.getInstance().userRepository.getNotificationSettings()
            result.onSuccess { settings ->
                currentSettings = settings
                updateUI()
                binding.progressBar.visibility = android.view.View.GONE
            }
            result.onFailure { error ->
                binding.progressBar.visibility = android.view.View.GONE
                Toast.makeText(this@NotificationSettingsActivity, "Erreur: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUI() {
        currentSettings?.let { settings ->
            binding.switchNewPhotoFromUser.isChecked = settings.newPhotoFromUser
            binding.switchNewPhotoInGroup.isChecked = settings.newPhotoInGroup
            binding.switchNewPhotoNearby.isChecked = settings.newPhotoNearby
            binding.switchNewPhotoWithTag.isChecked = settings.newPhotoWithTag
            binding.switchNewPhotoPlace.isChecked = settings.newPhotoInFollowedPlace
            binding.radiusSlider.value = settings.nearbyRadiusKm
            refreshFollowTags(settings.followedTags)
            refreshFollowUsers(settings.followedUsers)
            refreshFollowPlaces(settings.followedPlaceIds)
            updateNearbyCenterUi()
        }
    }

    private fun setupListeners() {
        binding.switchNewPhotoFromUser.setOnCheckedChangeListener { _, _ -> saveSettings() }
        binding.switchNewPhotoInGroup.setOnCheckedChangeListener { _, _ -> saveSettings() }
        binding.switchNewPhotoNearby.setOnCheckedChangeListener { _, _ -> saveSettings() }
        binding.switchNewPhotoWithTag.setOnCheckedChangeListener { _, _ -> saveSettings() }
        binding.switchNewPhotoPlace.setOnCheckedChangeListener { _, _ -> saveSettings() }

        binding.radiusSlider.addOnChangeListener { _, _, fromUser ->
            if (fromUser) saveSettings()
        }

        binding.addFollowTagButton.setOnClickListener { addFollowTag() }
        binding.addFollowUserButton.setOnClickListener { addFollowUser() }
        binding.addFollowPlaceButton.setOnClickListener { addFollowPlace() }

        binding.pickNearbyCenterButton.setOnClickListener { openNearbyCenterPicker() }
        binding.clearNearbyCenterButton.setOnClickListener { clearNearbyCenter() }
    }

    private fun baseFromUi(): NotificationSettings = NotificationSettings(
        newPhotoFromUser = binding.switchNewPhotoFromUser.isChecked,
        newPhotoInGroup = binding.switchNewPhotoInGroup.isChecked,
        newPhotoNearby = binding.switchNewPhotoNearby.isChecked,
        newPhotoWithTag = binding.switchNewPhotoWithTag.isChecked,
        newPhotoInFollowedPlace = binding.switchNewPhotoPlace.isChecked,
        nearbyRadiusKm = binding.radiusSlider.value,
        nearbyCenterLat = currentSettings?.nearbyCenterLat,
        nearbyCenterLng = currentSettings?.nearbyCenterLng,
        followedUsers = currentSettings?.followedUsers.orEmpty(),
        followedTags = currentSettings?.followedTags.orEmpty(),
        followedPlaceIds = currentSettings?.followedPlaceIds.orEmpty()
    )

    private fun setupDropdowns() {
        setupNoKeyboardDropdown(binding.followTagInput)
        setupNoKeyboardDropdown(binding.followPlaceInput)
        setupNoKeyboardDropdown(binding.followUserInput)

        binding.followPlaceInput.setOnItemClickListener { _, _, position, _ ->
            val label = binding.followPlaceInput.adapter?.getItem(position)?.toString().orEmpty()
            binding.followPlaceInput.tag = placeLabelToId[label]
        }
        binding.followUserInput.setOnItemClickListener { _, _, position, _ ->
            val label = binding.followUserInput.adapter?.getItem(position)?.toString().orEmpty()
            binding.followUserInput.tag = userLabelToToken[label]
        }
    }

    private fun setupNoKeyboardDropdown(view: MaterialAutoCompleteTextView) {
        view.keyListener = null
        view.isCursorVisible = false
        view.setOnClickListener {
            hideKeyboard(view)
            view.showDropDown()
        }
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                hideKeyboard(v)
            }
        }
    }

    private fun hideKeyboard(v: android.view.View) {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun loadDropdownSuggestions() {
        lifecycleScope.launch {
            val app = TravelingApp.getInstance()

            val tagSet = linkedSetOf<String>()
            app.placeRepository.getAllUniqueTags().onSuccess { tagSet.addAll(it) }
            app.photoRepository.getAllUniqueTags().onSuccess { tagSet.addAll(it) }
            val tags = tagSet
                .map { it.trim().lowercase(Locale.getDefault()) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            binding.followTagInput.setAdapter(
                android.widget.ArrayAdapter(this@NotificationSettingsActivity, android.R.layout.simple_dropdown_item_1line, tags)
            )

            val placesResult = app.placeRepository.getAllPlaces().first()
            val places = placesResult.getOrNull().orEmpty()
            val placePairs = places
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .map { place ->
                    val cityPart = place.city?.trim().orEmpty()
                    val label = if (cityPart.isNotBlank()) "${place.name} • $cityPart" else place.name
                    val uniqueLabel = if (places.count { p -> p.name == place.name && (p.city ?: "") == (place.city ?: "") } > 1) {
                        "$label (${place.id})"
                    } else label
                    uniqueLabel to place.id
                }
            placeLabelToId = placePairs.toMap()
            binding.followPlaceInput.setAdapter(
                android.widget.ArrayAdapter(this@NotificationSettingsActivity, android.R.layout.simple_dropdown_item_1line, placePairs.map { it.first })
            )

            val meId = app.userRepository.getCurrentUserId()
            val usersResult = app.firestoreRepository.getAllUsers(limit = 300)
            val users = usersResult.getOrNull().orEmpty()
                .filter { it.id.isNotBlank() && it.id != meId }
                .sortedBy { it.username.lowercase(Locale.getDefault()) }
            val userPairs = users.map { user ->
                val label = "${user.username} (${user.id})"
                label to user.id
            }
            userLabelToToken = userPairs.toMap()
            binding.followUserInput.setAdapter(
                android.widget.ArrayAdapter(this@NotificationSettingsActivity, android.R.layout.simple_dropdown_item_1line, userPairs.map { it.first })
            )
        }
    }

    private fun addFollowTag() {
        val tag = binding.followTagInput.text?.toString()?.trim()?.lowercase().orEmpty()
        if (tag.isBlank()) {
            Toast.makeText(this, "Sélectionnez un tag", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = baseFromUi().copy(
            followedTags = (currentSettings?.followedTags.orEmpty() + tag).distinct()
        )
        currentSettings = settings
        binding.followTagInput.setText("", false)
        refreshFollowTags(settings.followedTags)
        saveSettings()
    }

    private fun addFollowUser() {
        val token = (binding.followUserInput.tag as? String)
            ?.trim()
            .orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, "Sélectionnez un utilisateur", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = baseFromUi().copy(
            followedUsers = (currentSettings?.followedUsers.orEmpty() + token).distinct()
        )
        currentSettings = settings
        binding.followUserInput.tag = null
        binding.followUserInput.setText("", false)
        refreshFollowUsers(settings.followedUsers)
        saveSettings()
    }

    private fun addFollowPlace() {
        val id = (binding.followPlaceInput.tag as? String)
            ?.trim()
            .orEmpty()
        if (id.isBlank()) {
            Toast.makeText(this, "Sélectionnez un lieu", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = baseFromUi().copy(
            followedPlaceIds = (currentSettings?.followedPlaceIds.orEmpty() + id).distinct()
        )
        currentSettings = settings
        binding.followPlaceInput.tag = null
        binding.followPlaceInput.setText("", false)
        refreshFollowPlaces(settings.followedPlaceIds)
        saveSettings()
    }


    private fun refreshFollowTags(tags: List<String>) {
        binding.followTagsGroup.removeAllViews()
        tags.forEach { tag ->
            val chip = Chip(this).apply {
                text = tag
                isCloseIconVisible = true
                setOnCloseIconClickListener { removeFollowTag(tag) }
            }
            binding.followTagsGroup.addView(chip)
        }
    }

    private fun refreshFollowUsers(users: List<String>) {
        binding.followUsersGroup.removeAllViews()
        users.forEach { user ->
            val chip = Chip(this).apply {
                text = user
                isCloseIconVisible = true
                setOnCloseIconClickListener { removeFollowUser(user) }
            }
            binding.followUsersGroup.addView(chip)
        }
    }

    private fun refreshFollowPlaces(ids: List<String>) {
        binding.followPlacesGroup.removeAllViews()
        ids.forEach { id ->
            val chip = Chip(this).apply {
                text = placeLabelToId.entries.firstOrNull { it.value == id }?.key ?: id
                isCloseIconVisible = true
                setOnCloseIconClickListener { removeFollowPlace(id) }
            }
            binding.followPlacesGroup.addView(chip)
        }
    }

    private fun updateNearbyCenterUi() {
        val lat = currentSettings?.nearbyCenterLat
        val lng = currentSettings?.nearbyCenterLng
        if (lat != null && lng != null) {
            binding.nearbyCenterValue.text = String.format(
                Locale.getDefault(),
                "%.5f, %.5f",
                lat,
                lng
            )
            binding.clearNearbyCenterButton.isEnabled = true
        } else {
            binding.nearbyCenterValue.text = getString(com.shimtraveling.R.string.notification_nearby_center_default)
            binding.clearNearbyCenterButton.isEnabled = false
        }
    }

    private fun openNearbyCenterPicker() {
        val prefs = getSharedPreferences(LocationCache.PREFS_NAME, MODE_PRIVATE)
        val lastLat = prefs.getFloat(LocationCache.KEY_LAT, Float.NaN)
        val lastLng = prefs.getFloat(LocationCache.KEY_LNG, Float.NaN)

        val initialLat = currentSettings?.nearbyCenterLat
            ?: if (!lastLat.isNaN()) lastLat.toDouble() else 48.8566
        val initialLng = currentSettings?.nearbyCenterLng
            ?: if (!lastLng.isNaN()) lastLng.toDouble() else 2.3522

        nearbyCenterPickerLauncher.launch(
            Intent(this, ShareMapActivity::class.java).apply {
                putExtra(ShareMapActivity.EXTRA_MODE, ShareMapActivity.MODE_PICK_LOCATION)
                putExtra(ShareMapActivity.EXTRA_INITIAL_LAT, initialLat)
                putExtra(ShareMapActivity.EXTRA_INITIAL_LNG, initialLng)
            }
        )
    }

    private fun clearNearbyCenter() {
        val updated = (currentSettings ?: baseFromUi()).copy(
            nearbyCenterLat = null,
            nearbyCenterLng = null
        )
        currentSettings = updated
        updateNearbyCenterUi()
        saveSettings()
    }


    private fun removeFollowTag(tag: String) {
        val updated = currentSettings?.followedTags.orEmpty().filterNot { it == tag }
        currentSettings = currentSettings?.copy(followedTags = updated) ?: baseFromUi().copy(followedTags = updated)
        refreshFollowTags(updated)
        saveSettings()
    }

    private fun removeFollowUser(user: String) {
        val updated = currentSettings?.followedUsers.orEmpty().filterNot { it == user }
        currentSettings = currentSettings?.copy(followedUsers = updated) ?: baseFromUi().copy(followedUsers = updated)
        refreshFollowUsers(updated)
        saveSettings()
    }

    private fun removeFollowPlace(id: String) {
        val updated = currentSettings?.followedPlaceIds.orEmpty().filterNot { it == id }
        currentSettings = currentSettings?.copy(followedPlaceIds = updated) ?: baseFromUi().copy(followedPlaceIds = updated)
        refreshFollowPlaces(updated)
        saveSettings()
    }


    private fun saveSettings() {
        val settings = baseFromUi()
        currentSettings = settings

        lifecycleScope.launch {
            val result = TravelingApp.getInstance().userRepository.saveNotificationSettings(settings)
            result.onFailure { error ->
                Toast.makeText(this@NotificationSettingsActivity, "Erreur sauvegarde: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
