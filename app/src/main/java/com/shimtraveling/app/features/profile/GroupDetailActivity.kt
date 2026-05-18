package com.shimtraveling.features.profile

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Group
import com.shimtraveling.data.model.Photo
import com.shimtraveling.data.model.User
import com.shimtraveling.databinding.ActivityGroupDetailBinding
import com.shimtraveling.features.photo.PhotoDetailActivity
import com.shimtraveling.features.share.PublishPhotoActivity
import com.shimtraveling.ui.adapter.PhotoAdapter
import com.shimtraveling.ui.viewmodel.PhotoViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GroupDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailBinding
    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var photoViewModel: PhotoViewModel
    private var group: Group? = null
    private var currentUserId: String? = null
    private var isOwner: Boolean = false
    private var allPhotos: List<Photo> = emptyList()

    private val photoDetailLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val photoId = data?.getStringExtra("photoId")
            val isLiked = data?.getBooleanExtra("isLiked", false) ?: false
            val likesCount = data?.getIntExtra("likesCount", 0) ?: 0
            if (photoId != null) {
                photoAdapter.updatePhoto(photoId, isLiked, likesCount)

                allPhotos = allPhotos.map {
                    if (it.id == photoId) it.copy(isLiked = isLiked, likes = likesCount) else it
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoViewModel = ViewModelProvider(this, PhotoViewModel.Factory(application))[PhotoViewModel::class.java]

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupFab()
        setupLeaveButton()
        loadGroupData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupRecyclerView() {
        photoAdapter = PhotoAdapter(
            onPhotoClick = { photo -> onPhotoClick(photo) },
            onLikeClick = { photo ->
                val result = photoViewModel.toggleLike(photo)
                photoAdapter.updatePhoto(photo.id, result.first, result.second)
            }
        )
        binding.photosRecycler.apply {
            layoutManager = GridLayoutManager(this@GroupDetailActivity, 2)
            adapter = photoAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupSearch() {
        binding.searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                filterPhotos(v.text?.toString() ?: "")
                true
            } else false
        }
        binding.searchInput.doOnTextChanged { text, _, _, _ ->
            filterPhotos(text?.toString() ?: "")
        }
    }

    private fun setupFab() {
        binding.publishButton.setOnClickListener {
            val intent = Intent(this, PublishPhotoActivity::class.java)
            intent.putExtra("groupId", group?.id)
            startActivity(intent)
        }
    }

    private fun filterPhotos(query: String) {
        val filtered = if (query.isBlank()) {
            allPhotos
        } else {
            allPhotos.filter { photo ->
                photo.placeName.contains(query, true) ||
                        photo.description?.contains(query, true) == true ||
                        photo.tags.any { it.contains(query, true) }
            }
        }
        displayPhotos(filtered)
    }

    private fun setupTagChips(tags: List<String>) {
        binding.filterChips.removeAllViews()

        val allChip = Chip(this).apply {
            text = "Tout"
            isCheckable = true
            isChecked = true
            setOnClickListener {
                filterPhotos(binding.searchInput.text?.toString() ?: "")
            }
        }
        binding.filterChips.addView(allChip)

        tags.forEach { tag ->
            val chip = Chip(this).apply {
                text = tag.replaceFirstChar { it.uppercase() }
                isCheckable = true
                setOnClickListener {
                    filterByTag(tag)
                }
            }
            binding.filterChips.addView(chip)
        }
    }

    private fun filterByTag(tag: String) {
        val filtered = allPhotos.filter { it.tags.contains(tag) }
        displayPhotos(filtered)
    }

    private fun reloadGroupPhotos() {
        val g = group ?: return
        val userId = currentUserId ?: return
        lifecycleScope.launch {
            TravelingApp.getInstance().photoRepository.getPhotosByGroupWithLikeStatus(g.id, userId).collect { result ->
                result.onSuccess { photos ->
                    allPhotos = photos
                    photoAdapter.submitList(photos)
                    setupTagChips(extractTags(photos))
                }
            }
        }
    }

    private fun extractTags(photos: List<Photo>): List<String> {
        return photos.flatMap { it.tags }.distinct().sorted()
    }

    private fun setupLeaveButton() {
        binding.leaveButton.setOnClickListener { leaveGroup() }
    }

    private fun loadGroupData() {
        @Suppress("DEPRECATION")
        group = intent.getParcelableExtra("group")

        group?.let { g ->
            binding.groupName.text = g.name

            if (!g.description.isNullOrBlank()) {
                binding.groupDescription.text = g.description
                binding.groupDescription.visibility = View.VISIBLE
            } else {
                binding.groupDescription.visibility = View.GONE
            }

            updateMemberCount(g)

            lifecycleScope.launch {
                val userResult = TravelingApp.getInstance().userRepository.getCurrentUser()
                userResult.collect { result ->
                    result.onSuccess { user ->
                        currentUserId = user?.id
                        isOwner = user != null && g.ownerId == user.id
                        val isMember = user != null && g.members.contains(user.id)

                        if (isOwner) {
                            binding.leaveButton.visibility = View.VISIBLE
                            binding.leaveButton.text = getString(R.string.leave_group_owner)
                            binding.manageMembersButton.visibility = View.VISIBLE
                            binding.manageMembersButton.setOnClickListener {
                                showManageMembersDialog(g)
                            }
                        } else if (isMember) {
                            binding.leaveButton.visibility = View.VISIBLE
                            binding.leaveButton.text = getString(R.string.leave_group)
                            binding.manageMembersButton.visibility = View.GONE
                        } else {
                            finish()
                        }

                        loadPhotos(g)
                    }
                }
            }
        }
    }

    private fun updateMemberCount(group: Group) {
        val memberCount = group.members.size
        binding.memberCount.text = resources.getQuantityString(R.plurals.members_count, memberCount, memberCount)
    }

    private fun loadPhotos(group: Group) {
        lifecycleScope.launch {
            val userId = currentUserId
            if (userId != null) {
                TravelingApp.getInstance().photoRepository.getPhotosByGroupWithLikeStatus(group.id, userId).collect { result ->
                    result.onSuccess { photos ->
                        allPhotos = photos
                        displayPhotos(photos)
                        setupTagChips(extractTags(photos))
                    }
                }
            } else {
                TravelingApp.getInstance().firestoreRepository.getPhotosByGroup(group.id).collect { result ->
                    result.onSuccess { photos ->
                        allPhotos = photos
                        displayPhotos(photos)
                        setupTagChips(extractTags(photos))
                    }
                }
            }
        }
    }

    private fun displayPhotos(photos: List<Photo>) {
        photoAdapter.submitList(photos)
        binding.emptyPhotos.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onPhotoClick(photo: Photo) {
        val intent = Intent(this, PhotoDetailActivity::class.java)
        intent.putExtra("photo", photo)
        photoDetailLauncher.launch(intent)
    }

    private fun leaveGroup() {
        val g = group ?: return

        if (isOwner) {
            handleOwnerLeave(g)
        } else {
            handleMemberLeave(g)
        }
    }

    private fun handleOwnerLeave(group: Group) {
        val otherMembers = group.members.filter { it != group.ownerId }

        if (otherMembers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_group_title))
                .setMessage(getString(R.string.delete_group_message))
                .setPositiveButton(getString(R.string.delete_group_confirm)) { _, _ ->
                    deleteGroup(group)
                }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        } else {
            lifecycleScope.launch {
                val members = mutableListOf<User>()
                for (memberId in otherMembers) {
                    val result = TravelingApp.getInstance().firestoreRepository.getUserById(memberId)
                    result.getOrNull()?.let { members.add(it) }
                }

                val memberNames = members.map { it.username }.toTypedArray()

                AlertDialog.Builder(this@GroupDetailActivity)
                    .setTitle(getString(R.string.select_new_owner_title))
                    .setItems(memberNames) { _, which ->
                        val newOwner = members[which]
                        transferOwnership(group, newOwner)
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
        }
    }

    private fun handleMemberLeave(group: Group) {
        lifecycleScope.launch {
            val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
            userResult.onSuccess { user ->
                if (user != null) {
                    val updatedMembers = group.members - user.id
                    val updatedGroup = group.copy(members = updatedMembers)
                    TravelingApp.getInstance().firestoreRepository.addGroup(updatedGroup)

                    val updatedUserGroups = user.groups - group.id
                    val updatedUser = user.copy(groups = updatedUserGroups)
                    TravelingApp.getInstance().firestoreRepository.updateUser(updatedUser)

                    TravelingApp.getInstance().userRepository.refreshCurrentUser()

                    Toast.makeText(this@GroupDetailActivity, getString(R.string.group_left), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@GroupDetailActivity, getString(R.string.login_required), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun transferOwnership(group: Group, newOwner: User) {
        lifecycleScope.launch {
            val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
            userResult.onSuccess { currentUser ->
                if (currentUser != null) {
                    val updatedMembers = group.members - currentUser.id
                    val updatedGroup = group.copy(ownerId = newOwner.id, members = updatedMembers)
                    TravelingApp.getInstance().firestoreRepository.addGroup(updatedGroup)

                    val updatedUserGroups = currentUser.groups - group.id
                    val updatedUser = currentUser.copy(groups = updatedUserGroups)
                    TravelingApp.getInstance().firestoreRepository.updateUser(updatedUser)

                    TravelingApp.getInstance().userRepository.refreshCurrentUser()

                    Toast.makeText(this@GroupDetailActivity, getString(R.string.ownership_transferred, newOwner.username), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun deleteGroup(group: Group) {
        lifecycleScope.launch {
            for (memberId in group.members) {
                val userResult = TravelingApp.getInstance().firestoreRepository.getUserById(memberId)
                userResult.getOrNull()?.let { member ->
                    val updatedGroups = member.groups - group.id
                    val updatedMember = member.copy(groups = updatedGroups)
                    TravelingApp.getInstance().firestoreRepository.updateUser(updatedMember)
                }
            }

            TravelingApp.getInstance().firestoreRepository.deleteGroup(group.id)

            TravelingApp.getInstance().userRepository.refreshCurrentUser()

            Toast.makeText(this@GroupDetailActivity, getString(R.string.group_deleted), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showManageMembersDialog(group: Group) {
        lifecycleScope.launch {
            val otherMembers = group.members.filter { it != group.ownerId }

            if (otherMembers.isEmpty()) {
                Toast.makeText(this@GroupDetailActivity, getString(R.string.no_results), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val members = mutableListOf<User>()
            for (memberId in otherMembers) {
                val result = TravelingApp.getInstance().firestoreRepository.getUserById(memberId)
                result.getOrNull()?.let { members.add(it) }
            }

            val memberNamesWithAction = members.map { "${it.username} — ${getString(R.string.remove_member)}" }.toTypedArray()

            AlertDialog.Builder(this@GroupDetailActivity)
                .setTitle(getString(R.string.manage_members))
                .setItems(memberNamesWithAction) { _, which ->
                    val memberToRemove = members[which]
                    removeMember(group, memberToRemove)
                }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        }
    }

    private fun removeMember(group: Group, member: User) {
        lifecycleScope.launch {
            val updatedMembers = group.members - member.id
            val updatedGroup = group.copy(members = updatedMembers)
            TravelingApp.getInstance().firestoreRepository.addGroup(updatedGroup)

            val updatedUserGroups = member.groups - group.id
            val updatedMember = member.copy(groups = updatedUserGroups)
            TravelingApp.getInstance().firestoreRepository.updateUser(updatedMember)

            this@GroupDetailActivity.group = updatedGroup
            updateMemberCount(updatedGroup)

            Toast.makeText(this@GroupDetailActivity, getString(R.string.member_removed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
