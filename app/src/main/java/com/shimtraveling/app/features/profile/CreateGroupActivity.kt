package com.shimtraveling.features.profile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.Group
import com.shimtraveling.databinding.ActivityCreateGroupBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*

class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding
    private var isCreating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Créer un groupe"
    }

    private fun setupButtons() {
        binding.createButton.setOnClickListener {
            createGroup()
        }
    }

    private fun createGroup() {
        if (isCreating) return

        val name = binding.nameInput.text.toString().trim()
        val description = binding.descriptionInput.text.toString().trim()

        if (!validateInputs(name)) {
            return
        }

        isCreating = true
        lifecycleScope.launch {
            try {
                val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
                userResult.onSuccess { user ->
                    if (user != null) {
                        val existingGroup = TravelingApp.getInstance().firestoreRepository.getGroupByName(name)
                        if (existingGroup != null) {
                            runOnUiThread {
                                Toast.makeText(this@CreateGroupActivity, "Un groupe avec ce nom existe déjà", Toast.LENGTH_SHORT).show()
                            }
                            isCreating = false
                            return@onSuccess
                        }

                        val groupId = UUID.randomUUID().toString()
                        val group = Group(
                            id = groupId,
                            name = name,
                            description = description.ifBlank { null },
                            ownerId = user.id,
                            members = listOf(user.id),
                            photos = emptyList(),
                            photoCount = 0,
                            createdAt = Date()
                        )

                        val addResult = TravelingApp.getInstance().firestoreRepository.addGroup(group)
                        if (addResult.isSuccess) {
                            val updatedGroups = user.groups + groupId
                            val updatedUser = user.copy(groups = updatedGroups)
                            TravelingApp.getInstance().firestoreRepository.updateUser(updatedUser)

                            TravelingApp.getInstance().userRepository.refreshCurrentUser()

                            runOnUiThread {
                                Toast.makeText(this@CreateGroupActivity, "Groupe créé avec succès", Toast.LENGTH_SHORT).show()
                                setResult(RESULT_OK)
                                finish()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@CreateGroupActivity, "Erreur lors de la création", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@CreateGroupActivity, "Connectez-vous pour créer un groupe", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } finally {
                isCreating = false
            }
        }
    }

    private fun validateInputs(name: String): Boolean {
        var isValid = true

        if (name.isBlank()) {
            binding.nameLayout.error = "Le nom du groupe est requis"
            isValid = false
        } else if (name.length < 3) {
            binding.nameLayout.error = "Le nom doit contenir au moins 3 caractères"
            isValid = false
        } else {
            binding.nameLayout.error = null
        }

        return isValid
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
