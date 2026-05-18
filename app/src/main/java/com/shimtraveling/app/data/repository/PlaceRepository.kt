package com.shimtraveling.data.repository

import com.shimtraveling.data.firestore.FirestoreRepository
import com.shimtraveling.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class PlaceRepository(private val firestore: FirestoreRepository) {

    private fun Flow<Result<List<Place>>>.withLikeStatus(userId: String) = map { result ->
        result.map { list ->
            list.map { place ->
                place.copy(
                    isLiked = firestore.isPlaceLiked(place.id, userId),
                    likes = firestore.getPlaceLikeCount(place.id)
                )
            }
        }
    }

    fun getAllPlaces(): Flow<Result<List<Place>>> = firestore.getAllPlaces()

    fun getPlaceById(id: String): Flow<Result<Place>> = flow {
        emit(firestore.getPlaceById(id))
    }

    fun searchPlaces(query: String): Flow<Result<List<Place>>> = firestore.searchPlaces(query)

    fun getPlacesByType(type: PlaceType): Flow<Result<List<Place>>> = firestore.getPlacesByType(type)

    fun getPlacesByTags(tags: List<String>): Flow<Result<List<Place>>> = firestore.getPlacesByTags(tags)

    suspend fun getAllUniqueTags(): Result<List<String>> = firestore.getAllUniqueTags()

    fun getPlacesByAuthor(authorId: String): Flow<Result<List<Place>>> = firestore.getPlacesByAuthor(authorId)

    fun getPlacesNearby(lat: Double, lng: Double, radiusKm: Double): Flow<Result<List<Place>>> = flow {
        emit(firestore.getPlacesNearby(lat, lng, radiusKm))
    }

    fun getPlacesByDateRange(startDate: java.util.Date, endDate: java.util.Date): Flow<Result<List<Place>>> =
        firestore.getPlacesByDateRange(startDate.time, endDate.time)

    fun getRandomPlace(): Flow<Result<Place>> = flow {
        val allPlacesResult = firestore.getAllPlaces()
        allPlacesResult.collect { result ->
            val places = result.getOrNull()
            if (places.isNullOrEmpty()) {
                emit(Result.failure(Exception("No places available")))
            } else {
                emit(Result.success(places.random()))
            }
        }
    }

    fun getPlacesWithLikeStatus(userId: String) =
        firestore.getAllPlaces().withLikeStatus(userId)

    fun searchPlacesWithLikeStatus(query: String, userId: String) =
        firestore.searchPlaces(query).withLikeStatus(userId)

    fun getPlacesByTypeWithLikeStatus(type: PlaceType, userId: String) =
        firestore.getPlacesByType(type).withLikeStatus(userId)

    fun getPlacesByTagsWithLikeStatus(tags: List<String>, userId: String) =
        firestore.getPlacesByTags(tags).withLikeStatus(userId)

    suspend fun likePlace(placeId: String, userId: String?): Result<Unit> {
        return firestore.likePlace(placeId, userId)
    }

    suspend fun unlikePlace(placeId: String, userId: String?): Result<Unit> {
        return firestore.unlikePlace(placeId, userId)
    }

    suspend fun isPlaceLiked(placeId: String, userId: String): Boolean {
        return firestore.isPlaceLiked(placeId, userId)
    }

    suspend fun getPlaceLikeCount(placeId: String): Int {
        return firestore.getPlaceLikeCount(placeId)
    }

    suspend fun reportPlace(placeId: String, userId: String, reason: String): Result<Unit> {
        return firestore.reportPlace(placeId, userId, reason)
    }

    suspend fun reportPlace(placeId: String, reason: String) {
    }

    suspend fun addPlace(place: Place): Result<Unit> {
        return firestore.addPlace(place)
    }

    suspend fun updatePlace(place: Place): Result<Unit> {
        return firestore.updatePlace(place)
    }

    suspend fun deletePlace(placeId: String): Result<Unit> {
        return firestore.deletePlace(placeId)
    }

    suspend fun getCommentsByPlace(placeId: String): Result<List<Comment>> {
        return firestore.getPlaceComments(placeId)
    }

    suspend fun addComment(placeId: String, comment: Comment): Result<Unit> {
        return firestore.addPlaceComment(placeId, comment)
    }

    fun getPhotosByPlace(
        placeId: String,
        viewerUserId: String? = null,
        viewerGroupIds: List<String> = emptyList()
    ): Flow<Result<List<Photo>>> {
        return firestore.getVisiblePhotosByPlace(placeId, viewerUserId, viewerGroupIds)
    }
}
