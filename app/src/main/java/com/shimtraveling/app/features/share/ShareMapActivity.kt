package com.shimtraveling.features.share

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Photo
import com.shimtraveling.data.model.Place
import com.shimtraveling.features.place.PlaceDetailActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale


class ShareMapActivity : AppCompatActivity() {

    companion object {
        const val MODE_PLACES = "places"
        const val MODE_PHOTOS = "photos"
        const val MODE_BOTH = "both"
        const val MODE_PICK_LOCATION = "pick_location"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PICKED_LAT = "picked_lat"
        const val EXTRA_PICKED_LNG = "picked_lng"
        const val EXTRA_INITIAL_LAT = "initial_lat"
        const val EXTRA_INITIAL_LNG = "initial_lng"
    }

    private lateinit var mapView: MapView
    private lateinit var searchCard: MaterialCardView
    private lateinit var searchInput: EditText
    private lateinit var voiceSearchButton: ImageButton
    private lateinit var pickerOverlayCard: MaterialCardView
    private lateinit var pickerInstruction: TextView
    private lateinit var confirmLocationButton: MaterialButton

    private var allPlaces: List<Place> = emptyList()
    private var allPhotos: List<Photo> = emptyList()
    private var displayMode: String = MODE_PLACES
    private var selectedGeoPoint: GeoPoint? = null
    private var selectedLocationMarker: Marker? = null

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = com.shimtraveling.ui.common.VoiceSearch.extractFirstResult(result.data) ?: return@registerForActivityResult
            searchInput.setText(spokenText)
            applyFilter(spokenText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().setUserAgentValue(packageName)

        setContentView(R.layout.activity_share_map)

        displayMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PLACES

        @Suppress("DEPRECATION")
        allPlaces = intent.getParcelableArrayListExtra("places") ?: emptyList()
        @Suppress("DEPRECATION")
        allPhotos = intent.getParcelableArrayListExtra("photos") ?: emptyList()

        setupToolbar()
        setupViews()
        setupMapView()
        setupSearch()
        loadDataAndDisplay()
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (displayMode == MODE_PICK_LOCATION) {
            supportActionBar?.setDisplayShowTitleEnabled(true)
            supportActionBar?.title = getString(R.string.share_filter_center_picker_title)
        } else {
            supportActionBar?.setDisplayShowTitleEnabled(false)
        }
    }

    private fun setupViews() {
        searchCard = findViewById(R.id.search_card)
        searchInput = findViewById(R.id.search_input)
        voiceSearchButton = findViewById(R.id.voice_search_button)
        pickerOverlayCard = findViewById(R.id.picker_overlay_card)
        pickerInstruction = findViewById(R.id.picker_instruction)
        confirmLocationButton = findViewById(R.id.confirm_location_button)
    }

    private fun setupSearch() {
        if (displayMode == MODE_PICK_LOCATION) {
            searchCard.visibility = android.view.View.GONE
            pickerOverlayCard.visibility = android.view.View.VISIBLE
            confirmLocationButton.isEnabled = false
            confirmLocationButton.setOnClickListener { confirmPickedLocation() }
            return
        }

        pickerOverlayCard.visibility = android.view.View.GONE
        searchInput.doOnTextChanged { text, _, _, _ ->
            applyFilter(text?.toString() ?: "")
        }
        voiceSearchButton.setOnClickListener { startVoiceSearch() }
    }

