package com.shimtraveling.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale


object ImageLabelingHelper {

    suspend fun suggestTags(bitmap: Bitmap): List<String> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        return try {
            val labels = labeler.process(image).await()
            labels
                .asSequence()
                .filter { it.confidence >= 0.47f }
                .sortedByDescending { it.confidence }
                .map { normalizeLabel(it.text) }
                .filter { it.length >= 2 && it.length <= 32 }
                .distinct()
                .take(8)
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeLabel(text: String): String =
        text.trim().lowercase(Locale.getDefault()).replace("\\s+".toRegex(), " ")
}
