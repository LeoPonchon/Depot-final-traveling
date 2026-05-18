package com.shimtraveling.data.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

interface ElevationApiService {
    @GET("v1/elevation/{lat}/{lon}?json")
    suspend fun getElevation(
        @Path("lat") lat: Double,
        @Path("lon") lon: Double
    ): ElevationResponse

    companion object {
        private const val BASE_URL = "https://www.elevation-api.eu/"

        fun create(): ElevationApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ElevationApiService::class.java)
        }
    }
}

data class ElevationResponse(
    val elevation: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)
