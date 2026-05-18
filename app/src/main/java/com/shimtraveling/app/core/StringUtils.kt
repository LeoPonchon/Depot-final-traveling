package com.shimtraveling.core

import kotlin.math.min

object StringUtils {


    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) {
            for (j in 0..s2.length) {
                when {
                    i == 0 -> dp[i][j] = j
                    j == 0 -> dp[i][j] = i
                    else -> {
                        val cost = if (s1[i - 1].lowercase() == s2[j - 1].lowercase()) 0 else 1
                        dp[i][j] = min(
                            min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + cost
                        )
                    }
                }
            }
        }
        return dp[s1.length][s2.length]
    }


    fun similarityScore(s1: String, s2: String): Double {
        val maxLength = maxOf(s1.length, s2.length)
        if (maxLength == 0) return 1.0
        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLength.toDouble())
    }


    fun isTooSimilar(name1: String, name2: String, threshold: Double = 0.8): Boolean {
        val clean1 = name1.trim().lowercase().replace(Regex("\\s+"), " ")
        val clean2 = name2.trim().lowercase().replace(Regex("\\s+"), " ")

        if (clean1 == clean2) return true

        if (clean1.contains(clean2) || clean2.contains(clean1)) return true

        return similarityScore(clean1, clean2) >= threshold
    }
}
