package com.shimtraveling.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.ActivityType
import com.shimtraveling.data.model.EffortLevel
import com.shimtraveling.data.model.PathPreferences
import com.shimtraveling.data.model.PathGenerationProgress
import com.shimtraveling.data.model.PathStep
import com.shimtraveling.data.model.PathType
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.data.repository.PathRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.shimtraveling.data.repository.UserRepository

class PathViewModel(
    private val repository: PathRepository,
    private val userRepository: UserRepository,
    private val dataCache: com.shimtraveling.data.cache.DataCache
) : ViewModel() {

    private companion object {
        private const val PLACEHOLDER_ID = "__placeholder_path__"
        private const val FALLBACK_STEP_DURATION_MINUTES = 60
    }

    private val _paths = MutableStateFlow<Result<List<TravelPath>>>(Result.success(emptyList()))
    val paths: StateFlow<Result<List<TravelPath>>> = _paths.asStateFlow()

    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _generationProgress = MutableStateFlow<PathGenerationProgress?>(null)
    val generationProgress: StateFlow<PathGenerationProgress?> = _generationProgress.asStateFlow()

    private val _savedPaths = MutableStateFlow<List<TravelPath>>(emptyList())
    val savedPaths: StateFlow<List<TravelPath>> = _savedPaths.asStateFlow()

    private val _availableCities = MutableStateFlow<List<String>>(emptyList())
    val availableCities: StateFlow<List<String>> = _availableCities.asStateFlow()

    private val _selectedCity = MutableStateFlow("")
    val selectedCity: StateFlow<String> = _selectedCity.asStateFlow()

    private var lastPreferences: PathPreferences? = null

    init {
        loadAvailableCities()
    }

    fun loadSavedPaths(userId: String) {
        viewModelScope.launch {
            try {
                repository.getSavedPaths(userId).collect { result ->
                    result.onSuccess { paths ->
                        paths.forEach { dataCache.cacheSavedPath(it) }
                        _savedPaths.value = paths
                    }
                    result.onFailure {
                        val cached = dataCache.getCachedSavedPaths()
                        if (!cached.isNullOrEmpty()) {
                            _savedPaths.value = cached
                        }
                    }
                }
            } catch (e: Exception) {
                val cached = dataCache.getCachedSavedPaths()
                if (!cached.isNullOrEmpty()) {
                    _savedPaths.value = cached
                }
            }
        }
    }

    private fun loadAvailableCities() {
        viewModelScope.launch {
            try {
                val result = repository.getAvailableCities()
                result.onSuccess { cities ->
                    _availableCities.value = cities
                }
            } catch (e: Exception) {
                _availableCities.value = listOf("Paris", "Lyon", "Marseille", "Bordeaux", "Toulouse", "Nice", "Strasbourg", "Lille", "Nantes", "Montpellier")
            }
        }
    }

    fun setSelectedCity(city: String) {
        _selectedCity.value = city
    }

    fun generatePaths(preferences: PathPreferences) {
        lastPreferences = preferences
        _infoMessage.value = null
        val city = _selectedCity.value
        if (city.isBlank()) {
            val cached = dataCache.getCachedPaths()
            if (!cached.isNullOrEmpty()) {
                _infoMessage.value = null
                _paths.value = Result.success(cached)
                return
            }
            _paths.value = Result.success(listOf(buildPlaceholderPath("À corriger : choisissez une ville pour générer un parcours.")))
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _isLoading.value = true
            _generationProgress.value = null
            _infoMessage.value = null
            try {
                val userResult = userRepository.getCurrentUser().first()
                val userId = userResult.getOrNull()?.id

                repository.generatePaths(preferences, city, userId) { progress ->
                    _generationProgress.value = progress
                }.collect { result ->
                    result.onSuccess { paths ->
                        val hasAnyRealOption = paths.any { it.steps.isNotEmpty() }
                        if (hasAnyRealOption) {
                            dataCache.cachePaths(paths)
                            _infoMessage.value = null
                            _paths.value = Result.success(paths)
                        } else {
                            val closest = repository.generateClosestPaths(preferences, city, userId) { progress ->
                                _generationProgress.value = progress
                            }
                            closest.onSuccess { r ->
                                dataCache.cachePaths(r.paths)
                                _infoMessage.value = r.note
                                _paths.value = Result.success(r.paths)
                            }
                            closest.onFailure {
                                val fallback = buildFallbackPath(city, preferences, null)
                                _infoMessage.value = null
                                _paths.value = Result.success(listOf(fallback))
                            }
                        }
                    }
                    result.onFailure { originalError ->
                        val closest = repository.generateClosestPaths(preferences, city, userId) { progress ->
                            _generationProgress.value = progress
                        }
                        closest.onSuccess { r ->
                            dataCache.cachePaths(r.paths)
                            _infoMessage.value = r.note
                            _paths.value = Result.success(r.paths)
                        }
                        closest.onFailure {
                            val cached = dataCache.getCachedPaths()
                            if (!cached.isNullOrEmpty()) {
                                _infoMessage.value = null
                                _paths.value = Result.success(cached)
                            } else {
                                val fallback = buildFallbackPath(city, preferences, originalError)
                                _infoMessage.value = null
                                _paths.value = Result.success(listOf(fallback))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val cached = dataCache.getCachedPaths()
                if (!cached.isNullOrEmpty()) {
                    _infoMessage.value = null
                    _paths.value = Result.success(cached)
                } else {
                    val fallback = buildFallbackPath(city, preferences, e)
                    _infoMessage.value = null
                    _paths.value = Result.success(listOf(fallback))
                }
            } finally {
                _isLoading.value = false
                _generationProgress.value = null
            }
        }
    }

    private fun buildPlaceholderPath(message: String): TravelPath {
        return TravelPath(
            id = PLACEHOLDER_ID,
            name = "Aucun parcours trouvé",
            description = message,
            type = PathType.BALANCED,
            steps = emptyList(),
            totalDurationMinutes = 0,
            totalCost = null,
            hasCompletePricing = false,
            totalEffort = EffortLevel.LOW,
            distanceKm = 0.0,
            city = _selectedCity.value
        )
    }

    private suspend fun buildFallbackPath(city: String, preferences: PathPreferences, error: Throwable?): TravelPath {
        val cityPlaces = repository.getPlacesForCity(city).getOrNull().orEmpty()
        val requestedSteps = preferences.maxSteps.coerceAtLeast(1)

        val prereqMessage = firstPrereqMessage(
            availablePlacesCount = cityPlaces.size,
            requestedSteps = requestedSteps,
            preferences = preferences,
            error = error
        )

        val firstPlace = cityPlaces.firstOrNull()
        val steps = if (firstPlace != null) {
            listOf(
                PathStep(
                    id = "fallback_step_${firstPlace.id}",
                    order = 1,
                    placeId = firstPlace.id,
                    placeName = firstPlace.name,
                    placeImageUrl = firstPlace.imageUrl,
                    placeType = firstPlace.type,
                    latitude = firstPlace.latitude,
                    longitude = firstPlace.longitude,
                    activityType = ActivityType.DISCOVERY,
                    estimatedDurationMinutes = FALLBACK_STEP_DURATION_MINUTES,
                    estimatedCost = null,
                    effortLevel = EffortLevel.LOW,
                    notes = null,
                    openingHours = firstPlace.openingHours,
                    weatherCondition = null,
                    timeOfDay = null,
                    startTimeMinutesFromMidnight = null,
                    walkingTimeFromPreviousMinutes = null,
                    tags = firstPlace.tags,
                    videoUrl = firstPlace.videoUrl,
                    elevationMeters = null
                )
            )
        } else {
            emptyList()
        }

        return TravelPath(
            id = PLACEHOLDER_ID,
            name = if (steps.isEmpty()) "Aucun parcours trouvé" else "Parcours minimal",
            description = prereqMessage,
            type = PathType.BALANCED,
            steps = steps,
            totalDurationMinutes = if (steps.isEmpty()) 0 else FALLBACK_STEP_DURATION_MINUTES,
            totalCost = null,
            hasCompletePricing = false,
            totalEffort = EffortLevel.LOW,
            distanceKm = 0.0,
            city = city
        )
    }

    private fun firstPrereqMessage(
        availablePlacesCount: Int,
        requestedSteps: Int,
        preferences: PathPreferences,
        error: Throwable?
    ): String {
        val raw = error?.message?.trim().orEmpty()
        if (raw.isNotBlank() && !raw.contains("Une erreur est survenue lors de la génération", ignoreCase = true)) {
            return "À corriger : $raw"
        }

        return when {
            availablePlacesCount == 0 ->
                "À corriger : aucun lieu n’est disponible pour cette ville. Ajoutez des lieux (Profil → Ajouter un lieu) ou changez de ville."

            availablePlacesCount < requestedSteps ->
                "À corriger : pas assez de lieux (${availablePlacesCount}) pour ${requestedSteps} étape(s). Réduisez “Étapes” à ${availablePlacesCount} ou ajoutez des lieux."

            preferences.mustVisitPlaces.isNotEmpty() ->
                "À corriger : vos lieux obligatoires sont probablement trop contraignants. Retirez 1–2 lieux obligatoires puis réessayez."

            preferences.tags.isNotEmpty() ->
                "À corriger : vos tags sont peut-être trop spécifiques. Supprimez un tag puis réessayez."

            else ->
                "À corriger : critères trop stricts. Réduisez les filtres ou baissez le nombre d’étapes."
        }
    }


    fun regeneratePaths() {
        val prefs = lastPreferences ?: return
        generatePaths(prefs)
    }

    fun savePath(path: TravelPath, userId: String) {
        viewModelScope.launch {
            val pathWithUser = path.copy(userId = userId, isSaved = true)
            repository.savePath(pathWithUser)
            repository.getSavedPaths(userId).collect { result ->
                result.onSuccess { paths -> _savedPaths.value = paths }
            }
        }
    }

    fun removeSavedPath(pathId: String) {
        val currentSaved = _savedPaths.value.toMutableList()
        currentSaved.removeAll { it.id == pathId }
        _savedPaths.value = currentSaved
    }

    class Factory(private val application: Application) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = application as TravelingApp
            val repository = app.pathRepository
            val userRepository = app.userRepository
            val cache = app.dataCache
            if (modelClass.isAssignableFrom(PathViewModel::class.java)) {
                return PathViewModel(repository, userRepository, cache) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
