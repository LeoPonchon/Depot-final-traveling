package com.shimtraveling.features.profile

import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import android.util.Log
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.databinding.ActivityAdminManagementBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class AdminManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminManagementBinding
    private val functions by lazy { FirebaseFunctions.getInstance("europe-west9") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupActions()
        loadAdminStatus()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.admin_title)
    }

    private fun setupActions() {
        binding.btnOpenModeration.setOnClickListener {
            startActivity(android.content.Intent(this, ModerationActivity::class.java))
        }

        binding.btnClearDatabase.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Purge complète (Firebase)")
                .setMessage(
                    "Cette action SUPPRIME TOUT :\n" +
                        "• tous les documents Firestore,\n" +
                        "• tous les fichiers Storage,\n" +
                        "• tous les comptes Firebase Auth,\n" +
                        "en conservant uniquement votre compte admin et ses données Firestore.\n\n" +
                        "Action irréversible."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Tout supprimer") { _, _ ->
                    runFullFirebasePurge()
                }
                .show()
        }
    }

    private fun runFullFirebasePurge() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Toast.makeText(this, R.string.admin_access_denied, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnClearDatabase.isEnabled = false
        Toast.makeText(this, "Purge en cours… (peut prendre 1–3 min)", Toast.LENGTH_LONG).show()

        functions
            .getHttpsCallable("purgeProjectExceptAdmin")
            .call()
            .addOnSuccessListener {
                Toast.makeText(this, "Purge terminée. Redémarrage…", Toast.LENGTH_LONG).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        clearAppCaches()
                        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        firestore.terminate().await()
                        firestore.clearPersistence().await()
                        Glide.get(this@AdminManagementActivity).clearDiskCache()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@AdminManagementActivity,
                                getString(R.string.settings_clear_database_error, e.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            Glide.get(this@AdminManagementActivity).clearMemory()
                        }
                        Process.killProcess(Process.myPid())
                    }
                }
            }
            .addOnFailureListener { e ->
                binding.btnClearDatabase.isEnabled = true
                Toast.makeText(this, "Erreur purge: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun loadAdminStatus() {
        lifecycleScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null) {
                Toast.makeText(this@AdminManagementActivity, R.string.admin_access_denied, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val result = TravelingApp.getInstance().firestoreRepository.getUserById(uid)
            result.onFailure { e ->
                Log.e("AdminManagement", "Admin check failed for uid=$uid", e)
            }
            val user = result.getOrNull()
            if (user?.isAdmin != true) {
                Toast.makeText(this@AdminManagementActivity, R.string.admin_access_denied, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val reportsResult = TravelingApp.getInstance().firestoreRepository.listOpenReports()
            reportsResult.onSuccess { reports ->
                binding.reportsSummary.text = resources.getQuantityString(
                    R.plurals.admin_reports_count,
                    reports.size,
                    reports.size
                )
            }
            reportsResult.onFailure {
                binding.reportsSummary.text = getString(R.string.moderation_load_error)
            }
        }
    }

    private fun clearAppCaches() {
        val app = application as TravelingApp
        app.dataCache.clearCache()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
