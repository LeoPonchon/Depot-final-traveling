package com.shimtraveling.ui.common

import android.content.Intent
import android.speech.RecognizerIntent
import java.util.Locale

object VoiceSearch {

    fun buildIntent(prompt: String, locale: Locale = Locale.getDefault()): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }

    fun extractFirstResult(data: Intent?): String? =
        data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

