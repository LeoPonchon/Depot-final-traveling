package com.shimtraveling.features.profile

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shimtraveling.R
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.firestore.ReportDocument
import com.shimtraveling.data.model.PhotoModerationStatus
import com.shimtraveling.databinding.ActivityModerationBinding
import kotlinx.coroutines.launch

class ModerationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModerationBinding
    private var reports: List<ReportDocument> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModerationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.reportsList.setOnItemClickListener { _, _, position, _ ->
            val report = reports.getOrNull(position) ?: return@setOnItemClickListener
            showActions(report)
        }
        loadReports()
    }

    private fun loadReports() {
        lifecycleScope.launch {
            val result = TravelingApp.getInstance().firestoreRepository.listOpenReports()
            result.onSuccess { list ->
                reports = list
                val lines = list.map { report ->
                    "${reportTypeLabel(report)} ${reportTargetId(report)} · ${report.reason} (${report.userId})"
                }
                binding.reportsList.adapter = ArrayAdapter(
                    this@ModerationActivity,
                    android.R.layout.simple_list_item_1,
                    lines
                )
                binding.emptyView.visibility =
                    if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
            result.onFailure {
                binding.emptyView.text = it.message ?: getString(R.string.moderation_load_error)
                binding.emptyView.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun showActions(report: ReportDocument) {
        val isPhotoReport = report.targetType.equals("PHOTO", ignoreCase = true)
        val options = if (isPhotoReport) {
            arrayOf(
                getString(R.string.moderation_hide_photo),
                getString(R.string.moderation_resolve_only)
            )
        } else {
            arrayOf(getString(R.string.moderation_resolve_only))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_moderation)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    when {
                        isPhotoReport && which == 0 -> {
                            TravelingApp.getInstance().firestoreRepository.updatePhotoModeration(
                                report.photoId.ifBlank { report.targetId },
                                PhotoModerationStatus.HIDDEN
                            )
                            TravelingApp.getInstance().firestoreRepository
                                .updateReportStatus(report.id, "RESOLVED")
                        }

                        else -> {
                            TravelingApp.getInstance().firestoreRepository
                                .updateReportStatus(report.id, "RESOLVED")
                        }
                    }
                    loadReports()
                }
            }
            .show()
    }

    private fun reportTypeLabel(report: ReportDocument): String {
        return if (report.targetType.equals("PLACE", ignoreCase = true)) "[Lieu]" else "[Photo]"
    }

    private fun reportTargetId(report: ReportDocument): String {
        return report.targetId.ifBlank {
            if (report.targetType.equals("PLACE", ignoreCase = true)) report.placeId else report.photoId
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
