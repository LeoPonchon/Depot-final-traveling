package com.shimtraveling.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Place
import com.shimtraveling.data.model.PlaceType
import com.shimtraveling.data.repository.PlaceRepository
import com.shimtraveling.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

class ShareViewModel(private val repository: PlaceRepository, private val userRepository: UserRepository) : ViewModel() {

    private val _places = MutableStateFlow<Result<List<Place>>>(Result.success(emptyList()))
    val places: StateFlow<Result<List<Place>>> = _places.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedType = MutableStateFlow<PlaceType?>(null)
    private val _filterStartDate = MutableStateFlow<Date?>(null)
    private val _filterEndDate = MutableStateFlow<Date?>(null)
    private val _filterLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    private val _filterRadiusKm = MutableStateFlow(10.0)

    private var currentUser: com.shimtraveling.data.model.User? = null

    init {
        loadPlaces()
        loadAvailableTags()
        checkLoginStatus()
    }

    private fun checkLoginStatus() {
        viewModelScope.launch {
            userRepository.getCurrentUser().collect { result ->
                result.onSuccess { user ->
                    currentUser = user
                    _isLoggedIn.value = user != null
                }
            }
        }
    }

    fun loadPlaces() {
        viewModelScope.launch {
            _isLoading.value = true
            val selectedTags = _selectedTags.value
            val selectedType = _selectedType.value
            val query = _searchQuery.value
            val startDate = _filterStartDate.value
            val endDate = _filterEndDate.value
            val location = _filterLocation.value
            val radiusKm = _filterRadiusKm.value

            when {
                location != null -> {
                    val lat = location.first
                    val lng = location.second
                    collectPlaces(repository.getPlacesNearby(lat, lng, radiusKm))
                }
                startDate != null && endDate != null -> {
                    collectPlaces(repository.getPlacesByDateRange(startDate, endDate))
                }
                query.isNotBlank() -> {
                    if (currentUser != null) {
                        collectPlaces(repository.searchPlacesWithLikeStatus(query, currentUser!!.id))
                    } else {
                        collectPlaces(repository.searchPlaces(query))
                    }
                }
                selectedTags.isNotEmpty() -> {
                    if (currentUser != null) {
                        collectPlaces(repository.getPlacesByTagsWithLikeStatus(selectedTags.toList(), currentUser!!.id))
                    } else {
                        collectPlaces(repository.getPlacesByTags(selectedTags.toList()))
                    }
                }
                selectedType != null -> {
                    if (currentUser != null) {
                        collectPlaces(repository.getPlacesByTypeWithLikeStatus(selectedType, currentUser!!.id))
                    } else {
                        collectPlaces(repository.getPlacesByType(selectedType))
                    }
                }
                else -> {
                    if (currentUser != null) {
                        collectPlaces(repository.getPlacesWithLikeStatus(currentUser!!.id))
                    } else {
                        collectPlaces(repository.getAllPlaces())
                    }
                }
            }
        }
    }

    private suspend fun collectPlaces(flow: Flow<Result<List<Place>>>) {
        flow.collect { result ->
            _places.value = result
            _isLoading.value = false
        }
    }

    private fun loadAvailableTags() {
        viewModelScope.launch {
            val result = repository.getAllUniqueTags()
            result.onSuccess { tags ->
                _availableTags.value = tags
            }
        }
    }

    fun searchPlaces(query: String) {
        _searchQuery.value = query
        loadPlaces()
    }

    fun filterByType(type: PlaceType?) {
        _selectedType.value = type
        _selectedTags.value = emptySet()
        loadPlaces()
    }

    fun filterByDateRange(startDate: Date?, endDate: Date?) {
        _filterStartDate.value = startDate
        _filterEndDate.value = endDate
        loadPlaces()
    }

    fun filterByLocation(latitude: Double, longitude: Double, radiusKm: Double = 10.0) {
        _filterLocation.value = Pair(latitude, longitude)
        _filterRadiusKm.value = radiusKm
        loadPlaces()
    }

    fun clearLocationFilter() {
        _filterLocation.value = null
        loadPlaces()
    }

    fun toggleTag(tag: String) {
        val currentTags = _selectedTags.value.toMutableSet()
        if (currentTags.contains(tag)) {
            currentTags.remove(tag)
        } else {
            currentTags.add(tag)
        }
        _selectedTags.value = currentTags
        _selectedType.value = null
        _searchQuery.value = ""
        loadPlaces()
    }

    fun clearFilters() {
        _selectedTags.value = emptySet()
        _selectedType.value = null
        _searchQuery.value = ""
        _filterStartDate.value = null
        _filterEndDate.value = null
        _filterLocation.value = null
        loadPlaces()
    }

    fun toggleLike(place: Place): Boolean {
        if (!_isLoggedIn.value || currentUser == null) {
            return false
        }

        viewModelScope.launch {
            val userId = currentUser!!.id
            if (place.isLiked) {
                repository.unlikePlace(place.id, userId)
                userRepository.removeFavoriteSuspend(place.id)
            } else {
                repository.likePlace(place.id, userId)
                userRepository.addFavoriteSuspend(place.id)
            }
            loadPlaces()
        }
        return true
    }

    fun getRandomPlace() {
        viewModelScope.launch {
            repository.getRandomPlace().collect { result ->
                result.onSuccess { place ->
                    _places.value = Result.success(listOf(place))
                }
            }
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = (application as TravelingApp).placeRepository
            val userRepository = (application as TravelingApp).userRepository
            if (modelClass.isAssignableFrom(ShareViewModel::class.java)) {
                return ShareViewModel(repository, userRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
