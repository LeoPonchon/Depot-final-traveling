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
import com.shimtraveling.data.repository.PhotoRepository
import com.shimtraveling.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PhotoDetailViewModel(
    application: Application,
    private val photoRepository: PhotoRepository,
    private val userRepository: UserRepository
) : AndroidViewModel(application) {

    private val _photo = MutableStateFlow<Photo?>(null)
    val photo: StateFlow<Photo?> = _photo.asStateFlow()

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _commentError = MutableStateFlow<String?>(null)
    val commentError: StateFlow<String?> = _commentError.asStateFlow()

    private val _reportStatus = MutableStateFlow<Boolean?>(null)
    val reportStatus: StateFlow<Boolean?> = _reportStatus.asStateFlow()

    private val _deleteStatus = MutableStateFlow<Boolean?>(null)
    val deleteStatus: StateFlow<Boolean?> = _deleteStatus.asStateFlow()

    private var currentUserId: String? = null

    fun initialize(photo: Photo) {
        _photo.value = photo
        loadUserAndRefreshLikeStatus(photo)
        loadComments(photo.id)
    }

    private fun loadUserAndRefreshLikeStatus(photo: Photo) {
        viewModelScope.launch {
            userRepository.getCurrentUser().first().onSuccess { user ->
                currentUserId = user?.id
            }
            val likeId = SessionIdentity.getLikeUserId(getApplication())
            val isLiked = photoRepository.isPhotoLiked(photo.id, likeId)
            val likeCount = photoRepository.getPhotoLikeCount(photo.id)
            _photo.value = photo.copy(isLiked = isLiked, likes = likeCount)
        }
    }

    fun loadComments(photoId: String) {
        viewModelScope.launch {
            val result = photoRepository.getCommentsByPhoto(photoId)
            result.onSuccess { _comments.value = it }
        }
    }


    fun toggleLike(): Pair<Boolean, Int>? {
        val p = _photo.value ?: return null
        val likeId = SessionIdentity.getLikeUserId(getApplication())

        val newLiked = !p.isLiked
        val newCount = if (newLiked) p.likes + 1 else (p.likes - 1).coerceAtLeast(0)
        _photo.value = p.copy(isLiked = newLiked, likes = newCount)

        viewModelScope.launch {
            if (newLiked) {
                photoRepository.likePhoto(p.id, likeId)
            } else {
                photoRepository.unlikePhoto(p.id, likeId)
            }
        }
        return Pair(newLiked, newCount)
    }

    fun addComment(content: String) {
        val p = _photo.value ?: return
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
                    val result = photoRepository.addComment(comment)
                    if (result.isSuccess) {
                        loadComments(p.id)
                        _commentError.value = null
                    } else {
                        _commentError.value = "Erreur lors de l'ajout du commentaire"
                    }
                }
            }
            _isLoading.value = false
        }
    }

    fun reportPhoto(reason: String) {
        val p = _photo.value ?: return
        val reporterId = SessionIdentity.getLikeUserId(getApplication())
        viewModelScope.launch {
            val result = photoRepository.reportPhoto(p.id, reporterId, reason)
            _reportStatus.value = result.isSuccess
        }
    }

    fun deletePhoto() {
        val p = _photo.value ?: return
        viewModelScope.launch {
            val result = photoRepository.deletePhoto(p.id)
            _deleteStatus.value = result.isSuccess
        }
    }

    fun clearCommentError() { _commentError.value = null }
    fun clearReportStatus() { _reportStatus.value = null }
    fun clearDeleteStatus() { _deleteStatus.value = null }
    fun getCurrentUserId(): String? = currentUserId

    class Factory(private val application: Application) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as TravelingApp
            if (modelClass.isAssignableFrom(PhotoDetailViewModel::class.java)) {
                return PhotoDetailViewModel(application, app.photoRepository, app.userRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
