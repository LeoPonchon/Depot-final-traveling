package com.shimtraveling.data.repository

import android.util.Log
import com.shimtraveling.data.api.OsrmApiService
import com.shimtraveling.data.api.OsrmRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoutingRepository {

    private val api = OsrmApiService.create()


    suspend fun getRoute(
        coordinates: List<Pair<Double, Double>>,
        profile: String = "walking"
    ): OsrmRoute? {
        if (coordinates.size < 2) return null

        val coordinateString = coordinates.joinToString(";") { "${it.second},${it.first}" }

        return try {
            withContext(Dispatchers.IO) {
                val response = api.getRoute(profile, coordinateString)
                if (response.code == "Ok" && response.routes.isNotEmpty()) {
                    response.routes.first()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("RoutingRepository", "Error fetching route from OSRM", e)
            null
        }
    }
}
