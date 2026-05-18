package com.shimtraveling.features.profile

import android.view.Menu
import android.view.MenuItem
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import android.text.method.LinkMovementMethod
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.shimtraveling.R
import com.shimtraveling.data.repository.AuthException
import com.shimtraveling.databinding.ActivityRegisterBinding
import com.shimtraveling.ui.common.openGuide
import com.shimtraveling.ui.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var viewModel: ProfileViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupLegalNotice()
        setupViewModel()
        setupFieldValidation()
        observeViewModel()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.profile_register)
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this, ProfileViewModel.Factory(application))[ProfileViewModel::class.java]
    }

    private fun setupLegalNotice() {
        binding.legalNotice.isVisible = true
        binding.legalNotice.text = HtmlCompat.fromHtml(
            getString(R.string.register_legal_notice),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        binding.legalNotice.movementMethod = LinkMovementMethod.getInstance()
        binding.legalNotice.setLinkTextColor(ContextCompat.getColor(this, R.color.primary))
    }

    private fun setupButtons() {
        binding.registerButton.setOnClickListener {
            clearErrors()
            val username = binding.usernameInput.text.toString().trim()
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()
            val confirmPassword = binding.confirmPasswordInput.text.toString()

            if (validateInputs(username, email, password, confirmPassword)) {
                viewModel.register(username, email, password)
            }
        }

        binding.loginLink.setOnClickListener {
            finish()
        }
    }

    private fun setupFieldValidation() {
        binding.usernameInput.doAfterTextChanged { binding.usernameLayout.error = null }
        binding.emailInput.doAfterTextChanged { binding.emailLayout.error = null }
        binding.passwordInput.doAfterTextChanged { binding.passwordLayout.error = null }
        binding.confirmPasswordInput.doAfterTextChanged { binding.confirmPasswordLayout.error = null }
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
                binding.registerButton.isEnabled = !loading
            }
        }
    }

    private fun validateInputs(username: String, email: String, password: String, confirmPassword: String): Boolean {
        var isValid = true

        if (username.isBlank()) {
            binding.usernameLayout.error = "Nom d\'utilisateur requis"
            isValid = false
        } else {
            binding.usernameLayout.error = null
        }

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
        } else if (password.length < 6) {
            binding.passwordLayout.error = "Le mot de passe doit contenir au moins 6 caractères"
            isValid = false
        } else {
            binding.passwordLayout.error = null
        }

        if (confirmPassword.isBlank()) {
            binding.confirmPasswordLayout.error = "Confirmation requise"
            isValid = false
        } else if (password != confirmPassword) {
            binding.confirmPasswordLayout.error = "Les mots de passe ne correspondent pas"
            isValid = false
        } else {
            binding.confirmPasswordLayout.error = null
        }

    return isValid
    }

    private fun applyAuthError(error: Throwable) {
        val authError = error as? AuthException
        if (authError != null) {
            binding.usernameLayout.error = authError.usernameError
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
                message.contains("nom d'utilisateur", ignoreCase = true) ||
                    message.contains("username", ignoreCase = true) -> binding.usernameLayout.error = message
                else -> Toast.makeText(
                    this,
                    message.ifBlank { "Inscription impossible." },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun clearErrors() {
        binding.usernameLayout.error = null
        binding.emailLayout.error = null
        binding.passwordLayout.error = null
        binding.confirmPasswordLayout.error = null
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
