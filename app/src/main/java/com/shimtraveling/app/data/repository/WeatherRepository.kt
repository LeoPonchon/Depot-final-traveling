package com.shimtraveling.data.repository

import android.util.Log
import com.shimtraveling.BuildConfig
import com.shimtraveling.data.api.WeatherApiService
import com.shimtraveling.data.model.WeatherCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


data class CityWeatherSnapshot(
    val condition: WeatherCondition?,
    val humidityPercent: Int?
)

class WeatherRepository(private val apiKey: String = BuildConfig.OPENWEATHER_API_KEY) {

    private val api = WeatherApiService.create()

    suspend fun getCityWeatherSnapshot(city: String): CityWeatherSnapshot? {
        if (apiKey.isBlank()) {
            Log.w("WeatherRepository", "OPENWEATHER_API_KEY is missing; weather checks are disabled.")
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val response = api.getCurrentWeather(city, apiKey)
                CityWeatherSnapshot(
                    condition = convertToWeatherCondition(response),
                    humidityPercent = response.main.humidity
                )
            }
        } catch (e: Exception) {
            Log.e("WeatherRepository", "Error fetching weather snapshot for $city", e)
            null
        }
    }

    suspend fun getWeatherConditionForCity(city: String): WeatherCondition? {
        return getCityWeatherSnapshot(city)?.condition
    }

    suspend fun getWeatherConditionForCoordinates(lat: Double, lon: Double): WeatherCondition? {
        if (apiKey.isBlank()) {
            Log.w("WeatherRepository", "OPENWEATHER_API_KEY is missing; weather checks are disabled.")
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val response = api.getCurrentWeatherByCoordinates(lat, lon, apiKey)
                convertToWeatherCondition(response)
            }
        } catch (e: Exception) {
            Log.e("WeatherRepository", "Error fetching weather for coordinates", e)
            null
        }
    }

    private fun convertToWeatherCondition(response: com.shimtraveling.data.api.WeatherResponse): WeatherCondition {
        val temp = response.main.temp
        val weatherMain = response.weather.firstOrNull()?.main?.lowercase().orEmpty()

        val precipitation = weatherMain.contains("rain") ||
            weatherMain.contains("drizzle") ||
            weatherMain.contains("thunderstorm")

        return when {
            precipitation -> WeatherCondition.RAINY
            temp < 5 -> WeatherCondition.COLD
            temp > 30 -> WeatherCondition.HOT
            weatherMain.contains("cloud") -> WeatherCondition.CLOUDY
            else -> WeatherCondition.SUNNY
        }
    }

    fun getWeatherDescription(condition: WeatherCondition): String {
        return when (condition) {
            WeatherCondition.SUNNY -> "Ensoleillé"
            WeatherCondition.CLOUDY -> "Nuageux"
            WeatherCondition.RAINY -> "Pluvieux"
            WeatherCondition.COLD -> "Froid (< 5°C)"
            WeatherCondition.HOT -> "Chaud (> 30°C)"
        }
    }
}
