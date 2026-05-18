package com.shimtraveling.data.api

data class OsrmResponse(
    val code: String,
    val routes: List<OsrmRoute>
)

data class OsrmRoute(
    val distance: Double,
    val duration: Double,
    val geometry: OsrmGeometry
)

data class OsrmGeometry(
    val type: String,
    val coordinates: List<List<Double>>
) {
    fun getLatLonCoordinates(): List<Pair<Double, Double>> {
        return coordinates.map {
            Pair(it[1], it[0])
        }
    }
}
