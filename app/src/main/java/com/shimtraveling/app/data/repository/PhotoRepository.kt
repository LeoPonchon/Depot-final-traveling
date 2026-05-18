package com.shimtraveling.data.repository

import com.shimtraveling.data.firestore.FirestoreRepository
import com.shimtraveling.data.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class PhotoRepository(
    private val firestore: FirestoreRepository,
    private val storageRepository: StorageRepository
) {

    private fun Flow<Result<List<Photo>>>.withLikeStatus(userId: String) = map { result ->
        result.map { list ->
            list.map { photo ->
                photo.copy(
                    isLiked = firestore.isPhotoLiked(photo.id, userId),
                    likes = firestore.getPhotoLikeCount(photo.id)
                )
            }
        }
    }

    fun getAllPhotos(): Flow<Result<List<Photo>>> = firestore.getAllPhotos()

    fun getRecentPhotos(limit: Int): Flow<Result<List<Photo>>> = firestore.getRecentPhotos(limit)

    fun getPhotoById(id: String): Flow<Result<Photo?>> = flow {
        emit(firestore.getPhotoById(id))
    }

    fun searchPhotos(
        query: String,
        viewerUserId: String? = null,
        viewerGroupIds: List<String> = emptyList()
    ): Flow<Result<List<Photo>>> = firestore.searchPhotos(query, viewerUserId, viewerGroupIds)

    fun getPhotosByCategory(category: PhotoCategory): Flow<Result<List<Photo>>> =
        firestore.getPhotosByCategory(category)

    fun getPhotosByTags(tags: List<String>): Flow<Result<List<Photo>>> =
        firestore.getPhotosByTags(tags)

    suspend fun getAllUniqueTags(): Result<List<String>> = firestore.getAllPhotoTags()

    fun getPhotosByAuthor(authorId: String): Flow<Result<List<Photo>>> =
        firestore.getPhotosByAuthor(authorId)

    fun getPrivatePhotosByAuthor(authorId: String): Flow<Result<List<Photo>>> =
        firestore.getPrivatePhotosByAuthor(authorId)

    fun getPrivatePhotosByAuthorWithLikeStatus(authorId: String, userId: String) =
        firestore.getPrivatePhotosByAuthor(authorId).withLikeStatus(userId)

    fun getPhotosByAuthorWithLikeStatus(authorId: String, userId: String) =
        firestore.getPhotosByAuthor(authorId).withLikeStatus(userId)

    suspend fun getPhotoByIdWithLikeStatus(photoId: String, userId: String): Result<Photo?> {
        val photoResult = firestore.getPhotoById(photoId)
        return photoResult.map { photo ->
            if (photo != null) {
                val isLiked = firestore.isPhotoLiked(photo.id, userId)
                val likeCount = firestore.getPhotoLikeCount(photo.id)
                photo.copy(isLiked = isLiked, likes = likeCount)
            } else {
                null
            }
        }
    }

    fun getPhotosNearby(lat: Double, lng: Double, radiusKm: Double): Flow<Result<List<Photo>>> = flow {
        emit(firestore.getPhotosNearby(lat, lng, radiusKm))
    }

    fun getPhotosByDateRange(startDate: java.util.Date, endDate: java.util.Date): Flow<Result<List<Photo>>> =
        firestore.getPhotosByDateRange(startDate.time, endDate.time)

    fun getVisiblePhotosByDateRange(
        startDate: java.util.Date,
        endDate: java.util.Date,
        userId: String,
        userGroups: List<String>
    ): Flow<Result<List<Photo>>> =
        firestore.getVisiblePhotos(userId, userGroups).map { result ->
            result.map { list ->
                list.filter { it.createdAt.time in startDate.time..endDate.time }
            }
        }

    fun getPhotosBySimilarity(
        photoId: String,
        viewerUserId: String? = null,
        viewerGroupIds: List<String> = emptyList()
    ): Flow<Result<List<Photo>>> =
        firestore.getPhotosBySimilarity(photoId, viewerUserId, viewerGroupIds)

    fun getRandomPhoto(): Flow<Result<Photo>> = flow {
        val allPhotosResult = firestore.getAllPhotos()
        allPhotosResult.collect { result ->
            val photos = result.getOrNull()
            if (photos.isNullOrEmpty()) {
                emit(Result.failure(Exception("No photos available")))
            } else {
                emit(Result.success(photos.random()))
            }
        }
    }

    fun getPhotosWithLikeStatus(userId: String) =
        firestore.getAllPhotos().withLikeStatus(userId)

    fun searchPhotosWithLikeStatus(query: String, userId: String, userGroups: List<String> = emptyList()) =
        firestore.searchPhotos(query, userId, userGroups).withLikeStatus(userId)

    fun getPhotosByCategoryWithLikeStatus(category: PhotoCategory, userId: String) =
        firestore.getPhotosByCategory(category).withLikeStatus(userId)

    fun getPhotosByTagsWithLikeStatus(tags: List<String>, userId: String) =
        firestore.getPhotosByTags(tags).withLikeStatus(userId)

    fun getPublicPhotos(): Flow<Result<List<Photo>>> = firestore.getPublicPhotos()
    fun getVisiblePhotos(userId: String?, userGroups: List<String>): Flow<Result<List<Photo>>> =
        firestore.getVisiblePhotos(userId, userGroups)

    fun getVisiblePhotosWithLikeStatus(userId: String, userGroups: List<String>) =
        firestore.getVisiblePhotos(userId, userGroups).withLikeStatus(userId)
    fun getPhotosByGroup(groupId: String): Flow<Result<List<Photo>>> =
        firestore.getPhotosByGroup(groupId)

    fun getPhotosByGroupWithLikeStatus(groupId: String, userId: String) =
        firestore.getPhotosByGroup(groupId).withLikeStatus(userId)

    suspend fun likePhoto(photoId: String, userId: String?): Result<Unit> {
        return firestore.likePhoto(photoId, userId)
    }

    suspend fun unlikePhoto(photoId: String, userId: String?): Result<Unit> {
        return firestore.unlikePhoto(photoId, userId)
    }

    suspend fun isPhotoLiked(photoId: String, userId: String): Boolean {
        return firestore.isPhotoLiked(photoId, userId)
    }

    suspend fun getPhotoLikeCount(photoId: String): Int {
        return firestore.getPhotoLikeCount(photoId)
    }

    suspend fun attachLikeStatus(photos: List<Photo>, userId: String): List<Photo> {
        if (photos.isEmpty()) return photos
        return coroutineScope {
            photos.map { photo ->
                async {
                    val isLiked = firestore.isPhotoLiked(photo.id, userId)
                    val count = firestore.getPhotoLikeCount(photo.id)
                    photo.copy(isLiked = isLiked, likes = count)
                }
            }.awaitAll()
        }
    }

    suspend fun reportPhoto(photoId: String, userId: String, reason: String): Result<Unit> {
        return firestore.reportPhoto(photoId, userId, reason)
    }

    suspend fun addPhoto(photo: Photo): Result<Unit> {
        return firestore.addPhoto(photo)
    }

    suspend fun updatePhoto(photo: Photo): Result<Unit> {
        return firestore.updatePhoto(photo)
    }

    suspend fun deletePhoto(photoId: String): Result<Unit> {
        val photoResult = firestore.getPhotoById(photoId)
        val photo = photoResult.getOrElse { return Result.failure(it) }

        if (photo?.url?.isNotBlank() == true) {
            val storageDeleteResult = storageRepository.deletePhotoByUrl(photo.url)
            if (storageDeleteResult.isFailure) {
                return Result.failure(
                    storageDeleteResult.exceptionOrNull()
                        ?: Exception("Erreur lors de la suppression du fichier Storage")
                )
            }
        }
        photo?.audioUrl?.takeIf { it.isNotBlank() }?.let { storageRepository.deletePhotoByUrl(it) }

        return firestore.deletePhoto(photoId)
    }

    suspend fun getCommentsByPhoto(photoId: String): Result<List<Comment>> {
        return firestore.getCommentsByPhoto(photoId)
    }

    suspend fun addComment(comment: Comment): Result<Unit> {
        return firestore.addComment(comment)
    }
}
