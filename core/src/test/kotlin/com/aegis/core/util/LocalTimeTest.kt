package com.aegis.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalTimeTest {

    private val day = LocalTime.MILLIS_PER_DAY

    @Test
    fun `the epoch was a Thursday`() {
        // The anchor the rest of the day-of-week arithmetic hangs off.
        assertEquals(Days.THURSDAY, LocalTime.dayOfWeek(0, 0))
        assertEquals(Days.MONDAY, LocalTime.dayOfWeek(19_681L * day, 0))
        assertEquals(Days.FRIDAY, LocalTime.dayOfWeek(19_685L * day, 0))
        assertEquals(Days.FRIDAY, LocalTime.dayOfWeek(day, 0))
        assertEquals(Days.WEDNESDAY, LocalTime.dayOfWeek(-day, 0))
    }

    @Test
    fun `the local day boundary follows the time zone`() {
        val utcMidnight = 10 * day
        assertEquals(10, LocalTime.epochDay(utcMidnight, 0))
        // One hour behind UTC: it is still the previous evening.
        assertEquals(9, LocalTime.epochDay(utcMidnight, -60))
        assertEquals(23 * 60, LocalTime.minuteOfDay(utcMidnight, -60))
    }

    @Test
    fun `dates before the epoch do not wrap`() {
        assertEquals(-1, LocalTime.epochDay(-1, 0))
        assertEquals(1_439, LocalTime.minuteOfDay(-60_000, 0))
    }

    @Test
    fun `a plain window contains the hours between its ends`() {
        val nineToFive = TimeWindow.of(9, 0, 17, 0)
        val monday = 19_681L * day // 2023-11-20 was a Monday

        assertFalse(nineToFive.contains(monday + 8 * 3_600_000, 0))
        assertTrue(nineToFive.contains(monday + 9 * 3_600_000, 0))
        assertTrue(nineToFive.contains(monday + 16 * 3_600_000 + 59 * 60_000, 0))
        assertFalse(nineToFive.contains(monday + 17 * 3_600_000, 0))
    }

    @Test
    fun `a window that wraps midnight covers both sides of it`() {
        // "No social after 22:00 until 07:00" is the common case and must just work.
        val overnight = TimeWindow.of(22, 0, 7, 0)
        val monday = 19_678L * day

        assertTrue(overnight.contains(monday + 23 * 3_600_000, 0))
        assertTrue(overnight.contains(monday + day + 2 * 3_600_000, 0), "02:00 the next morning")
        assertFalse(overnight.contains(monday + day + 8 * 3_600_000, 0))
        assertTrue(overnight.wrapsMidnight)
    }

    @Test
    fun `a wrapping window respects which days it was set for`() {
        val fridayNight = TimeWindow.of(22, 0, 3, 0, setOf(Days.FRIDAY))
        val friday = 19_685L * day // 2023-11-24, a Friday

        assertTrue(fridayNight.contains(friday + 23 * 3_600_000, 0))
        assertTrue(fridayNight.contains(friday + day + 3_600_000, 0), "01:00 Saturday belongs to Friday night")
        assertFalse(fridayNight.contains(friday + day + 23 * 3_600_000, 0), "Saturday night is not covered")
    }

    @Test
    fun `windows describe themselves in a form a block screen can print`() {
        assertEquals("09:00–17:00, weekdays", TimeWindow.of(9, 0, 17, 0, Days.WEEKDAYS).describe())
        assertEquals("22:00–07:00, every day", TimeWindow.of(22, 0, 7, 0).describe())
    }

    @Test
    fun `durations read the way a person would say them`() {
        assertEquals("0m", LocalTime.formatDuration(0))
        assertEquals("45m", LocalTime.formatDuration(45))
        assertEquals("1h", LocalTime.formatDuration(60))
        assertEquals("2h 30m", LocalTime.formatDuration(150))
    }
}
