package com.shimtraveling.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shimtraveling.data.model.Place
import com.shimtraveling.data.model.TravelPath


class DataCache(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("traveling_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun cachePlaces(places: List<Place>) {
        val json = gson.toJson(places)
        sharedPreferences.edit()
            .putString("cached_places", json)
            .putLong("places_cache_time", System.currentTimeMillis())
            .apply()
    }

    fun getCachedPlaces(): List<Place>? {
        val cacheTime = sharedPreferences.getLong("places_cache_time", 0)
        if (System.currentTimeMillis() - cacheTime > CACHE_DURATION_MS) {
            return null
        }
        val json = sharedPreferences.getString("cached_places", null) ?: return null
        val type = object : TypeToken<List<Place>>() {}.type
        return gson.fromJson(json, type)
    }

    fun cachePaths(paths: List<TravelPath>) {
        val json = gson.toJson(paths)
        sharedPreferences.edit()
            .putString("cached_paths", json)
            .putLong("paths_cache_time", System.currentTimeMillis())
            .apply()
        cacheMediaUrls(extractMediaUrls(paths))
    }

    fun getCachedPaths(): List<TravelPath>? {
        val cacheTime = sharedPreferences.getLong("paths_cache_time", 0)
        if (System.currentTimeMillis() - cacheTime > CACHE_DURATION_MS) {
            return null
        }
        val json = sharedPreferences.getString("cached_paths", null) ?: return null
        val type = object : TypeToken<List<TravelPath>>() {}.type
        return gson.fromJson(json, type)
    }

    fun cacheMediaUrls(urls: List<String>) {
        val json = gson.toJson(urls)
        sharedPreferences.edit()
            .putString("cached_media_urls", json)
            .putLong("media_cache_time", System.currentTimeMillis())
            .apply()
    }

    fun getCachedMediaUrls(): List<String>? {
        val cacheTime = sharedPreferences.getLong("media_cache_time", 0)
        if (System.currentTimeMillis() - cacheTime > MEDIA_CACHE_DURATION_MS) {
            return null
        }
        val json = sharedPreferences.getString("cached_media_urls", null) ?: return null
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }

    fun removeCachedMediaUrls() {
        sharedPreferences.edit()
            .remove("cached_media_urls")
            .remove("media_cache_time")
            .apply()
    }

    fun isMediaCacheValid(): Boolean {
        val cacheTime = sharedPreferences.getLong("media_cache_time", 0)
        return System.currentTimeMillis() - cacheTime <= MEDIA_CACHE_DURATION_MS
    }

    private fun extractMediaUrls(paths: List<TravelPath>): List<String> {
        return paths.flatMap { path ->
            path.steps.flatMap { step ->
                listOfNotNull(step.placeImageUrl.takeIf { it.isNotBlank() }, step.videoUrl?.takeIf { it.isNotBlank() })
            }
        }.distinct()
    }

    fun cacheSavedPath(path: TravelPath) {
        val existingPaths = getCachedSavedPaths()?.toMutableList() ?: mutableListOf()
        existingPaths.removeAll { it.id == path.id }
        existingPaths.add(path)
        val json = gson.toJson(existingPaths)
        sharedPreferences.edit()
            .putString("saved_paths", json)
            .apply()
    }

    fun getCachedSavedPaths(): List<TravelPath>? {
        val json = sharedPreferences.getString("saved_paths", null) ?: return null
        val type = object : TypeToken<List<TravelPath>>() {}.type
        return gson.fromJson(json, type)
    }

    fun removeCachedSavedPath(pathId: String) {
        val existingPaths = getCachedSavedPaths()?.toMutableList() ?: return
        existingPaths.removeAll { it.id == pathId }
        val json = gson.toJson(existingPaths)
        sharedPreferences.edit()
            .putString("saved_paths", json)
            .apply()
    }

    fun clearCache() {
        sharedPreferences.edit().clear().apply()
    }

    fun isCacheValid(): Boolean {
        val cacheTime = sharedPreferences.getLong("places_cache_time", 0)
        return System.currentTimeMillis() - cacheTime <= CACHE_DURATION_MS
    }

    fun isPathCacheValid(): Boolean {
        val cacheTime = sharedPreferences.getLong("paths_cache_time", 0)
        return System.currentTimeMillis() - cacheTime <= CACHE_DURATION_MS
    }

    companion object {
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
        private const val MEDIA_CACHE_DURATION_MS = 48 * 60 * 60 * 1000L
    }
}
