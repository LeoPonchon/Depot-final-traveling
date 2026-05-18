package com.shimtraveling.features.path

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.NavigationHelper
import android.content.Intent
import com.shimtraveling.data.model.TravelPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class PathMapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var travelPath: TravelPath? = null
    private lateinit var navigationHelper: NavigationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().setUserAgentValue(packageName)

        setContentView(R.layout.activity_path_map)

        navigationHelper = NavigationHelper(this)

        setupToolbar()

        @Suppress("DEPRECATION")
        travelPath = intent.getParcelableExtra("path")

        setupMapView()
        lifecycleScope.launch {
            displayPath()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        travelPath?.let { supportActionBar?.title = it.name }
    }

    private fun setupMapView() {
        mapView = findViewById(R.id.map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
    }

    private suspend fun displayPath() {
        travelPath?.let { path ->
            val steps = path.steps.sortedBy { it.order }
            if (steps.isEmpty()) return

            val points = mutableListOf<GeoPoint>()
            val routeCoordinates = mutableListOf<Pair<Double, Double>>()

            val currentLocation = com.shimtraveling.TravelingApp.getInstance().locationService.getCurrentLocation()
            if (currentLocation != null && steps.none { it.id == "origin" }) {
                val myPos = GeoPoint(currentLocation.latitude, currentLocation.longitude)
                points.add(myPos)
                routeCoordinates.add(Pair(currentLocation.latitude, currentLocation.longitude))

                val myMarker = Marker(mapView)
                myMarker.position = myPos
                myMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                myMarker.title = "Ma position actuelle"
                myMarker.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_person)?.apply {
                    setTint(androidx.core.content.ContextCompat.getColor(this@PathMapActivity, R.color.primary))
                }
                myMarker.setOnMarkerClickListener { _, _ -> true }
                myMarker.infoWindow = null
                mapView.overlays.add(myMarker)
            }

            steps.forEachIndexed { index, step ->
                val point = GeoPoint(step.latitude, step.longitude)
                points.add(point)
                routeCoordinates.add(Pair(step.latitude, step.longitude))

                val marker = Marker(mapView)
                marker.position = point
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                if (step.id == "origin") {
                    marker.title = "Départ"
                    marker.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_person)?.apply {
                        setTint(androidx.core.content.ContextCompat.getColor(this@PathMapActivity, R.color.primary))
                    }
                    marker.infoWindow = null
                    marker.setOnMarkerClickListener { _, _ -> true }
                } else {
                    marker.title = "${index + 1}. ${step.placeName}"
                    marker.snippet = "${step.activityType.getDisplayName()} - ${step.estimatedDurationMinutes}min"

                    marker.setOnMarkerClickListener { m, _ ->
                        lifecycleScope.launch {
                            try {
                                val result = TravelingApp.getInstance().placeRepository.getPlaceById(step.placeId).first()
                                result.onSuccess { place ->
                                    val intent = Intent(this@PathMapActivity, com.shimtraveling.features.place.PlaceDetailActivity::class.java)
                                    intent.putExtra("place", place)
                                    startActivity(intent)
                                }
                            } catch (e: Exception) {
                            }
                        }
                        true
                    }
                }

                mapView.overlays.add(marker)
            }

            if (points.size > 1) {
                val route = TravelingApp.getInstance().routingRepository.getRoute(routeCoordinates)
                val polyline = Polyline(mapView)
                polyline.outlinePaint.color = ResourcesCompat.getColor(resources, R.color.primary, null)
                polyline.outlinePaint.strokeWidth = 8f

                if (route != null) {
                    val geoPoints = route.geometry.getLatLonCoordinates().map { GeoPoint(it.first, it.second) }
                    polyline.setPoints(geoPoints)
                } else {
                    polyline.setPoints(points)
                }

                mapView.overlays.add(polyline)
                mapView.invalidate()
            }

            if (points.isNotEmpty()) {
                val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
                mapView.post {
                    mapView.zoomToBoundingBox(boundingBox, true, 100)
                }
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
