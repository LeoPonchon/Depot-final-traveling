package com.shimtraveling.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.firebase.firestore.FirebaseFirestore
import com.shimtraveling.R
import com.shimtraveling.core.LocationCache
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.firestore.FirestoreCollections
import com.shimtraveling.data.firestore.UserDocument
import com.shimtraveling.data.model.NotificationSettings
import com.shimtraveling.features.profile.NotificationsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.Random
import java.util.concurrent.TimeUnit

class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TravelingApp
        val userRepository = app.userRepository
        val photoRepository = app.photoRepository
        val firestoreRepository = app.firestoreRepository

        val userResult = userRepository.getCurrentUser().first()
        val user = userResult.getOrNull() ?: return Result.success()

        val settingsResult = userRepository.getNotificationSettings()
        val settings = settingsResult.getOrNull() ?: NotificationSettings()

        val prefs = applicationContext.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(
            "last_check_${user.id}",
            System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(15)
        )

        val locationPrefs = applicationContext.getSharedPreferences(
            LocationCache.PREFS_NAME, Context.MODE_PRIVATE
        )
        val lastLat = locationPrefs.getFloat(LocationCache.KEY_LAT, Float.NaN)
        val lastLng = locationPrefs.getFloat(LocationCache.KEY_LNG, Float.NaN)

        val nearbyCenterLat = settings.nearbyCenterLat
        val nearbyCenterLng = settings.nearbyCenterLng
        val effectiveNearbyLat = nearbyCenterLat ?: if (!lastLat.isNaN()) lastLat.toDouble() else Double.NaN
        val effectiveNearbyLng = nearbyCenterLng ?: if (!lastLng.isNaN()) lastLng.toDouble() else Double.NaN

        val photosResult = photoRepository.getRecentPhotos(50).first()
        val photos = photosResult.getOrNull() ?: emptyList()
        val newPhotos = photos
            .filter { it.createdAt.time > lastCheck && it.authorId != user.id }
            .sortedByDescending { it.createdAt }

        val followedUserTokens = (settings.followedUsers + user.followedUsers).distinct()
        val followedAuthorIds = resolveAuthorIdsFromFollowTokens(followedUserTokens)
            .toMutableSet().apply {
                followedUserTokens.forEach { t ->
                    val s = t.trim()
                    if (s.length >= 20 && s.matches(Regex("[a-zA-Z0-9]+"))) add(s)
                }
            }
        val followedTags = (settings.followedTags + user.followedTags).map { it.lowercase(Locale.getDefault()) }.distinct()
        val followedPlaces = (settings.followedPlaceIds + user.followedPlaces)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val hasConfiguredRules =
            (settings.newPhotoFromUser && followedUserTokens.isNotEmpty()) ||
            (settings.newPhotoInGroup && user.groups.isNotEmpty()) ||
            settings.newPhotoNearby ||
            (settings.newPhotoWithTag && followedTags.isNotEmpty()) ||
            (settings.newPhotoInFollowedPlace && followedPlaces.isNotEmpty())

        for (photo in newPhotos) {
            val match = when {
                !hasConfiguredRules -> true
                else -> {
                    var m = false
                    if (settings.newPhotoFromUser && followedAuthorIds.isNotEmpty() &&
                        photo.authorId in followedAuthorIds) {
                        m = true
                    }
                    if (settings.newPhotoInGroup && photo.groupId != null &&
                        photo.groupId in user.groups) {
                        m = true
                    }
                    if (settings.newPhotoNearby && !effectiveNearbyLat.isNaN() && !effectiveNearbyLng.isNaN() &&
                        photo.latitude != 0.0 && photo.longitude != 0.0) {
                        val km = app.locationService.calculateDistanceKm(
                            effectiveNearbyLat, effectiveNearbyLng,
                            photo.latitude, photo.longitude
                        )
                        if (km <= settings.nearbyRadiusKm) m = true
                    }
                    if (settings.newPhotoWithTag && followedTags.isNotEmpty()) {
                        val tagsLower = photo.tags.map { it.lowercase(Locale.getDefault()) }
                        if (tagsLower.any { t ->
                                followedTags.any { f -> t.contains(f) || f.contains(t) }
                            }) m = true
                    }
                    if (settings.newPhotoInFollowedPlace && followedPlaces.isNotEmpty() &&
                        photo.placeId in followedPlaces) {
                        m = true
                    }
                    m
                }
            }

            if (match) {
                val title = "Nouvelle photo à ${photo.placeName}"
                val message = "${photo.authorName} a partagé une nouvelle photo."

                showSystemNotification(title, message, photo.id)
                break
            }
        }

        prefs.edit()
            .putLong("last_check_${user.id}", System.currentTimeMillis())
            .apply()

        return Result.success()
    }


    private fun showSystemNotification(title: String, message: String, photoId: String) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "traveling_notifications"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ShimTraveling Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, NotificationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("PHOTO_ID", photoId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(Random().nextInt(), notification)
    }

    private suspend fun resolveAuthorIdsFromFollowTokens(raw: List<String>): Set<String> {
        val out = linkedSetOf<String>()
        val usersCollection = FirebaseFirestore.getInstance().collection(FirestoreCollections.USERS)
        val allUsers = try {
            usersCollection.get().await().documents.mapNotNull { it.toObject(UserDocument::class.java) }
        } catch (_: Exception) {
            emptyList()
        }

        for (token in raw) {
            val t = token.trim()
            if (t.isEmpty()) continue
            try {
                if (usersCollection.document(t).get().await().exists()) {
                    out.add(t)
                    continue
                }
                val match = allUsers.firstOrNull { it.username.equals(t, ignoreCase = true) }
                if (match != null) out.add(match.id)
            } catch (_: Exception) {  }
        }
        return out
    }

    companion object {
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "notification_worker",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
