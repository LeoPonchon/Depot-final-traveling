package com.shimtraveling.features.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shimtraveling.R
import com.shimtraveling.databinding.ActivityVideoPlayerBinding

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var resumePosition: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resumePosition = savedInstanceState?.getInt(STATE_POSITION) ?: 0

        setupToolbar()

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)?.trim().orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty()
        if (title.isNotEmpty()) {
            supportActionBar?.title = title
            binding.videoTitle.text = title
        }

        if (videoUrl.isEmpty()) {
            Toast.makeText(this, R.string.video_player_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        playVideo(videoUrl)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.video_player_title)
    }

    private fun playVideo(videoUrl: String) {
        binding.videoProgress.visibility = android.view.View.VISIBLE

        val mediaController = MediaController(this).apply {
            setAnchorView(binding.videoView)
        }

        binding.videoView.apply {
            setMediaController(mediaController)
            setVideoURI(Uri.parse(videoUrl))
            setOnPreparedListener { mediaPlayer ->
                binding.videoProgress.visibility = android.view.View.GONE
                mediaPlayer.isLooping = false
                if (resumePosition > 0) {
                    seekTo(resumePosition)
                }
                start()
            }
            setOnErrorListener { _, _, _ ->
                binding.videoProgress.visibility = android.view.View.GONE
                Toast.makeText(this@VideoPlayerActivity, R.string.video_player_error, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    override fun onPause() {
        resumePosition = binding.videoView.currentPosition
        binding.videoView.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_POSITION, binding.videoView.currentPosition)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        binding.videoView.stopPlayback()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VIDEO_URL = "video_url"
        private const val EXTRA_TITLE = "title"
        private const val STATE_POSITION = "position"

        fun createIntent(context: Context, videoUrl: String, title: String? = null): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_TITLE, title)
            }
        }
    }
}
