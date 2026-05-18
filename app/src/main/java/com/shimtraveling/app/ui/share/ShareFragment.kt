package com.shimtraveling.ui.share

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.shimtraveling.R
import com.shimtraveling.data.model.Photo
import com.shimtraveling.data.model.PhotoCategory
import com.shimtraveling.databinding.FragmentShareBinding
import androidx.navigation.fragment.findNavController
import com.shimtraveling.features.photo.PhotoDetailActivity
import com.shimtraveling.features.share.PublishPhotoActivity
import com.shimtraveling.features.share.ShareMapActivity
import com.shimtraveling.ui.adapter.PhotoAdapter
import com.shimtraveling.ui.main.MainActivity
import com.shimtraveling.ui.viewmodel.PhotoViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.content.res.ColorStateList
import android.widget.Toast

class ShareFragment : Fragment() {

    private var _binding: FragmentShareBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: PhotoViewModel
    private lateinit var photoAdapter: PhotoAdapter
    private var nearbyChipSuppress = false
    private var nearbyPickInProgress = false
    private var pendingNearbyRadiusKm: Double = 25.0

    private val nearbyPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        nearbyPickInProgress = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val lat = result.data?.getDoubleExtra(ShareMapActivity.EXTRA_PICKED_LAT, Double.NaN) ?: Double.NaN
            val lng = result.data?.getDoubleExtra(ShareMapActivity.EXTRA_PICKED_LNG, Double.NaN) ?: Double.NaN
            if (!lat.isNaN() && !lng.isNaN()) {
                viewModel.filterByLocation(lat, lng, pendingNearbyRadiusKm)
                return@registerForActivityResult
            }
        }

        nearbyChipSuppress = true
        binding.nearbyFilterChip.isChecked = false
        nearbyChipSuppress = false
        viewModel.clearLocationFilter()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentShareBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val photoDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val photoId = data?.getStringExtra("photoId")
            val isLiked = data?.getBooleanExtra("isLiked", false) ?: false
            val likesCount = data?.getIntExtra("likesCount", 0) ?: 0
            if (photoId != null) {
                photoAdapter.updatePhoto(photoId, isLiked, likesCount)
            }
        }
    }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = com.shimtraveling.ui.common.VoiceSearch.extractFirstResult(result.data) ?: return@registerForActivityResult
            binding.searchInput.setText(spokenText)
            viewModel.searchPhotos(spokenText)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.helpButton.setOnClickListener {
            findNavController().navigate(R.id.navigation_guide)
        }
        setupViewModel()
        setupRecyclerView()
        setupSearch()
        setupBridgeActions()
        setupRandomButton()
        setupVoiceSearch()
        setupViewToggle()
        setupCategoryChips()
        setupExtraFilterChips()
        observeData()
        maybeConsumeSharePrefill()
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(
            this, PhotoViewModel.Factory(requireActivity().application)
        )[PhotoViewModel::class.java]
    }

    private fun setupRecyclerView() {
        photoAdapter = PhotoAdapter(
            onPhotoClick = { photo -> onPhotoClick(photo) },
            onLikeClick = { photo -> onLikeClick(photo) }
        )
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = photoAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
        }
    }

    private fun onPhotoClick(photo: Photo) {
        val intent = Intent(requireContext(), PhotoDetailActivity::class.java)
        intent.putExtra("photo", photo)
        photoDetailLauncher.launch(intent)
    }

    private fun onLikeClick(photo: Photo) {
        val result = viewModel.toggleLike(photo)
        photoAdapter.updatePhoto(photo.id, result.first, result.second)
    }

    private fun setupSearch() {
        binding.searchInput.doOnTextChanged { text, _, _, _ ->
            viewModel.searchPhotos(text?.toString() ?: "")
        }
        binding.voiceSearchButton.setOnClickListener { startVoiceSearch() }
    }

    private fun setupBridgeActions() {
        binding.openPathFromSearchButton.setOnClickListener { openTravelPathFromCurrentSearch() }
    }

    private fun openTravelPathFromCurrentSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            Toast.makeText(requireContext(), R.string.share_open_path_from_search_empty, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(requireContext(), MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_PATH, true)
                putExtra(MainActivity.EXTRA_MUST_VISIT_TOKEN, query)
            }
        )
    }

    private fun setupRandomButton() {
        binding.randomButton.setOnClickListener {
            Toast.makeText(requireContext(), R.string.share_random, Toast.LENGTH_SHORT).show()
            viewModel.getRandomPhoto()
        }
        binding.publishButton.setOnClickListener {
            startActivity(Intent(requireContext(), PublishPhotoActivity::class.java))
        }
    }

    private fun updateFloatingActionLayout(showPublishButton: Boolean) {
        binding.publishButton.visibility = if (showPublishButton) View.VISIBLE else View.GONE

        val endMarginDp = if (showPublishButton) 88 else 16
        val endMarginPx = (endMarginDp * resources.displayMetrics.density).toInt()
        val bottomMarginPx = (16 * resources.displayMetrics.density).toInt()

        (binding.randomButton.layoutParams as ViewGroup.MarginLayoutParams).apply {
            marginEnd = endMarginPx
            bottomMargin = bottomMarginPx
        }.also {
            binding.randomButton.layoutParams = it
        }
    }

    private fun setupVoiceSearch() {
    }

    private fun setupViewToggle() {
        binding.viewToggle.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 1) openMapView()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }


    private fun openMapView() {
        val currentPhotos = viewModel.photos.value.getOrNull() ?: emptyList()
        val intent = Intent(requireContext(), ShareMapActivity::class.java).apply {
            putParcelableArrayListExtra("photos", ArrayList(currentPhotos))
            putExtra(ShareMapActivity.EXTRA_MODE, ShareMapActivity.MODE_PHOTOS)
        }
        startActivity(intent)
        binding.viewToggle.selectTab(binding.viewToggle.getTabAt(0))
    }

    private fun startVoiceSearch() {
        val intent = com.shimtraveling.ui.common.VoiceSearch.buildIntent(
            prompt = "Dites le nom d'un lieu, une ville ou un type de lieu...",
            locale = Locale.getDefault()
        )
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Recherche vocale non disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photos.collect { result ->
                result.onSuccess { photos ->
                    photoAdapter.submitList(photos)
                    binding.emptyView.text = getString(R.string.no_results)
                    binding.emptyView.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                }
                result.onFailure { error ->
                    photoAdapter.submitList(emptyList())
                    binding.emptyView.text = error.message ?: getString(R.string.no_results)
                    binding.emptyView.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), error.message ?: getString(R.string.no_results), Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiMessage.collect { msg ->
                if (msg.isNullOrBlank()) return@collect
                binding.emptyView.text = msg
                binding.emptyView.visibility = View.VISIBLE
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                viewModel.clearUiMessage()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.availableTags.collect { tags ->
                setupTagChips(tags)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedTags.collect { selectedTags ->
                updateTagChipsSelection(selectedTags)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCategory.collect { selected ->
                updateCategoryChipsSelection(selected)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoggedIn.collect { logged ->
                updateFloatingActionLayout(logged)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hasNearbyFilter.collect { active ->
                if (nearbyPickInProgress) return@collect
                nearbyChipSuppress = true
                binding.nearbyFilterChip.isChecked = active
                nearbyChipSuppress = false
            }
        }
    }

    private fun setupCategoryChips() {
        binding.categoryChips.removeAllViews()
        val allTypes = buildFilterChip("all_cat", checkable = true).apply {
            text = getString(R.string.share_filter_all_types)
            isChecked = viewModel.selectedCategory.value == null
            setOnClickListener { viewModel.filterByCategory(null) }
        }
        binding.categoryChips.addView(allTypes)
        PhotoCategory.values().forEach { cat ->
            val chip = buildFilterChip("cat_${cat.name}", checkable = true).apply {
                text = cat.getDisplayName()
                isChecked = viewModel.selectedCategory.value == cat
                setOnClickListener { viewModel.filterByCategory(cat) }
            }
            binding.categoryChips.addView(chip)
        }
    }

    private fun setupExtraFilterChips() {
        applyChipStyle(binding.nearbyFilterChip, checkable = true)
        binding.nearbyFilterChip.setOnCheckedChangeListener { _, isChecked ->
            if (nearbyChipSuppress) return@setOnCheckedChangeListener
            if (isChecked) {
                showNearbyPicker()
            } else {
                viewModel.clearLocationFilter()
            }
        }

        applyChipStyle(binding.periodFilterChip, checkable = false)
        binding.periodFilterChip.setOnClickListener { showDateRangePicker() }
        binding.periodFilterChip.setOnLongClickListener {
            viewModel.clearDateRangeFilter()
            binding.periodFilterChip.text = getString(R.string.share_filter_period)
            Toast.makeText(requireContext(), R.string.share_filter_period_clear, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun resetPeriodChipLabel() {
        binding.periodFilterChip.text = getString(R.string.share_filter_period)
    }

    private fun showNearbyPicker() {
        nearbyPickInProgress = true
        val ctx = requireContext()
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val label = TextView(ctx).apply {
            text = "${getString(R.string.share_filter_radius_label)} : ${pendingNearbyRadiusKm.toInt()}"
        }
        val slider = Slider(ctx).apply {
            valueFrom = 1f
            valueTo = 50f
            stepSize = 1f
            value = pendingNearbyRadiusKm.toFloat().coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, v, _ ->
                label.text = "${getString(R.string.share_filter_radius_label)} : ${v.toInt()}"
            }
        }
        container.addView(label)
        container.addView(slider)

        fun cancelNearby() {
            nearbyPickInProgress = false
            nearbyChipSuppress = true
            binding.nearbyFilterChip.isChecked = false
            nearbyChipSuppress = false
            viewModel.clearLocationFilter()
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.share_filter_nearby))
            .setMessage(getString(R.string.share_filter_center_picker_instruction))
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelNearby() }
            .setOnCancelListener { cancelNearby() }
            .setPositiveButton(R.string.share_filter_center_button) { _, _ ->
                pendingNearbyRadiusKm = slider.value.toDouble()
                nearbyPickerLauncher.launch(
                    Intent(ctx, ShareMapActivity::class.java).apply {
                        putExtra(ShareMapActivity.EXTRA_MODE, ShareMapActivity.MODE_PICK_LOCATION)
                    }
                )
            }
            .show()
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.share_filter_period))
            .build()
        picker.addOnPositiveButtonClickListener { range ->
            val startCal = Calendar.getInstance().also {
                it.timeInMillis = range.first
                it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0)
                it.set(Calendar.SECOND, 0); it.set(Calendar.MILLISECOND, 0)
            }
            val endCal = Calendar.getInstance().also {
                it.timeInMillis = range.second
                it.set(Calendar.HOUR_OF_DAY, 23); it.set(Calendar.MINUTE, 59)
                it.set(Calendar.SECOND, 59); it.set(Calendar.MILLISECOND, 999)
            }
            viewModel.filterByDateRange(startCal.time, endCal.time)
            val fmt = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
            binding.periodFilterChip.text = "${fmt.format(startCal.time)} – ${fmt.format(endCal.time)}"
        }
        picker.show(parentFragmentManager, "share_date_range")
    }

    private fun updateCategoryChipsSelection(selected: PhotoCategory?) {
        binding.categoryChips.children.forEachIndexed { index, view ->
            if (view is Chip) {
                view.isChecked = when {
                    index == 0 -> selected == null
                    else -> PhotoCategory.values().getOrNull(index - 1) == selected
                }
            }
        }
    }

    private fun applyChipStyle(chip: Chip, checkable: Boolean = true) {
        val ctx = requireContext()
        chip.isCheckable = checkable
        chip.chipStrokeWidth = 1f
        chip.chipStrokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, R.color.outline)
        )
        chip.chipBackgroundColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                ContextCompat.getColor(ctx, R.color.primary),
                ContextCompat.getColor(ctx, R.color.white)
            )
        )
        chip.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(ctx, R.color.white),
                    ContextCompat.getColor(ctx, R.color.text_primary)
                )
            )
        )
    }

    private fun setupTagChips(tags: List<String>) {
        binding.filterChips.removeAllViews()

        val allChip = buildFilterChip("Tout").apply {
            text = "Tout"
            isCheckable = true
            isChecked = viewModel.selectedTags.value.isEmpty()
            setOnClickListener {
                resetPeriodChipLabel()
                viewModel.clearFilters()
            }
        }
        binding.filterChips.addView(allChip)

        tags.forEach { tag ->
            val chip = buildFilterChip(tag).apply {
                text = tag.replaceFirstChar { it.uppercase() }
                isCheckable = true
                isChecked = viewModel.selectedTags.value.contains(tag)
                setOnClickListener { viewModel.toggleTag(tag) }
            }
            binding.filterChips.addView(chip)
        }
    }

    private fun buildFilterChip(tag: String, checkable: Boolean = true): Chip {
        val context = requireContext()
        val chip = Chip(context, null, com.google.android.material.R.style.Widget_Material3_Chip_Filter)

        chip.text = tag
        chip.isClickable = true
        applyChipStyle(chip, checkable)
        return chip
    }

    private fun updateTagChipsSelection(selectedTags: Set<String>) {
        binding.filterChips.children.forEachIndexed { index, view ->
            if (view is Chip) {
                if (index == 0) {
                    view.isChecked = selectedTags.isEmpty()
                } else {
                    val tag = view.text.toString().lowercase()
                    view.isChecked = selectedTags.contains(tag)
                }
            }
        }
    }

    private fun maybeConsumeSharePrefill() {
        val fromArgs = arguments?.getString(MainActivity.EXTRA_PREFILL_SHARE_QUERY)?.trim().orEmpty()
        val fromIntent = (activity as? MainActivity)
            ?.intent?.getStringExtra(MainActivity.EXTRA_PREFILL_SHARE_QUERY)?.trim().orEmpty()
        val query = fromIntent.ifBlank { fromArgs }
        if (query.isBlank()) return
        arguments?.remove(MainActivity.EXTRA_PREFILL_SHARE_QUERY)
        (activity as? MainActivity)?.intent?.removeExtra(MainActivity.EXTRA_PREFILL_SHARE_QUERY)
        (activity as? MainActivity)?.intent?.removeExtra(MainActivity.EXTRA_OPEN_SHARE)
        if (binding.searchInput.text?.toString() != query) {
            binding.searchInput.setText(query)
        }
        viewModel.searchPhotos(query)
    }

    override fun onResume() {
        super.onResume()
        val authorId = (activity as? MainActivity)
            ?.intent?.getStringExtra(MainActivity.EXTRA_FILTER_AUTHOR_ID)?.trim().orEmpty()
        if (authorId.isNotEmpty()) {
            (activity as? MainActivity)?.intent?.removeExtra(MainActivity.EXTRA_FILTER_AUTHOR_ID)
            resetPeriodChipLabel()
            viewModel.filterByAuthor(authorId)
            Toast.makeText(requireContext(), R.string.share_filter_author_applied, Toast.LENGTH_SHORT).show()
        }
        maybeConsumeSharePrefill()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
