package com.shimtraveling.features.profile

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.core.AppSettings
import com.shimtraveling.databinding.ActivitySettingsBinding
import com.shimtraveling.ui.common.openGuide
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupInitialUiState()
        setupListeners()
        lifecycleScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null) {
                binding.btnAdminManagement.visibility = android.view.View.GONE
                return@launch
            }

            val result = TravelingApp.getInstance().firestoreRepository.getUserById(uid)
            result.onFailure { e ->
                Log.e("SettingsActivity", "Admin check failed for uid=$uid", e)
                Toast.makeText(this@SettingsActivity, "Impossible de vérifier le statut admin (réseau ?)", Toast.LENGTH_SHORT).show()
            }
            val isAdmin = result.getOrNull()?.isAdmin == true
            binding.btnAdminManagement.visibility =
                if (isAdmin) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun setupInitialUiState() {
        when (AppSettings.getThemeMode(this)) {
            AppSettings.THEME_DARK -> binding.themeToggle.check(binding.btnThemeDark.id)
            AppSettings.THEME_SYSTEM -> binding.themeToggle.check(binding.btnThemeSystem.id)
            else -> binding.themeToggle.check(binding.btnThemeLight.id)
        }

        when (AppSettings.getLanguage(this)) {
            AppSettings.LANG_EN -> binding.languageToggle.check(binding.btnEnglish.id)
            else -> binding.languageToggle.check(binding.btnFrench.id)
        }
    }

    private fun setupListeners() {
        binding.btnClearCache.setOnClickListener {
            clearAppCaches()
            Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        binding.btnNotificationSettings.setOnClickListener {
            startActivity(android.content.Intent(this, NotificationSettingsActivity::class.java))
        }

        binding.btnAdminManagement.setOnClickListener {
            startActivity(android.content.Intent(this, AdminManagementActivity::class.java))
        }

        binding.themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selectedTheme = when (checkedId) {
                binding.btnThemeDark.id -> AppSettings.THEME_DARK
                binding.btnThemeSystem.id -> AppSettings.THEME_SYSTEM
                else -> AppSettings.THEME_LIGHT
            }
            AppSettings.setThemeMode(this, selectedTheme)
            val mode = when (selectedTheme) {
                AppSettings.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppSettings.THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        binding.languageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val selectedLanguage = when (checkedId) {
                binding.btnEnglish.id -> AppSettings.LANG_EN
                else -> AppSettings.LANG_FR
            }

            if (selectedLanguage != AppSettings.getLanguage(this)) {
                AppSettings.setLanguage(this, selectedLanguage)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedLanguage))
            }
        }
    }

    private fun clearAppCaches() {
        val app = application as TravelingApp
        app.dataCache.clearCache()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
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
