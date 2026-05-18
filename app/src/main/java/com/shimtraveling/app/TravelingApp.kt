package com.shimtraveling

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.shimtraveling.core.AppSettings
import com.shimtraveling.data.cache.DataCache
import com.shimtraveling.data.firestore.FirestoreRepository
import com.shimtraveling.data.repository.PathRepository
import com.shimtraveling.data.repository.PhotoRepository
import com.shimtraveling.data.repository.PlaceRepository
import com.shimtraveling.data.repository.UserRepository
import com.shimtraveling.data.repository.WeatherRepository
import com.shimtraveling.data.repository.RoutingRepository
import com.shimtraveling.data.repository.ElevationRepository
import com.shimtraveling.data.repository.StorageRepository
import com.shimtraveling.core.LocationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class TravelingApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    lateinit var firestoreRepository: FirestoreRepository
    lateinit var placeRepository: PlaceRepository
    lateinit var photoRepository: PhotoRepository
    lateinit var pathRepository: PathRepository
    lateinit var userRepository: UserRepository
    lateinit var dataCache: DataCache
    lateinit var weatherRepository: WeatherRepository
    lateinit var routingRepository: RoutingRepository
    lateinit var elevationRepository: ElevationRepository
    lateinit var storageRepository: StorageRepository
    lateinit var locationService: LocationService

    override fun onCreate() {
        super.onCreate()
        instance = this
        applySavedUiPreferences()
        FirebaseApp.initializeApp(this)
        firestore = FirebaseFirestore.getInstance()

        val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
        firestore.firestoreSettings = settings

        auth = FirebaseAuth.getInstance()
        firestoreRepository = FirestoreRepository(firestore)
        storageRepository = StorageRepository(FirebaseStorage.getInstance())
        placeRepository = PlaceRepository(firestoreRepository)
        photoRepository = PhotoRepository(firestoreRepository, storageRepository)
        userRepository = UserRepository(firestoreRepository, auth)
        weatherRepository = WeatherRepository()
        routingRepository = RoutingRepository()
        elevationRepository = ElevationRepository()
        locationService = LocationService(this)
        pathRepository = PathRepository(firestoreRepository, weatherRepository, elevationRepository, routingRepository)
        dataCache = DataCache(this)

        com.shimtraveling.worker.NotificationWorker.enqueue(this)

        auth.addAuthStateListener { fbAuth ->
            val u = fbAuth.currentUser
            if (u == null) {
                ensureAnonymousSession()
                return@addAuthStateListener
            }
            applicationScope.launch(Dispatchers.IO) {
                if (!u.isAnonymous) {
                    try {
                        val token = FirebaseMessaging.getInstance().token.await()
                        firestoreRepository.updateFcmToken(u.uid, token)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        ensureAnonymousSession()
    }

    private fun applySavedUiPreferences() {
        val language = AppSettings.getLanguage(this)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))

        val mode = when (AppSettings.getThemeMode(this)) {
            AppSettings.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppSettings.THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    companion object {
        private lateinit var instance: TravelingApp

        fun getInstance(): TravelingApp = instance
    }

    private fun ensureAnonymousSession() {
        if (auth.currentUser != null) return
        applicationScope.launch {
            try {
                auth.signInAnonymously().await()
            } catch (_: Exception) {
            }
        }
    }
}
