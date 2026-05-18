package com.shimtraveling.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date


enum class LocationPrecision {
    EXACT,
    APPROXIMATE;

    companion object {
        fun fromString(value: String?): LocationPrecision =
            values().find { it.name.equals(value, ignoreCase = true) } ?: EXACT
    }
}


enum class PhotoModerationStatus {
    VISIBLE,
    HIDDEN,
    PENDING;

    companion object {
        fun fromString(value: String?): PhotoModerationStatus =
            values().find { it.name.equals(value, ignoreCase = true) } ?: VISIBLE
    }
}

fun Photo.isModerationVisibleToPublic(): Boolean =
    moderationStatus != PhotoModerationStatus.HIDDEN

enum class TimeOfDay {
    MORNING, AFTERNOON, EVENING;

    fun getDisplayName(): String = when (this) {
        MORNING -> "Matin"
        AFTERNOON -> "Après-midi"
        EVENING -> "Soir"
    }

    companion object {
        fun fromString(value: String): TimeOfDay = values().find { it.name.equals(value, true) } ?: AFTERNOON
    }
}

@Parcelize
data class Photo(
    val id: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val placeId: String,
    val placeName: String,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String? = null,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val category: PhotoCategory = PhotoCategory.OTHER,

    val placeType: String? = null,

    val searchCategories: List<String> = emptyList(),
    val moderationStatus: PhotoModerationStatus = PhotoModerationStatus.VISIBLE,
    val likes: Int = 0,
    val isLiked: Boolean = false,
    val isReported: Boolean = false,
    val visibility: PhotoVisibility = PhotoVisibility.PUBLIC,
    val groupId: String? = null,
    val howToGo: String? = null,
    val takenAt: Date? = null,
    val createdAt: Date = Date(),
    val price: Double? = null,
    val timeOfDay: TimeOfDay? = null,

    val audioUrl: String? = null,
    val locationPrecision: LocationPrecision = LocationPrecision.EXACT
) : Parcelable

enum class PhotoVisibility {
    PUBLIC, GROUP, PRIVATE
}

enum class PhotoCategory {
    NATURE, MUSEUM, RESTAURANT, MONUMENT, SHOPPING, STREET, PARK, BEACH, MOUNTAIN, OTHER;

    fun getDisplayName(): String = when (this) {
        NATURE -> "Nature"
        MUSEUM -> "Musée"
        RESTAURANT -> "Restaurant"
        MONUMENT -> "Monument"
        SHOPPING -> "Shopping"
        STREET -> "Rue"
        PARK -> "Parc"
        BEACH -> "Plage"
        MOUNTAIN -> "Montagne"
        OTHER -> "Autre"
    }

    companion object {
        fun fromString(value: String): PhotoCategory = values().find { it.name.equals(value, true) } ?: OTHER
    }
}

fun Photo.matchesCategoryFilter(category: PhotoCategory): Boolean {
    val key = category.name
    if (category == this.category) return true
    if (placeType != null && placeType.equals(key, ignoreCase = true)) return true
    return searchCategories.any { it.equals(key, ignoreCase = true) }
}

@Parcelize
data class Comment(
    val id: String,
    val photoId: String,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String? = null,
    val content: String,
    val createdAt: Date = Date()
) : Parcelable
