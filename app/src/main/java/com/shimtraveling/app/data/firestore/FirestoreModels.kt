package com.shimtraveling.data.firestore

data class PlaceDocument(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String? = null,
    val city: String? = null,
    val type: String = "OTHER",
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatar: String? = null,
    val likes: Int = 0,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val price: Double? = null,
    val priceCount: Int = 0,
    val openingHours: String? = null,

    val openingHoursStructuredJson: String? = null,
    val videoUrl: String? = null
)

data class PhotoDocument(
    val id: String = "",
    val url: String = "",
    val thumbnailUrl: String? = null,
    val placeId: String = "",
    val placeName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String? = null,
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatar: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val category: String = "OTHER",
    val placeType: String? = null,
    val searchCategories: List<String> = emptyList(),
    val moderationStatus: String = "VISIBLE",
    val likes: Int = 0,
    val visibility: String = "PUBLIC",
    val groupId: String? = null,
    val howToGo: String? = null,
    val takenAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),

    val publicCreatedAt: Long? = null,
    val price: Double? = null,
    val timeOfDay: String? = null,
    val audioUrl: String? = null,
    val locationPrecision: String? = "EXACT"
)

data class CommentDocument(
    val id: String = "",
    val photoId: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatar: String? = null,
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class LikeDocument(
    val id: String = "",
    val photoId: String = "",
    val userId: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class PlaceLikeDocument(
    val id: String = "",
    val placeId: String = "",
    val userId: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class PathLikeDocument(
    val id: String = "",
    val pathId: String = "",
    val userId: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class GroupDocument(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val ownerId: String = "",
    val members: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
    val photoCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class SavedPathDocument(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val type: String = "BALANCED",
    val stepsJson: String = "",
    val totalDurationMinutes: Int = 0,
    val totalCost: Double? = null,
    val hasCompletePricing: Boolean = false,
    val totalEffort: String = "MEDIUM",
    val distanceKm: Double = 0.0,
    val userId: String? = null,
    val likes: Int = 0,
    val city: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class UserDocument(
    val id: String = "",
    val username: String = "",
    val email: String = "",
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
    val createdAt: Long = System.currentTimeMillis()
)

data class ReportDocument(
    val id: String = "",
    val targetType: String = "PHOTO",
    val targetId: String = "",
    val photoId: String = "",
    val placeId: String = "",
    val userId: String = "",
    val reason: String = "",
    val status: String = "OPEN",
    val createdAt: Long = System.currentTimeMillis()
)

data class NotificationDocument(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val type: String = "SYSTEM",
    val userId: String = "",
    val isRead: Boolean = false,
    val relatedId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class PlaceCommentDocument(
    val id: String = "",
    val placeId: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatar: String? = null,
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
