package com.aegis.core.util

import kotlinx.serialization.Serializable

/** ISO day numbering: Monday = 1 … Sunday = 7. */
object Days {
    const val MONDAY = 1
    const val TUESDAY = 2
    const val WEDNESDAY = 3
    const val THURSDAY = 4
    const val FRIDAY = 5
    const val SATURDAY = 6
    const val SUNDAY = 7

    val ALL: Set<Int> = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)
    val WEEKDAYS: Set<Int> = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    val WEEKEND: Set<Int> = setOf(SATURDAY, SUNDAY)

    fun shortLabel(day: Int): String = when (day) {
        MONDAY -> "Mon"; TUESDAY -> "Tue"; WEDNESDAY -> "Wed"; THURSDAY -> "Thu"
        FRIDAY -> "Fri"; SATURDAY -> "Sat"; SUNDAY -> "Sun"; else -> "?"
    }
}

/**
 * Local calendar arithmetic done by hand.
 *
 * Deliberately not `java.time`: this same code runs on the phone, and plain arithmetic
 * needs no library desugaring, no time-zone database lookup on a hot path, and — the
 * reason that actually matters — it is trivial to test at any instant in history.
 */
object LocalTime {
    const val MILLIS_PER_MINUTE = 60_000L
    const val MINUTES_PER_DAY = 1_440
    const val MILLIS_PER_DAY = MINUTES_PER_DAY * MILLIS_PER_MINUTE

    fun localMillis(utcMillis: Long, utcOffsetMinutes: Int): Long =
        utcMillis + utcOffsetMinutes * MILLIS_PER_MINUTE

    /** Days since 1970-01-01 in local time. This is the key a daily budget resets on. */
    fun epochDay(utcMillis: Long, utcOffsetMinutes: Int): Long =
        Math.floorDiv(localMillis(utcMillis, utcOffsetMinutes), MILLIS_PER_DAY)

    fun minuteOfDay(utcMillis: Long, utcOffsetMinutes: Int): Int =
        (Math.floorMod(localMillis(utcMillis, utcOffsetMinutes), MILLIS_PER_DAY) / MILLIS_PER_MINUTE).toInt()

    /** ISO day of week. 1970-01-01 was a Thursday, which anchors the arithmetic. */
    fun dayOfWeek(utcMillis: Long, utcOffsetMinutes: Int): Int =
        (Math.floorMod(epochDay(utcMillis, utcOffsetMinutes) + 3, 7L) + 1).toInt()

    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val normalised = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        val hours = normalised / 60
        val minutes = normalised % 60
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
    }

    fun formatDuration(minutes: Int): String = when {
        minutes <= 0 -> "0m"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

/**
 * A recurring local-time window, e.g. "nothing social before 09:00 or after 22:00".
 *
 * Windows that wrap past midnight are supported and are the common case for a sleep
 * rule, so [contains] handles `start > end` rather than making the caller split it.
 */
@Serializable
data class TimeWindow(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val days: Set<Int> = Days.ALL,
    val label: String = "",
) {
    val wrapsMidnight: Boolean get() = startMinuteOfDay > endMinuteOfDay

    fun contains(utcMillis: Long, utcOffsetMinutes: Int): Boolean {
        val minute = LocalTime.minuteOfDay(utcMillis, utcOffsetMinutes)
        val day = LocalTime.dayOfWeek(utcMillis, utcOffsetMinutes)
        return if (!wrapsMidnight) {
            day in days && minute >= startMinuteOfDay && minute < endMinuteOfDay
        } else {
            // The tail after midnight belongs to the window that started the previous day.
            val previousDay = if (day == Days.MONDAY) Days.SUNDAY else day - 1
            (day in days && minute >= startMinuteOfDay) ||
                (previousDay in days && minute < endMinuteOfDay)
        }
    }

    fun describe(): String {
        val range = "${LocalTime.formatMinuteOfDay(startMinuteOfDay)}–${LocalTime.formatMinuteOfDay(endMinuteOfDay)}"
        val dayPart = when (days) {
            Days.ALL -> "every day"
            Days.WEEKDAYS -> "weekdays"
            Days.WEEKEND -> "weekends"
            else -> days.sorted().joinToString(", ") { Days.shortLabel(it) }
        }
        return if (label.isBlank()) "$range, $dayPart" else "$label ($range, $dayPart)"
    }

    companion object {
        fun of(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int, days: Set<Int> = Days.ALL) =
            TimeWindow(startHour * 60 + startMinute, endHour * 60 + endMinute, days)
    }
}