    private fun startVoiceSearch() {
        val intent = com.shimtraveling.ui.common.VoiceSearch.buildIntent(
            prompt = "Rechercher un lieu ou une photo...",
            locale = Locale.getDefault()
        )
        try {
            voiceSearchLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Recherche vocale non disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFilter(query: String) {
        if (query.isBlank()) {
            displayOnMap(allPlaces, allPhotos)
            return
        }
        val terms = query.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        val filteredPlaces = allPlaces.filter { place ->
            terms.all { term ->
                place.name.lowercase().contains(term) ||
                    place.tags.any { it.lowercase().contains(term) } ||
                    place.address?.lowercase()?.contains(term) == true ||
                    place.city?.lowercase()?.contains(term) == true
            }
        }

        val filteredPhotos = allPhotos.filter { photo ->
            terms.all { term ->
                photo.placeName.lowercase().contains(term) ||
                    photo.tags.any { it.lowercase().contains(term) } ||
                    photo.address?.lowercase()?.contains(term) == true ||
                    photo.authorName.lowercase().contains(term) ||
                    photo.description?.lowercase()?.contains(term) == true
            }
        }

        when (displayMode) {
            MODE_PHOTOS -> displayOnMap(allPlaces, filteredPhotos)
            MODE_PLACES -> displayOnMap(filteredPlaces, emptyList())
            else -> displayOnMap(filteredPlaces, filteredPhotos)
        }
    }

    private fun setupMapView() {
        mapView = findViewById(R.id.map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(12.0)

        if (displayMode == MODE_PICK_LOCATION) {
            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    p?.let(::selectLocation)
                    return true
                }

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p?.let(::selectLocation)
                    return true
                }
            })
            mapView.overlays.add(eventsOverlay)

            val initialLat = intent.getDoubleExtra(EXTRA_INITIAL_LAT, Double.NaN)
            val initialLng = intent.getDoubleExtra(EXTRA_INITIAL_LNG, Double.NaN)
            if (!initialLat.isNaN() && !initialLng.isNaN()) {
                val initialPoint = GeoPoint(initialLat, initialLng)
                mapView.controller.setCenter(initialPoint)
                mapView.controller.setZoom(15.0)
                selectLocation(initialPoint)
            } else {
                mapView.controller.setCenter(GeoPoint(48.8566, 2.3522))
            }
        }
    }

    private fun loadDataAndDisplay() {
        if (displayMode == MODE_PICK_LOCATION) return

        val needLoadPlaces = (displayMode == MODE_PLACES || displayMode == MODE_BOTH || displayMode == MODE_PHOTOS)
        val needLoadPhotos = (displayMode == MODE_PHOTOS || displayMode == MODE_BOTH) && allPhotos.isEmpty()

        if (!needLoadPlaces && !needLoadPhotos) {
            displayOnMap(allPlaces, allPhotos)
            return
        }

        lifecycleScope.launch {
            if (needLoadPlaces) {
                val result = TravelingApp.getInstance().placeRepository.getAllPlaces().first()
                result.onSuccess { places -> allPlaces = places }
            }

            if (needLoadPhotos) {
                val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
                val user = userResult.getOrNull()
                if (user != null) {
                    val result = TravelingApp.getInstance().photoRepository
                        .getVisiblePhotosWithLikeStatus(user.id, user.groups)
                        .first()
                    result.onSuccess { photos -> allPhotos = photos }
                } else {
                    val result = TravelingApp.getInstance().photoRepository.getPublicPhotos().first()
                    result.onSuccess { photos -> allPhotos = photos }
                }
            }

            displayOnMap(allPlaces, allPhotos)
        }
    }

    private fun selectLocation(point: GeoPoint) {
        selectedGeoPoint = point
        selectedLocationMarker?.let { mapView.overlays.remove(it) }
        selectedLocationMarker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = getString(R.string.share_filter_center_picker_marker)
        }
        mapView.overlays.add(selectedLocationMarker)
        confirmLocationButton.isEnabled = true
        pickerInstruction.text = getString(
            R.string.share_filter_center_picker_selected,
            String.format(Locale.getDefault(), "%.5f", point.latitude),
            String.format(Locale.getDefault(), "%.5f", point.longitude)
        )
        mapView.invalidate()
    }

    private fun confirmPickedLocation() {
        val point = selectedGeoPoint ?: return
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_PICKED_LAT, point.latitude)
                putExtra(EXTRA_PICKED_LNG, point.longitude)
            }
        )
        finish()
    }

    private fun displayOnMap(places: List<Place>, photos: List<Photo>) {
        mapView.overlays.removeAll { it is Marker }

        val markers = mutableListOf<GeoPoint>()

        val photosByPlaceId = photos
            .mapNotNull { p -> p.placeId.trim().takeIf { it.isNotEmpty() }?.let { id -> id to p } }
            .groupBy({ it.first }, { it.second })

        val placeById = places.associateBy { it.id }
        val placesFromPhotos = photosByPlaceId.keys.mapNotNull { placeId -> placeById[placeId] }

        val placesToRender = when (displayMode) {
            MODE_PLACES -> places
            MODE_PHOTOS -> placesFromPhotos
            MODE_BOTH -> (places + placesFromPhotos).distinctBy { it.id }
            else -> emptyList()
        }

        if (displayMode == MODE_PLACES || displayMode == MODE_BOTH || displayMode == MODE_PHOTOS) {
            placesToRender.forEach { place ->
                val point = GeoPoint(place.latitude, place.longitude)
                markers.add(point)

                val marker = Marker(mapView).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    val priceInfo = place.price?.let { String.format(Locale.getDefault(), "%.2f €", it) } ?: "Prix non défini"
                    val photoCount = photosByPlaceId[place.id]?.size ?: 0
                    title = place.name
                    snippet = buildString {
                        append(place.type.getDisplayName())
                        append(" • ")
                        append(priceInfo)
                        if (photoCount > 0) {
                            append(" • ")
                            append(photoCount)
                            append(" photo")
                            if (photoCount > 1) append("s")
                        }
                    }
                    setOnMarkerClickListener { _, _ ->
                        startActivity(Intent(this@ShareMapActivity, PlaceDetailActivity::class.java).apply {
                            putExtra("place", place)
                        })
                        true
                    }
                }
                mapView.overlays.add(marker)
            }
        }

        mapView.invalidate()

        if (markers.isNotEmpty()) {
            if (markers.size == 1) {
                mapView.controller.setCenter(markers.first())
                mapView.controller.animateTo(markers.first(), 18.0, 1000L)
            } else {
                val boundingBox = BoundingBox.fromGeoPoints(markers)
                mapView.post { mapView.zoomToBoundingBox(boundingBox, true, 150) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
