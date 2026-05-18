package com.shimtraveling.data.repository

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await

class StorageRepository(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    suspend fun uploadPhoto(
        userId: String,
        photoId: String,
        imageUri: Uri,
        customMetadata: Map<String, String> = emptyMap()
    ): Result<String> {
        return try {
            ensureWriteSession(userId)
            val extension = imageUri.lastPathSegment
                ?.substringAfterLast('.', "jpg")
                ?.lowercase()
                ?.ifBlank { "jpg" }
                ?: "jpg"
            val storageRef = storage.reference
                .child("users")
                .child(userId)
                .child("photos")
                .child("$photoId.$extension")

            val metaBuilder = StorageMetadata.Builder()
                .setContentType("image/$extension")
            customMetadata.forEach { (k, v) -> metaBuilder.setCustomMetadata(k, v) }
            storageRef.putFile(imageUri, metaBuilder.build()).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePhotoByUrl(photoUrl: String): Result<Unit> {
        return try {
            if (photoUrl.isBlank()) {
                return Result.success(Unit)
            }
            if (!photoUrl.startsWith("gs://") && !photoUrl.startsWith("https://")) {
                return Result.success(Unit)
            }

            val storageRef = storage.getReferenceFromUrl(photoUrl)
            storageRef.delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is StorageException && e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                Result.success(Unit)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun uploadAvatar(userId: String, imageUri: Uri): Result<String> {
        return try {
            ensureWriteSession(userId)
            val extension = imageUri.lastPathSegment
                ?.substringAfterLast('.', "jpg")
                ?.lowercase()
                ?.ifBlank { "jpg" }
                ?: "jpg"
            val storageRef = storage.reference
                .child("users")
                .child(userId)
                .child("avatar")
                .child("avatar-profile.$extension")

            val metadata = StorageMetadata.Builder()
                .setContentType("image/$extension")
                .setCustomMetadata("visibility", "PUBLIC")
                .setCustomMetadata("assetType", "avatar")
                .build()

            storageRef.putFile(imageUri, metadata).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadVoiceAttachment(
        userId: String,
        photoId: String,
        uri: Uri,
        customMetadata: Map<String, String> = emptyMap()
    ): Result<String> {
        return try {
            ensureWriteSession(userId)
            val ext = uri.lastPathSegment
                ?.substringAfterLast('.', "m4a")
                ?.lowercase()
                ?.ifBlank { "m4a" }
                ?: "m4a"
            val storageRef = storage.reference
                .child("users")
                .child(userId)
                .child("voice")
                .child("$photoId.$ext")

            val metaBuilder = StorageMetadata.Builder()
                .setContentType("audio/$ext")
                .setCustomMetadata("assetType", "voice")
            customMetadata.forEach { (k, v) -> metaBuilder.setCustomMetadata(k, v) }
            val metadata = metaBuilder.build()

            storageRef.putFile(uri, metadata).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun ensureWriteSession(expectedUserId: String) {
        val user = auth.currentUser ?: throw IllegalStateException("Session Firebase indisponible")
        if (user.isAnonymous) {
            throw IllegalStateException("Connectez-vous avec un compte pour envoyer ce fichier")
        }
        if (user.uid != expectedUserId) {
            throw IllegalStateException("La session active ne correspond pas au profil en cours")
        }
        user.getIdToken(true).await()
    }
}
