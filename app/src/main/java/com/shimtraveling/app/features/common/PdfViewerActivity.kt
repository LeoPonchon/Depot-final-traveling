package com.shimtraveling.features.common

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shimtraveling.R
import com.shimtraveling.databinding.ActivityPdfViewerBinding
import com.shimtraveling.ui.common.openGuide
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentPageIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentPageIndex = savedInstanceState?.getInt(STATE_PAGE_INDEX) ?: 0

        setupToolbar()
        setupButtons()

        val pdfPath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) {
            supportActionBar?.title = title
        }

        if (!openDocument(pdfPath)) {
            Toast.makeText(this, R.string.pdf_viewer_open_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        renderPage(currentPageIndex)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.pdf_viewer_title)
    }

    private fun setupButtons() {
        binding.previousButton.setOnClickListener { renderPage(currentPageIndex - 1) }
        binding.nextButton.setOnClickListener { renderPage(currentPageIndex + 1) }
    }

    private fun openDocument(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.exists() || !file.isFile) return false

        return runCatching {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(requireNotNull(fileDescriptor))
        }.isSuccess
    }

    private fun renderPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index !in 0 until renderer.pageCount) return

        currentPage?.close()
        currentPage = renderer.openPage(index)
        currentPageIndex = index

        val page = requireNotNull(currentPage)
        val scale = 2
        val bitmap = Bitmap.createBitmap(
            page.width * scale,
            page.height * scale,
            Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        binding.pageImage.setImageBitmap(bitmap)
        binding.pageIndicator.text = getString(
            R.string.pdf_viewer_page_indicator,
            index + 1,
            renderer.pageCount
        )
        binding.previousButton.isEnabled = index > 0
        binding.nextButton.isEnabled = index < renderer.pageCount - 1
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE_INDEX, currentPageIndex)
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

    override fun onDestroy() {
        currentPage?.close()
        pdfRenderer?.close()
        fileDescriptor?.close()
        currentPage = null
        pdfRenderer = null
        fileDescriptor = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_TITLE = "title"
        private const val STATE_PAGE_INDEX = "page_index"

        fun createIntent(context: Context, file: File, title: String? = null): Intent {
            return Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, file.absolutePath)
                putExtra(EXTRA_TITLE, title ?: file.name)
            }
        }
    }
}
