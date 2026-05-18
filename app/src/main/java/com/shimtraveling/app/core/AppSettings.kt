package com.shimtraveling.core

import android.content.Context

object AppSettings {
    private const val PREFS_NAME = "traveling_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_THEME_MODE = "theme_mode"

    const val LANG_FR = "fr"
    const val LANG_EN = "en"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_SYSTEM = "system"

    fun getLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANG_FR) ?: LANG_FR
    }

    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }

    fun isDarkModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)
    }

    fun setDarkModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, enabled)
            .apply()
    }

    fun getThemeMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_THEME_MODE, null)
        if (stored != null) return stored
        return if (prefs.getBoolean(KEY_DARK_MODE, false)) THEME_DARK else THEME_LIGHT
    }

    fun setThemeMode(context: Context, themeMode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, themeMode)
            .putBoolean(KEY_DARK_MODE, themeMode == THEME_DARK)
            .apply()
    }
}
