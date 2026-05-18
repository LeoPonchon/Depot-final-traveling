package com.shimtraveling.ui.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.SessionIdentity
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.data.model.User
import com.shimtraveling.data.repository.PathRepository
import com.shimtraveling.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PathDetailViewModel(
    application: Application,
    private val pathRepository: PathRepository,
    private val userRepository: UserRepository
) : AndroidViewModel(application) {

    private val _path = MutableStateFlow<TravelPath?>(null)
    val path: StateFlow<TravelPath?> = _path

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setPath(path: TravelPath) {
        _path.value = path
        loadUserAndLikeStatus(path.id)
    }

    private fun loadUserAndLikeStatus(pathId: String) {
        viewModelScope.launch {
            val userResult = userRepository.getCurrentUser().first()
            val currentUser = userResult.getOrNull()
            _user.value = currentUser
            val likeUserId = SessionIdentity.getLikeUserId(getApplication())
            _isLiked.value = pathRepository.isPathLiked(pathId, likeUserId)
            _isSaved.value = currentUser?.pathFavorites?.contains(pathId) == true
        }
    }

    fun toggleSave() {
        val currentPath = _path.value ?: return
        val currentUser = _user.value

        if (currentUser == null) {
            _error.value = "Connectez-vous pour enregistrer un parcours"
            return
        }

        viewModelScope.launch {
            if (_isSaved.value) {
                val deleteResult = pathRepository.deleteSavedPath(currentPath.id)
                val removeResult = userRepository.removePathFavoriteSuspend(currentPath.id)
                if (deleteResult.isSuccess && removeResult.isSuccess) {
                    _isSaved.value = false
                } else {
                    _error.value = "Erreur lors du retrait du parcours"
                }
            } else {
                val pathToSave = currentPath.copy(userId = currentUser.id, isSaved = true)
                val result = pathRepository.savePath(pathToSave)
                if (result.isSuccess) {
                    userRepository.addPathFavoriteSuspend(currentPath.id)
                    _isSaved.value = true
                } else {
                    _error.value = "Erreur lors de l'enregistrement"
                }
            }
        }
    }

    fun toggleLike() {
        val currentPath = _path.value ?: return
        val likeUserId = SessionIdentity.getLikeUserId(getApplication())

        viewModelScope.launch {
            if (_isLiked.value) {
                val result = pathRepository.unlikePath(currentPath.id, likeUserId)
                if (result.isSuccess) {
                    _isLiked.value = false
                    _path.value = currentPath.copy(likes = (currentPath.likes - 1).coerceAtLeast(0), isLiked = false)
                } else {
                    _error.value = "Erreur lors du retrait du like"
                }
            } else {
                val result = pathRepository.likePath(currentPath.id, likeUserId)
                if (result.isSuccess) {
                    _isLiked.value = true
                    _path.value = currentPath.copy(likes = currentPath.likes + 1, isLiked = true)
                } else {
                    _error.value = "Erreur lors du like"
                }
            }
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as TravelingApp
            if (modelClass.isAssignableFrom(PathDetailViewModel::class.java)) {
                return PathDetailViewModel(application, app.pathRepository, app.userRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
