package com.shimtraveling.core

import com.shimtraveling.data.model.TimeOfDay
import java.util.Calendar
import java.util.Locale


object OpeningHoursEvaluator {


    private val closedKeywords = listOf(
        "fermé", "ferme", "closed", "fermeture", "vacances", "indisponible"
    )


    fun isLikelyOpen(openingHours: String?, timeOfDay: TimeOfDay?, weekday: Int, startMinutesFromMidnight: Int?): Boolean {
        if (openingHours.isNullOrBlank()) return true
        val text = openingHours.lowercase(Locale.FRENCH)

        if (closedKeywords.any { text.contains(it) } && !text.contains("sauf")) {
            val dayNameFr = frenchWeekday(weekday).lowercase(Locale.FRENCH)
            if (text.contains(dayNameFr) && (text.contains("fermé") || text.contains("ferme"))) {
                return false
            }
        }

        val slotStart = approximateHourForSlot(timeOfDay, startMinutesFromMidnight) ?: return true
        val ranges = extractHourRanges(text)
        if (ranges.isEmpty()) return true
        return ranges.any { slotStart >= it.first && slotStart < it.second }
    }

    private fun frenchWeekday(calendarDay: Int): String = when (calendarDay) {
        Calendar.MONDAY -> "lundi"
        Calendar.TUESDAY -> "mardi"
        Calendar.WEDNESDAY -> "mercredi"
        Calendar.THURSDAY -> "jeudi"
        Calendar.FRIDAY -> "vendredi"
        Calendar.SATURDAY -> "samedi"
        Calendar.SUNDAY -> "dimanche"
        else -> ""
    }

    private fun approximateHourForSlot(timeOfDay: TimeOfDay?, startMinutes: Int?): Double? {
        if (startMinutes != null) {
            val h = startMinutes / 60.0 + (startMinutes % 60) / 60.0
            return h
        }
        return when (timeOfDay) {
            TimeOfDay.MORNING -> 10.0
            TimeOfDay.AFTERNOON -> 15.0
            TimeOfDay.EVENING -> 19.0
            null -> null
        }
    }

    private fun extractHourRanges(text: String): List<Pair<Double, Double>> {
        val results = mutableListOf<Pair<Double, Double>>()
        val regex = Regex("(\\d{1,2})\\s*h\\s*[-–àa]\\s*(\\d{1,2})\\s*h?", RegexOption.IGNORE_CASE)
        regex.findAll(text).forEach { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@forEach
            val b = m.groupValues[2].toIntOrNull() ?: return@forEach
            results.add(a.toDouble() to b.toDouble().coerceAtLeast(a + 0.5))
        }
        return results
    }
}
