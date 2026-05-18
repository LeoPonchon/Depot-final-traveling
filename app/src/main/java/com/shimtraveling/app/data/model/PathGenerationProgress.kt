package com.shimtraveling.data.model

enum class PathGenerationStage {
    START,
    LOAD_PLACES,
    FILTER_CITY,
    WEATHER,
    FILTER_PREFERENCES,
    PRICING,
    GENERATE_ECONOMIC,
    GENERATE_BALANCED,
    GENERATE_COMFORT,
    FINALIZE,
    DONE
}

data class PathGenerationProgress(
    val stage: PathGenerationStage,
    val message: String,
    val current: Int? = null,
    val total: Int? = null
)

