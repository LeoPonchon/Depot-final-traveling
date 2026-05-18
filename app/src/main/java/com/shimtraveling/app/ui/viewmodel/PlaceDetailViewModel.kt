package com.shimtraveling.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.SessionIdentity
import com.shimtraveling.data.model.Comment
import com.shimtraveling.data.model.Photo
import com.shimtraveling.data.model.Place
import com.shimtraveling.data.repository.PlaceRepository
import com.shimtraveling.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlaceDetailViewModel(
    application: Application,
    private val placeRepository: PlaceRepository,
    private val userRepository: UserRepository
) : AndroidViewModel(application) {

    private val _place = MutableStateFlow<Place?>(null)
    val place: StateFlow<Place?> = _place.asStateFlow()

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _commentError = MutableStateFlow<String?>(null)
    val commentError: StateFlow<String?> = _commentError.asStateFlow()

    private val _reportStatus = MutableStateFlow<Boolean?>(null)
    val reportStatus: StateFlow<Boolean?> = _reportStatus.asStateFlow()

    private var currentUserId: String? = null
    private var currentUserGroups: List<String> = emptyList()

    fun initialize(place: Place) {
        _place.value = place
        loadUserThenRefresh(place)
        loadComments(place.id)
    }

    private fun loadUserThenRefresh(place: Place) {
        viewModelScope.launch {
            val result = userRepository.getCurrentUser().first()
            result.onSuccess { user ->
                currentUserId = user?.id
                currentUserGroups = user?.groups ?: emptyList()
            }
            val likeId = SessionIdentity.getLikeUserId(getApplication())
            val isLiked = placeRepository.isPlaceLiked(place.id, likeId)
            val likeCount = placeRepository.getPlaceLikeCount(place.id)
            _place.value = place.copy(isLiked = isLiked, likes = likeCount)
            loadPhotosForPlace(place.id)
            recalculatePrice(place.id)
        }
    }

    private fun loadPhotosForPlace(placeId: String) {
        viewModelScope.launch {
            val result = TravelingApp.getInstance().firestoreRepository
                .getVisiblePhotosForPlace(placeId, currentUserId, currentUserGroups)
            result.onSuccess { _photos.value = it }
        }
    }

    private fun recalculatePrice(placeId: String) {
        viewModelScope.launch {
            val result = TravelingApp.getInstance().firestoreRepository
                .recalculatePlacePriceForUser(placeId, currentUserId, currentUserGroups)
            result.onSuccess { avg ->
                _place.value = _place.value?.copy(price = avg)
            }
        }
    }

    fun loadComments(placeId: String) {
        viewModelScope.launch {
            val result = placeRepository.getCommentsByPlace(placeId)
            result.onSuccess { _comments.value = it }
        }
    }


    fun toggleLike(): Pair<Boolean, Int>? {
        val p = _place.value ?: return null
        val likeId = SessionIdentity.getLikeUserId(getApplication())

        val newLiked = !p.isLiked
        val newCount = if (newLiked) p.likes + 1 else (p.likes - 1).coerceAtLeast(0)
        _place.value = p.copy(isLiked = newLiked, likes = newCount)

        viewModelScope.launch {
            if (newLiked) {
                placeRepository.likePlace(p.id, likeId)
                currentUserId?.let { userRepository.addPlaceFavoriteSuspend(p.id) }
            } else {
                placeRepository.unlikePlace(p.id, likeId)
                currentUserId?.let { userRepository.removePlaceFavoriteSuspend(p.id) }
            }
        }
        return Pair(newLiked, newCount)
    }

    fun togglePhotoLike(photo: Photo) {
        val likeId = SessionIdentity.getLikeUserId(getApplication())
        val newLiked = !photo.isLiked
        val updated = _photos.value.map {
            if (it.id == photo.id) it.copy(
                isLiked = newLiked,
                likes = if (newLiked) it.likes + 1 else (it.likes - 1).coerceAtLeast(0)
            ) else it
        }
        _photos.value = updated

        viewModelScope.launch {
            if (newLiked) {
                TravelingApp.getInstance().photoRepository.likePhoto(photo.id, likeId)
                currentUserId?.let { TravelingApp.getInstance().userRepository.addPhotoFavoriteSuspend(photo.id) }
            } else {
                TravelingApp.getInstance().photoRepository.unlikePhoto(photo.id, likeId)
                currentUserId?.let { TravelingApp.getInstance().userRepository.removePhotoFavoriteSuspend(photo.id) }
            }
        }
    }

    fun addComment(content: String) {
        val p = _place.value ?: return
        val userId = currentUserId ?: run {
            _commentError.value = "Connectez-vous pour commenter"
            return
        }
        if (content.isBlank()) {
            _commentError.value = "Le commentaire ne peut pas être vide"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val userResult = userRepository.getCurrentUser().first()
            userResult.onSuccess { user ->
                if (user != null) {
                    val liveUser = TravelingApp.getInstance().firestoreRepository.getUserById(user.id).getOrNull()
                        ?: user
                    val comment = Comment(
                        id = java.util.UUID.randomUUID().toString(),
                        photoId = p.id,
                        authorId = userId,
                        authorName = liveUser.username,
                        authorAvatar = liveUser.avatar,
                        content = content,
                        createdAt = java.util.Date()
                    )
                    val result = placeRepository.addComment(p.id, comment)
                    if (result.isSuccess) {
                        loadComments(p.id)
                        _commentError.value = null
                    } else {
                        _commentError.value = "Erreur lors de l'ajout"
                    }
                }
            }
            _isLoading.value = false
        }
    }

    fun reportPlace(reason: String) {
        val p = _place.value ?: return
        val reporterId = SessionIdentity.getLikeUserId(getApplication())
        viewModelScope.launch {
            val result = placeRepository.reportPlace(p.id, reporterId, reason)
            _reportStatus.value = result.isSuccess
        }
    }

    fun clearCommentError() { _commentError.value = null }
    fun clearReportStatus() { _reportStatus.value = null }
    fun getCurrentUserId(): String? = currentUserId

    class Factory(private val application: Application) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as TravelingApp
            if (modelClass.isAssignableFrom(PlaceDetailViewModel::class.java)) {
                return PlaceDetailViewModel(application, app.placeRepository, app.userRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
