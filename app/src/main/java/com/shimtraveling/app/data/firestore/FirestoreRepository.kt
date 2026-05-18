package com.shimtraveling.data.firestore

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.shimtraveling.data.model.*
import kotlinx.coroutines.channels.awaitClose
import com.shimtraveling.core.PhotoSimilarity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*
import kotlin.math.*

object FirestoreCollections {
    const val PLACES = "places"
    const val PHOTOS = "photos"
    const val COMMENTS = "comments"
    const val PLACE_COMMENTS = "place_comments"
    const val LIKES = "likes"
    const val PLACE_LIKES = "place_likes"
    const val GROUPS = "groups"
    const val SAVED_PATHS = "saved_paths"
    const val USERS = "users"
    const val REPORTS = "reports"
    const val NOTIFICATIONS = "notifications"
}

private inline fun <reified T : Any> DocumentSnapshot.toObject(): T? {
    return this.toObject(T::class.java)
}

private inline fun <reified T : Any> QuerySnapshot.toObjects(): List<T> {
    return this.documents.mapNotNull { it.toObject<T>() }
}

class FirestoreRepository(private val db: FirebaseFirestore) {

    private val gson = Gson()

    private val placesCollection      = db.collection(FirestoreCollections.PLACES)
    private val photosCollection      = db.collection(FirestoreCollections.PHOTOS)
    private val commentsCollection    = db.collection(FirestoreCollections.COMMENTS)
    private val placeCommentsCollection = db.collection(FirestoreCollections.PLACE_COMMENTS)
    private val likesCollection       = db.collection(FirestoreCollections.LIKES)
    private val placeLikesCollection  = db.collection(FirestoreCollections.PLACE_LIKES)
    private val groupsCollection      = db.collection(FirestoreCollections.GROUPS)
    private val savedPathsCollection  = db.collection(FirestoreCollections.SAVED_PATHS)
    private val pathLikesCollection   = db.collection("path_likes")
    private val usersCollection       = db.collection(FirestoreCollections.USERS)
    private val reportsCollection     = db.collection(FirestoreCollections.REPORTS)
    private val notificationsCollection = db.collection(FirestoreCollections.NOTIFICATIONS)

    private fun QuerySnapshot.toPhotos(): List<Photo> {
        return documents.mapNotNull { doc ->
            doc.toObject<PhotoDocument>()?.let { documentToPhoto(it) }
        }
    }

    private fun List<Photo>.sortedByCreatedAtDesc(): List<Photo> =
        sortedByDescending { it.createdAt.time }

    private suspend fun enrichPhotosWithLiveAuthors(photos: List<Photo>): List<Photo> {
        if (photos.isEmpty()) return photos
        val authors = loadUserAvatarById(photos.map { it.authorId }.toSet())
        if (authors.isEmpty()) return photos
        return photos.map { p ->
            val live = authors[p.authorId]
            if (live != null) p.copy(authorName = live.first, authorAvatar = live.second) else p
        }
    }

