package com.shimtraveling.ui.profile

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.databinding.FragmentProfileBinding
import com.shimtraveling.features.profile.LoginActivity
import com.shimtraveling.features.profile.RegisterActivity
import com.shimtraveling.features.profile.*
import com.shimtraveling.ui.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by activityViewModels { ProfileViewModel.Factory(requireActivity().application) }

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.loadCurrentUser()
        }
    }

    private val registerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.loadCurrentUser()
        }
    }

    private val avatarPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { uploadAvatar(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.helpButton.setOnClickListener {
            findNavController().navigate(R.id.navigation_guide)
        }
        observeData()
        setupButtons()
        refreshProfile()
    }

    override fun onResume() {
        super.onResume()
        refreshProfile()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentUser.collect { result ->
                result.onSuccess { user ->
                    if (user != null) {
                        showLoggedInState(user.username, user.email, user.avatar)
                    } else {
                        showAnonymousState()
                    }
                }
                result.onFailure { error ->
                    showAnonymousState()
                    android.widget.Toast
                        .makeText(requireContext(), "Erreur profil: ${error.message}", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun refreshProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            TravelingApp.getInstance().userRepository.refreshCurrentUser()
            viewModel.loadCurrentUser()
        }
    }

    private fun showLoggedInState(username: String, email: String, avatar: String?) {
        binding.anonymousContainer.visibility = View.GONE
        binding.userContainer.visibility = View.VISIBLE
        binding.usernameText.text = username
        binding.emailText.text = email

        if (!avatar.isNullOrBlank()) {
            Glide.with(this)
                .load(avatar)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .into(binding.avatarImage)
        } else {
            Glide.with(this)
                .load(R.drawable.ic_person)
                .circleCrop()
                .into(binding.avatarImage)
        }
    }

    private fun showAnonymousState() {
        binding.anonymousContainer.visibility = View.VISIBLE
        binding.userContainer.visibility = View.GONE
    }

    private fun setupButtons() {
        binding.loginButton.setOnClickListener {
            loginLauncher.launch(Intent(requireContext(), LoginActivity::class.java))
        }

        binding.registerButton.setOnClickListener {
            registerLauncher.launch(Intent(requireContext(), RegisterActivity::class.java))
        }

        binding.editAvatarButton.setOnClickListener {
            avatarPicker.launch("image/*")
        }

        binding.myPhotosButton.setOnClickListener {
            startActivity(Intent(requireContext(), MyPhotosActivity::class.java))
        }

        binding.favoritesButton.setOnClickListener {
            startActivity(Intent(requireContext(), FavoritesActivity::class.java))
        }

        binding.myPathsButton.setOnClickListener {
            startActivity(Intent(requireContext(), MyPathsActivity::class.java))
        }

        binding.groupsButton.setOnClickListener {
            startActivity(Intent(requireContext(), GroupsActivity::class.java))
        }

        binding.notificationsButton.setOnClickListener {
            startActivity(Intent(requireContext(), NotificationsActivity::class.java))
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        binding.addPlaceButton.setOnClickListener {
            startActivity(Intent(requireContext(), AddPlaceActivity::class.java))
        }

        binding.logoutButton.setOnClickListener {
            viewModel.logout()
        }
    }

    private fun uploadAvatar(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val userId = TravelingApp.getInstance().userRepository.getCurrentUserId() ?: return@launch

            val uploadResult = TravelingApp.getInstance().storageRepository.uploadAvatar(userId, uri)
            uploadResult.onSuccess { avatarUrl ->
                val updateResult = TravelingApp.getInstance().userRepository.updateAvatar(avatarUrl)
                updateResult.onSuccess {
                    android.widget.Toast.makeText(requireContext(), "Avatar mis à jour", android.widget.Toast.LENGTH_SHORT).show()
                }
                updateResult.onFailure { error ->
                    android.widget.Toast.makeText(requireContext(), "Erreur: ${error.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            uploadResult.onFailure { error ->
                android.widget.Toast.makeText(requireContext(), "Erreur de téléchargement: ${error.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
