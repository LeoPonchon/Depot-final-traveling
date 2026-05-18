package com.shimtraveling.ui.path

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.shimtraveling.R
import com.shimtraveling.data.model.ActivityType
import com.shimtraveling.data.model.EffortLevel
import com.shimtraveling.data.model.PathPreferences
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.databinding.FragmentPathBinding
import com.shimtraveling.features.path.PathDetailActivity
import com.shimtraveling.ui.adapter.PathAdapter
import com.shimtraveling.ui.main.MainActivity
import com.shimtraveling.ui.viewmodel.PathViewModel
import kotlinx.coroutines.launch
import java.util.Locale

class PathFragment : Fragment() {

    companion object {
        const val ARG_PREFILL_CITY = "prefill_city"
        const val ARG_MUST_VISIT = "must_visit"
    }

    private var _binding: FragmentPathBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: PathViewModel
    private lateinit var pathAdapter: PathAdapter
    private val selectedActivities = mutableSetOf<ActivityType>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPathBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.helpButton.setOnClickListener {
            findNavController().navigate(R.id.navigation_guide)
        }
        setupViewModel()
        applyMustVisitFromArgs()
        setupCitySelector()
        setupActivityChips()
        setupEffortSlider()
        setupStepsSlider()
        setupWeatherTolerances()
        setupDepartureTimeInput()
        setupBridgeButton()
        setupRecyclerView()
        setupGenerateButton()
        setupRegenerateButton()
        observeData()
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this, PathViewModel.Factory(requireActivity().application))[PathViewModel::class.java]
    }

    private fun applyMustVisitFromArgs() {
        val fromArgs = arguments?.getString(ARG_MUST_VISIT)?.trim().orEmpty()
        val fromActivity = (activity as? MainActivity)?.intent?.getStringExtra(MainActivity.EXTRA_MUST_VISIT_TOKEN)?.trim().orEmpty()
        val must = fromArgs.ifBlank { fromActivity }
        if (must.isNotEmpty()) {
            val existing = binding.mustVisitInput.text?.toString().orEmpty()
            if (existing.isBlank()) {
                binding.mustVisitInput.setText(must)
            }
        }
    }

    private fun setupCitySelector() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.availableCities.collect { cities ->
                if (cities.isNotEmpty()) {
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, cities)
                    binding.cityInput.setAdapter(adapter)
                    binding.cityInput.setOnItemClickListener { _, _, position, _ ->
                        viewModel.setSelectedCity(cities[position])
                    }
                    val prefill = arguments?.getString(ARG_PREFILL_CITY)?.trim().orEmpty()
                        .ifBlank {
                            (activity as? MainActivity)?.intent?.getStringExtra(MainActivity.EXTRA_PREFILL_CITY)?.trim().orEmpty()
                        }
                    if (prefill.isNotEmpty()) {
                        binding.cityInput.setText(prefill, false)
                        viewModel.setSelectedCity(prefill)
                    } else if (cities.contains("Paris")) {
                        binding.cityInput.setText("Paris", false)
                        viewModel.setSelectedCity("Paris")
                    }
                }
            }
        }
    }

    private fun setupActivityChips() {
        ActivityType.values().forEach { activity ->
            selectedActivities.add(activity)
            val chip = Chip(requireContext()).apply {
                text = activity.getDisplayName()
                isCheckable = true
                isChecked = true
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedActivities.add(activity)
                    } else {
                        selectedActivities.remove(activity)
                    }
                }
            }
            binding.activitiesChips.addView(chip)
        }
    }

    private fun setupEffortSlider() {
        binding.effortSlider.value = 2f
        updateEffortLabel(2f)
        binding.effortSlider.addOnChangeListener { _, value, _ ->
            updateEffortLabel(value)
        }
    }

    private fun updateEffortLabel(value: Float) {
        val effort = when (value.toInt()) {
            1 -> EffortLevel.LOW
            2 -> EffortLevel.MEDIUM
            3 -> EffortLevel.HIGH
            else -> EffortLevel.MEDIUM
        }
        binding.effortLabel.text = "${effort.getDisplayName()} - ${effort.getDescription()}"
    }

    private fun setupStepsSlider() {
        binding.stepsSlider.value = 4f
        updateStepsLabel(4f)
        binding.stepsSlider.addOnChangeListener { _, value, _ ->
            val clamped = value.coerceAtLeast(2f)
            if (clamped != value) {
                binding.stepsSlider.value = clamped
                return@addOnChangeListener
            }
            updateStepsLabel(clamped)
        }
    }

    private fun updateStepsLabel(value: Float) {
        val steps = value.toInt()
        binding.stepsLabel.text = "${steps} étape${if (steps > 1) "s" else ""}"
    }

    private fun setupWeatherTolerances() {
        binding.avoidColdSwitch.isChecked = false
        binding.avoidHeatSwitch.isChecked = false
        binding.avoidRainSwitch.isChecked = false
        binding.avoidHumiditySwitch.isChecked = false
    }

    private fun setupDepartureTimeInput() {
        binding.departureTimeInput.setText("09:00")

        val open = View.OnClickListener { openDepartureTimePicker() }
        binding.departureTimeInput.setOnClickListener(open)
        binding.departureTimeLayout.setOnClickListener(open)
    }

    private fun openDepartureTimePicker() {
        val currentMinutes = parseDepartureTime(binding.departureTimeInput.text?.toString()) ?: (9 * 60)
        val currentHour = (currentMinutes / 60).coerceIn(0, 23)
        val currentMinute = (currentMinutes % 60).coerceIn(0, 59)

        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(currentHour)
            .setMinute(currentMinute)
            .setTitleText(getString(R.string.path_departure_time))
            .build()

        picker.addOnPositiveButtonClickListener {
            binding.departureTimeInput.setText(
                String.format(Locale.getDefault(), "%02d:%02d", picker.hour, picker.minute)
            )
        }

        picker.show(parentFragmentManager, "departure_time_picker")
    }

    private fun setupBridgeButton() {
        binding.openShareFromPreferencesButton.setOnClickListener { openTravelShareFromPreferences() }
    }

    private fun setupRecyclerView() {
        pathAdapter = PathAdapter(
            onPathClick = { path -> onPathClick(path) }
        )
        binding.pathsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = pathAdapter
        }
    }

    private fun setupGenerateButton() {
        binding.generateButton.setOnClickListener {
            val city = binding.cityInput.text.toString()
            if (city.isBlank()) {
                android.widget.Toast.makeText(requireContext(), "Veuillez sélectionner une ville", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tags = binding.tagsInput.text.toString().split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val mustVisit = binding.mustVisitInput.text.toString().split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val departureMinutes = parseDepartureTime(binding.departureTimeInput.text?.toString())
                ?: run {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Heure de départ invalide (format HH:mm)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

            val preferences = PathPreferences(
                activities = selectedActivities.toList(),
                mustVisitPlaces = mustVisit,
                tags = tags,
                maxBudget = if (binding.budgetInput.text.isNullOrBlank()) null else binding.budgetInput.text.toString().toDoubleOrNull(),
                maxDurationMinutes = (binding.durationSlider.value * 60).toInt(),
                maxEffortLevel = when (binding.effortSlider.value.toInt()) {
                    1 -> EffortLevel.LOW
                    2 -> EffortLevel.MEDIUM
                    3 -> EffortLevel.HIGH
                    else -> EffortLevel.MEDIUM
                },
                maxSteps = binding.stepsSlider.value.toInt(),
                avoidCold = binding.avoidColdSwitch.isChecked,
                avoidHeat = binding.avoidHeatSwitch.isChecked,
                avoidRain = binding.avoidRainSwitch.isChecked,
                avoidHumidity = binding.avoidHumiditySwitch.isChecked,
                departureTimeMinutes = departureMinutes,
                audienceSeniors = binding.audienceSeniorsSwitch.isChecked,
                audienceChildren = binding.audienceChildrenSwitch.isChecked,
                audienceReducedMobility = binding.audienceMobilitySwitch.isChecked,
                audienceHealthSensitivity = binding.audienceHealthSwitch.isChecked
            )
            viewModel.generatePaths(preferences)
        }
    }

    private fun setupRegenerateButton() {
        binding.regenerateButton.setOnClickListener {
            viewModel.regeneratePaths()
        }
    }

    private fun openTravelShareFromPreferences() {
        val city = binding.cityInput.text?.toString()?.trim().orEmpty()
        val mustVisit = binding.mustVisitInput.text?.toString()?.trim().orEmpty()
        val tags = binding.tagsInput.text?.toString()?.trim().orEmpty()
        val query = listOf(city, mustVisit, tags)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        if (query.isBlank()) {
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.path_open_share_from_preferences_empty),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        startActivity(Intent(requireContext(), MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_SHARE, true)
            putExtra(MainActivity.EXTRA_PREFILL_SHARE_QUERY, query)
        })
    }

    private fun parseDepartureTime(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.paths.collect { result ->
                result.onSuccess { paths ->
                    android.util.Log.d("PathFragment", "Received ${paths.size} paths")
                    pathAdapter.submitList(paths)
                    binding.pathsContainer.visibility = View.VISIBLE
                    binding.errorMessage.visibility = View.GONE
                }
                result.onFailure { error ->
                    android.util.Log.e("PathFragment", "Error loading paths", error)
                    binding.pathsContainer.visibility = View.GONE
                    binding.pathsNote.visibility = View.GONE
                    binding.errorMessage.text = error.message ?: "Une erreur est survenue"
                    binding.errorMessage.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.infoMessage.collect { message ->
                if (message.isNullOrBlank()) {
                    binding.pathsNote.visibility = View.GONE
                    return@collect
                }
                if (binding.pathsContainer.visibility != View.VISIBLE) return@collect
                binding.pathsNote.text = message
                binding.pathsNote.visibility = View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                binding.loadingMessage.visibility = if (isLoading) View.VISIBLE else View.GONE
                binding.generateButton.isEnabled = !isLoading
                binding.regenerateButton.isEnabled = !isLoading
                if (isLoading) {
                    binding.errorMessage.visibility = View.GONE
                    binding.pathsNote.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.generationProgress.collect { progress ->
                if (progress == null) {
                    binding.loadingMessage.text = getString(R.string.path_loading_message)
                    return@collect
                }
                binding.loadingMessage.text = buildString {
                    append(progress.message)
                    if (progress.current != null && progress.total != null) {
                        append(" ")
                        append("(")
                        append(progress.current)
                        append("/")
                        append(progress.total)
                        append(")")
                    }
                }
            }
        }
    }

    private fun onPathClick(path: TravelPath) {
        val intent = Intent(requireContext(), PathDetailActivity::class.java)
        intent.putExtra("path", path)
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
