package com.shimtraveling.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize


@Parcelize
data class OpenTimeRange(val openMinutes: Int, val closeMinutes: Int) : Parcelable


@Parcelize
data class DayOpeningSchedule(val dayOfWeek: Int, val ranges: List<OpenTimeRange> = emptyList()) : Parcelable


@Parcelize
data class StructuredOpeningHours(
    val timeZoneId: String = "Europe/Paris",
    val weekly: List<DayOpeningSchedule> = emptyList()
) : Parcelable
