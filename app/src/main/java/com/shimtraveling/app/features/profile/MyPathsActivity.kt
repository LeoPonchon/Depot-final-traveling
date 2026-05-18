package com.shimtraveling.features.profile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shimtraveling.TravelingApp
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.databinding.ActivityMyPathsBinding
import com.shimtraveling.features.path.PathDetailActivity
import com.shimtraveling.ui.adapter.PathAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MyPathsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyPathsBinding
    private lateinit var pathAdapter: PathAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyPathsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadSavedPaths()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Mes parcours"
    }

    private fun setupRecyclerView() {
        pathAdapter = PathAdapter(
            onPathClick = { path -> onPathClick(path) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MyPathsActivity)
            adapter = pathAdapter
        }
    }

    private fun loadSavedPaths() {
        lifecycleScope.launch {
            val userResult = TravelingApp.getInstance().userRepository.getCurrentUser().first()
            val user = userResult.getOrNull()
            if (user == null) {
                binding.emptyView.visibility = android.view.View.VISIBLE
                return@launch
            }
            TravelingApp.getInstance().pathRepository.getSavedPaths(user.id).collect { pathsResult ->
                pathsResult.onSuccess { paths ->
                    pathAdapter.submitList(paths)
                    binding.emptyView.visibility =
                        if (paths.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    private fun onPathClick(path: TravelPath) {
        val intent = Intent(this, PathDetailActivity::class.java)
        intent.putExtra("path", path)
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
