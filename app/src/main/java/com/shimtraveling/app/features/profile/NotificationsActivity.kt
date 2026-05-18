package com.shimtraveling.features.profile

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.shimtraveling.databinding.ActivityNotificationsBinding
import com.shimtraveling.features.photo.PhotoDetailActivity

class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()

        intent.getStringExtra("PHOTO_ID")?.takeIf { it.isNotBlank() }?.let { photoId ->
            startActivity(
                Intent(this, PhotoDetailActivity::class.java)
                    .putExtra(PhotoDetailActivity.EXTRA_PHOTO_ID, photoId)
            )
            finish()
            return
        }

        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Notifications"
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
