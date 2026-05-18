package com.shimtraveling.features.profile

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Place
import com.shimtraveling.data.model.PlaceType
import com.shimtraveling.databinding.ActivityAddPlaceBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Date
import java.util.UUID

class AddPlaceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddPlaceBinding
    private var selectedPoint: GeoPoint? = null
    private var detectedAddress: String? = null
    private var detectedCity: String? = null
    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))

        binding = ActivityAddPlaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupMapView()
        setupTypeSpinner()
        setupTagsGenerator()
        setupSaveButton()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupMapView() {
        binding.mapView.setMultiTouchControls(true)
        val mapController = binding.mapView.controller
        mapController.setZoom(6.0)
        mapController.setCenter(GeoPoint(46.603354, 1.888334))

        binding.mapView.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let { handleMapClick(it) }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                return false
            }
        })
        binding.mapView.overlays.add(eventsOverlay)
    }

    private fun handleMapClick(point: GeoPoint) {
        selectedPoint = point

        if (marker == null) {
            marker = Marker(binding.mapView)
            binding.mapView.overlays.add(marker)
        }
        marker?.position = point
        marker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        binding.mapView.invalidate()

        lifecycleScope.launch {
            val addressInfo = com.shimtraveling.core.GeocodingHelper.getAddressInfo(this@AddPlaceActivity, point.latitude, point.longitude)
            detectedAddress = addressInfo.fullAddress
            detectedCity = addressInfo.city

            binding.detectedAddressText.text = detectedAddress ?: "Adresse introuvable"
        }
    }

    private fun setupTypeSpinner() {
        val types = PlaceType.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.typeSpinner.adapter = adapter
    }

    private fun setupTagsGenerator() {
        binding.tagsLayout.setEndIconOnClickListener { suggestTags() }
    }

    private fun suggestTags() {
        val name = binding.nameInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()
        val typeStr = binding.typeSpinner.selectedItem?.toString() ?: "OTHER"

        if (name.isEmpty() && description.isEmpty()) {
            Toast.makeText(this, "Entrez un nom ou une description pour générer des tags", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val suggestedTags = mutableListOf<String>()

            val type = try {
                PlaceType.valueOf(typeStr)
            } catch (e: Exception) {
                PlaceType.OTHER
            }
            suggestedTags.add(type.getDisplayName())

            if (name.isNotEmpty()) {
                suggestedTags.add(name)
            }

            val descLower = description.lowercase()
            when (type) {
                PlaceType.RESTAURANT -> {
                    if (descLower.contains("gastronomi") || descLower.contains("fine")) suggestedTags.add("Gastronomie")
                    if (descLower.contains("tradition") || descLower.contains("français")) suggestedTags.add("Traditionnel")
                    if (descLower.contains("pizza") || descLower.contains("italien")) suggestedTags.add("Italien")
                    if (descLower.contains("sushi") || descLower.contains("japonais")) suggestedTags.add("Japonais")
                    if (descLower.contains("veggie") || descLower.contains("végétarien")) suggestedTags.add("Végétarien")
                    if (descLower.contains("vue") || descLower.contains("terrasse")) suggestedTags.add("Avec vue")
                }
                PlaceType.MUSEUM -> {
                    if (descLower.contains("art") || descLower.contains("peinture")) suggestedTags.add("Art")
                    if (descLower.contains("histoire")) suggestedTags.add("Histoire")
                    if (descLower.contains("science") || descLower.contains("technique")) suggestedTags.add("Sciences")
                    if (descLower.contains("gratuit")) suggestedTags.add("Gratuit")
                }
                PlaceType.MONUMENT -> {
                    if (descLower.contains("histoire") || descLower.contains("ancien")) suggestedTags.add("Historique")
                    if (descLower.contains("religieux") || descLower.contains("église")) suggestedTags.add("Religieux")
                    if (descLower.contains("vue") || descLower.contains("panoramique")) suggestedTags.add("Point de vue")
                }
                PlaceType.NATURE, PlaceType.PARK -> {
                    if (descLower.contains("promenade") || descLower.contains("randonnée")) suggestedTags.add("Randonnée")
                    if (descLower.contains("lac") || descLower.contains("étang")) suggestedTags.add("Lac")
                    if (descLower.contains("forêt") || descLower.contains("bois")) suggestedTags.add("Forêt")
                    if (descLower.contains("fleur")) suggestedTags.add("Jardin")
                }
                PlaceType.BEACH -> {
                    if (descLower.contains("sable")) suggestedTags.add("Sable fin")
                    if (descLower.contains("rocher") || descLower.contains("crique")) suggestedTags.add("Criques")
                    if (descLower.contains("sport") || descLower.contains("nage")) suggestedTags.add("Sports nautiques")
                }
                PlaceType.SHOPPING -> {
                    if (descLower.contains("luxe") || descLower.contains("marque")) suggestedTags.add("Luxe")
                    if (descLower.contains("marché")) suggestedTags.add("Marché")
                    if (descLower.contains("vintage") || descLower.contains("seconde")) suggestedTags.add("Vintage")
                }
                PlaceType.MOUNTAIN -> {
                    if (descLower.contains("ski") || descLower.contains("neige")) suggestedTags.add("Ski")
                    if (descLower.contains("randonnée") || descLower.contains("sentier")) suggestedTags.add("Randonnée")
                    if (descLower.contains("sommet") || descLower.contains("pic")) suggestedTags.add("Sommet")
                }
                else -> {
                    if (descLower.contains("beau") || descLower.contains("magnifique")) suggestedTags.add("Magnifique")
                    if (descLower.contains("insolite") || descLower.contains("unique")) suggestedTags.add("Insolite")
                }
            }

            if (descLower.contains("famille") || descLower.contains("enfant")) suggestedTags.add("En famille")
            if (descLower.contains("romantique") || descLower.contains("couple")) suggestedTags.add("Romantique")
            if (descLower.contains("amis") || descLower.contains("groupe")) suggestedTags.add("Entre amis")

            suggestedTags.addAll(listOf("Découverte", "À voir"))

            val currentTags = binding.tagsInput.text.toString()
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

            suggestedTags.forEach { if (!currentTags.contains(it)) currentTags.add(it) }

            binding.tagsInput.setText(currentTags.joinToString(", "))
            Toast.makeText(this@AddPlaceActivity, "Tags suggérés", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSaveButton() {
        binding.saveButton.setOnClickListener {
            validateAndSave()
        }
    }

    private fun validateAndSave() {
        val name = binding.nameInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()
        val tagsStr = binding.tagsInput.text.toString().trim()

        if (name.isEmpty() || description.isEmpty() || selectedPoint == null) {
            Toast.makeText(this, "Veuillez remplir le nom, la description et sélectionner un lieu sur la carte", Toast.LENGTH_SHORT).show()
            return
        }

        val lat = selectedPoint!!.latitude
        val lng = selectedPoint!!.longitude

        val type = PlaceType.valueOf(binding.typeSpinner.selectedItem.toString())
        val tags = if (tagsStr.isNotEmpty()) tagsStr.split(",").map { it.trim() } else emptyList()

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            binding.saveButton.isEnabled = false

            val firestore = TravelingApp.getInstance().firestoreRepository
            val userRepository = TravelingApp.getInstance().userRepository

            val similarPlace = firestore.findSimilarPlace(name)
            if (similarPlace != null) {
                binding.loadingIndicator.visibility = View.GONE
                binding.saveButton.isEnabled = true

                showSimilarityWarning(similarPlace)
                return@launch
            }

            val currentUserResult = userRepository.getCurrentUser().first()
            val user = currentUserResult.getOrNull()

            if (user == null) {
                Toast.makeText(this@AddPlaceActivity, "Vous devez être connecté", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val liveUser = firestore.getUserById(user.id).getOrNull() ?: user

            val openingHours = binding.openingHoursInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val videoUrl = binding.videoUrlInput.text?.toString()?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
            val newPlace = Place(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                imageUrl = "",
                latitude = lat,
                longitude = lng,
                address = detectedAddress,
                city = detectedCity,
                type = type,
                authorId = liveUser.id,
                authorName = liveUser.username,
                authorAvatar = liveUser.avatar,
                tags = tags,
                createdAt = Date(),
                updatedAt = Date(),
                openingHours = openingHours,
                videoUrl = videoUrl
            )

            val result = firestore.addPlace(newPlace)

            binding.loadingIndicator.visibility = View.GONE
            binding.saveButton.isEnabled = true

            if (result.isSuccess) {
                Toast.makeText(this@AddPlaceActivity, "Lieu créé avec succès !", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this@AddPlaceActivity, "Erreur lors de la création", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun showSimilarityWarning(existingPlace: Place) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Lieu déjà existant ?")
            .setMessage("Un lieu similaire nommé \"${existingPlace.name}\" existe déjà. \n\nVeuillez vérifier s'il s'agit du même endroit avant d'en créer un nouveau.")
            .setPositiveButton("Compris", null)
            .show()
    }
}
