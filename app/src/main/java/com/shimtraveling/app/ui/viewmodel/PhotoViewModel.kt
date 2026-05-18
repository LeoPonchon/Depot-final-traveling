package com.shimtraveling.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.NetworkConnectivity
import com.shimtraveling.core.SessionIdentity
import com.shimtraveling.data.model.Photo
import com.shimtraveling.data.model.PhotoCategory
import com.shimtraveling.data.model.User
import com.shimtraveling.data.repository.PhotoRepository
import com.shimtraveling.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

class PhotoViewModel(
    private val application: Application,
    private val photoRepository: PhotoRepository,
    private val userRepository: UserRepository,
    private val autoLoad: Boolean = true
) : ViewModel() {

    private enum class Mode { FEED, SIMILAR }
    private var mode: Mode = Mode.FEED

    private val _photos = MutableStateFlow<Result<List<Photo>>>(Result.success(emptyList()))
    val photos: StateFlow<Result<List<Photo>>> = _photos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()

    private val _selectedCategory = MutableStateFlow<PhotoCategory?>(null)
    val selectedCategory: StateFlow<PhotoCategory?> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _filterStartDate = MutableStateFlow<Date?>(null)
    private val _filterEndDate = MutableStateFlow<Date?>(null)
    private val _filterLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    private val _filterRadiusKm = MutableStateFlow(10.0)
    private val _filterAuthorId = MutableStateFlow<String?>(null)

    private val _hasNearbyFilter = MutableStateFlow(false)
    val hasNearbyFilter: StateFlow<Boolean> = _hasNearbyFilter.asStateFlow()

    private var currentUser: User? = null
    private var photosJob: kotlinx.coroutines.Job? = null

    init {
        checkLoginStatus()
        if (autoLoad) {
            loadPhotos()
            loadAvailableTags()
        }
    }

    private fun checkLoginStatus() {
        viewModelScope.launch {
            userRepository.getCurrentUser().collect { result ->
                result.onSuccess { user ->
                    currentUser = user
                    _isLoggedIn.value = user != null
                    if (autoLoad && mode == Mode.FEED) {
                        loadPhotos()
                    }
                }
            }
        }
    }

    fun loadPhotos() {
        photosJob?.cancel()
        mode = Mode.FEED
        photosJob = viewModelScope.launch {
            _isLoading.value = true
            val selectedTags = _selectedTags.value
            val selectedCategory = _selectedCategory.value
            val query = _searchQuery.value
            val startDate = _filterStartDate.value
            val endDate = _filterEndDate.value
            val location = _filterLocation.value
            val radiusKm = _filterRadiusKm.value
            val authorId = _filterAuthorId.value

            when {
                location != null -> {
                    val lat = location.first
                    val lng = location.second
                    collectPhotos(photoRepository.getPhotosNearby(lat, lng, radiusKm), enrichLikes = true)
                }
                startDate != null && endDate != null -> {
                    val logged = currentUser
                    if (logged != null) {
                        collectPhotos(
                            photoRepository.getVisiblePhotosByDateRange(startDate, endDate, logged.id, logged.groups),
                            enrichLikes = true
                        )
                    } else {
                        collectPhotos(photoRepository.getPhotosByDateRange(startDate, endDate), enrichLikes = true)
                    }
                }
                query.isNotBlank() -> {
                    val logged = currentUser
                    if (logged != null) {
                        collectPhotos(
                            photoRepository.searchPhotosWithLikeStatus(query, logged.id, logged.groups),
                            enrichLikes = false
                        )
                    } else {
                        collectPhotos(photoRepository.searchPhotos(query), enrichLikes = true)
                    }
                }
                authorId != null -> {
                    collectPhotos(photoRepository.getPhotosByAuthor(authorId), enrichLikes = true)
                }
                selectedTags.isNotEmpty() -> {
                    val userId = currentUser?.id
                    if (userId != null) {
                        collectPhotos(photoRepository.getPhotosByTagsWithLikeStatus(selectedTags.toList(), userId), enrichLikes = false)
                    } else {
                        collectPhotos(photoRepository.getPhotosByTags(selectedTags.toList()), enrichLikes = true)
                    }
                }
                selectedCategory != null -> {
                    val userId = currentUser?.id
                    if (userId != null) {
                        collectPhotos(photoRepository.getPhotosByCategoryWithLikeStatus(selectedCategory, userId), enrichLikes = false)
                    } else {
                        collectPhotos(photoRepository.getPhotosByCategory(selectedCategory), enrichLikes = true)
                    }
                }
                else -> {
                    val user = currentUser
                    if (user != null) {
                        collectPhotos(photoRepository.getVisiblePhotosWithLikeStatus(user.id, user.groups), enrichLikes = false)
                    } else {
                        collectPhotos(photoRepository.getPublicPhotos(), enrichLikes = true)
                    }
                }
            }
        }
    }

    private suspend fun collectPhotos(flow: Flow<Result<List<Photo>>>, enrichLikes: Boolean) {
        val likeId = SessionIdentity.getLikeUserId(application)
        if (!NetworkConnectivity.isOnline(application)) {
            _photos.value = Result.success(emptyList())
            _uiMessage.value = "Pas de connexion internet"
            _isLoading.value = false
            return
        }
        flow.collect { result ->
            result.onSuccess { list ->
                val out = if (enrichLikes) {
                    photoRepository.attachLikeStatus(list, likeId)
                } else {
                    list
                }
                _photos.value = Result.success(out)
            }
            result.onFailure { _photos.value = result }
            _isLoading.value = false
        }
    }

    fun clearUiMessage() { _uiMessage.value = null }

    private fun loadAvailableTags() {
        viewModelScope.launch {
            val result = photoRepository.getAllUniqueTags()
            result.onSuccess { tags ->
                _availableTags.value = tags
            }
        }
    }

    fun searchPhotos(query: String) {
        _searchQuery.value = query
        if (query.isNotBlank()) {
            _filterLocation.value = null
            _hasNearbyFilter.value = false
            _filterStartDate.value = null
            _filterEndDate.value = null
            _filterAuthorId.value = null
            _selectedTags.value = emptySet()
            _selectedCategory.value = null
        }
        loadPhotos()
    }

    fun filterByCategory(category: PhotoCategory?) {
        _selectedCategory.value = category
        _selectedTags.value = emptySet()
        _filterLocation.value = null
        _hasNearbyFilter.value = false
        _filterStartDate.value = null
        _filterEndDate.value = null
        _filterAuthorId.value = null
        _searchQuery.value = ""
        loadPhotos()
    }

    fun filterByDateRange(startDate: Date?, endDate: Date?) {
        _filterStartDate.value = startDate
        _filterEndDate.value = endDate
        if (startDate != null && endDate != null) {
            _filterLocation.value = null
            _hasNearbyFilter.value = false
            _selectedTags.value = emptySet()
            _selectedCategory.value = null
            _filterAuthorId.value = null
            _searchQuery.value = ""
        }
        loadPhotos()
    }

    fun clearDateRangeFilter() {
        _filterStartDate.value = null
        _filterEndDate.value = null
        loadPhotos()
    }

    fun filterByLocation(latitude: Double, longitude: Double, radiusKm: Double = 10.0) {
        _filterLocation.value = Pair(latitude, longitude)
        _filterRadiusKm.value = radiusKm
        _hasNearbyFilter.value = true
        _filterStartDate.value = null
        _filterEndDate.value = null
        _selectedTags.value = emptySet()
        _selectedCategory.value = null
        _filterAuthorId.value = null
        _searchQuery.value = ""
        loadPhotos()
    }


    fun filterByAuthorOrSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        if (looksLikeFirebaseUid(q)) {
            filterByAuthor(q)
        } else {
            searchPhotos(q)
        }
    }

    fun filterByAuthor(authorId: String) {
        _filterAuthorId.value = authorId
        _filterLocation.value = null
        _hasNearbyFilter.value = false
        _filterStartDate.value = null
        _filterEndDate.value = null
        _selectedTags.value = emptySet()
        _selectedCategory.value = null
        _searchQuery.value = ""
        loadPhotos()
    }

    fun clearLocationFilter() {
        _filterLocation.value = null
        _hasNearbyFilter.value = false
        loadPhotos()
    }

    fun toggleTag(tag: String) {
        val currentTags = _selectedTags.value.toMutableSet()
        if (currentTags.contains(tag)) {
            currentTags.remove(tag)
        } else {
            currentTags.add(tag)
        }
        _selectedTags.value = currentTags
        _selectedCategory.value = null
        _searchQuery.value = ""
        _filterLocation.value = null
        _hasNearbyFilter.value = false
        _filterStartDate.value = null
        _filterEndDate.value = null
        _filterAuthorId.value = null
        loadPhotos()
    }

    fun clearFilters() {
        _selectedTags.value = emptySet()
        _selectedCategory.value = null
        _searchQuery.value = ""
        _filterStartDate.value = null
        _filterEndDate.value = null
        _filterLocation.value = null
        _filterAuthorId.value = null
        _hasNearbyFilter.value = false
        loadPhotos()
    }

    fun toggleLike(photo: Photo): Pair<Boolean, Int> {
        val likeId = SessionIdentity.getLikeUserId(application)
        val accountUserId = currentUser?.id
        val newIsLiked = !photo.isLiked
        val newLikes = if (newIsLiked) photo.likes + 1 else (photo.likes - 1).coerceAtLeast(0)

        viewModelScope.launch {
            if (photo.isLiked) {
                photoRepository.unlikePhoto(photo.id, likeId)
                accountUserId?.let { userRepository.removePhotoFavoriteSuspend(photo.id) }
            } else {
                photoRepository.likePhoto(photo.id, likeId)
                accountUserId?.let { userRepository.addPhotoFavoriteSuspend(photo.id) }
            }
        }
        return Pair(newIsLiked, newLikes)
    }

    fun getRandomPhoto() {
        viewModelScope.launch {
            val likeId = SessionIdentity.getLikeUserId(application)
            photoRepository.getRandomPhoto().collect { result ->
                result.onSuccess { photo ->
                    val list = photoRepository.attachLikeStatus(listOf(photo), likeId)
                    _photos.value = Result.success(list)
                }
                result.onFailure { e ->
                    _photos.value = Result.failure(e)
                }
            }
        }
    }

    fun getSimilarPhotos(photoId: String) {
        photosJob?.cancel()
        mode = Mode.SIMILAR
        photosJob = viewModelScope.launch {
            _isLoading.value = true
            val likeId = SessionIdentity.getLikeUserId(application)
            val viewer = currentUser
            photoRepository.getPhotosBySimilarity(photoId, viewer?.id, viewer?.groups.orEmpty()).collect { result ->
                result.onSuccess { list ->
                    _photos.value = Result.success(photoRepository.attachLikeStatus(list, likeId))
                }
                result.onFailure { _photos.value = result }
                _isLoading.value = false
            }
        }
    }

    companion object {
        private fun looksLikeFirebaseUid(s: String): Boolean {
            val t = s.trim()
            return t.length in 20..128 && t.matches(Regex("[a-zA-Z0-9]+"))
        }
    }

    class Factory(
        private val application: Application,
        private val autoLoad: Boolean = true
    ) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val photoRepository = (application as TravelingApp).photoRepository
            val userRepository = (application as TravelingApp).userRepository
            if (modelClass.isAssignableFrom(PhotoViewModel::class.java)) {
                return PhotoViewModel(application, photoRepository, userRepository, autoLoad) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
