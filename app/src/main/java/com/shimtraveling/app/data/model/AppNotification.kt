package com.shimtraveling.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
data class AppNotification(
    val id: String,
    val title: String,
    val message: String,
    val type: NotificationType,
    val createdAt: Date = Date(),
    val isRead: Boolean = false,
    val relatedId: String? = null
) : Parcelable

enum class NotificationType {
    NEW_PHOTO,
    NEW_PATH,
    LIKE_RECEIVED,
    COMMENT_RECEIVED,
    SYSTEM
}
