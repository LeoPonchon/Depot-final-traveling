package com.shimtraveling.features.profile

import android.view.Menu
import android.view.MenuItem
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.shimtraveling.R
import com.shimtraveling.data.repository.AuthException
import com.shimtraveling.databinding.ActivityLoginBinding
import com.shimtraveling.ui.common.openGuide
import com.shimtraveling.ui.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var viewModel: ProfileViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupViewModel()
        setupFieldValidation()
        observeViewModel()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.profile_login)
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this, ProfileViewModel.Factory(application))[ProfileViewModel::class.java]
    }

    private fun setupButtons() {
        binding.loginButton.setOnClickListener {
            clearErrors()
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()

            if (validateInputs(email, password)) {
                viewModel.login(email, password)
            }
        }

        binding.registerLink.setOnClickListener {
            finish()
        }
    }

    private fun setupFieldValidation() {
        binding.emailInput.doAfterTextChanged { binding.emailLayout.error = null }
        binding.passwordInput.doAfterTextChanged { binding.passwordLayout.error = null }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.authResult.collect { result ->
                result ?: return@collect
                result.onSuccess {
                    setResult(RESULT_OK)
                    finish()
                }
                result.onFailure { error ->
                    applyAuthError(error)
                    viewModel.clearAuthResult()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.loginButton.isEnabled = !loading
            }
        }
    }

    private fun validateInputs(email: String, password: String): Boolean {
        var isValid = true

        if (email.isBlank()) {
            binding.emailLayout.error = "Email requis"
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.error = "Email invalide"
            isValid = false
        } else {
            binding.emailLayout.error = null
        }

        if (password.isBlank()) {
            binding.passwordLayout.error = "Mot de passe requis"
            isValid = false
        } else {
            binding.passwordLayout.error = null
        }

        return isValid
    }

    private fun applyAuthError(error: Throwable) {
        val authError = error as? AuthException
        if (authError != null) {
            binding.emailLayout.error = authError.emailError
            binding.passwordLayout.error = authError.passwordError
            authError.generalError?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        } else {
            val message = error.message.orEmpty()
            when {
                message.contains("email", ignoreCase = true) -> binding.emailLayout.error = message
                message.contains("mot de passe", ignoreCase = true) ||
                    message.contains("password", ignoreCase = true) -> binding.passwordLayout.error = message
                else -> Toast.makeText(
                    this,
                    message.ifBlank { "Connexion impossible." },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun clearErrors() {
        binding.emailLayout.error = null
        binding.passwordLayout.error = null
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
