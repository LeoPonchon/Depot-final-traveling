package com.shimtraveling.features.path

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.NavigationHelper
import com.shimtraveling.core.PdfGenerator
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.data.model.WeatherCondition
import com.shimtraveling.databinding.ActivityPathDetailBinding
import com.shimtraveling.R
import com.shimtraveling.ui.common.openGuide
import com.shimtraveling.ui.adapter.PathDetailFooterAdapter
import com.shimtraveling.ui.adapter.PathDetailHeaderAdapter
import com.shimtraveling.ui.adapter.PathStepAdapter
import com.shimtraveling.ui.viewmodel.PathDetailViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PathDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPathDetailBinding
    private lateinit var navigationHelper: NavigationHelper
    private lateinit var pdfGenerator: PdfGenerator
    private lateinit var stepAdapter: PathStepAdapter
    private lateinit var headerAdapter: PathDetailHeaderAdapter
    private lateinit var footerAdapter: PathDetailFooterAdapter
    private lateinit var viewModel: PathDetailViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPathDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this, PathDetailViewModel.Factory(application)
        )[PathDetailViewModel::class.java]

        @Suppress("DEPRECATION")
        val travelPath = intent.getParcelableExtra<TravelPath>("path")
        travelPath?.let { viewModel.setPath(it) }

        navigationHelper = NavigationHelper(this)
        pdfGenerator = PdfGenerator(this)
        setupToolbar()
        setupRecyclerView()
        observeViewModel()

        travelPath?.city?.let { city -> fetchAndDisplayWeather(city) }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupRecyclerView() {
        headerAdapter = PathDetailHeaderAdapter()
        stepAdapter = PathStepAdapter(
            onStepClick = { step ->
                lifecycleScope.launch {
                    try {
                        val placeResult = TravelingApp.getInstance().placeRepository
                            .getPlaceById(step.placeId).first()
                        placeResult.onSuccess { place ->
                            startActivity(
                                Intent(this@PathDetailActivity,
                                    com.shimtraveling.features.place.PlaceDetailActivity::class.java
                                ).apply { putExtra("place", place) }
                            )
                        }
                        placeResult.onFailure { error ->
                            android.widget.Toast.makeText(
                                this@PathDetailActivity,
                                "Lieu non disponible: ${error.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            this@PathDetailActivity,
                            "Erreur: ${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onGalleryClick = { step ->
                startActivity(
                    Intent(this@PathDetailActivity, StepGalleryActivity::class.java).apply {
                        putExtra(StepGalleryActivity.EXTRA_PLACE_ID, step.placeId)
                        putExtra(StepGalleryActivity.EXTRA_PLACE_NAME, step.placeName)
                        putExtra(StepGalleryActivity.EXTRA_VIDEO_URL, step.videoUrl)
                    }
                )
            },
            onSharePhotoClick = null
        )

        footerAdapter = PathDetailFooterAdapter(
            onNavigate = { viewModel.path.value?.let { path -> navigationHelper.openPathNavigation(path) } },
            onToggleSave = { viewModel.toggleSave() },
            onToggleLike = { viewModel.toggleLike() },
            onShare = { viewModel.path.value?.let { path -> navigationHelper.sharePath(path) } },
            onExportPdf = {
                viewModel.path.value?.let { path ->
                    val pdfFile = pdfGenerator.generatePathPdf(path)
                    pdfGenerator.openPdf(pdfFile)
                    android.widget.Toast.makeText(this, "PDF généré et ouvert", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.pathDetailRecycler.apply {
            layoutManager = LinearLayoutManager(this@PathDetailActivity)
            adapter = ConcatAdapter(headerAdapter, stepAdapter, footerAdapter)
            setHasFixedSize(false)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.path.collectLatest { path ->
                path ?: return@collectLatest
                supportActionBar?.title = path.name
                headerAdapter.setPath(path)
                stepAdapter.submitList(path.steps.sortedBy { it.order })
                loadSharePhotosForSteps(path)
            }
        }

        lifecycleScope.launch {
            viewModel.isLiked.collectLatest { isLiked ->
                val likes = viewModel.path.value?.likes ?: 0
                footerAdapter.setLikeState(isLiked, likes)
            }
        }

        lifecycleScope.launch {
            viewModel.isSaved.collectLatest { isSaved ->
                footerAdapter.setSaveState(isSaved)
            }
        }

        lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let {
                    android.widget.Toast.makeText(this@PathDetailActivity, it, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun fetchAndDisplayWeather(city: String) {
        lifecycleScope.launch {
            try {
                val snapshot = TravelingApp.getInstance().weatherRepository
                    .getCityWeatherSnapshot(city)
                if (snapshot != null) {
                    val conditionLabel = snapshot.condition?.let {
                        TravelingApp.getInstance().weatherRepository.getWeatherDescription(it)
                    } ?: "Météo inconnue"
                    val humidityLabel = snapshot.humidityPercent?.let { "Humidité : $it%" } ?: ""

                    val weatherText = buildString {
                        append("Météo à $city : $conditionLabel")
                        if (humidityLabel.isNotBlank()) append(" • $humidityLabel")
                        snapshot.condition?.let { condition ->
                            when (condition) {
                                WeatherCondition.RAINY -> append(" • Conseil : prévoyez un imperméable.")
                                WeatherCondition.COLD -> append(" • Conseil : couvrez-vous.")
                                WeatherCondition.HOT -> append(" • Conseil : restez hydraté.")
                                else -> {  }
                            }
                        }
                    }

                    headerAdapter.setWeather(weatherText)
                }
            } catch (e: Exception) {
                android.util.Log.w("PathDetailActivity", "Weather fetch failed: ${e.message}")
            }
        }
    }

    private fun loadSharePhotosForSteps(path: TravelPath) {
        lifecycleScope.launch {
            val app = TravelingApp.getInstance()
            val user = app.userRepository.getCurrentUser().first().getOrNull()
            val groups = user?.groups ?: emptyList()
            val uid = user?.id
            val map = mutableMapOf<String, List<com.shimtraveling.data.model.Photo>>()
            for (st in path.steps.distinctBy { it.placeId }) {
                app.firestoreRepository.getVisiblePhotosForPlace(st.placeId, uid, groups)
                    .onSuccess { map[st.placeId] = it }
            }
            stepAdapter.updateSharePhotos(map)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.help_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                openGuide()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
