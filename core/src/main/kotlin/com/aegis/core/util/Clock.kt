package com.aegis.core.util

/**
 * Time, injected.
 *
 * Every self-control feature in Aegis is ultimately a statement about time — cooling-off
 * delays, daily budgets, scheduled windows, grace-tap pauses. Reading the clock through
 * this interface is what makes all of that testable without waiting 24 hours, and it is
 * also the seam where "the user set their phone clock back an hour" gets handled in
 * one place instead of seven.
 */
interface Clock {
    /** Wall-clock time, milliseconds since the Unix epoch. */
    fun nowMillis(): Long

    /**
     * Monotonic time since boot, milliseconds. Unaffected by the user changing the
     * system clock, so anything that must not be cheatable is measured with this.
     */
    fun elapsedRealtimeMillis(): Long

    /** Offset from UTC in minutes for the device's current time zone. */
    fun utcOffsetMinutes(): Int
}

/**
 * Plain-JVM clock, used by tests and by anything running off-device.
 *
 * Note for Android callers: `System.nanoTime()` does not advance during deep sleep, so a
 * multi-hour delay measured with it would stretch across a night on a phone. The app
 * module supplies its own `Clock` backed by `SystemClock.elapsedRealtime()` for that
 * reason, and this implementation should not be used there.
 */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000
    override fun utcOffsetMinutes(): Int =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
}

/** Deterministic clock for tests and previews. */
class FakeClock(
    private var wallMillis: Long = 0L,
    private var monotonicMillis: Long = 0L,
    private var offsetMinutes: Int = 0,
) : Clock {
    override fun nowMillis(): Long = wallMillis
    override fun elapsedRealtimeMillis(): Long = monotonicMillis
    override fun utcOffsetMinutes(): Int = offsetMinutes

    /** Advance both clocks, as real time passing would. */
    fun advance(millis: Long) {
        wallMillis += millis
        monotonicMillis += millis
    }

    /** Move only the wall clock — what a user gets by editing the date in Settings. */
    fun tamperWallClock(millis: Long) {
        wallMillis += millis
    }

    fun setWallClock(millis: Long) {
        wallMillis = millis
    }

    /** Simulate a reboot: the monotonic clock restarts from near zero, the wall clock does not. */
    fun setMonotonicForReboot(millis: Long) {
        monotonicMillis = millis
    }

    fun setUtcOffsetMinutes(minutes: Int) {
        offsetMinutes = minutes
    }
}
