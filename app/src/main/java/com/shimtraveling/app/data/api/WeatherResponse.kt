package com.shimtraveling.data.api

import com.google.gson.annotations.SerializedName

data class WeatherResponse(
    @SerializedName("weather")
    val weather: List<WeatherInfo>,
    @SerializedName("main")
    val main: WeatherMain,
    @SerializedName("wind")
    val wind: WindInfo,
    @SerializedName("name")
    val cityName: String,
    @SerializedName("cod")
    val code: Int
)

data class WeatherInfo(
    @SerializedName("id")
    val id: Int,
    @SerializedName("main")
    val main: String,
    @SerializedName("description")
    val description: String,
    @SerializedName("icon")
    val icon: String
)

data class WeatherMain(
    @SerializedName("temp")
    val temp: Double,
    @SerializedName("feels_like")
    val feelsLike: Double,
    @SerializedName("temp_min")
    val tempMin: Double,
    @SerializedName("temp_max")
    val tempMax: Double,
    @SerializedName("pressure")
    val pressure: Int,
    @SerializedName("humidity")
    val humidity: Int
)

data class WindInfo(
    @SerializedName("speed")
    val speed: Double,
    @SerializedName("deg")
    val deg: Int
)
