package com.shimtraveling.core


object CityResolver {

    fun guessCity(address: String?, fallbackLabel: String): String {
        val fromPostal = guessCityFromFrenchPostalCode(address)
        if (!fromPostal.isNullOrBlank()) return fromPostal

        val fromLabel = fallbackLabel.substringAfterLast(',').trim()
        return fromLabel.ifBlank { fallbackLabel }
    }

    private fun guessCityFromFrenchPostalCode(address: String?): String? {
        val raw = address?.trim().orEmpty()
        if (raw.isBlank()) return null

        val postalRegex = "\\b(\\d{5})\\b".toRegex()
        val postal = postalRegex.find(raw)?.value ?: return null
        return when (postal.take(2)) {
            "75" -> "Paris"
            "34" -> "Montpellier"
            "13" -> "Marseille"
            "69" -> "Lyon"
            "06" -> "Nice"
            "31" -> "Toulouse"
            "33" -> "Bordeaux"
            "44" -> "Nantes"
            "67" -> "Strasbourg"
            "59" -> "Lille"
            else -> null
        }
    }
}
