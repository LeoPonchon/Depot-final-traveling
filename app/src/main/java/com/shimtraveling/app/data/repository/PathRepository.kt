package com.shimtraveling.data.repository

import android.util.Log
import com.shimtraveling.core.OpeningHoursEvaluator
import com.shimtraveling.data.firestore.FirestoreRepository
import com.shimtraveling.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.*
import kotlin.math.*

class PathRepository(
    private val firestore: FirestoreRepository,
    private val weatherRepository: WeatherRepository? = null,
    private val elevationRepository: com.shimtraveling.data.repository.ElevationRepository? = null,
    private val routingRepository: RoutingRepository? = null
) {
    data class ClosestPathsResult(
        val paths: List<TravelPath>,
        val note: String,
        val unmetConstraints: List<String>
    )

    private data class FailureDetails(
        val attempts: Int,
        val budgetFailCount: Int,
        val durationFailCount: Int,
        val durationTooShortCount: Int,
        val durationTooLongCount: Int,
        val durationApproxMinMinutes: Int?,
        val durationApproxMaxMinutes: Int?,
        val bestApproxDurationMinutes: Int?,

        val bestApproxDeltaToWindowMinutes: Int?,
        val durationAccurateMinMinutes: Int?,
        val durationAccurateMaxMinutes: Int?,
        val bestAccurateDurationMinutes: Int?,
        val bestAccurateDeltaToTargetMinutes: Int?,
        val effortFailCount: Int,
        val maxEffortSeen: EffortLevel?,
        val openingHoursFailCount: Int,
        val osrmRefineCount: Int,
        val costMin: Double?,
        val costMax: Double?
    )

    companion object {

        private const val HUMIDITY_THRESHOLD_PERCENT = 72

        private const val DURATION_MARGIN_MINUTES = 30

        private const val DURATION_FUZZ_MINUTES = 2

        private const val OSRM_NEAR_MISS_THRESHOLD_MINUTES = 60

        private const val OSRM_MAX_REFINE_PER_TYPE = 12
    }

    private fun durationMarginMinutes(): Int = DURATION_MARGIN_MINUTES

    private fun budgetRatioFor(type: PathType): Double = when (type) {
        PathType.ECONOMIC -> 0.50
        PathType.BALANCED -> 0.75
        PathType.COMFORT -> 1.00
    }

    private fun effectiveMaxBudgetFor(type: PathType, totalBudget: Double?): Double? {
        val budget = totalBudget ?: return null
        if (budget <= 0.0) return null
        return budget * budgetRatioFor(type)
    }

    private fun prereqFailureDetails(): FailureDetails = FailureDetails(
        attempts = 0,
        budgetFailCount = 0,
        durationFailCount = 0,
        durationTooShortCount = 0,
        durationTooLongCount = 0,
        durationApproxMinMinutes = null,
        durationApproxMaxMinutes = null,
        bestApproxDurationMinutes = null,
        bestApproxDeltaToWindowMinutes = null,
        durationAccurateMinMinutes = null,
        durationAccurateMaxMinutes = null,
        bestAccurateDurationMinutes = null,
        bestAccurateDeltaToTargetMinutes = null,
        effortFailCount = 0,
        maxEffortSeen = null,
        openingHoursFailCount = 0,
        osrmRefineCount = 0,
        costMin = null,
        costMax = null
    )

    private fun formatHoursMinutes(totalMinutes: Int): String {
        val m = totalMinutes.coerceAtLeast(0)
        val h = m / 60
        val mm = m % 60
        return "${h}h${mm.toString().padStart(2, '0')}"
    }

    fun getSavedPaths(userId: String): Flow<Result<List<TravelPath>>> = firestore.getPathsByUser(userId)

    suspend fun savePath(path: TravelPath): Result<Unit> = firestore.savePath(path)

    suspend fun deleteSavedPath(pathId: String): Result<Unit> = firestore.deleteSavedPath(pathId)

    suspend fun likePath(pathId: String, userId: String): Result<Unit> = firestore.likePath(pathId, userId)

    suspend fun unlikePath(pathId: String, userId: String): Result<Unit> = firestore.unlikePath(pathId, userId)

    suspend fun isPathLiked(pathId: String, userId: String): Boolean = firestore.isPathLiked(pathId, userId)

    suspend fun generatePaths(
        preferences: PathPreferences,
        city: String,
        userId: String? = null,
        onProgress: (PathGenerationProgress) -> Unit = {}
    ): Flow<Result<List<TravelPath>>> = flow {
        try {
            onProgress(PathGenerationProgress(PathGenerationStage.START, "Preparation de la generation..."))

            onProgress(PathGenerationProgress(PathGenerationStage.LOAD_PLACES, "Chargement des lieux disponibles..."))
            val allPlacesResult = firestore.getAllPlaces().first()
            val allPlaces = allPlacesResult.getOrNull() ?: emptyList()

            onProgress(PathGenerationProgress(PathGenerationStage.FILTER_CITY, "Filtrage des lieux pour $city..."))
            val cityPlaces = filterPlacesByCity(allPlaces, city)
            if (cityPlaces.isEmpty()) {
                emit(Result.failure(Exception("Aucun lieu trouv\u00E9 pour la ville: $city")))
                return@flow
            }
            onProgress(
                PathGenerationProgress(
                    PathGenerationStage.FILTER_CITY,
                    "${cityPlaces.size} lieux trouves pour $city."
                )
            )

            var targetSteps = preferences.maxSteps.coerceIn(1, 8)
            if (preferences.audienceSeniors || preferences.audienceReducedMobility || preferences.audienceHealthSensitivity) {
                targetSteps = minOf(targetSteps, 5)
            }

            val effectivePrefs = preferences.copy(
                maxEffortLevel = run {
                    var e = preferences.maxEffortLevel
                    if (preferences.audienceSeniors || preferences.audienceReducedMobility || preferences.audienceHealthSensitivity) {
                        e = minOf(e, EffortLevel.LOW)
                    }
                    if (preferences.audienceChildren) {
                        e = minOf(e, EffortLevel.MEDIUM)
                    }
                    e
                },
                maxSteps = targetSteps,
                avoidHumidity = preferences.avoidHumidity || preferences.audienceHealthSensitivity
            )

            onProgress(
                PathGenerationProgress(
                    PathGenerationStage.START,
                    buildString {
                        append("Criteres: ")
                        append(effectivePrefs.maxSteps)
                        append(" etapes, effort max ")
                        append(effectivePrefs.maxEffortLevel.getDisplayName())
                        val meteo = mutableListOf<String>()
                        if (effectivePrefs.avoidCold) meteo.add("froid")
                        if (effectivePrefs.avoidHeat) meteo.add("chaleur")
                        if (effectivePrefs.avoidRain) meteo.add("pluie")
                        if (effectivePrefs.avoidHumidity) meteo.add("humidite")
                        if (meteo.isNotEmpty()) {
                            append(", eviter: ")
                            append(meteo.joinToString(", "))
                        }
                    }
                )
            )

            val mustTokens = preferences.mustVisitPlaces.map { it.trim() }.filter { it.isNotEmpty() }
            val elevationCache = mutableMapOf<String, Double?>()
            val walkingMinutesCache = mutableMapOf<String, Int>()
            val routingCallCount = intArrayOf(0)

            var weatherCondition: WeatherCondition? = null
            var humidityPercent: Int? = null
            val needsWeatherFetch = weatherRepository != null &&
                (effectivePrefs.avoidCold || effectivePrefs.avoidHeat || effectivePrefs.avoidRain || effectivePrefs.avoidHumidity)
            if (needsWeatherFetch) {
                try {
                    onProgress(PathGenerationProgress(PathGenerationStage.WEATHER, "Recuperation de la meteo (OpenWeather)..."))
                    val snapshot = weatherRepository?.getCityWeatherSnapshot(city)
                    weatherCondition = snapshot?.condition
                    humidityPercent = snapshot?.humidityPercent
                    Log.d("PathRepository", "Weather for $city: $weatherCondition, humidity=$humidityPercent%")
                    val weatherMsg = buildString {
                        append("Meteo: ")
                        append(weatherCondition?.getDisplayName() ?: "inconnue")
                        if (humidityPercent != null) append(" (humidite $humidityPercent%)")
                    }
                    onProgress(PathGenerationProgress(PathGenerationStage.WEATHER, weatherMsg))
                } catch (e: Exception) {
                    Log.e("PathRepository", "Error fetching weather", e)
                    onProgress(PathGenerationProgress(PathGenerationStage.WEATHER, "Meteo indisponible, generation sans contrainte meteo."))
                }
            }

            val pathSteps = cityPlaces.map { place ->
                placeToPathStep(place, weatherCondition, elevationMeters = null)
            }

            val (mandatoryResolved, unmatchedMust) = resolveMustVisitTokens(mustTokens, pathSteps)
            if (unmatchedMust.isNotEmpty()) {
                emit(Result.failure(Exception(
                    "Lieux obligatoires introuvables dans $city : ${unmatchedMust.joinToString(", ")}"
                )))
                return@flow
            }
            if (mandatoryResolved.size > targetSteps) {
                emit(Result.failure(Exception(
                    "Trop de lieux obligatoires (${mandatoryResolved.size}) pour un parcours de $targetSteps \u00E9tapes."
                )))
                return@flow
            }

            onProgress(PathGenerationProgress(PathGenerationStage.FILTER_PREFERENCES, "Application des filtres (activites, tags, meteo, humidite, effort)..."))
            val beforeFilterCount = pathSteps.size
            val filteredSteps = filterPlacesByPreferences(pathSteps, effectivePrefs, humidityPercent)
            val afterFilterCount = filteredSteps.size
            val filterMsg = buildString {
                append("Filtres appliques: ")
                append(afterFilterCount)
                append("/")
                append(beforeFilterCount)
                append(" lieux restants")
                if (effectivePrefs.avoidHumidity && humidityPercent != null && humidityPercent >= HUMIDITY_THRESHOLD_PERCENT) {
                    append(" (humidite elevee: ")
                    append(humidityPercent)
                    append("%, exterieurs limites)")
                }
            }
            onProgress(PathGenerationProgress(PathGenerationStage.FILTER_PREFERENCES, filterMsg))
            val mandatoryDistinct = mandatoryResolved.distinctBy { it.placeId }
            val excludedMandatory = mandatoryDistinct.filter { m -> filteredSteps.none { it.placeId == m.placeId } }
            if (excludedMandatory.isNotEmpty()) {
                val note = "Lieux obligatoires exclus par les filtres : ${excludedMandatory.joinToString { it.placeName }}"
                val types = listOf(PathType.ECONOMIC, PathType.BALANCED, PathType.COMFORT)
                emit(
                    Result.success(
                        types.map { t -> buildUnavailablePath(t, city, effectivePrefs, prereqFailureDetails(), note) }
                    )
                )
                return@flow
            }

            if (filteredSteps.isEmpty()) {
                val types = listOf(PathType.ECONOMIC, PathType.BALANCED, PathType.COMFORT)
                emit(
                    Result.success(
                        types.map { t ->
                            buildUnavailablePath(t, city, effectivePrefs, prereqFailureDetails(), "Aucun lieu ne correspond aux crit\u00E8res s\u00E9lectionn\u00E9s")
                        }
                    )
                )
                return@flow
            }

            onProgress(PathGenerationProgress(PathGenerationStage.PRICING, "Chargement des prix (selon le creneau horaire)..."))
            val pricesResult = firestore.getPlacePricesByTimeOfDay(userId)
            val pricesByTimeOfDay = pricesResult.getOrNull() ?: emptyMap()

            val fillerNeeded = targetSteps - mandatoryDistinct.size
            if (filteredSteps.size < targetSteps || (fillerNeeded > 0 && filteredSteps.size < mandatoryDistinct.size + fillerNeeded)) {
                val note = "Pas assez de lieux (${filteredSteps.size}) pour g\u00E9n\u00E9rer un parcours de $targetSteps \u00E9tapes"
                val types = listOf(PathType.ECONOMIC, PathType.BALANCED, PathType.COMFORT)
                emit(Result.success(types.map { t -> buildUnavailablePath(t, city, effectivePrefs, prereqFailureDetails(), note) }))
                return@flow
            }

            val minVisitNoWalkMinutes = minVisitDurationNoWalking(mandatoryDistinct, filteredSteps, targetSteps)

            val (options, failures) = generatePathOptions(
                filteredSteps,
                pricesByTimeOfDay,
                effectivePrefs,
                targetSteps,
                city,
                mandatoryDistinct,
                walkingMinutesCache,
                routingCallCount,
                elevationCache,
                onProgress
            )

            if (options.isEmpty()) {
                val margin = durationMarginMinutes()
                val windowMin = (effectivePrefs.maxDurationMinutes - margin).coerceAtLeast(0)
                val windowMax = effectivePrefs.maxDurationMinutes + margin
                val msg = buildString {
                    val margin = durationMarginMinutes()
                    val windowMin = (effectivePrefs.maxDurationMinutes - margin).coerceAtLeast(0)
                    val windowMax = effectivePrefs.maxDurationMinutes + margin
                    append("Impossible de cr\u00E9er un parcours avec ces crit\u00E8res.\n")
                    append("Cible: ")
                    append(effectivePrefs.maxSteps)
                    append(" \u00E9tapes, ")
                    append(effectivePrefs.maxDurationMinutes)
                    append(" min (\u00B1")
                    append(margin)
                    append("), soit ")
                    append(formatHoursMinutes(windowMin))
                    append("â€“")
                    append(formatHoursMinutes(windowMax))
                    append(", effort max ")
                    append(effectivePrefs.maxEffortLevel.getDisplayName())
                    if (effectivePrefs.maxBudget != null) {
                        append(", budget max ")
                        append(effectivePrefs.maxBudget)
                    }
                    append(". Dur\u00E9e mini visite (sans marche): ")
                    append(minVisitNoWalkMinutes)
                    append(" min.\n")

                    val types = listOf(PathType.ECONOMIC, PathType.BALANCED, PathType.COMFORT)
                    types.forEach { type ->
                        val d = failures[type] ?: return@forEach
                        append("â€¢ ")
                        append(type.getDisplayName())
                        append(": ")
                        append(d.attempts)
                        append(" essais, ")
                        append(d.openingHoursFailCount)
                        append(" rejet\u00E9s (horaires), ")
                        append(d.budgetFailCount)
                        append(" rejet\u00E9s (budget), ")
                        append(d.effortFailCount)
                        append(" rejet\u00E9s (effort). ")
                        if (d.durationFailCount > 0) {
                            append("Dur\u00E9e (estimation): ")
                            if (d.durationApproxMinMinutes != null && d.durationApproxMaxMinutes != null) {
                                append(d.durationApproxMinMinutes)
                                append("â€“")
                                append(d.durationApproxMaxMinutes)
                                append(" min (min\u2013max observ\u00E9s). ")
                            }
                            if (d.durationAccurateMinMinutes != null && d.durationAccurateMaxMinutes != null) {
                                append("Dur\u00E9e (OSRM): ")
                                append(d.durationAccurateMinMinutes)
                                append("â€“")
                                append(d.durationAccurateMaxMinutes)
                                append(" min. ")
                            }
                            append("Rejets dur\u00E9e: ")
                            append(d.durationTooShortCount)
                            append(" parcours trop courts, ")
                            append(d.durationTooLongCount)
                            append(" parcours trop longs. ")
                            if (d.bestApproxDurationMinutes != null && d.bestApproxDeltaToWindowMinutes != null) {
                                append("Meilleur (estimation) ")
                                append(d.bestApproxDurationMinutes)
                                append(" min (")
                                val deltaToWindow = d.bestApproxDeltaToWindowMinutes
                                if (deltaToWindow < 0) {
                                    append(abs(deltaToWindow))
                                    append(" min sous le minimum ")
                                    append(windowMin)
                                } else {
                                    append(deltaToWindow)
                                    append(" min au-dessus du maximum ")
                                    append(windowMax)
                                }
                                append("). ")
                            }
                            if (d.bestAccurateDurationMinutes != null) {
                                val best = d.bestAccurateDurationMinutes
                                val deltaToTarget = d.bestAccurateDeltaToTargetMinutes ?: (best - effectivePrefs.maxDurationMinutes)
                                append("Meilleur (OSRM) ")
                                append(best)
                                append(" min (\u00E9cart cible ")
                                if (deltaToTarget >= 0) append("+") else append("")
                                append(deltaToTarget)
                                append(" min, ")
                                val deltaToWindow = when {
                                    best < windowMin -> windowMin - best
                                    best > windowMax -> best - windowMax
                                    else -> 0
                                }
                                if (deltaToWindow == 0) {
                                    append("dans la fen\u00EAtre). ")
                                } else if (best < windowMin) {
                                    append(deltaToWindow)
                                    append(" min sous le minimum). ")
                                } else {
                                    append(deltaToWindow)
                                    append(" min au-dessus du maximum). ")
                                }
                            }
                        }
                        if (d.costMin != null && d.costMax != null) {
                            append("Co\u00FBt: ")
                            append(String.format(Locale.US, "%.0f", d.costMin))
                            append("â€“")
                            append(String.format(Locale.US, "%.0f", d.costMax))
                            append(". ")
                        }
                        if (d.maxEffortSeen != null) {
                            append("Effort max vu: ")
                            append(d.maxEffortSeen.getDisplayName())
                            append(". ")
                        }
                        if (d.osrmRefineCount > 0) {
                            append("Affinage OSRM: ")
                            append(d.osrmRefineCount)
                            append(" candidat(s) \u00E9valu\u00E9(s).")
                        }
                        append("\n")
                    }

                    append("Conseils: r\u00E9duisez le nombre d'\u00E9tapes, augmentez la dur\u00E9e, ou relevez l'effort max; ")
                    append("d\u00E9sactivez des filtres m\u00E9t\u00E9o/humidit\u00E9/tags si besoin.")
                }
                emit(Result.failure(Exception(msg)))
            } else {
                onProgress(PathGenerationProgress(PathGenerationStage.DONE, "Parcours genere(s) avec succes."))
                emit(Result.success(options))
            }
        } catch (e: Exception) {
            Log.e("PathRepository", "Error generating paths", e)
            emit(Result.failure(Exception("Une erreur est survenue lors de la g\u00E9n\u00E9ration. Veuillez r\u00E9essayer.")))
        }
    }

    private fun resolveMustVisitTokens(tokens: List<String>, citySteps: List<PathStep>): Pair<List<PathStep>, List<String>> {
        val matched = mutableListOf<PathStep>()
        val seenIds = mutableSetOf<String>()
        val unmatched = mutableListOf<String>()
        for (token in tokens) {
            val t = token.trim()
            if (t.isEmpty()) continue
            val found = citySteps.firstOrNull { s ->
                s.placeId.equals(t, ignoreCase = true) ||
                    s.placeName.contains(t, ignoreCase = true) ||
                    s.tags.any { tag ->
                        tag.contains(t, ignoreCase = true) || t.contains(tag, ignoreCase = true)
                    }
            }
            if (found != null) {
                if (seenIds.add(found.placeId)) {
                    matched.add(found)
                }
            } else {
                unmatched.add(t)
            }
        }
        return Pair(matched, unmatched)
    }

    private suspend fun generatePathOptions(
        availableSteps: List<PathStep>,
        pricesByTimeOfDay: Map<String, FirestoreRepository.PlacePrices>,
        prefs: PathPreferences,
        targetSteps: Int,
        city: String,
        mandatorySteps: List<PathStep>,
        walkingMinutesCache: MutableMap<String, Int>,
        routingCallCount: IntArray,
        elevationCache: MutableMap<String, Double?>,
        onProgress: (PathGenerationProgress) -> Unit
    ): Pair<List<TravelPath>, Map<PathType, FailureDetails>> {
        val result = mutableListOf<TravelPath>()
        val diagnostics = mutableMapOf<PathType, FailureDetails>()
        val types = listOf(PathType.ECONOMIC, PathType.BALANCED, PathType.COMFORT)
        for (type in types) {
            val stage = when (type) {
                PathType.ECONOMIC -> PathGenerationStage.GENERATE_ECONOMIC
                PathType.BALANCED -> PathGenerationStage.GENERATE_BALANCED
                PathType.COMFORT -> PathGenerationStage.GENERATE_COMFORT
            }
            onProgress(PathGenerationProgress(stage, "Generation option ${type.getDisplayName()}..."))
            val (path, failures) = findOptimizedPath(
                availableSteps,
                pricesByTimeOfDay,
                prefs,
                targetSteps,
                city,
                type,
                mandatorySteps,
                walkingMinutesCache,
                routingCallCount,
                elevationCache,
                onProgress,
                stage
            )
            diagnostics[type] = failures
            result.add(path ?: buildUnavailablePath(type, city, prefs, failures))
        }
        return Pair(result, diagnostics)
    }

    private fun buildUnavailablePath(
        type: PathType,
        city: String,
        prefs: PathPreferences,
        failures: FailureDetails,
        note: String? = null
    ): TravelPath {
        val budgetLimit = effectiveMaxBudgetFor(type, prefs.maxBudget)
        val budgetLine = if (budgetLimit != null) {
            val pct = (budgetRatioFor(type) * 100).roundToInt()
            "Budget cible: $pct% (max ${"%.2f".format(Locale.getDefault(), budgetLimit)}). "
        } else {
            ""
        }

        val description = buildString {
            append("Aucun parcours ${type.getDisplayName()} trouv\u00E9 pour $city. ")
            if (!note.isNullOrBlank()) {
                append(note.trim())
                if (!note.trim().endsWith(".")) append(".")
                append(" ")
            }
            append(budgetLine)
            append("Essais: ${failures.attempts}. Rejets \u2014 budget: ${failures.budgetFailCount}, dur\u00E9e: ${failures.durationFailCount}, effort: ${failures.effortFailCount}, horaires: ${failures.openingHoursFailCount}.")
        }

        return TravelPath(
            id = "unavailable_${type.name.lowercase(Locale.getDefault())}_${city.lowercase(Locale.getDefault())}",
            name = "${type.getDisplayName()} - $city",
            description = description,
            type = type,
            steps = emptyList(),
            totalDurationMinutes = 0,
            totalCost = null,
            hasCompletePricing = false,
            totalEffort = EffortLevel.LOW,
            distanceKm = 0.0,
            city = city
        )
    }

    private suspend fun findOptimizedPath(
        availableSteps: List<PathStep>,
        pricesByTimeOfDay: Map<String, FirestoreRepository.PlacePrices>,
        prefs: PathPreferences,
        targetSteps: Int,
        city: String,
        type: PathType,
        mandatorySteps: List<PathStep>,
        walkingMinutesCache: MutableMap<String, Int>,
        routingCallCount: IntArray,
        elevationCache: MutableMap<String, Double?>,
        onProgress: (PathGenerationProgress) -> Unit,
        stage: PathGenerationStage
    ): Pair<TravelPath?, FailureDetails> {
        var budgetFailCount = 0
        var durationFailCount = 0
        var effortFailCount = 0
        var openingHoursFailCount = 0
        var osrmRefineCount = 0

        var durationTooShortCount = 0
        var durationTooLongCount = 0
        var durationApproxMin: Int? = null
        var durationApproxMax: Int? = null
        var bestApproxDuration: Int? = null
        var bestApproxDeltaToWindow: Int? = null
        var durationAccurateMin: Int? = null
        var durationAccurateMax: Int? = null
        var bestAccurateDuration: Int? = null
        var bestAccurateDeltaToTarget: Int? = null

        var costMin: Double? = null
        var costMax: Double? = null

        var maxEffortSeen: EffortLevel? = null

        val mandatory = mandatorySteps.distinctBy { it.placeId }
        val mandatoryIds = mandatory.map { it.placeId }.toSet()
        val fillerPool = availableSteps.filter { it.placeId !in mandatoryIds }

        val maxBudgetForType = effectiveMaxBudgetFor(type, prefs.maxBudget)

        val maxAttempts = 80
        repeat(maxAttempts) { attempt ->
            if (attempt == 0 || (attempt + 1) % 10 == 0 || attempt == maxAttempts - 1) {
                onProgress(PathGenerationProgress(stage, "Optimisation ${type.getDisplayName()} (tentative ${attempt + 1}/$maxAttempts)...", attempt + 1, maxAttempts))
            }
            val neededFillers = (targetSteps - mandatory.size).coerceAtLeast(0)
            val fillers = if (neededFillers == 0) {
                emptyList()
            } else {
                selectFillSteps(fillerPool, neededFillers, type, attempt)
            }
            if (fillers.size < neededFillers) {
                return@repeat
            }
            val combined = mandatory + fillers
            if (combined.distinctBy { s -> s.placeId }.size != combined.size) {
                return@repeat
            }
            val orderedSteps = orderStepsByNearestNeighbor(combined)
                .mapIndexed { index, step -> step.copy(order = index + 1) }
            val enrichedStepsApprox = applyTimingAndPricing(
                orderedSteps,
                pricesByTimeOfDay,
                prefs,
                walkingMinutesCache,
                routingCallCount,
                onProgress,
                stage,
                accurateRouting = false
            )
            val weekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val hoursOk = enrichedStepsApprox.all { step ->
                OpeningHoursEvaluator.isLikelyOpen(
                    step.openingHours,
                    step.timeOfDay,
                    weekday,
                    step.startTimeMinutesFromMidnight
                )
            }
            if (!hoursOk) {
                openingHoursFailCount++
                return@repeat
            }
            val totalDurationApprox = enrichedStepsApprox.sumOf { s -> s.estimatedDurationMinutes + (s.walkingTimeFromPreviousMinutes ?: 0) }
            val totalCost = enrichedStepsApprox.mapNotNull { it.estimatedCost }.sum()

            durationApproxMin = durationApproxMin?.let { min(it, totalDurationApprox) } ?: totalDurationApprox
            durationApproxMax = durationApproxMax?.let { max(it, totalDurationApprox) } ?: totalDurationApprox
            costMin = costMin?.let { min(it, totalCost) } ?: totalCost
            costMax = costMax?.let { max(it, totalCost) } ?: totalCost

            if (maxBudgetForType != null && totalCost > maxBudgetForType) {
                budgetFailCount++
                return@repeat
            }

            val hops = (orderedSteps.size - 1).coerceAtLeast(0)
            val marginApprox = durationMarginMinutes() + 15 + hops * 20
            val windowMinStrict = (prefs.maxDurationMinutes - durationMarginMinutes()).coerceAtLeast(0)
            val windowMaxStrict = prefs.maxDurationMinutes + durationMarginMinutes()
            val windowMinApprox = (prefs.maxDurationMinutes - marginApprox).coerceAtLeast(0)
            val windowMaxApprox = prefs.maxDurationMinutes + marginApprox
            val deltaToStrictWindowApprox = when {
                totalDurationApprox < windowMinStrict -> totalDurationApprox - windowMinStrict
                totalDurationApprox > windowMaxStrict -> totalDurationApprox - windowMaxStrict
                else -> 0
            }
            if (deltaToStrictWindowApprox != 0) {
                if (bestApproxDeltaToWindow == null || abs(deltaToStrictWindowApprox) < abs(bestApproxDeltaToWindow!!)) {
                    bestApproxDeltaToWindow = deltaToStrictWindowApprox
                    bestApproxDuration = totalDurationApprox
                }
            }

            val approxOutside =
                totalDurationApprox < windowMinApprox - DURATION_FUZZ_MINUTES ||
                    totalDurationApprox > windowMaxApprox + DURATION_FUZZ_MINUTES
            if (approxOutside) {
                val nearMiss = abs(deltaToStrictWindowApprox) <= OSRM_NEAR_MISS_THRESHOLD_MINUTES
                val canRefine = osrmRefineCount < OSRM_MAX_REFINE_PER_TYPE
                if (!nearMiss || !canRefine) {
                    durationFailCount++
                    val delta = totalDurationApprox - prefs.maxDurationMinutes
                    if (delta < 0) durationTooShortCount++ else durationTooLongCount++
                    return@repeat
                }
            }

            val effort = calculateTotalEffort(enrichedStepsApprox, elevationCache)
            maxEffortSeen = maxEffortSeen?.let { if (effort.ordinal > it.ordinal) effort else it } ?: effort
            if (effort.ordinal > prefs.maxEffortLevel.ordinal) {
                effortFailCount++
                return@repeat
            }

            onProgress(PathGenerationProgress(stage, "Affinage des temps de marche (OSRM) pour ${type.getDisplayName()}..."))
            osrmRefineCount++
            val enrichedSteps = applyTimingAndPricing(
                orderedSteps,
                pricesByTimeOfDay,
                prefs,
                walkingMinutesCache,
                routingCallCount,
                onProgress,
                stage,
                accurateRouting = true
            )

            val hoursOkAccurate = enrichedSteps.all { step ->
                OpeningHoursEvaluator.isLikelyOpen(
                    step.openingHours,
                    step.timeOfDay,
                    weekday,
                    step.startTimeMinutesFromMidnight
                )
            }
            if (!hoursOkAccurate) {
                return@repeat
            }

            val totalDuration = enrichedSteps.sumOf { s -> s.estimatedDurationMinutes + (s.walkingTimeFromPreviousMinutes ?: 0) }
            val margin = durationMarginMinutes()
            val windowMin = (prefs.maxDurationMinutes - margin).coerceAtLeast(0)
            val windowMax = prefs.maxDurationMinutes + margin
            if (totalDuration < windowMin - DURATION_FUZZ_MINUTES || totalDuration > windowMax + DURATION_FUZZ_MINUTES) {
                durationFailCount++
                val delta = totalDuration - prefs.maxDurationMinutes
                if (delta < 0) durationTooShortCount++ else durationTooLongCount++
                durationAccurateMin = durationAccurateMin?.let { min(it, totalDuration) } ?: totalDuration
                durationAccurateMax = durationAccurateMax?.let { max(it, totalDuration) } ?: totalDuration
                if (bestAccurateDeltaToTarget == null || abs(delta) < abs(bestAccurateDeltaToTarget!!)) {
                    bestAccurateDeltaToTarget = delta
                    bestAccurateDuration = totalDuration
                }
                return@repeat
            }

            val hasCompletePricing = enrichedSteps.all { it.estimatedCost != null }
            val finalTotalCost = if (hasCompletePricing) enrichedSteps.sumOf { it.estimatedCost ?: 0.0 } else null
            val effectiveType = type

            return Pair(
                TravelPath(
                    id = UUID.randomUUID().toString(),
                    name = "${effectiveType.getDisplayName()} - $city",
                    description = "${effectiveType.getDescription()} \u00E0 $city (ordre optimis\u00E9 pour limiter la marche).",
                    type = effectiveType,
                    steps = enrichedSteps,
                    totalDurationMinutes = totalDuration,
                    totalCost = finalTotalCost,
                    hasCompletePricing = hasCompletePricing,
                    totalEffort = effort,
                    city = city,
                    distanceKm = calculateTotalDistance(enrichedSteps)
                ),
                FailureDetails(
                    attempts = attempt + 1,
                    budgetFailCount = budgetFailCount,
                    durationFailCount = durationFailCount,
                    durationTooShortCount = durationTooShortCount,
                    durationTooLongCount = durationTooLongCount,
                    durationApproxMinMinutes = durationApproxMin,
                    durationApproxMaxMinutes = durationApproxMax,
                    bestApproxDurationMinutes = bestApproxDuration,
                    bestApproxDeltaToWindowMinutes = bestApproxDeltaToWindow,
                    durationAccurateMinMinutes = durationAccurateMin,
                    durationAccurateMaxMinutes = durationAccurateMax,
                    bestAccurateDurationMinutes = bestAccurateDuration,
                    bestAccurateDeltaToTargetMinutes = bestAccurateDeltaToTarget,
                    effortFailCount = effortFailCount,
                    maxEffortSeen = maxEffortSeen,
                    openingHoursFailCount = openingHoursFailCount,
                    osrmRefineCount = osrmRefineCount,
                    costMin = costMin,
                    costMax = costMax
                )
            )
        }

        return Pair(
            null,
            FailureDetails(
                attempts = maxAttempts,
                budgetFailCount = budgetFailCount,
                durationFailCount = durationFailCount,
                durationTooShortCount = durationTooShortCount,
                durationTooLongCount = durationTooLongCount,
                durationApproxMinMinutes = durationApproxMin,
                durationApproxMaxMinutes = durationApproxMax,
                bestApproxDurationMinutes = bestApproxDuration,
                bestApproxDeltaToWindowMinutes = bestApproxDeltaToWindow,
                durationAccurateMinMinutes = durationAccurateMin,
                durationAccurateMaxMinutes = durationAccurateMax,
                bestAccurateDurationMinutes = bestAccurateDuration,
                bestAccurateDeltaToTargetMinutes = bestAccurateDeltaToTarget,
                effortFailCount = effortFailCount,
                maxEffortSeen = maxEffortSeen,
                openingHoursFailCount = openingHoursFailCount,
                osrmRefineCount = osrmRefineCount,
                costMin = costMin,
                costMax = costMax
            )
        )
    }

    private fun selectFillSteps(
        pool: List<PathStep>,
        count: Int,
        type: PathType,
        attemptSeed: Int
    ): List<PathStep> {
        val distinct = pool.distinctBy { it.placeId }
        if (distinct.size < count) return emptyList()
        val rnd = Random(attemptSeed.toLong() * 7919L + type.ordinal * 31L)
        return when (type) {
            PathType.ECONOMIC -> distinct.sortedBy { economicCostHeuristic(it) }.take(count)
            PathType.COMFORT -> distinct.sortedByDescending { comfortHeuristic(it) }.take(count)
            PathType.BALANCED -> distinct.shuffled(rnd).take(count)
        }
    }

    private fun economicCostHeuristic(step: PathStep): Double {
        return when (step.placeType) {
            PlaceType.PARK, PlaceType.STREET, PlaceType.NATURE, PlaceType.MONUMENT -> 0.0
            PlaceType.MUSEUM -> 12.0
            PlaceType.SHOPPING -> 20.0
            PlaceType.RESTAURANT -> 28.0
            PlaceType.BEACH, PlaceType.MOUNTAIN -> 5.0
            PlaceType.OTHER -> 10.0
        }
    }

    private fun comfortHeuristic(step: PathStep): Double {
        return when (step.placeType) {
            PlaceType.MUSEUM -> 12.0
            PlaceType.RESTAURANT -> 10.0
            PlaceType.SHOPPING -> 8.0
            PlaceType.MONUMENT -> 7.0
            PlaceType.PARK -> 5.0
            PlaceType.BEACH -> 9.0
            PlaceType.NATURE, PlaceType.MOUNTAIN -> 4.0
            PlaceType.STREET -> 3.0
            PlaceType.OTHER -> 5.0
        }
    }

    private fun orderStepsByNearestNeighbor(steps: List<PathStep>): List<PathStep> {
        if (steps.size <= 1) return steps
        val centroidLat = steps.map { it.latitude }.average()
        val centroidLng = steps.map { it.longitude }.average()
        val remaining = steps.toMutableList()
        val ordered = mutableListOf<PathStep>()
        var current = remaining.minByOrNull {
            calculateDistance(it.latitude, it.longitude, centroidLat, centroidLng)
        }!!
        remaining.remove(current)
        ordered.add(current)
        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull {
                calculateDistance(current.latitude, current.longitude, it.latitude, it.longitude)
            }!!
            remaining.remove(next)
            ordered.add(next)
            current = next
        }
        return ordered
    }

	    private suspend fun calculateTotalEffort(
	        steps: List<PathStep>,
	        elevationCache: MutableMap<String, Double?>
	    ): EffortLevel {
	        val walkingMinutesTotal = steps.sumOf { it.walkingTimeFromPreviousMinutes ?: 0 }
	        val hasWalkingData = steps.drop(1).any { it.walkingTimeFromPreviousMinutes != null }
	        val distanceKmFallback = if (!hasWalkingData) calculateTotalDistance(steps) else null
	        var elevationGain = 0.0
	        var prevElevation: Double? = null
	        for (step in steps) {
	            val currentElevation = step.elevationMeters ?: run {
	                val repo = elevationRepository ?: return@run null
	                elevationCache.getOrPut(step.placeId) { repo.getElevation(step.latitude, step.longitude) }
	            }
	            if (prevElevation != null && currentElevation != null && currentElevation > prevElevation) {
	                elevationGain += currentElevation - prevElevation
	            }
	            prevElevation = currentElevation
	        }

	        val walkingPoints = if (hasWalkingData) (walkingMinutesTotal / 20.0) else 0.0
	        val elevationPoints = elevationGain / 30.0
	        val distancePoints = distanceKmFallback ?: 0.0
	        val effortPoints = walkingPoints + elevationPoints + distancePoints

	        return when {
	            effortPoints < 3 -> EffortLevel.LOW
	            effortPoints < 7 -> EffortLevel.MEDIUM
	            else -> EffortLevel.HIGH
	        }
	    }

    suspend fun getAvailableCities(): Result<List<String>> {
        return try {
            val allPlacesResult = firestore.getAllPlaces().first()
            val allPlaces = allPlacesResult.getOrNull() ?: emptyList()

            val cities = mutableSetOf<String>()
            allPlaces.forEach { place ->
                if (!place.city.isNullOrBlank()) {
                    cities.add(place.city.trim().replaceFirstChar { it.uppercase() })
                } else {
                    place.address?.let { address ->
                        val city = extractCityFromAddress(address)
                        if (city.isNotEmpty()) {
                            cities.add(city)
                        }
                    }
                }
            }

            if (cities.isEmpty()) {
                cities.add("Paris")
            }

            Result.success(cities.toList().sorted())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun filterPlacesByCity(places: List<Place>, city: String): List<Place> {
        return places.filter { place ->
            place.city?.equals(city, ignoreCase = true) == true ||
            place.address?.contains(city, ignoreCase = true) == true ||
            place.tags.any { it.equals(city, ignoreCase = true) } ||
            place.name.contains(city, ignoreCase = true)
        }
    }

    suspend fun generateClosestPaths(
        preferences: PathPreferences,
        city: String,
        userId: String? = null,
        onProgress: (PathGenerationProgress) -> Unit = {}
    ): Result<ClosestPathsResult> {
        val cityPlacesCount = getPlacesForCity(city).getOrNull()?.size
        val relaxationSteps: List<Pair<String, (PathPreferences) -> PathPreferences>> = buildList {
            add("suppression des tags" to { p: PathPreferences -> p.copy(tags = emptyList()) })
            add("suppression des lieux obligatoires" to { p: PathPreferences -> p.copy(mustVisitPlaces = emptyList()) })
            add("désactivation des contraintes météo" to { p: PathPreferences ->
                p.copy(
                    avoidCold = false,
                    avoidHeat = false,
                    avoidRain = false,
                    avoidHumidity = false
                )
            })
            add("toutes les activités" to { p: PathPreferences -> p.copy(activities = ActivityType.values().toList()) })
            add("augmentation de l'effort max" to { p: PathPreferences -> p.copy(maxEffortLevel = EffortLevel.HIGH) })
            add("désactivation du budget max" to { p: PathPreferences -> p.copy(maxBudget = null) })
            add("réduction du nombre d'étapes" to { p: PathPreferences ->
                val count = cityPlacesCount ?: p.maxSteps
                p.copy(maxSteps = min(p.maxSteps, max(1, count)))
            })
            add("augmentation de la durée max" to { p: PathPreferences -> p.copy(maxDurationMinutes = p.maxDurationMinutes + 120) })
        }

        val appliedRelaxations = mutableListOf<String>()
        var relaxedPrefs = preferences
        for ((reason, relax) in relaxationSteps) {
            val next = relax(relaxedPrefs)
            if (next == relaxedPrefs) continue
            relaxedPrefs = next
            appliedRelaxations.add(reason)

            onProgress(
                PathGenerationProgress(
                    PathGenerationStage.START,
                    "Aucun parcours exact. Recherche du meilleur résultat (${appliedRelaxations.last()})…"
                )
            )

            val result = try {
                generatePaths(relaxedPrefs, city, userId, onProgress).first()
            } catch (e: Exception) {
                Result.failure(e)
            }
            val all = result.getOrNull().orEmpty()
            val real = all.filter { it.steps.isNotEmpty() }
            if (real.isNotEmpty()) {
                val unmetAny = buildUnmetConstraints(preferences, relaxedPrefs, real.firstOrNull(), appliedRelaxations)
                val pathsWithDetails = all.map { p ->
                    val unmet = buildUnmetConstraints(preferences, relaxedPrefs, p, appliedRelaxations)
                    p.copy(description = buildClosestDescription(unmet))
                }
                val note = buildString {
                    append("Parcours les plus proches (")
                    append(appliedRelaxations.joinToString(", "))
                    append(").")
                    if (unmetAny.isNotEmpty()) {
                        append("\n")
                        append(unmetAny.joinToString("\n"))
                    }
                }
                return Result.success(ClosestPathsResult(pathsWithDetails, note, unmetAny))
            }
        }

        val bestEffort = try {
            buildBestEffortPath(preferences, city, userId, onProgress)
        } catch (e: Exception) {
            null
        }
        if (bestEffort != null) {
            val requested = preferences.maxSteps.coerceAtLeast(1)
            val got = bestEffort.steps.size
            val unmet = buildUnmetConstraints(preferences, preferences, bestEffort, emptyList())
            val bestWithDetails = bestEffort.copy(description = buildClosestDescription(unmet))
            val note = buildString {
                if (got < requested) {
                    append("Aucun parcours exact. Voici le meilleur parcours possible (${got}/${requested} étapes).")
                } else {
                    append("Aucun parcours exact. Voici le meilleur parcours possible.")
                }
                if (unmet.isNotEmpty()) {
                    append("\n")
                    append(unmet.joinToString("\n"))
                }
            }
            val types = listOf(PathType.ECONOMIC, PathType.BALANCED, PathType.COMFORT)
            val paths = types.map { t ->
                bestWithDetails.copy(
                    id = UUID.randomUUID().toString(),
                    type = t,
                    name = "${t.getDisplayName()} - $city"
                )
            }
            return Result.success(ClosestPathsResult(paths, note, unmet))
        }

        return Result.failure(Exception("Impossible de proposer des parcours proches avec les donn\u00E9es actuelles."))
    }

    private fun buildClosestDescription(unmetConstraints: List<String>): String {
        if (unmetConstraints.isEmpty()) return "Parcours proposé."
        return buildString {
            append("Contraintes non respectées :\n")
            append(unmetConstraints.joinToString("\n"))
        }
    }

    private fun buildUnmetConstraints(
        original: PathPreferences,
        used: PathPreferences,
        samplePath: TravelPath?,
        appliedRelaxations: List<String>
    ): List<String> {
        val unmetByKey = linkedMapOf<String, String>()

        fun put(key: String, message: String) {
            if (message.isBlank()) return
            unmetByKey.putIfAbsent(key, message)
        }

        fun putOverride(key: String, message: String) {
            if (message.isBlank()) return
            unmetByKey[key] = message
        }

        if (appliedRelaxations.contains("suppression des tags") && original.tags.isNotEmpty()) {
            put(
                "tags",
                "- Contrainte de tags non respectée : aucun parcours possible avec les tags ${original.tags.joinToString(", ")}, ils ont été retirés."
            )
        }
        if (appliedRelaxations.contains("suppression des lieux obligatoires") && original.mustVisitPlaces.isNotEmpty()) {
            put(
                "must_visit",
                "- Contrainte de lieux obligatoires non respectée : aucun parcours possible avec ${original.mustVisitPlaces.joinToString(", ")}, ils ont été retirés."
            )
        }
        if (
            appliedRelaxations.contains("désactivation des contraintes météo") &&
            (original.avoidCold || original.avoidHeat || original.avoidRain || original.avoidHumidity)
        ) {
            val flags = buildList {
                if (original.avoidCold) add("froid")
                if (original.avoidHeat) add("chaleur")
                if (original.avoidRain) add("pluie")
                if (original.avoidHumidity) add("humidité")
            }
            put(
                "weather",
                "- Contrainte météo non respectée : les contraintes (${flags.joinToString(", ")}) ont été désactivées pour pouvoir proposer un parcours."
            )
        }
        if (appliedRelaxations.contains("toutes les activités") && used.activities.size > original.activities.size) {
            put(
                "activities",
                "- Contrainte d'activités non respectée : vos activités sélectionnées étaient trop restrictives, le filtre a été élargi à toutes les activités."
            )
        }
        if (appliedRelaxations.contains("augmentation de l'effort max") && used.maxEffortLevel != original.maxEffortLevel) {
            put(
                "effort",
                "- Contrainte d'effort non respectée : effort max augmenté de ${original.maxEffortLevel.getDisplayName()} à ${used.maxEffortLevel.getDisplayName()} pour trouver un parcours."
            )
        }
        if (appliedRelaxations.contains("désactivation du budget max") && original.maxBudget != null) {
            put(
                "budget",
                "- Contrainte de budget non respectée : budget max (${original.maxBudget}) désactivé pour trouver un parcours."
            )
        }
        if (appliedRelaxations.contains("réduction du nombre d'étapes") && used.maxSteps < original.maxSteps) {
            put(
                "steps",
                "- Contrainte de nombre d'étapes non respectée : impossible de générer ${original.maxSteps} étapes, réduction à ${used.maxSteps}."
            )
        }
        if (appliedRelaxations.contains("augmentation de la durée max") && used.maxDurationMinutes > original.maxDurationMinutes) {
            val margin = durationMarginMinutes()
            put(
                "duration",
                "- Contrainte de durée non respectée : durée max augmentée de ${original.maxDurationMinutes} min à ${used.maxDurationMinutes} min (marge ±$margin)."
            )
        }

        if (samplePath != null) {
            val requestedSteps = original.maxSteps.coerceAtLeast(1)
            if (samplePath.steps.size < requestedSteps) {
                putOverride(
                    "steps",
                    "- Contrainte de nombre d'étapes non respectée : demandé ${requestedSteps}, obtenu ${samplePath.steps.size} (pas assez de lieux compatibles)."
                )
            }
            val margin = durationMarginMinutes()
            val windowMin = (original.maxDurationMinutes - margin).coerceAtLeast(0)
            val windowMax = original.maxDurationMinutes + margin
            if (samplePath.totalDurationMinutes < windowMin || samplePath.totalDurationMinutes > windowMax) {
                putOverride(
                    "duration",
                    "- Contrainte de durée non respectée : durée ${samplePath.totalDurationMinutes} min hors fenêtre ${windowMin}–${windowMax} min."
                )
            }
            if (original.maxBudget != null && samplePath.totalCost != null && samplePath.hasCompletePricing) {
                if (samplePath.totalCost > original.maxBudget) {
                    putOverride(
                        "budget",
                        "- Contrainte de budget non respectée : coût ${samplePath.totalCost} > budget max ${original.maxBudget}."
                    )
                }
            }
            if (samplePath.totalEffort.ordinal > original.maxEffortLevel.ordinal) {
                putOverride(
                    "effort",
                    "- Contrainte d'effort non respectée : effort ${samplePath.totalEffort.getDisplayName()} > effort max ${original.maxEffortLevel.getDisplayName()}."
                )
            }
        }

        return unmetByKey.values.toList()
    }

    private suspend fun buildBestEffortPath(
        preferences: PathPreferences,
        city: String,
        userId: String?,
        onProgress: (PathGenerationProgress) -> Unit
    ): TravelPath? {
        val cityPlaces = getPlacesForCity(city).getOrNull().orEmpty()
        if (cityPlaces.isEmpty()) return null

        val stepsAll = cityPlaces.map { place -> placeToPathStep(place, weatherCondition = null, elevationMeters = null) }
        val stepsFiltered = try {
            filterPlacesByPreferences(stepsAll, preferences, humidityPercent = null)
        } catch (_: Exception) {
            stepsAll
        }
        val pool = if (stepsFiltered.isNotEmpty()) stepsFiltered else stepsAll

        val requestedSteps = preferences.maxSteps.coerceAtLeast(1)
        val takeCount = min(requestedSteps, pool.distinctBy { it.placeId }.size).coerceAtLeast(1)
        val chosen = pool.distinctBy { it.placeId }.take(takeCount)
        val ordered = orderStepsByNearestNeighbor(chosen).mapIndexed { index, step -> step.copy(order = index + 1) }

        onProgress(PathGenerationProgress(PathGenerationStage.PRICING, "Calcul du meilleur parcours possible..."))
        val pricesResult = firestore.getPlacePricesByTimeOfDay(userId)
        val pricesByTimeOfDay = pricesResult.getOrNull() ?: emptyMap()
        val walkingMinutesCache = mutableMapOf<String, Int>()
        val routingCallCount = intArrayOf(0)

        val enriched = applyTimingAndPricing(
            ordered,
            pricesByTimeOfDay,
            preferences,
            walkingMinutesCache,
            routingCallCount,
            onProgress,
            PathGenerationStage.GENERATE_BALANCED,
            accurateRouting = false
        )

        val totalDuration = enriched.sumOf { s -> s.estimatedDurationMinutes + (s.walkingTimeFromPreviousMinutes ?: 0) }
        val hasCompletePricing = enriched.all { it.estimatedCost != null }
        val totalCost = if (hasCompletePricing) enriched.sumOf { it.estimatedCost ?: 0.0 } else null
        val effort = calculateTotalEffort(enriched, mutableMapOf())

        return TravelPath(
            id = UUID.randomUUID().toString(),
            name = "${PathType.BALANCED.getDisplayName()} - $city",
            description = "Parcours propos\u00E9 au plus proche de vos crit\u00E8res (certaines contraintes n'ont pas pu \u00EAtre respect\u00E9es).",
            type = PathType.BALANCED,
            steps = enriched,
            totalDurationMinutes = totalDuration,
            totalCost = totalCost,
            hasCompletePricing = hasCompletePricing,
            totalEffort = effort,
            city = city,
            distanceKm = calculateTotalDistance(enriched)
        )
    }

    suspend fun getPlacesForCity(city: String): Result<List<Place>> {
        return try {
            val allPlacesResult = firestore.getAllPlaces().first()
            val allPlaces = allPlacesResult.getOrNull() ?: emptyList()
            Result.success(filterPlacesByCity(allPlaces, city))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractCityFromAddress(address: String): String {
        val postalCodeRegex = "\\b(\\d{5})\\b".toRegex()
        val match = postalCodeRegex.find(address)
        return if (match != null) {
            val postalCode = match.value
            when {
                postalCode.startsWith("75") -> "Paris"
                postalCode.startsWith("34") -> "Montpellier"
                postalCode.startsWith("13") -> "Marseille"
                postalCode.startsWith("69") -> "Lyon"
                postalCode.startsWith("06") -> "Nice"
                postalCode.startsWith("31") -> "Toulouse"
                postalCode.startsWith("33") -> "Bordeaux"
                postalCode.startsWith("44") -> "Nantes"
                postalCode.startsWith("67") -> "Strasbourg"
                postalCode.startsWith("59") -> "Lille"
                else -> ""
            }
        } else {
            ""
        }
    }

    private fun placeToPathStep(
        place: Place,
        weatherCondition: WeatherCondition? = null,
        elevationMeters: Double? = null
    ): PathStep {
        return PathStep(
            id = "step_${place.id}",
            order = 0,
            placeId = place.id,
            placeName = place.name,
            placeImageUrl = place.imageUrl,
            placeType = place.type,
            latitude = place.latitude,
            longitude = place.longitude,
            activityType = placeTypeToActivityType(place.type),
            estimatedDurationMinutes = estimateDuration(place.type),
            estimatedCost = null,
            effortLevel = estimateEffort(place.type),
            notes = null,
            openingHours = place.openingHours?.takeIf { it.isNotBlank() },
            weatherCondition = weatherCondition,
            tags = place.tags,
            videoUrl = place.videoUrl?.takeIf { !it.isNullOrBlank() },
            elevationMeters = elevationMeters
        )
    }

    private fun placeTypeToActivityType(type: PlaceType): ActivityType {
        return when (type) {
            PlaceType.RESTAURANT -> ActivityType.RESTAURANT
            PlaceType.MUSEUM -> ActivityType.CULTURE
            PlaceType.NATURE -> ActivityType.NATURE
            PlaceType.PARK -> ActivityType.NATURE
            PlaceType.BEACH -> ActivityType.LEISURE
            PlaceType.SHOPPING -> ActivityType.SHOPPING
            PlaceType.MONUMENT -> ActivityType.DISCOVERY
            PlaceType.MOUNTAIN -> ActivityType.NATURE
            PlaceType.STREET -> ActivityType.DISCOVERY
            PlaceType.OTHER -> ActivityType.DISCOVERY
        }
    }

    private fun estimateDuration(type: PlaceType): Int {
        return when (type) {
            PlaceType.RESTAURANT -> 60
            PlaceType.MUSEUM -> 150
            PlaceType.MONUMENT -> 90
            PlaceType.NATURE -> 120
            PlaceType.PARK -> 60
            PlaceType.BEACH -> 180
            PlaceType.SHOPPING -> 90
            PlaceType.MOUNTAIN -> 240
            PlaceType.STREET -> 45
            PlaceType.OTHER -> 60
        }
    }

    private fun estimateEffort(type: PlaceType): EffortLevel {
        return when (type) {
            PlaceType.MOUNTAIN -> EffortLevel.HIGH
            PlaceType.NATURE -> EffortLevel.MEDIUM
            PlaceType.MUSEUM -> EffortLevel.MEDIUM
            PlaceType.MONUMENT -> EffortLevel.LOW
            PlaceType.RESTAURANT -> EffortLevel.LOW
            PlaceType.BEACH -> EffortLevel.LOW
            PlaceType.SHOPPING -> EffortLevel.LOW
            PlaceType.PARK -> EffortLevel.LOW
            PlaceType.STREET -> EffortLevel.LOW
            PlaceType.OTHER -> EffortLevel.LOW
        }
    }

    private fun filterPlacesByPreferences(
        places: List<PathStep>,
        preferences: PathPreferences,
        humidityPercent: Int? = null
    ): List<PathStep> {
        var filtered = places

        if (preferences.activities.isNotEmpty()) {
            filtered = filtered.filter { step ->
                preferences.activities.contains(step.activityType)
            }
        }

	        if (preferences.tags.isNotEmpty()) {
	            filtered = filtered.filter { step ->
	                preferences.tags.any { tag ->
	                    step.tags.any { it.contains(tag, ignoreCase = true) } ||
	                        step.placeName.contains(tag, ignoreCase = true)
	                }
	            }
	        }

	        if (preferences.avoidCold || preferences.avoidHeat || preferences.avoidRain) {
	            filtered = filtered.filter { step ->
	                when {
	                    preferences.avoidCold && step.weatherCondition == WeatherCondition.COLD -> false
                    preferences.avoidHeat && step.weatherCondition == WeatherCondition.HOT -> false
                    preferences.avoidRain && step.weatherCondition == WeatherCondition.RAINY -> false
                    else -> true
                }
            }
        }

        if (preferences.avoidHumidity && humidityPercent != null && humidityPercent >= HUMIDITY_THRESHOLD_PERCENT) {
            filtered = filtered.filter { step -> !isHumiditySensitiveOutdoorStep(step) }
        }

        if (preferences.audienceHealthSensitivity) {
            filtered = filtered.filter { step ->
                when (step.activityType) {
                    ActivityType.NATURE -> step.effortLevel == EffortLevel.LOW
                    ActivityType.LEISURE -> step.effortLevel != EffortLevel.HIGH
                    else -> true
                }
            }
        }

        return filtered
    }


    private fun isHumiditySensitiveOutdoorStep(step: PathStep): Boolean {
        return when (step.placeType) {
            PlaceType.MUSEUM, PlaceType.RESTAURANT, PlaceType.SHOPPING -> false
            else -> true
        }
    }

    private fun minVisitDurationNoWalking(
        mandatorySteps: List<PathStep>,
        availableSteps: List<PathStep>,
        targetSteps: Int
    ): Int {
        val mandatory = mandatorySteps.distinctBy { it.placeId }
        val mandatoryIds = mandatory.map { it.placeId }.toSet()
        val neededFillers = (targetSteps - mandatory.size).coerceAtLeast(0)
        val fillers = if (neededFillers == 0) {
            emptyList()
        } else {
            availableSteps
                .filter { it.placeId !in mandatoryIds }
                .distinctBy { it.placeId }
                .sortedBy { it.estimatedDurationMinutes }
                .take(neededFillers)
        }
        return (mandatory + fillers).sumOf { it.estimatedDurationMinutes }
    }


    private fun calculateTotalDistance(steps: List<PathStep>): Double {
        var totalDistance = 0.0
        for (i in 0 until steps.size - 1) {
            val step1 = steps[i]
            val step2 = steps[i + 1]
            totalDistance += calculateDistance(
                step1.latitude, step1.longitude,
                step2.latitude, step2.longitude
            )
        }
        return totalDistance
    }

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }


    private fun legKey(from: PathStep, to: PathStep): String = "${from.placeId}->${to.placeId}"

    private fun approxLegDurationMinutes(from: PathStep, to: PathStep): Int {
        val distanceKm = calculateDistance(from.latitude, from.longitude, to.latitude, to.longitude)
        return ((distanceKm / 5.0) * 60.0).roundToInt().coerceAtLeast(1)
    }

    private suspend fun legDurationMinutes(
        from: PathStep,
        to: PathStep,
        walkingMinutesCache: MutableMap<String, Int>,
        routingCallCount: IntArray,
        onProgress: (PathGenerationProgress) -> Unit,
        stage: PathGenerationStage
    ): Int {
        val cacheKey = legKey(from, to)
        walkingMinutesCache[cacheKey]?.let { return it }
        val distanceKm = calculateDistance(from.latitude, from.longitude, to.latitude, to.longitude)
        val routing = routingRepository ?: return ((distanceKm / 5.0) * 60.0).roundToInt().coerceAtLeast(1)
        return try {
            routingCallCount[0] += 1
            val n = routingCallCount[0]
            if (n == 1 || n == 10 || n == 25 || (n % 50 == 0)) {
                onProgress(PathGenerationProgress(stage, "Calcul des temps de marche (OSRM)... ($n appels)"))
            }
            val route = routing.getRoute(
                listOf(Pair(from.latitude, from.longitude), Pair(to.latitude, to.longitude)),
                "walking"
            )
            val minutes = if (route != null) {
                (route.duration / 60.0).roundToInt().coerceAtLeast(1)
            } else {
                ((distanceKm / 5.0) * 60.0).roundToInt().coerceAtLeast(1)
            }
            walkingMinutesCache[cacheKey] = minutes
            walkingMinutesCache[legKey(to, from)] = minutes
            minutes
        } catch (_: Exception) {
            val minutes = ((distanceKm / 5.0) * 60.0).roundToInt().coerceAtLeast(1)
            walkingMinutesCache[cacheKey] = minutes
            walkingMinutesCache[legKey(to, from)] = minutes
            minutes
        }
    }

    private suspend fun applyTimingAndPricing(
        orderedSteps: List<PathStep>,
        pricesByTimeOfDay: Map<String, FirestoreRepository.PlacePrices>,
        prefs: PathPreferences,
        walkingMinutesCache: MutableMap<String, Int>,
        routingCallCount: IntArray,
        onProgress: (PathGenerationProgress) -> Unit,
        stage: PathGenerationStage,
        accurateRouting: Boolean
    ): List<PathStep> {
        val departureTimeMinutes = prefs.departureTimeMinutes
        var currentStart = departureTimeMinutes
        val result = mutableListOf<PathStep>()

        orderedSteps.forEachIndexed { index, step ->
            val walkingMinutes = if (index == 0) {
                null
            } else {
                val previous = result.last()
                if (accurateRouting) {
                    legDurationMinutes(previous, step, walkingMinutesCache, routingCallCount, onProgress, stage)
                } else {
                    approxLegDurationMinutes(previous, step)
                }
            }

            if (walkingMinutes != null) {
                currentStart += walkingMinutes
            }

            val slot = slotFromMinutes(currentStart)
            val placePrices = pricesByTimeOfDay[step.placeId]
            val price = when (slot) {
                TimeOfDay.MORNING -> placePrices?.morning
                TimeOfDay.AFTERNOON -> placePrices?.afternoon
                TimeOfDay.EVENING -> placePrices?.evening
            }

            val enriched = step.copy(
                timeOfDay = slot,
                startTimeMinutesFromMidnight = currentStart,
                walkingTimeFromPreviousMinutes = walkingMinutes,
                estimatedCost = price
            )
            result.add(enriched)
            currentStart += step.estimatedDurationMinutes
        }

        return result
    }

    private fun slotFromMinutes(minutesFromMidnight: Int): TimeOfDay {
        val normalized = ((minutesFromMidnight % (24 * 60)) + (24 * 60)) % (24 * 60)
        return when {
            normalized < 12 * 60 -> TimeOfDay.MORNING
            normalized < 18 * 60 -> TimeOfDay.AFTERNOON
            else -> TimeOfDay.EVENING
        }
    }
}
