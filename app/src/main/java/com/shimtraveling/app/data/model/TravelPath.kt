package com.shimtraveling.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
data class TravelPath(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: PathType,
    val steps: List<PathStep>,
    val totalDurationMinutes: Int,
    val totalCost: Double? = null,
    val hasCompletePricing: Boolean = false,
    val totalEffort: EffortLevel,
    val distanceKm: Double,
    val userId: String? = null,
    val isSaved: Boolean = false,
    val likes: Int = 0,
    val isLiked: Boolean = false,
    val city: String? = null,
    val createdAt: Date = Date()
) : Parcelable {
    val formattedDuration: String
        get() {
            val hours = totalDurationMinutes / 60
            val minutes = totalDurationMinutes % 60
            return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
        }
}

@Parcelize
data class PathStep(
    val id: String,
    val order: Int,
    val placeId: String,
    val placeName: String,
    val placeImageUrl: String,
    val placeType: PlaceType,
    val latitude: Double,
    val longitude: Double,
    val activityType: ActivityType,
    val estimatedDurationMinutes: Int,
    val estimatedCost: Double? = null,
    val effortLevel: EffortLevel,
    val notes: String? = null,
    val openingHours: String? = null,
    val weatherCondition: WeatherCondition? = null,
    val timeOfDay: TimeOfDay? = null,
    val startTimeMinutesFromMidnight: Int? = null,
    val walkingTimeFromPreviousMinutes: Int? = null,
    val tags: List<String> = emptyList(),

    val videoUrl: String? = null,

    val elevationMeters: Double? = null
) : Parcelable

enum class PathType {
    ECONOMIC,
    BALANCED,
    COMFORT;

    fun getDisplayName(): String = when (this) {
        ECONOMIC -> "Économique"
        BALANCED -> "Équilibré"
        COMFORT -> "Confort"
    }

    fun getDescription(): String = when (this) {
        ECONOMIC -> "Optimisé pour le budget"
        BALANCED -> "Équilibre entre coût et confort"
        COMFORT -> "Optimisé pour le confort"
    }
}

enum class ActivityType {
    RESTAURANT,
    CULTURE,
    NATURE,
    SHOPPING,
    LEISURE,
    DISCOVERY;

    fun getDisplayName(): String = when (this) {
        RESTAURANT -> "Restauration"
        CULTURE -> "Culture"
        NATURE -> "Nature"
        SHOPPING -> "Shopping"
        LEISURE -> "Loisirs"
        DISCOVERY -> "Découverte"
    }

    fun getIcon(): String = when (this) {
        RESTAURANT -> "restaurant"
        CULTURE -> "museum"
        NATURE -> "park"
        SHOPPING -> "shopping_bag"
        LEISURE -> "sports_esports"
        DISCOVERY -> "explore"
    }
}

enum class EffortLevel {
    LOW,
    MEDIUM,
    HIGH;

    fun getDisplayName(): String = when (this) {
        LOW -> "Faible"
        MEDIUM -> "Moyen"
        HIGH -> "Élevé"
    }

    fun getDescription(): String = when (this) {
        LOW -> "Accessible à tous"
        MEDIUM -> "Marche modérée"
        HIGH -> "Niveau sportif"
    }
}

enum class WeatherCondition {
    SUNNY,
    CLOUDY,
    RAINY,
    COLD,
    HOT;

    fun getDisplayName(): String = when (this) {
        SUNNY -> "Ensoleillé"
        CLOUDY -> "Nuageux"
        RAINY -> "Pluvieux"
        COLD -> "Froid"
        HOT -> "Chaud"
    }
}

@Parcelize
data class PathPreferences(
    val activities: List<ActivityType> = ActivityType.values().toList(),
    val mustVisitPlaces: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val maxBudget: Double? = null,
    val maxDurationMinutes: Int = 240,
    val maxEffortLevel: EffortLevel = EffortLevel.MEDIUM,
    val maxSteps: Int = 4,
    val avoidCold: Boolean = false,
    val avoidHeat: Boolean = false,
    val avoidRain: Boolean = false,

    val avoidHumidity: Boolean = false,
    val departureTimeMinutes: Int = 9 * 60,
    val audienceSeniors: Boolean = false,
    val audienceChildren: Boolean = false,
    val audienceReducedMobility: Boolean = false,

    val audienceHealthSensitivity: Boolean = false
) : Parcelable

