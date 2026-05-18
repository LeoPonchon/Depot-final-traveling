package com.shimtraveling.ui.main

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.shimtraveling.R
import com.google.firebase.auth.FirebaseAuth
import com.shimtraveling.core.LocationCache
import com.shimtraveling.TravelingApp
import com.shimtraveling.features.photo.PhotoDetailActivity
import com.shimtraveling.features.place.PlaceDetailActivity
import com.shimtraveling.ui.path.PathFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mergeLegacyPathExtras(intent)
        setContentView(R.layout.activity_main)

        setupNavigation()
        cacheLastLocationIfPossible()
        maybeShowTravelingIntro()
        consumeTravelingDeepLink(intent)
    }

    private fun maybeShowTravelingIntro() {
        val prefs = getSharedPreferences("traveling_app", MODE_PRIVATE)
        if (prefs.getBoolean("seen_traveling_intro", false)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.intro_traveling_title)
            .setMessage(R.string.intro_traveling_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putBoolean("seen_traveling_intro", true).apply()
            }
            .show()
    }

    private fun mergeLegacyPathExtras(intent: Intent) {
        if (intent.getStringExtra(EXTRA_PREFILL_CITY).isNullOrBlank()) {
            intent.getStringExtra(LEGACY_EXTRA_PLACE_NAME)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                intent.putExtra(EXTRA_PREFILL_CITY, it)
            }
        }
    }

    private fun cacheLastLocationIfPossible() {
        lifecycleScope.launch {
            val app = application as? TravelingApp ?: return@launch
            if (!app.locationService.hasLocationPermission()) return@launch
            val loc = app.locationService.getCurrentLocation() ?: return@launch
            getSharedPreferences(LocationCache.PREFS_NAME, MODE_PRIVATE).edit()
                .putFloat(LocationCache.KEY_LAT, loc.latitude.toFloat())
                .putFloat(LocationCache.KEY_LNG, loc.longitude.toFloat())
                .putLong(LocationCache.KEY_TIME, System.currentTimeMillis())
                .apply()
            val firebaseUser = FirebaseAuth.getInstance().currentUser ?: return@launch
            if (firebaseUser.isAnonymous) return@launch
            val uid = firebaseUser.uid
            withContext(Dispatchers.IO) {
                app.firestoreRepository.syncNotifyLastLocation(uid, loc.latitude, loc.longitude)
            }
        }
    }

    private fun setupNavigation() {
        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController
        navView.setupWithNavController(navController)

        if (intent.getBooleanExtra(EXTRA_OPEN_GUIDE, false)) {
            navController.navigate(R.id.navigation_guide)
        } else if (intent.getBooleanExtra(EXTRA_OPEN_PATH, false)) {
            navigateToPathWithPrefill(navController, intent)
        } else if (intent.getBooleanExtra(EXTRA_OPEN_SHARE, false)) {
            navigateToShareWithPrefill(navController, intent)
        }
    }

    private fun navigateToPathWithPrefill(
        navController: androidx.navigation.NavController,
        fromIntent: Intent
    ) {
        val city = fromIntent.getStringExtra(EXTRA_PREFILL_CITY)?.trim().orEmpty()
        val must = fromIntent.getStringExtra(EXTRA_MUST_VISIT_TOKEN)?.trim().orEmpty()
        val args = Bundle().apply {
            if (city.isNotEmpty()) putString(PathFragment.ARG_PREFILL_CITY, city)
            if (must.isNotEmpty()) putString(PathFragment.ARG_MUST_VISIT, must)
        }
        if (args.keySet().isEmpty()) {
            navController.navigate(R.id.navigation_path)
        } else {
            navController.navigate(R.id.navigation_path, args)
        }
    }

    private fun navigateToShareWithPrefill(
        navController: androidx.navigation.NavController,
        fromIntent: Intent
    ) {
        val query = fromIntent.getStringExtra(EXTRA_PREFILL_SHARE_QUERY)?.trim().orEmpty()
        val args = Bundle().apply {
            if (query.isNotEmpty()) putString(EXTRA_PREFILL_SHARE_QUERY, query)
        }
        if (args.keySet().isEmpty()) {
            navController.navigate(R.id.navigation_share)
        } else {
            navController.navigate(R.id.navigation_share, args)
        }
    }

    private fun consumeTravelingDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "shimtraveling" || uri.host != "app") return
        when (uri.pathSegments.firstOrNull()) {
            "path" -> {
                val city = uri.getQueryParameter("city").orEmpty()
                val must = uri.getQueryParameter("must").orEmpty()
                intent.putExtra(EXTRA_OPEN_PATH, true)
                if (city.isNotEmpty()) intent.putExtra(EXTRA_PREFILL_CITY, city)
                if (must.isNotEmpty()) intent.putExtra(EXTRA_MUST_VISIT_TOKEN, must)
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
                navigateToPathWithPrefill(navHostFragment.navController, intent)
            }
            "share" -> {
                val query = uri.getQueryParameter("query").orEmpty()
                intent.putExtra(EXTRA_OPEN_SHARE, true)
                if (query.isNotEmpty()) intent.putExtra(EXTRA_PREFILL_SHARE_QUERY, query)
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
                navigateToShareWithPrefill(navHostFragment.navController, intent)
            }
            "photo" -> {
                val id = uri.pathSegments.getOrNull(1) ?: return
                startActivity(Intent(this, PhotoDetailActivity::class.java).putExtra(PhotoDetailActivity.EXTRA_PHOTO_ID, id))
            }
            "place" -> {
                val id = uri.pathSegments.getOrNull(1) ?: return
                lifecycleScope.launch {
                    val place = TravelingApp.getInstance().placeRepository.getPlaceById(id).first().getOrNull()
                    if (place != null) {
                        startActivity(
                            Intent(this@MainActivity, PlaceDetailActivity::class.java)
                                .putExtra("place", place as Parcelable)
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mergeLegacyPathExtras(intent)
        consumeTravelingDeepLink(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_GUIDE, false)) {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
            navHostFragment.navController.navigate(R.id.navigation_guide)
        } else if (intent.getBooleanExtra(EXTRA_OPEN_PATH, false)) {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
            navigateToPathWithPrefill(navHostFragment.navController, intent)
        } else if (intent.getBooleanExtra(EXTRA_OPEN_SHARE, false)) {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as androidx.navigation.fragment.NavHostFragment
            navigateToShareWithPrefill(navHostFragment.navController, intent)
        }
    }

    companion object {
        const val EXTRA_OPEN_GUIDE = "OPEN_GUIDE"
        const val EXTRA_OPEN_PATH = "OPEN_PATH_GEN"
        const val EXTRA_OPEN_SHARE = "OPEN_SHARE"
        const val EXTRA_PREFILL_CITY = "PREFILL_CITY"
        const val EXTRA_PREFILL_SHARE_QUERY = "PREFILL_SHARE_QUERY"

        const val EXTRA_MUST_VISIT_TOKEN = "MUST_VISIT_TOKEN"

        const val LEGACY_EXTRA_PLACE_NAME = "PLACE_NAME"

        const val EXTRA_FILTER_AUTHOR_ID = "FILTER_AUTHOR_ID"

    }
}
