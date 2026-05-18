package com.shimtraveling.features.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Group
import com.shimtraveling.databinding.ActivityGroupsBinding
import com.shimtraveling.ui.adapter.GroupAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GroupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupsBinding
    private lateinit var groupAdapter: GroupAdapter
    private var currentUserId: String? = null
    private val app by lazy { TravelingApp.getInstance() }
    private val createGroupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadGroups()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        loadGroups()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.profile_groups)
    }

    private fun setupRecyclerView() {
        groupAdapter = GroupAdapter(
            currentUserId = null,
            onGroupClick = { group -> onGroupClick(group) },
            onJoinClick = { group -> joinGroup(group) },
            onLeaveClick = { group -> leaveGroup(group) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@GroupsActivity)
            adapter = groupAdapter
        }
    }

    private fun setupButtons() {
        binding.createGroupButton.setOnClickListener {
            val intent = Intent(this, CreateGroupActivity::class.java)
            createGroupLauncher.launch(intent)
        }
    }

    private fun loadGroups() {
        lifecycleScope.launch {
            val result = app.userRepository.getCurrentUser().first()
            result.onSuccess { user ->
                if (user != null) {
                    currentUserId = user.id
                    loadAllGroups(user.id, user.groups)
                } else {
                    currentUserId = null
                    loadAllGroups(null, emptyList())
                }
            }
        }
    }

    private suspend fun loadAllGroups(userId: String?, userGroupIds: List<String>) {
        val allGroupsResult = app.firestoreRepository.getAllGroups()
        allGroupsResult.onSuccess { groups ->
            val photoCounts = mutableMapOf<String, Int>()
            for (group in groups) {
                var count = group.photoCount

                val canRead = userId != null && (group.ownerId == userId || userGroupIds.contains(group.id))
                if (canRead && count <= 0) {
                    val serverCount = app.firestoreRepository.getGroupPhotoCount(group.id).getOrNull()
                    if (serverCount != null && serverCount != count) {
                        count = serverCount
                        app.firestoreRepository.setGroupPhotoCount(group.id, serverCount)
                    }
                }

                photoCounts[group.id] = count
            }
            groupAdapter.updateData(groups, userId, userGroupIds, photoCounts)
            binding.emptyView.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            if (groups.isEmpty()) {
                binding.emptyView.text = "Aucun groupe disponible"
            }
        }
    }

    private fun onGroupClick(group: Group) {
        val intent = Intent(this, GroupDetailActivity::class.java)
        intent.putExtra("group", group)
        startActivity(intent)
    }

    private fun joinGroup(group: Group) {
        lifecycleScope.launch {
            val userResult = app.userRepository.getCurrentUser().first()
            userResult.onSuccess { user ->
                if (user != null) {
                    if (group.members.contains(user.id)) {
                        Toast.makeText(this@GroupsActivity, "Vous êtes déjà membre de ce groupe", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    updateGroupMembership(group, user.id, join = true)

                    Toast.makeText(this@GroupsActivity, "Vous avez rejoint le groupe", Toast.LENGTH_SHORT).show()
                    loadGroups()
                } else {
                    Toast.makeText(this@GroupsActivity, "Connectez-vous pour rejoindre un groupe", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun leaveGroup(group: Group) {
        lifecycleScope.launch {
            val userResult = app.userRepository.getCurrentUser().first()
            userResult.onSuccess { user ->
                if (user != null) {
                    if (group.ownerId == user.id) {
                        Toast.makeText(this@GroupsActivity, "Vous ne pouvez pas quitter un groupe dont vous êtes le propriétaire", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    updateGroupMembership(group, user.id, join = false)

                    Toast.makeText(this@GroupsActivity, "Vous avez quitté le groupe", Toast.LENGTH_SHORT).show()
                    loadGroups()
                } else {
                    Toast.makeText(this@GroupsActivity, "Connectez-vous pour quitter un groupe", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun updateGroupMembership(group: Group, userId: String, join: Boolean) {
        val updatedMembers = if (join) group.members + userId else group.members - userId
        app.firestoreRepository.addGroup(group.copy(members = updatedMembers))

        val userResult = app.userRepository.getCurrentUser().first()
        userResult.getOrNull()?.let { user ->
            val updatedUserGroups = if (join) user.groups + group.id else user.groups - group.id
            app.firestoreRepository.updateUser(user.copy(groups = updatedUserGroups))
        }
        app.userRepository.refreshCurrentUser()
    }

    override fun onResume() {
        super.onResume()
        loadGroups()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