    private fun listenPhotosQuery(
        query: Query,
        filter: (List<Photo>) -> List<Photo> = { it }
    ): Flow<Result<List<Photo>>> = callbackFlow {
        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(Result.failure(error))
                return@addSnapshotListener
            }
            val photos = snapshot?.toPhotos().orEmpty()
            launch {
                try {
                    val enriched = enrichPhotosWithLiveAuthors(photos)
                    trySend(Result.success(filter(enriched).sortedByCreatedAtDesc()))
                } catch (e: Exception) {
                    trySend(Result.success(filter(photos).sortedByCreatedAtDesc()))
                }
            }
        }
        awaitClose { listener.remove() }
    }

    private fun listenAllPlacesFiltered(
        filter: (List<Place>) -> List<Place>
    ): Flow<Result<List<Place>>> = callbackFlow {
        val listener = placesCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(Result.failure(error))
                return@addSnapshotListener
            }
            val allPlaces = snapshot?.documents?.mapNotNull { doc ->
                doc.toObject<PlaceDocument>()?.let { documentToPlace(it) }
            }.orEmpty()
            trySend(Result.success(filter(allPlaces).sortedByDescending { it.createdAt.time }))
        }
        awaitClose { listener.remove() }
    }

    private fun listenAllSavedPathsFiltered(
        filter: (List<TravelPath>) -> List<TravelPath>
    ): Flow<Result<List<TravelPath>>> = callbackFlow {
        val listener = savedPathsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(Result.failure(error))
                return@addSnapshotListener
            }
            val allPaths = snapshot?.documents?.mapNotNull { doc ->
                doc.toObject<SavedPathDocument>()?.let { documentToPath(it) }
            }.orEmpty()
            trySend(Result.success(filter(allPaths).sortedByDescending { it.createdAt.time }))
        }
        awaitClose { listener.remove() }
    }


    fun getAllPlaces(): Flow<Result<List<Place>>> = callbackFlow {
        val listener = placesCollection.addSnapshotListener { snapshot, error ->
            if (error != null) { trySend(Result.failure(error)); return@addSnapshotListener }
            val places = snapshot?.documents?.mapNotNull { doc ->
                doc.toObject<PlaceDocument>()?.let { documentToPlace(it) }
            } ?: emptyList()
            trySend(Result.success(places))
        }
        awaitClose { listener.remove() }
    }

    suspend fun getPlaceById(id: String): Result<Place> {
        return try {
            val doc = placesCollection.document(id).get().await()
            val placeDoc = doc.toObject<PlaceDocument>()
            if (placeDoc != null) {
                Result.success(documentToPlace(placeDoc))
            } else {
                Result.failure(Exception("Place not found"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun findSimilarPlace(name: String): Place? {
        return try {
            val snapshot = placesCollection.get().await()
            val allPlaces = snapshot.documents.mapNotNull { doc ->
                doc.toObject<PlaceDocument>()?.let { documentToPlace(it) }
            }
            allPlaces.find { com.shimtraveling.core.StringUtils.isTooSimilar(it.name, name) }
        } catch (e: Exception) { null }
    }

    fun getPlacesByType(type: PlaceType): Flow<Result<List<Place>>> = callbackFlow {
        listenAllPlacesFiltered { places ->
            places.filter { it.type == type }
        }.collect { trySend(it) }
    }

    fun getPlacesByTags(tags: List<String>): Flow<Result<List<Place>>> = callbackFlow {
        if (tags.isEmpty()) { getAllPlaces().collect { trySend(it) }; close(); return@callbackFlow }
        listenAllPlacesFiltered { places ->
            places.filter { place ->
                place.tags.any { tag -> tags.any { wanted -> tag.equals(wanted, ignoreCase = true) } }
            }
        }.collect { trySend(it) }
    }

    data class PlacePrices(
        val morning: Double? = null,
        val afternoon: Double? = null,
        val evening: Double? = null
    )

    suspend fun getPlacePricesByTimeOfDay(userId: String?): Result<Map<String, PlacePrices>> {
        return try {
            val allPhotosSnap = photosCollection.get().await()
            val photos = allPhotosSnap.documents.mapNotNull { it.toObject<PhotoDocument>() }
            val accessiblePhotos = photos.filter { photo ->
                when (photo.visibility) {
                    "PUBLIC" -> true
                    "PRIVATE" -> userId != null && photo.authorId == userId
                    "GROUP" -> userId != null && photo.authorId == userId
                    else -> true
                }
            }.filter { it.price != null && it.price!! > 0 && it.timeOfDay != null }

            val priceMap = mutableMapOf<String, PlacePrices>()
            accessiblePhotos.groupBy { it.placeId }.forEach { (placeId, list) ->
                priceMap[placeId] = PlacePrices(
                    morning = list.filter { it.timeOfDay == "MORNING" }.mapNotNull { it.price }.takeIf { it.isNotEmpty() }?.average(),
                    afternoon = list.filter { it.timeOfDay == "AFTERNOON" }.mapNotNull { it.price }.takeIf { it.isNotEmpty() }?.average(),
                    evening = list.filter { it.timeOfDay == "EVENING" }.mapNotNull { it.price }.takeIf { it.isNotEmpty() }?.average()
                )
            }
            Result.success(priceMap)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAllUniqueTags(): Result<List<String>> {
        return try {
            val snapshot = placesCollection.get().await()
            val allTags = mutableSetOf<String>()
            snapshot.documents.forEach { doc -> doc.toObject<PlaceDocument>()?.tags?.forEach { allTags.add(it) } }
            Result.success(allTags.sorted())
        } catch (e: Exception) { Result.failure(e) }
    }

    fun searchPlaces(query: String): Flow<Result<List<Place>>> = callbackFlow {
        val listener = placesCollection.addSnapshotListener { snapshot, error ->
            if (error != null) { trySend(Result.failure(error)); return@addSnapshotListener }
            val allPlaces = snapshot?.documents?.mapNotNull { doc ->
                doc.toObject<PlaceDocument>()?.let { documentToPlace(it) }
            } ?: emptyList()
            if (query.isBlank()) { trySend(Result.success(allPlaces)); return@addSnapshotListener }
            val terms = query.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (terms.isEmpty()) { trySend(Result.success(allPlaces)); return@addSnapshotListener }
            val filtered = allPlaces.filter { place ->
                terms.all { term ->
                    place.name.lowercase().contains(term) ||
                    place.authorName.lowercase().contains(term) ||
                    place.address?.lowercase()?.contains(term) == true ||
                    place.city?.lowercase()?.contains(term) == true ||
                    place.description.lowercase().contains(term) ||
                    place.tags.any { it.lowercase().contains(term) }
                }
            }
            trySend(Result.success(filtered))
        }
        awaitClose { listener.remove() }
    }

    fun getPlacesByAuthor(authorId: String): Flow<Result<List<Place>>> = callbackFlow {
        listenAllPlacesFiltered { places ->
            places.filter { it.authorId == authorId }
        }.collect { trySend(it) }
    }

    suspend fun getPlacesByIds(ids: List<String>): Result<List<Place>> {
        return try {
            if (ids.isEmpty()) return Result.success(emptyList())
            val places = mutableListOf<Place>()
            for (id in ids) getPlaceById(id).getOrNull()?.let { places.add(it) }
            Result.success(places)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addPlace(place: Place): Result<Unit> {
        return try { placesCollection.document(place.id).set(placeToDocument(place)).await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updatePlace(place: Place): Result<Unit> {
        return try {
            placesCollection.document(place.id).set(placeToDocument(place).copy(updatedAt = System.currentTimeMillis())).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deletePlace(placeId: String): Result<Unit> {
        return try { placesCollection.document(placeId).delete().await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun likePlace(placeId: String, userId: String?): Result<Unit> {
        return try {
            val batch = db.batch()
            batch.update(placesCollection.document(placeId), "likes", com.google.firebase.firestore.FieldValue.increment(1))
            if (userId != null) {
                val likeId = "${placeId}_$userId"
                batch.set(placeLikesCollection.document(likeId), PlaceLikeDocument(id = likeId, placeId = placeId, userId = userId, createdAt = System.currentTimeMillis()))
            }
            batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unlikePlace(placeId: String, userId: String?): Result<Unit> {
        return try {
            val batch = db.batch()
            batch.update(placesCollection.document(placeId), "likes", com.google.firebase.firestore.FieldValue.increment(-1))
            if (userId != null) {
                batch.delete(placeLikesCollection.document("${placeId}_$userId"))
            }
            batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun isPlaceLiked(placeId: String, userId: String): Boolean {
        return try { placeLikesCollection.document("${placeId}_$userId").get().await().exists() }
        catch (e: Exception) { false }
    }

    suspend fun getPlaceLikeCount(placeId: String): Int {
        return try {
            placeLikesCollection.get().await().documents.count {
                it.toObject<PlaceLikeDocument>()?.placeId == placeId
            }
        }
        catch (e: Exception) { 0 }
    }

    suspend fun getPlacesNearby(lat: Double, lng: Double, radiusKm: Double): Result<List<Place>> {
        return try {
            val snapshot = placesCollection.get().await()
            val nearbyPlaces = snapshot.documents.mapNotNull { doc ->
                doc.toObject<PlaceDocument>()?.let { documentToPlace(it) }
            }.filter { calculateDistance(lat, lng, it.latitude, it.longitude) <= radiusKm }
             .sortedBy { calculateDistance(lat, lng, it.latitude, it.longitude) }
            Result.success(nearbyPlaces)
        } catch (e: Exception) { Result.failure(e) }
    }


    fun getAllPhotos(): Flow<Result<List<Photo>>> = callbackFlow {
        listenPhotosQuery(
            photosCollection
                .whereEqualTo("visibility", PhotoVisibility.PUBLIC.name)
                .whereIn("moderationStatus", listOf(PhotoModerationStatus.VISIBLE.name, PhotoModerationStatus.PENDING.name))
        ).collect { trySend(it) }
    }

    private suspend fun loadUserAvatarById(userIds: Set<String>): Map<String, Pair<String, String?>> {
        if (userIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Pair<String, String?>>()
        val ids = userIds.filter { it.isNotBlank() }.distinct()
        for (chunk in ids.chunked(10)) {
            val snap = usersCollection
                .whereIn(FieldPath.documentId(), chunk)
                .get()
                .await()
            for (doc in snap.documents) {
                val userDoc = doc.toObject<UserDocument>() ?: continue
                out[doc.id] = userDoc.username to userDoc.avatar
            }
        }
        return out
    }

    fun getPublicPhotos(): Flow<Result<List<Photo>>> = callbackFlow {
        listenPhotosQuery(
            photosCollection
                .whereEqualTo("visibility", PhotoVisibility.PUBLIC.name)
                .whereIn("moderationStatus", listOf(PhotoModerationStatus.VISIBLE.name, PhotoModerationStatus.PENDING.name))
        ).collect { trySend(it) }
    }

    fun getVisiblePhotos(userId: String?, userGroups: List<String>): Flow<Result<List<Photo>>> {
        if (userId.isNullOrBlank()) return getPublicPhotos()

        val publicFlow = getPublicPhotos()
        val privateFlow = listenPhotosQuery(
            photosCollection
                .whereEqualTo("visibility", PhotoVisibility.PRIVATE.name)
                .whereEqualTo("authorId", userId)
        )
        val groupIds = userGroups.filter { it.isNotBlank() }.distinct().take(10)
        val groupFlow: Flow<Result<List<Photo>>> =
            if (groupIds.isEmpty()) flow { emit(Result.success(emptyList())) }
            else listenPhotosQuery(
                photosCollection
                    .whereEqualTo("visibility", PhotoVisibility.GROUP.name)
                    .whereIn("groupId", groupIds)
            )

        return combine(publicFlow, privateFlow, groupFlow) { pubResult, privResult, grpResult ->
            val pub = pubResult.getOrNull().orEmpty()
            val priv = privResult.getOrNull().orEmpty()
            val grp = grpResult.getOrNull().orEmpty()

            val combined = (pub + priv + grp)
                .distinctBy { it.id }
                .sortedByCreatedAtDesc()

            Result.success(combined)
        }
    }

    suspend fun getVisiblePhotosForPlace(placeId: String, userId: String?, userGroups: List<String>): Result<List<Photo>> {
        return try {
            getVisiblePhotosByPlace(placeId, userId, userGroups).first()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun recalculatePlacePriceForUser(placeId: String, userId: String?, userGroups: List<String>): Result<Double?> {
        return try {
            val visible = getVisiblePhotosByPlace(placeId, userId, userGroups)
                .first()
                .getOrNull()
                .orEmpty()
            val prices = visible.mapNotNull { it.price }
            Result.success(if (prices.isEmpty()) null else prices.average())
        } catch (e: Exception) { Result.failure(e) }
    }

    fun searchPhotos(
        query: String,
        viewerUserId: String? = null,
        viewerGroupIds: List<String> = emptyList()
    ): Flow<Result<List<Photo>>> = callbackFlow {
        val listener = photosCollection
            .whereEqualTo("visibility", PhotoVisibility.PUBLIC.name)
            .whereIn("moderationStatus", listOf(PhotoModerationStatus.VISIBLE.name, PhotoModerationStatus.PENDING.name))
            .addSnapshotListener { snapshot, error ->
                if (error != null) { trySend(Result.failure(error)); return@addSnapshotListener }
                val allPhotos = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject<PhotoDocument>()?.let { documentToPhoto(it) }
                } ?: emptyList()
                launch {
                    val photos = try { enrichPhotosWithLiveAuthors(allPhotos) } catch (_: Exception) { allPhotos }

                    if (query.isBlank()) { trySend(Result.success(photos)); return@launch }
                    val terms = query.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    if (terms.isEmpty()) { trySend(Result.success(photos)); return@launch }
                    val filtered = photos.filter { photo ->
                        terms.all { term ->
                            photo.placeName.lowercase().contains(term) ||
                                photo.authorName.lowercase().contains(term) ||
                                photo.description?.lowercase()?.contains(term) == true ||
                                photo.address?.lowercase()?.contains(term) == true ||
                                photo.tags.any { it.lowercase().contains(term) }
                        }
                    }
                    trySend(Result.success(filtered))
                }
            }
        awaitClose { listener.remove() }
    }

    private fun isPhotoVisibleToViewer(photo: Photo, viewerUserId: String?, viewerGroupIds: List<String>): Boolean {
        if (!photo.isModerationVisibleToPublic() && viewerUserId != photo.authorId) return false
        return when (photo.visibility) {
            PhotoVisibility.PUBLIC -> true
            PhotoVisibility.PRIVATE -> viewerUserId != null && photo.authorId == viewerUserId
            PhotoVisibility.GROUP -> {
                if (viewerUserId == null) return false
                val gid = photo.groupId
                gid == null || viewerGroupIds.contains(gid) || photo.authorId == viewerUserId
            }
        }
    }


    fun getPhotosByCategory(category: PhotoCategory): Flow<Result<List<Photo>>> = callbackFlow {
        getPublicPhotos().collect { result ->
            trySend(result.map { list ->
                list.filter { it.matchesCategoryFilter(category) }
            })
        }
    }

    fun getPhotosByTags(tags: List<String>): Flow<Result<List<Photo>>> = callbackFlow {
        if (tags.isEmpty()) { getAllPhotos().collect { trySend(it) }; close(); return@callbackFlow }
        getPublicPhotos().collect { result ->
            trySend(result.map { list ->
                list.filter { photo ->
                    photo.tags.any { tag -> tags.any { wanted -> tag.equals(wanted, ignoreCase = true) } }
                }
            })
        }
    }

    suspend fun getAllPhotoTags(): Result<List<String>> {
        return try {
            val snapshot = photosCollection.get().await()
            val allTags = mutableSetOf<String>()
            snapshot.documents.forEach { doc -> doc.toObject<PhotoDocument>()?.tags?.forEach { allTags.add(it) } }
            Result.success(allTags.sorted())
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getPhotosByAuthor(authorId: String): Flow<Result<List<Photo>>> = callbackFlow {
        listenPhotosQuery(
            photosCollection.whereEqualTo("authorId", authorId)
        ).collect { trySend(it) }
    }

    fun getPrivatePhotosByAuthor(authorId: String): Flow<Result<List<Photo>>> = callbackFlow {
        listenPhotosQuery(
            photosCollection
                .whereEqualTo("authorId", authorId)
                .whereEqualTo("visibility", PhotoVisibility.PRIVATE.name)
        ).collect { trySend(it) }
    }

    fun getPhotosByGroup(groupId: String): Flow<Result<List<Photo>>> = callbackFlow {
        listenPhotosQuery(
            photosCollection
                .whereEqualTo("visibility", PhotoVisibility.GROUP.name)
                .whereEqualTo("groupId", groupId)
                .whereIn("moderationStatus", listOf(PhotoModerationStatus.VISIBLE.name, PhotoModerationStatus.PENDING.name))
        ).collect { trySend(it) }
    }

    suspend fun getGroupPhotoCount(groupId: String): Result<Int> {
        return try {
            val snapshot = photosCollection
                .whereEqualTo("visibility", PhotoVisibility.GROUP.name)
                .whereEqualTo("groupId", groupId)
                .whereIn("moderationStatus", listOf(PhotoModerationStatus.VISIBLE.name, PhotoModerationStatus.PENDING.name))
                .get(Source.SERVER)
                .await()
            Result.success(snapshot.size())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setGroupPhotoCount(groupId: String, photoCount: Int): Result<Unit> {
        return try {
            groupsCollection.document(groupId)
                .update("photoCount", photoCount.coerceAtLeast(0))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getPhotosByPlace(placeId: String): Flow<Result<List<Photo>>> = callbackFlow {
        listenPhotosQuery(
            photosCollection
                .whereEqualTo("placeId", placeId)
                .whereEqualTo("visibility", PhotoVisibility.PUBLIC.name)
                .whereIn("moderationStatus", listOf(PhotoModerationStatus.VISIBLE.name, PhotoModerationStatus.PENDING.name))
        ).collect { trySend(it) }
    }

    fun getVisiblePhotosByPlace(
        placeId: String,
        userId: String?,
        userGroups: List<String>
    ): Flow<Result<List<Photo>>> = callbackFlow {
        if (userId.isNullOrBlank()) {
            getPhotosByPlace(placeId).collect { trySend(it) }
            close()
            return@callbackFlow
        }

        val publicFlow = getPhotosByPlace(placeId)
        val privateFlow = listenPhotosQuery(
            photosCollection
                .whereEqualTo("placeId", placeId)
                .whereEqualTo("visibility", PhotoVisibility.PRIVATE.name)
                .whereEqualTo("authorId", userId)
        )
        val groupIds = userGroups.filter { it.isNotBlank() }.distinct().take(10)
        val groupFlow =
            if (groupIds.isEmpty()) flow { emit(Result.success(emptyList())) }
            else listenPhotosQuery(
                photosCollection
                    .whereEqualTo("placeId", placeId)
                    .whereEqualTo("visibility", PhotoVisibility.GROUP.name)
                    .whereIn("groupId", groupIds)
            )

        launch {
            publicFlow.collect { pubResult ->
                val pub = pubResult.getOrNull().orEmpty()
                val priv = privateFlow.first().getOrNull().orEmpty()
                val grp = groupFlow.first().getOrNull().orEmpty()
                trySend(Result.success((pub + priv + grp).distinctBy { it.id }.sortedByCreatedAtDesc()))
            }
        }
        awaitClose { }
    }

    suspend fun updatePlaceImage(placeId: String, imageUrl: String): Result<Unit> {
        return try {
            placesCollection.document(placeId).update(mapOf(
                "imageUrl" to imageUrl, "updatedAt" to System.currentTimeMillis()
            )).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getPhotosNearby(lat: Double, lng: Double, radiusKm: Double): Result<List<Photo>> {
        return try {
            val snapshot = photosCollection.get().await()
            val nearbyPhotos = snapshot.documents.mapNotNull { doc ->
                doc.toObject<PhotoDocument>()?.let { documentToPhoto(it) }
            }.filter { calculateDistance(lat, lng, it.latitude, it.longitude) <= radiusKm }
             .sortedBy { calculateDistance(lat, lng, it.latitude, it.longitude) }
            Result.success(nearbyPhotos)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getPhotosByDateRange(startDate: Long, endDate: Long): Flow<Result<List<Photo>>> = callbackFlow {
        getPublicPhotos().collect { result ->
            trySend(
                result.map { list ->
                    list.filter { it.createdAt.time in startDate..endDate }
                }
            )
        }
    }

    fun getPhotosBySimilarity(
        photoId: String,
        viewerUserId: String? = null,
        viewerGroupIds: List<String> = emptyList()
    ): Flow<Result<List<Photo>>> = flow {
        try {
            val ref = getPhotoById(photoId).getOrNull()
            if (ref == null) { emit(Result.success(emptyList())); return@flow }
            if (!isPhotoVisibleToViewer(ref, viewerUserId, viewerGroupIds)) {
                emit(Result.success(emptyList()))
                return@flow
            }
            val snapshot = photosCollection.get().await()
            val candidates = snapshot.documents.mapNotNull { doc ->
                val obj = doc.toObject<PhotoDocument>() ?: return@mapNotNull null
                val merged = if (obj.id.isBlank()) obj.copy(id = doc.id) else obj
                val p = documentToPhoto(merged)
                if (p.id == photoId) null else p
            }
            val ranked = candidates
                .filter { isPhotoVisibleToViewer(it, viewerUserId, viewerGroupIds) }
                .map { p -> p to PhotoSimilarity.score(ref, p) }
                .filter { it.second >= 0.06 }
                .sortedByDescending { it.second }
                .take(25).map { it.first }
            emit(Result.success(ranked))
        } catch (e: Exception) { emit(Result.failure(e)) }
    }

    suspend fun likePhoto(photoId: String, userId: String?): Result<Unit> {
        return try {
            val batch = db.batch()
            if (userId != null) {
                val likeId = "${photoId}_$userId"
                batch.set(likesCollection.document(likeId), LikeDocument(id = likeId, photoId = photoId, userId = userId, createdAt = System.currentTimeMillis()))
            }
            batch.update(photosCollection.document(photoId), "likes", com.google.firebase.firestore.FieldValue.increment(1))
            batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unlikePhoto(photoId: String, userId: String?): Result<Unit> {
        return try {
            val batch = db.batch()
            if (userId != null) batch.delete(likesCollection.document("${photoId}_$userId"))
            batch.update(photosCollection.document(photoId), "likes", com.google.firebase.firestore.FieldValue.increment(-1))
            batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun likePath(pathId: String, userId: String?): Result<Unit> {
        return try {
            val batch = db.batch()
            val pathRef = savedPathsCollection.document(pathId)
            if (pathRef.get().await().exists()) {
                batch.update(pathRef, "likes", com.google.firebase.firestore.FieldValue.increment(1))
            }
            if (userId != null) {
                val likeId = "${pathId}_$userId"
                batch.set(pathLikesCollection.document(likeId), PathLikeDocument(id = likeId, pathId = pathId, userId = userId, createdAt = System.currentTimeMillis()))
            }
            batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unlikePath(pathId: String, userId: String?): Result<Unit> {
        return try {
            val batch = db.batch()
            val pathRef = savedPathsCollection.document(pathId)
            if (pathRef.get().await().exists()) {
                batch.update(pathRef, "likes", com.google.firebase.firestore.FieldValue.increment(-1))
            }
            if (userId != null) {
                batch.delete(pathLikesCollection.document("${pathId}_$userId"))
            }
            batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun isPathLiked(pathId: String, userId: String): Boolean {
        return try { pathLikesCollection.document("${pathId}_$userId").get().await().exists() }
        catch (e: Exception) { false }
    }

    suspend fun getLikedPathsByUser(userId: String, limit: Int = 100): Result<List<TravelPath>> {
        return try {
            val likesSnap = pathLikesCollection.get().await()
            val likedPathIds = likesSnap.documents
                .mapNotNull { it.toObject<PathLikeDocument>() }
                .filter { it.userId == userId }
                .sortedByDescending { it.createdAt }
                .map { it.pathId }
                .distinct()
                .take(limit.coerceIn(1, 250))

            if (likedPathIds.isEmpty()) return Result.success(emptyList())

            val out = mutableListOf<TravelPath>()
            for (pathId in likedPathIds) {
                val doc = savedPathsCollection.document(pathId).get().await()
                val pathDoc = doc.toObject<SavedPathDocument>() ?: continue
                out.add(documentToPath(pathDoc))
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isPhotoLiked(photoId: String, userId: String): Boolean {
        return try { likesCollection.document("${photoId}_$userId").get().await().exists() }
        catch (e: Exception) { false }
    }

    suspend fun getPhotoLikeCount(photoId: String): Int {
        return try {
            likesCollection.get().await().documents.count {
                it.toObject<LikeDocument>()?.photoId == photoId
            }
        }
        catch (e: Exception) { 0 }
    }

    suspend fun reportPhoto(photoId: String, userId: String, reason: String): Result<Unit> {
        return try {
            val reportId = "${photoId}_${userId}_${System.currentTimeMillis()}"
            reportsCollection.document(reportId).set(
                ReportDocument(
                    id = reportId,
                    targetType = "PHOTO",
                    targetId = photoId,
                    photoId = photoId,
                    userId = userId,
                    reason = reason,
                    status = "OPEN",
                    createdAt = System.currentTimeMillis()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun listOpenReports(): Result<List<ReportDocument>> {
        return try {
            val snap = reportsCollection.limit(100).get().await()
            val list = snap.toObjects(ReportDocument::class.java)
                .map { normalizeReport(it) }
                .filter { it.status == "OPEN" || it.status.isBlank() }
                .sortedByDescending { it.createdAt }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateReportStatus(reportId: String, status: String): Result<Unit> {
        return try {
            reportsCollection.document(reportId).update("status", status).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updatePhotoModeration(photoId: String, status: PhotoModerationStatus): Result<Unit> {
        return try {
            photosCollection.document(photoId).update("moderationStatus", status.name).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addPhoto(photo: Photo): Result<Unit> {
        return try { photosCollection.document(photo.id).set(photoToDocument(photo)).await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updatePhoto(photo: Photo): Result<Unit> {
        return try { photosCollection.document(photo.id).set(photoToDocument(photo)).await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deletePhoto(photoId: String): Result<Unit> {
        return try { photosCollection.document(photoId).delete().await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun publishPhotoAtomic(photo: Photo, userPrice: Double?, placeId: String): Result<Unit> {
        return try {
            val batch = db.batch()
            batch.set(photosCollection.document(photo.id), photoToDocument(photo))
            if (placeId.isNotBlank()) {
                val placeSnap = placesCollection.document(placeId).get().await()
                val placeDoc = placeSnap.toObject<PlaceDocument>()

                if (placeDoc != null) {
                    if (userPrice != null) {
                        val currentTotal = (placeDoc.price ?: 0.0) * placeDoc.priceCount
                        val newCount = placeDoc.priceCount + 1
                        val newAverage = (currentTotal + userPrice) / newCount
                        batch.update(placesCollection.document(placeId), mapOf(
                            "price" to newAverage,
                            "priceCount" to newCount,
                            "updatedAt" to System.currentTimeMillis()
                        ))
                    }

                    if (placeDoc.imageUrl.isBlank()) {
                        batch.update(placesCollection.document(placeId), mapOf(
                            "imageUrl" to photo.url,
                            "updatedAt" to System.currentTimeMillis()
                        ))
                    }
                }
            }

            if (photo.visibility == PhotoVisibility.GROUP && !photo.groupId.isNullOrBlank()) {
                batch.update(
                    groupsCollection.document(photo.groupId),
                    mapOf("photoCount" to com.google.firebase.firestore.FieldValue.increment(1))
                )
            }
            batch.commit().await(); Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getPhotoById(id: String): Result<Photo?> {
        return try {
            val doc = photosCollection.document(id).get().await()
            Result.success(doc.toObject<PhotoDocument>()?.let { documentToPhoto(it) })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getCommentsByPhoto(photoId: String): Result<List<Comment>> {
        return try {
            val snapshot = commentsCollection.get().await()
            val commentsRaw = snapshot.documents.mapNotNull { doc ->
                doc.toObject<CommentDocument>()?.let { c ->
                    Comment(id = c.id, photoId = c.photoId, authorId = c.authorId,
                        authorName = c.authorName, authorAvatar = c.authorAvatar,
                        content = c.content, createdAt = Date(c.createdAt))
                }
            }.filter { it.photoId == photoId }.sortedBy { it.createdAt }

            val avatars = loadUserAvatarById(commentsRaw.map { it.authorId }.toSet())
            val comments = commentsRaw.map { c ->
                val live = avatars[c.authorId]
                if (live != null) c.copy(authorName = live.first, authorAvatar = live.second) else c
            }
            Result.success(comments)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addComment(comment: Comment): Result<Unit> {
        return try {
            commentsCollection.document(comment.id).set(CommentDocument(
                id = comment.id, photoId = comment.photoId, authorId = comment.authorId,
                authorName = comment.authorName, authorAvatar = comment.authorAvatar,
                content = comment.content, createdAt = comment.createdAt.time
            )).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getPlaceComments(placeId: String): Result<List<Comment>> {
        return try {
            val snapshot = placeCommentsCollection.get().await()
            val commentsRaw = snapshot.documents.mapNotNull { doc ->
                doc.toObject<PlaceCommentDocument>()?.let { c ->
                    Comment(id = c.id, photoId = c.placeId, authorId = c.authorId,
                        authorName = c.authorName, authorAvatar = c.authorAvatar,
                        content = c.content, createdAt = Date(c.createdAt))
                }
            }.filter { it.photoId == placeId }.sortedBy { it.createdAt }
            val avatars = loadUserAvatarById(commentsRaw.map { it.authorId }.toSet())
            val comments = commentsRaw.map { c ->
                val live = avatars[c.authorId]
                if (live != null) c.copy(authorName = live.first, authorAvatar = live.second) else c
            }
            Result.success(comments)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addPlaceComment(placeId: String, comment: Comment): Result<Unit> {
        return try {
            placeCommentsCollection.document(comment.id).set(PlaceCommentDocument(
                id = comment.id, placeId = placeId, authorId = comment.authorId,
                authorName = comment.authorName, authorAvatar = comment.authorAvatar,
                content = comment.content, createdAt = comment.createdAt.time
            )).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }


    suspend fun getUserById(id: String): Result<User?> {
        return try {
            val ref = usersCollection.document(id)
            val doc = try {
                ref.get(Source.SERVER).await()
            } catch (_: Exception) {
                ref.get().await()
            }
            val userDoc = doc.toObject<UserDocument>()
            val effectiveIsAdmin =
                doc.getBoolean("isAdmin") ?: doc.getBoolean("admin") ?: userDoc?.isAdmin ?: false
            Result.success(userDoc?.let { documentToUser(it, effectiveIsAdmin) })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getUserByEmail(email: String): Result<User?> {
        return try {
            val snapshot = usersCollection.get().await()
            Result.success(
                snapshot.documents
                    .mapNotNull { snap ->
                        val userDoc = snap.toObject<UserDocument>() ?: return@mapNotNull null
                        val effectiveIsAdmin =
                            snap.getBoolean("isAdmin") ?: snap.getBoolean("admin") ?: userDoc.isAdmin
                        documentToUser(userDoc, effectiveIsAdmin)
                    }
                    .firstOrNull { it.email.equals(email, ignoreCase = true) }
            )
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addUser(user: User): Result<Unit> {
        return try {
            usersCollection.document(user.id).set(userToWriteMap(user), SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun updateUser(user: User): Result<Unit> {
        return try {
            usersCollection.document(user.id).set(userToWriteMap(user), SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }


    suspend fun updateFcmToken(userId: String, token: String): Result<Unit> {
        return try {
            usersCollection.document(userId)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }


    suspend fun updateUserAvatar(userId: String, avatarUrl: String): Result<Unit> {
        return try {
            usersCollection.document(userId)
                .set(mapOf("avatar" to avatarUrl), SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun syncNotifyLastLocation(userId: String, lat: Double, lng: Double): Result<Unit> {
        return try {
            usersCollection.document(userId).set(
                mapOf(
                    "notifyLastLat" to lat,
                    "notifyLastLng" to lng,
                    "notifyLastLocationAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun savePath(path: TravelPath): Result<Unit> {
        return try { savedPathsCollection.document(path.id).set(pathToDocument(path)).await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteSavedPath(pathId: String): Result<Unit> {
        return try { savedPathsCollection.document(pathId).delete().await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    fun getPathsByUser(userId: String): Flow<Result<List<TravelPath>>> = callbackFlow {
        listenAllSavedPathsFiltered { paths ->
            paths.filter { it.userId == userId }
        }.collect { trySend(it) }
    }


    suspend fun getGroupById(id: String): Result<Group?> {
        return try {
            val doc = groupsCollection.document(id).get().await()
            Result.success(doc.toObject<GroupDocument>()?.let { documentToGroup(it) })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getGroupByName(name: String): Group? {
        return try {
            val snapshot = groupsCollection.get().await()
            snapshot.documents
                .mapNotNull { it.toObject<GroupDocument>() }
                .firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { documentToGroup(it) }
        } catch (e: Exception) { null }
    }

    suspend fun getAllGroups(): Result<List<Group>> {
        return try {
            val snapshot = groupsCollection.get().await()
            Result.success(snapshot.documents.mapNotNull { doc ->
                doc.toObject<GroupDocument>()?.let { documentToGroup(it) }
            })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteGroup(groupId: String): Result<Unit> {
        return try { groupsCollection.document(groupId).delete().await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addGroup(group: Group): Result<Unit> {
        return try { groupsCollection.document(group.id).set(groupToDocument(group)).await(); Result.success(Unit) }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun reportPlace(placeId: String, userId: String, reason: String): Result<Unit> {
        return try {
            val reportId = "${placeId}_${userId}_${System.currentTimeMillis()}"
            reportsCollection.document(reportId).set(
                ReportDocument(
                    id = reportId,
                    targetType = "PLACE",
                    targetId = placeId,
                    placeId = placeId,
                    userId = userId,
                    reason = reason,
                    status = "OPEN",
                    createdAt = System.currentTimeMillis()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }


    suspend fun createNotification(doc: NotificationDocument): Result<Unit> {
        return try {
            val id = doc.id.ifBlank { UUID.randomUUID().toString() }
            notificationsCollection.document(id).set(doc.copy(id = id)).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getNotifications(userId: String): Result<List<NotificationDocument>> {
        return try {
            val snapshot = notificationsCollection.get().await()
            val docs = snapshot.toObjects(NotificationDocument::class.java)
                .filter { it.userId == userId }
                .sortedByDescending { it.createdAt }
            Result.success(docs)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun markNotificationAsRead(notificationId: String): Result<Unit> {
        return try {
            notificationsCollection.document(notificationId).update("isRead", true).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun saveNotificationSettings(userId: String, settings: NotificationSettings): Result<Unit> {
        return try {
            val data = buildMap<String, Any>(16) {
                put("newPhotoFromUser", settings.newPhotoFromUser)
                put("newPhotoInGroup", settings.newPhotoInGroup)
                put("newPhotoNearby", settings.newPhotoNearby)
                put("newPhotoWithTag", settings.newPhotoWithTag)
                put("newPhotoInFollowedPlace", settings.newPhotoInFollowedPlace)
                put("nearbyRadiusKm", settings.nearbyRadiusKm)
                settings.nearbyCenterLat?.let { put("nearbyCenterLat", it) }
                settings.nearbyCenterLng?.let { put("nearbyCenterLng", it) }
                put("followedUsers", settings.followedUsers)
                put("followedTags", settings.followedTags)
                put("followedPlaceIds", settings.followedPlaceIds)
            }
            usersCollection.document(userId).collection("settings").document("notifications").set(data).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getNotificationSettings(userId: String): Result<NotificationSettings> {
        return try {
            val doc = usersCollection.document(userId).collection("settings").document("notifications").get().await()
            if (doc.exists()) {
                val centerLat = doc.getDouble("nearbyCenterLat")
                val centerLng = doc.getDouble("nearbyCenterLng")
                Result.success(NotificationSettings(
                    newPhotoFromUser = doc.getBoolean("newPhotoFromUser") ?: true,
                    newPhotoInGroup = doc.getBoolean("newPhotoInGroup") ?: true,
                    newPhotoNearby = doc.getBoolean("newPhotoNearby") ?: true,
                    newPhotoWithTag = doc.getBoolean("newPhotoWithTag") ?: true,
                    newPhotoInFollowedPlace = doc.getBoolean("newPhotoInFollowedPlace") ?: false,
                    nearbyRadiusKm = (doc.getDouble("nearbyRadiusKm")?.toFloat() ?: 5f),
                    nearbyCenterLat = if (centerLat != null && centerLng != null) centerLat else null,
                    nearbyCenterLng = if (centerLat != null && centerLng != null) centerLng else null,
                    followedUsers = (doc.get("followedUsers") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    followedTags = (doc.get("followedTags") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                    followedPlaceIds = (doc.get("followedPlaceIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                ))
            } else {
                Result.success(NotificationSettings())
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAllUsers(limit: Long = 250): Result<List<User>> {
        return try {
            val snapshot = usersCollection.limit(limit).get().await()
            Result.success(
                snapshot.documents.mapNotNull { snap ->
                    val userDoc = snap.toObject<UserDocument>() ?: return@mapNotNull null
                    val effectiveIsAdmin =
                        snap.getBoolean("isAdmin") ?: snap.getBoolean("admin") ?: userDoc.isAdmin
                    documentToUser(userDoc, effectiveIsAdmin)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getPlacesByDateRange(startDate: Long, endDate: Long): Flow<Result<List<Place>>> = callbackFlow {
        listenAllPlacesFiltered { places ->
            places.filter { it.createdAt.time in startDate..endDate }
        }.collect { trySend(it) }
    }

    fun getRecentPhotos(limit: Int): Flow<Result<List<Photo>>> = callbackFlow {
        listenPhotosQuery(
            photosCollection
                .whereEqualTo("visibility", PhotoVisibility.PUBLIC.name)
                .whereIn("moderationStatus", listOf(PhotoModerationStatus.VISIBLE.name, PhotoModerationStatus.PENDING.name))
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
        ).collect { trySend(it) }
    }


    private fun documentToPlace(doc: PlaceDocument): Place {
        val structured = doc.openingHoursStructuredJson?.trim()?.takeIf { it.isNotEmpty() }?.let { json ->
            try {
                gson.fromJson(json, StructuredOpeningHours::class.java)
            } catch (_: Exception) {
                null
            }
        }
        return Place(
            id = doc.id, name = doc.name, description = doc.description, imageUrl = doc.imageUrl,
            latitude = doc.latitude, longitude = doc.longitude, address = doc.address, city = doc.city,
            type = PlaceType.valueOf(doc.type), authorId = doc.authorId, authorName = doc.authorName,
            authorAvatar = doc.authorAvatar, likes = doc.likes, tags = doc.tags,
            createdAt = Date(doc.createdAt), updatedAt = Date(doc.updatedAt),
            price = doc.price, priceCount = doc.priceCount, openingHours = doc.openingHours,
            structuredOpeningHours = structured,
            videoUrl = doc.videoUrl
        )
    }

    private fun placeToDocument(place: Place): PlaceDocument = PlaceDocument(
        id = place.id, name = place.name, description = place.description, imageUrl = place.imageUrl,
        latitude = place.latitude, longitude = place.longitude, address = place.address, city = place.city,
        type = place.type.name, authorId = place.authorId, authorName = place.authorName,
        authorAvatar = place.authorAvatar, likes = place.likes, tags = place.tags,
        createdAt = place.createdAt.time, updatedAt = place.updatedAt.time,
        price = place.price, priceCount = place.priceCount, openingHours = place.openingHours,
        openingHoursStructuredJson = place.structuredOpeningHours?.let { gson.toJson(it) },
        videoUrl = place.videoUrl
    )

    private fun documentToPhoto(doc: PhotoDocument): Photo = Photo(
        id = doc.id, url = doc.url, thumbnailUrl = doc.thumbnailUrl, placeId = doc.placeId,
        placeName = doc.placeName, latitude = doc.latitude, longitude = doc.longitude,
        address = doc.address, authorId = doc.authorId, authorName = doc.authorName,
        authorAvatar = doc.authorAvatar, description = doc.description, tags = doc.tags,
        category = PhotoCategory.fromString(doc.category),
        placeType = doc.placeType,
        searchCategories = doc.searchCategories,
        moderationStatus = PhotoModerationStatus.fromString(doc.moderationStatus),
        likes = doc.likes,
        visibility = PhotoVisibility.valueOf(doc.visibility), groupId = doc.groupId,
        howToGo = doc.howToGo, takenAt = doc.takenAt?.let { Date(it) },
        createdAt = Date(doc.createdAt), price = doc.price,
        timeOfDay = doc.timeOfDay?.let { com.shimtraveling.data.model.TimeOfDay.fromString(it) },
        audioUrl = doc.audioUrl,
        locationPrecision = com.shimtraveling.data.model.LocationPrecision.fromString(doc.locationPrecision)
    )

    private fun photoToDocument(photo: Photo): PhotoDocument = PhotoDocument(
        id = photo.id, url = photo.url, thumbnailUrl = photo.thumbnailUrl, placeId = photo.placeId,
        placeName = photo.placeName, latitude = photo.latitude, longitude = photo.longitude,
        address = photo.address, authorId = photo.authorId, authorName = photo.authorName,
        authorAvatar = photo.authorAvatar, description = photo.description, tags = photo.tags,
        category = photo.category.name,
        placeType = photo.placeType,
        searchCategories = photo.searchCategories,
        moderationStatus = photo.moderationStatus.name,
        likes = photo.likes, visibility = photo.visibility.name,
        groupId = photo.groupId, howToGo = photo.howToGo, takenAt = photo.takenAt?.time,
        createdAt = photo.createdAt.time,
        publicCreatedAt = if (photo.visibility == PhotoVisibility.PUBLIC) photo.createdAt.time else null,
        price = photo.price,         timeOfDay = photo.timeOfDay?.name,
        audioUrl = photo.audioUrl,
        locationPrecision = photo.locationPrecision.name
    )

    private fun documentToUser(doc: UserDocument, effectiveIsAdmin: Boolean? = null): User = User(
        id = doc.id, username = doc.username, email = doc.email, avatar = doc.avatar, bio = doc.bio,
        favorites = doc.favorites, photoFavorites = doc.photoFavorites, placeFavorites = doc.placeFavorites,
        pathFavorites = doc.pathFavorites, groups = doc.groups, followedTags = doc.followedTags,
        followedUsers = doc.followedUsers, followedPlaces = doc.followedPlaces,
        isAdmin = effectiveIsAdmin ?: doc.isAdmin,
        createdAt = Date(doc.createdAt)
    )

    private fun userToDocument(user: User): UserDocument = UserDocument(
        id = user.id, username = user.username, email = user.email, avatar = user.avatar, bio = user.bio,
        favorites = user.favorites, photoFavorites = user.photoFavorites, placeFavorites = user.placeFavorites,
        pathFavorites = user.pathFavorites, groups = user.groups, followedTags = user.followedTags,
        followedUsers = user.followedUsers, followedPlaces = user.followedPlaces,
        isAdmin = user.isAdmin,
        createdAt = user.createdAt.time
    )


    private fun userToWriteMap(user: User): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "id" to user.id,
            "username" to user.username,
            "email" to user.email,
            "favorites" to user.favorites,
            "photoFavorites" to user.photoFavorites,
            "placeFavorites" to user.placeFavorites,
            "pathFavorites" to user.pathFavorites,
            "groups" to user.groups,
            "followedTags" to user.followedTags,
            "followedUsers" to user.followedUsers,
            "followedPlaces" to user.followedPlaces,
            "notifications" to user.notifications,
            "createdAt" to user.createdAt.time
        )
        user.avatar?.let { map["avatar"] = it }
        user.bio?.let { map["bio"] = it }
        return map
    }

    private fun documentToPath(doc: SavedPathDocument): TravelPath {
        val steps: List<PathStep> = if (doc.stepsJson.isNotBlank()) {
            try {
                val type = object : TypeToken<List<PathStep>>() {}.type
                gson.fromJson<List<PathStep>>(doc.stepsJson, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        } else { emptyList() }
        return TravelPath(
            id = doc.id, name = doc.name, description = doc.description,
            type = PathType.valueOf(doc.type), steps = steps,
            totalDurationMinutes = doc.totalDurationMinutes, totalCost = doc.totalCost,
            hasCompletePricing = doc.hasCompletePricing, totalEffort = EffortLevel.valueOf(doc.totalEffort),
            distanceKm = doc.distanceKm, userId = doc.userId, likes = doc.likes,
            city = doc.city, createdAt = Date(doc.createdAt)
        )
    }

    private fun pathToDocument(path: TravelPath): SavedPathDocument = SavedPathDocument(
        id = path.id, name = path.name, description = path.description, type = path.type.name,
        stepsJson = gson.toJson(path.steps), totalDurationMinutes = path.totalDurationMinutes,
        totalCost = path.totalCost, hasCompletePricing = path.hasCompletePricing,
        totalEffort = path.totalEffort.name, distanceKm = path.distanceKm, userId = path.userId,
        likes = path.likes, city = path.city, createdAt = path.createdAt.time
    )

    private fun documentToGroup(doc: GroupDocument): Group = Group(
        id = doc.id, name = doc.name, description = doc.description, ownerId = doc.ownerId,
        members = doc.members, photos = doc.photos, photoCount = doc.photoCount, createdAt = Date(doc.createdAt)
    )

    private fun groupToDocument(group: Group): GroupDocument = GroupDocument(
        id = group.id, name = group.name, description = group.description, ownerId = group.ownerId,
        members = group.members, photos = group.photos, photoCount = group.photoCount, createdAt = group.createdAt.time
    )

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun normalizeReport(report: ReportDocument): ReportDocument {
        val normalizedType = when {
            report.targetType.equals("PLACE", ignoreCase = true) -> "PLACE"
            report.targetType.equals("PHOTO", ignoreCase = true) -> "PHOTO"
            report.placeId.isNotBlank() -> "PLACE"
            else -> "PHOTO"
        }
        val normalizedTargetId = when {
            report.targetId.isNotBlank() -> report.targetId
            normalizedType == "PLACE" -> report.placeId
            else -> report.photoId
        }
        val normalizedPhotoId = when {
            report.photoId.isNotBlank() -> report.photoId
            normalizedType == "PHOTO" -> normalizedTargetId
            else -> ""
        }
        val normalizedPlaceId = when {
            report.placeId.isNotBlank() -> report.placeId
            normalizedType == "PLACE" -> normalizedTargetId
            else -> ""
        }
        return report.copy(
            targetType = normalizedType,
            targetId = normalizedTargetId,
            photoId = normalizedPhotoId,
            placeId = normalizedPlaceId
        )
    }
}
