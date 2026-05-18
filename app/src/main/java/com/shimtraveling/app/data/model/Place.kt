package com.shimtraveling.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
data class Place(
    val id: String,
    val name: String,
    val description: String,
    val imageUrl: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val city: String? = null,
    val type: PlaceType,
    val authorId: String,
    val authorName: String,
    val authorAvatar: String? = null,
    val likes: Int = 0,
    val isLiked: Boolean = false,
    val tags: List<String> = emptyList(),
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val distance: Double? = null,
    val price: Double? = null,
    val priceCount: Int = 0,
    val openingHours: String? = null,
    val structuredOpeningHours: StructuredOpeningHours? = null,

    val videoUrl: String? = null
) : Parcelable

enum class PlaceType {
    NATURE,
    MUSEUM,
    RESTAURANT,
    MONUMENT,
    SHOPPING,
    STREET,
    PARK,
    BEACH,
    MOUNTAIN,
    OTHER;

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
        fun fromString(value: String): PlaceType = values().find { it.name.equals(value, true) } ?: OTHER
    }
}
