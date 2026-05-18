package com.shimtraveling.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
data class User(
    val id: String,
    val username: String,
    val email: String,
    val avatar: String? = null,
    val bio: String? = null,
    val favorites: List<String> = emptyList(),
    val photoFavorites: List<String> = emptyList(),
    val placeFavorites: List<String> = emptyList(),
    val pathFavorites: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val followedTags: List<String> = emptyList(),
    val followedUsers: List<String> = emptyList(),
    val followedPlaces: List<String> = emptyList(),
    val notifications: List<String> = emptyList(),
    val isAdmin: Boolean = false,
    val createdAt: Date = Date()
) : Parcelable

@Parcelize
data class Group(
    val id: String,
    val name: String,
    val description: String? = null,
    val ownerId: String,
    val members: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
    val photoCount: Int = 0,
    val createdAt: Date = Date()
) : Parcelable

@Parcelize
data class NotificationSettings(
    val newPhotoFromUser: Boolean = true,
    val newPhotoInGroup: Boolean = true,
    val newPhotoNearby: Boolean = true,
    val newPhotoWithTag: Boolean = true,

    val newPhotoInFollowedPlace: Boolean = false,
    val nearbyRadiusKm: Float = 5f,

    val nearbyCenterLat: Double? = null,

    val nearbyCenterLng: Double? = null,

    val followedUsers: List<String> = emptyList(),
    val followedTags: List<String> = emptyList(),
    val followedPlaceIds: List<String> = emptyList(),
) : Parcelable
