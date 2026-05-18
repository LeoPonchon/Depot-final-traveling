package com.shimtraveling.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Group
import com.shimtraveling.data.model.Photo
import com.shimtraveling.data.model.PhotoCategory
import com.shimtraveling.data.model.PhotoModerationStatus
import com.shimtraveling.data.model.Place
import com.shimtraveling.data.model.LocationPrecision
import com.shimtraveling.data.model.PhotoVisibility
import com.shimtraveling.data.model.TimeOfDay
import com.shimtraveling.data.repository.PhotoRepository
import com.shimtraveling.data.repository.PlaceRepository
import com.shimtraveling.data.repository.StorageRepository
import com.shimtraveling.data.repository.UserRepository
import com.shimtraveling.core.NetworkConnectivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.pow
import kotlin.math.round

sealed class PublishState {
    object Idle : PublishState()
    object Loading : PublishState()
    object Success : PublishState()
    data class Error(val message: String) : PublishState()
}

class PublishPhotoViewModel(
    private val application: Application,
    private val photoRepository: PhotoRepository,
    private val placeRepository: PlaceRepository,
    private val userRepository: UserRepository,
    private val storageRepository: StorageRepository
) : ViewModel() {

    private val _publishState = MutableStateFlow<PublishState>(PublishState.Idle)
    val publishState: StateFlow<PublishState> = _publishState.asStateFlow()

    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    private val _userGroups = MutableStateFlow<List<Group>>(emptyList())
    val userGroups: StateFlow<List<Group>> = _userGroups.asStateFlow()

    init {
        loadPlaces()
        loadUserGroups()
    }

    private fun loadPlaces() {
        viewModelScope.launch {
            placeRepository.getAllPlaces().first().onSuccess { _places.value = it }
        }
    }

    private fun loadUserGroups() {
        viewModelScope.launch {
            val userResult = userRepository.getCurrentUser().first()
            userResult.onSuccess { user ->
                if (user != null && user.groups.isNotEmpty()) {
                    val groups = mutableListOf<Group>()
                    for (groupId in user.groups) {
                        TravelingApp.getInstance().firestoreRepository
                            .getGroupById(groupId)
                            .getOrNull()?.let { groups.add(it) }
                    }
                    _userGroups.value = groups
                }
            }
        }
    }

    fun publish(
        imageUri: Uri?,
        audioUri: Uri?,
        description: String,
        tags: List<String>,
        howToGo: String?,
        price: Double?,
        timeOfDay: TimeOfDay?,
        selectedPlace: Place,
        visibility: PhotoVisibility,
        groupId: String?,
        takenAt: Date? = null,
        locationPrecision: LocationPrecision = LocationPrecision.EXACT
    ) {
        if (description.isBlank()) {
            _publishState.value = PublishState.Error("Veuillez ajouter une description")
            return
        }
        if (imageUri == null) {
            _publishState.value = PublishState.Error("Veuillez sélectionner ou prendre une photo")
            return
        }

        viewModelScope.launch {
            _publishState.value = PublishState.Loading
            try {
                val userResult = userRepository.getCurrentUser().first()
                val user = userResult.getOrNull()
                if (user == null) {
                    _publishState.value = PublishState.Error("Connectez-vous pour publier")
                    return@launch
                }

                val liveUser = TravelingApp.getInstance().firestoreRepository.getUserById(user.id).getOrNull()
                    ?: user

                if (!NetworkConnectivity.isOnline(application)) {
                    _publishState.value = PublishState.Error("Connexion requise pour publier une photo")
                    return@launch
                }

                val photoId = java.util.UUID.randomUUID().toString()
                val storageMeta = mapOf(
                    "visibility" to visibility.name,
                    "groupId" to (groupId ?: "")
                )
                val photoUrl = storageRepository
                    .uploadPhoto(user.id, photoId, imageUri, storageMeta)
                    .getOrElse {
                        _publishState.value = PublishState.Error(
                            it.message ?: "Impossible d'uploader la photo"
                        )
                        return@launch
                    }

                val audioUrl = if (audioUri != null) {
                    storageRepository.uploadVoiceAttachment(user.id, photoId, audioUri, storageMeta).getOrElse {
                        _publishState.value = PublishState.Error(it.message ?: "Impossible d'uploader l'audio")
                        return@launch
                    }
                } else {
                    null
                }

                val lat = if (locationPrecision == LocationPrecision.APPROXIMATE) {
                    roundCoord(selectedPlace.latitude, 2)
                } else {
                    selectedPlace.latitude
                }
                val lng = if (locationPrecision == LocationPrecision.APPROXIMATE) {
                    roundCoord(selectedPlace.longitude, 2)
                } else {
                    selectedPlace.longitude
                }

                val photoCategory = runCatching { PhotoCategory.valueOf(selectedPlace.type.name) }
                    .getOrDefault(PhotoCategory.OTHER)
                val placeTypeName = selectedPlace.type.name
                val searchCategories = listOf(photoCategory.name, placeTypeName).distinct()
                val photo = Photo(
                    id = photoId,
                    url = photoUrl,
                    placeId = selectedPlace.id,
                    placeName = selectedPlace.name,
                    latitude = lat,
                    longitude = lng,
                    address = selectedPlace.address,
                    authorId = liveUser.id,
                    authorName = liveUser.username,
                    authorAvatar = liveUser.avatar,
                    description = description,
                    tags = tags,
                    category = photoCategory,
                    placeType = placeTypeName,
                    searchCategories = searchCategories,
                    moderationStatus = PhotoModerationStatus.VISIBLE,
                    visibility = visibility,
                    groupId = if (visibility == PhotoVisibility.GROUP) groupId else null,
                    howToGo = howToGo,
                    takenAt = takenAt,
                    createdAt = Date(),
                    price = price,
                    timeOfDay = timeOfDay,
                    audioUrl = audioUrl,
                    locationPrecision = locationPrecision
                )

                val result = TravelingApp.getInstance().firestoreRepository
                    .publishPhotoAtomic(photo, price, selectedPlace.id)

                if (result.isSuccess) {
                    _publishState.value = PublishState.Success
                } else {
                    _publishState.value = PublishState.Error("Erreur lors de la publication")
                }
            } catch (e: Exception) {
                _publishState.value = PublishState.Error(e.message ?: "Erreur inconnue")
            }
        }
    }

    fun resetState() { _publishState.value = PublishState.Idle }

    private fun roundCoord(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return round(value * factor) / factor
    }

    class Factory(private val application: Application) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as TravelingApp
            if (modelClass.isAssignableFrom(PublishPhotoViewModel::class.java)) {
                return PublishPhotoViewModel(
                    application,
                    app.photoRepository,
                    app.placeRepository,
                    app.userRepository,
                    app.storageRepository
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
