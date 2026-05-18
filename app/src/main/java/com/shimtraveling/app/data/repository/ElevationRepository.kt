package com.shimtraveling.data.repository

import android.util.Log
import com.shimtraveling.data.api.ElevationApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ElevationRepository {
    private val api = ElevationApiService.create()

    suspend fun getElevation(lat: Double, lon: Double): Double? {
        return try {
            withContext(Dispatchers.IO) {
                val response = api.getElevation(lat, lon)
                response.elevation
            }
        } catch (e: Exception) {
            Log.e("ElevationRepository", "Error fetching elevation for $lat, $lon", e)
            null
        }
    }
}
