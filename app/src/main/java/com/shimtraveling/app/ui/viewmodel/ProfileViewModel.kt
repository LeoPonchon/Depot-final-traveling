package com.shimtraveling.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.User
import com.shimtraveling.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(private val repository: UserRepository) : ViewModel() {

    private val _currentUser = MutableStateFlow<Result<User?>>(Result.success(null))
    val currentUser: StateFlow<Result<User?>> = _currentUser.asStateFlow()

    private val _authResult = MutableStateFlow<Result<User>?>(null)
    val authResult: StateFlow<Result<User>?> = _authResult.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var observingCurrentUser: Boolean = false

    init {
        loadCurrentUser()
    }

    fun loadCurrentUser() {
        if (observingCurrentUser) return
        observingCurrentUser = true
        executeWithLoading(repository.getCurrentUser()) { result ->
            _currentUser.value = result
        }
    }

    fun login(email: String, password: String) {
        executeWithLoading(repository.login(email, password)) { result ->
            _authResult.value = result
            result.onSuccess { user ->
                _currentUser.value = Result.success(user)
            }
        }
    }

    fun register(username: String, email: String, password: String) {
        executeWithLoading(repository.register(username, email, password)) { result ->
            _authResult.value = result
            result.onSuccess { user ->
                _currentUser.value = Result.success(user)
            }
        }
    }

    fun clearAuthResult() {
        _authResult.value = null
    }

    fun logout() {
        executeWithLoading(repository.logout()) {
            _currentUser.value = Result.success(null)
        }
    }

    fun updateProfile(user: User) {
        executeWithLoading(repository.updateProfile(user)) { result ->
            result.onSuccess { updatedUser ->
                _currentUser.value = Result.success(updatedUser)
            }
        }
    }

    private fun <T> executeWithLoading(flow: Flow<T>, onCollect: (T) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            var cleared = false
            try {
                flow.collect { value ->
                    onCollect(value)
                    if (!cleared) {
                        cleared = true
                        _isLoading.value = false
                    }
                }
            } catch (t: Throwable) {
                if (!cleared) _isLoading.value = false
                throw t
            } finally {
                if (!cleared) _isLoading.value = false
            }
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = (application as TravelingApp).userRepository
            if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
                return ProfileViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
