package com.aegis.app.engine

import com.aegis.core.util.Clock
import java.util.TimeZone

/**
 * The clock Aegis uses on a phone.
 *
 * The distinction this class exists for: `System.nanoTime()`, which the core's default
 * JVM clock uses, **stops advancing while the device is in deep sleep**. A phone left on a
 * bedside table overnight might report twenty minutes of monotonic time across eight real
 * hours. Since the cooling-off period (§3.6) refuses to release a change until the
 * monotonic deadline has also passed, using `nanoTime` would mean a 24-hour delay that
 * silently stretches to days for anyone who sleeps — punishing the honest case while doing
 * nothing extra against the dishonest one.
 *
 * `android.os.SystemClock.elapsedRealtime()` counts time spent in deep sleep, which is
 * exactly the semantics the delay needs: real time passing, unaffected by the user editing
 * the date in Settings.
 */
object AndroidClock : Clock {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()

    override fun utcOffsetMinutes(): Int =
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
}
