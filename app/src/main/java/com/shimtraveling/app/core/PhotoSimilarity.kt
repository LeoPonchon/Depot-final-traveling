package com.shimtraveling.core

import com.shimtraveling.data.model.Photo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt


object PhotoSimilarity {

    fun score(ref: Photo, other: Photo): Double {
        val tagJ = jaccard(
            ref.tags.map { it.lowercase().trim() }.filter { it.isNotEmpty() },
            other.tags.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        )
        val cat = if (ref.category == other.category) 0.22 else 0.0
        val place = softTokenOverlap(ref.placeName, other.placeName) * 0.12
        val geo = geoBonus(ref.latitude, ref.longitude, other.latitude, other.longitude)
        val desc = descriptionBonus(ref.description, other.description)
        return tagJ * 0.48 + cat + place + geo + desc
    }

    private fun jaccard(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.intersect(sb).size
        val union = sa.union(sb).size
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    private fun softTokenOverlap(s1: String, s2: String): Double {
        val t1 = s1.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val t2 = s2.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (t1.isEmpty() || t2.isEmpty()) return 0.0
        return t1.intersect(t2).size.toDouble() / t1.union(t2).size
    }

    private fun geoBonus(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (lat1 == 0.0 && lng1 == 0.0) return 0.0
        if (lat2 == 0.0 && lng2 == 0.0) return 0.0
        val km = haversineKm(lat1, lng1, lat2, lng2)
        return when {
            km < 2.0 -> 0.18
            km < 15.0 -> 0.12
            km < 80.0 -> 0.06
            km < 300.0 -> 0.03
            else -> 0.0
        }
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun descriptionBonus(d1: String?, d2: String?): Double {
        if (d1.isNullOrBlank() || d2.isNullOrBlank()) return 0.0
        return StringUtils.similarityScore(d1, d2) * 0.15
    }
}
