package com.shimtraveling.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.shimtraveling.data.model.Place
import com.shimtraveling.data.model.PlaceType
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.features.path.PathMapActivity
import com.shimtraveling.features.share.ShareMapActivity
import java.util.*

class NavigationHelper(private val context: Context) {

    fun openNavigationToPlace(lat: Double, lng: Double, placeName: String) {
        val dummyPlace = Place(
            id = "dummy_${UUID.randomUUID()}",
            name = placeName,
            description = "Navigation vers $placeName",
            imageUrl = "",
            latitude = lat,
            longitude = lng,
            type = PlaceType.OTHER,
            authorId = "system",
            authorName = "Système"
        )
        val intent = Intent(context, ShareMapActivity::class.java).apply {
            putParcelableArrayListExtra("places", arrayListOf(dummyPlace))
        }
        context.startActivity(intent)
    }


    fun openDirectionsToPlace(destLat: Double, destLng: Double, destName: String, originLat: Double? = null, originLng: Double? = null) {
        if (!openExternalDirections(destLat, destLng, destName, originLat, originLng)) {
            openInternalDirections(destLat, destLng, destName, originLat, originLng)
        }
    }

    private fun openExternalDirections(
        destLat: Double,
        destLng: Double,
        destName: String,
        originLat: Double?,
        originLng: Double?
    ): Boolean {
        val packageManager = context.packageManager

        val googleNavigationIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                buildString {
                    append("google.navigation:q=$destLat,$destLng")
                    append("&mode=w")
                }
            )
        ).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (googleNavigationIntent.resolveActivity(packageManager) != null) {
            context.startActivity(googleNavigationIntent)
            return true
        }

        val genericUri = if (originLat != null && originLng != null) {
            Uri.parse(
                "https://www.google.com/maps/dir/?api=1&origin=$originLat,$originLng&destination=$destLat,$destLng&travelmode=walking"
            )
        } else {
            Uri.parse("geo:$destLat,$destLng?q=$destLat,$destLng(${Uri.encode(destName)})")
        }
        val genericIntent = Intent(Intent.ACTION_VIEW, genericUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (genericIntent.resolveActivity(packageManager) != null) {
            context.startActivity(genericIntent)
            return true
        }

        return false
    }

    private fun openInternalDirections(
        destLat: Double,
        destLng: Double,
        destName: String,
        originLat: Double?,
        originLng: Double?
    ) {
        if (originLat == null || originLng == null) {
            openNavigationToPlace(destLat, destLng, destName)
            return
        }

        val originStep = com.shimtraveling.data.model.PathStep(
            id = "origin",
            placeId = "origin",
            placeName = "Ma position",
            placeImageUrl = "",
            placeType = com.shimtraveling.data.model.PlaceType.OTHER,
            latitude = originLat,
            longitude = originLng,
            activityType = com.shimtraveling.data.model.ActivityType.DISCOVERY,
            estimatedDurationMinutes = 0,
            estimatedCost = null,
            effortLevel = com.shimtraveling.data.model.EffortLevel.LOW,
            order = 0
        )
        val destStep = com.shimtraveling.data.model.PathStep(
            id = "dest",
            placeId = "dest",
            placeName = destName,
            placeImageUrl = "",
            placeType = com.shimtraveling.data.model.PlaceType.OTHER,
            latitude = destLat,
            longitude = destLng,
            activityType = com.shimtraveling.data.model.ActivityType.DISCOVERY,
            estimatedDurationMinutes = 0,
            estimatedCost = null,
            effortLevel = com.shimtraveling.data.model.EffortLevel.LOW,
            order = 1
        )

        val tempPath = com.shimtraveling.data.model.TravelPath(
            id = "temp_nav_${java.util.UUID.randomUUID()}",
            name = "Itinéraire vers $destName",
            description = "Navigation interne générée via OSRM",
            type = com.shimtraveling.data.model.PathType.BALANCED,
            steps = listOf(originStep, destStep),
            totalDurationMinutes = 0,
            totalCost = null,
            hasCompletePricing = false,
            totalEffort = com.shimtraveling.data.model.EffortLevel.LOW,
            distanceKm = 0.0
        )

        val intent = Intent(context, PathMapActivity::class.java).apply {
            putExtra("path", tempPath)
        }
        context.startActivity(intent)
    }

    fun openPathNavigation(path: TravelPath) {
        if (path.steps.isEmpty()) return

        val intent = Intent(context, PathMapActivity::class.java).apply {
            putExtra("path", path)
        }
        context.startActivity(intent)
    }

    fun sharePlace(lat: Double, lng: Double, placeName: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, placeName)
            putExtra(
                Intent.EXTRA_TEXT,
                "Découvrez $placeName sur l'application ShimTraveling !\nCoordonnées : $lat, $lng\nOuvrez ShimTraveling pour afficher le lieu et calculer l'itinéraire dans la carte intégrée."
            )
        }
        val chooser = Intent.createChooser(shareIntent, "Partager le lieu")
        context.startActivity(chooser)
    }

    fun sharePath(path: TravelPath) {
        val stepsText = path.steps.sortedBy { it.order }.joinToString("\n") {
            val costText = it.estimatedCost?.let { cost -> String.format("%.2f €", cost) } ?: "prix indisponible"
            "${it.order}. ${it.placeName} (${it.estimatedDurationMinutes} min, $costText)"
        }
        val totalCostText = if (path.hasCompletePricing && path.totalCost != null) {
            String.format("%.2f €", path.totalCost)
        } else {
            "Prix indisponible"
        }

        val shareText = buildString {
            appendLine(path.name)
            appendLine()
            appendLine("Durée totale: ${path.formattedDuration}")
            appendLine("Coût estimé: $totalCostText")
            appendLine("Distance: ${String.format("%.1f km", path.distanceKm)}")
            appendLine("Effort: ${path.totalEffort.getDisplayName()}")
            appendLine()
            appendLine("Étapes:")
            appendLine(stepsText)
            appendLine()
            appendLine("Ouvrez l'application ShimTraveling pour explorer ce parcours via notre carte interne !")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, path.name)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        val chooser = Intent.createChooser(shareIntent, "Partager le parcours")
        context.startActivity(chooser)
    }

    fun openPathGenerator(placeName: String? = null) {
        val intent = Intent(context, com.shimtraveling.ui.main.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(com.shimtraveling.ui.main.MainActivity.EXTRA_OPEN_PATH, true)
            if (!placeName.isNullOrBlank()) {
                putExtra(com.shimtraveling.ui.main.MainActivity.EXTRA_PREFILL_CITY, placeName.trim())
            }
        }
        context.startActivity(intent)
    }
}

