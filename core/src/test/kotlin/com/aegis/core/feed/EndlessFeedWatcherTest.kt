package com.aegis.core.feed

import com.aegis.core.rules.FeedRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The behaviour here takes twenty minutes to happen on a real phone, which is exactly why
 * it is modelled as pure logic over an injected clock reading. Every case below would
 * otherwise be a half-hour of scrolling to find out.
 */
class EndlessFeedWatcherTest {

    private val instagram = "com.instagram.android"
    private val rule = FeedRule(
        enabled = true,
        afterMinutes = 10,
        remindEveryMinutes = 5,
        minScrolls = 25,
        idleGapSeconds = 90,
        packageNames = setOf(instagram),
    )

    private fun minutes(value: Int) = value * 60_000L

    /** Scroll steadily for [forMinutes], one flick every 10 seconds. */
    private fun EndlessFeedWatcher.scrollSteadily(
        forMinutes: Int,
        from: Long = 0L,
        rule: FeedRule,
        packageName: String = "com.instagram.android",
    ): List<FeedVerdict.Interrupt> {
        val interrupts = mutableListOf<FeedVerdict.Interrupt>()
        var now = from
        val until = from + minutes(forMinutes)
        while (now <= until) {
            (onScroll(now, packageName, rule) as? FeedVerdict.Interrupt)?.let { interrupts += it }
            now += 10_000L
        }
        return interrupts
    }

    @Test
    fun `says nothing for the first ten minutes`() {
        val watcher = EndlessFeedWatcher()
        val interrupts = watcher.scrollSteadily(forMinutes = 9, rule = rule)
        assertTrue(interrupts.isEmpty(), "interrupted early: $interrupts")
    }

    @Test
    fun `speaks up once the run passes the limit`() {
        val watcher = EndlessFeedWatcher()
        val interrupts = watcher.scrollSteadily(forMinutes = 11, rule = rule)

        assertEquals(1, interrupts.size, "expected exactly one interruption, got $interrupts")
        assertEquals(10, interrupts.first().minutes)
        assertTrue(!interrupts.first().repeat, "the first interruption is not a repeat")
    }

    @Test
    fun `keeps reminding, but only at the reminder interval`() {
        val watcher = EndlessFeedWatcher()
        // 26 minutes: interruptions at 10, 15, 20 and 25.
        val interrupts = watcher.scrollSteadily(forMinutes = 26, rule = rule)

        assertEquals(listOf(10, 15, 20, 25), interrupts.map { it.minutes })
        assertTrue(interrupts.drop(1).all { it.repeat }, "everything after the first is a repeat")
    }

    @Test
    fun `an app left open on the table is not scrolling`() {
        // Duration alone must never be enough. Five flicks over half an hour is somebody
        // glancing at their phone, not a feed.
        val watcher = EndlessFeedWatcher()
        var now = 0L
        val interrupts = mutableListOf<FeedVerdict.Interrupt>()
        repeat(5) {
            (watcher.onScroll(now, instagram, rule) as? FeedVerdict.Interrupt)?.let { interrupts += it }
            now += 80_000L
        }
        assertTrue(interrupts.isEmpty(), "fired without enough scrolling: $interrupts")
    }

    @Test
    fun `a real break starts the count again`() {
        val watcher = EndlessFeedWatcher()
        watcher.scrollSteadily(forMinutes = 9, rule = rule)

        // Put the phone down for five minutes — well past the 90-second gap.
        val after = watcher.scrollSteadily(forMinutes = 9, from = minutes(14), rule = rule)

        assertTrue(after.isEmpty(), "a five-minute break should not carry the old run over")
    }

    @Test
    fun `glancing at a notification does not hand back a fresh ten minutes`() {
        // The failure this guards against: a gap short enough to be a glance resetting the
        // clock, so the counter never reaches the limit and the feature silently never
        // fires. Anyone bouncing between two apps would get infinite scrolling for free.
        val watcher = EndlessFeedWatcher()
        var interrupts = watcher.scrollSteadily(forMinutes = 8, rule = rule)
        assertTrue(interrupts.isEmpty())

        // 60 seconds away, inside the idle gap.
        interrupts = watcher.scrollSteadily(forMinutes = 4, from = minutes(9), rule = rule)

        assertTrue(interrupts.isNotEmpty(), "the run should have continued through the glance")
    }

    @Test
    fun `scrolling somewhere else entirely ends the run`() {
        val watcher = EndlessFeedWatcher()
        watcher.scrollSteadily(forMinutes = 9, rule = rule)

        // One scroll in an app the rule does not name.
        val verdict = watcher.onScroll(minutes(9) + 1_000L, "com.android.settings", rule)
        assertIs<FeedVerdict.Quiet>(verdict)

        val after = watcher.scrollSteadily(forMinutes = 5, from = minutes(9) + 2_000L, rule = rule)
        assertTrue(after.isEmpty(), "the run should have been abandoned, not resumed")
    }

    @Test
    fun `a disabled rule never fires, whatever the scrolling looks like`() {
        val watcher = EndlessFeedWatcher()
        val off = rule.copy(enabled = false)
        val interrupts = watcher.scrollSteadily(forMinutes = 40, rule = off)
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun `an app that is not watched never fires`() {
        val watcher = EndlessFeedWatcher()
        val interrupts = watcher.scrollSteadily(
            forMinutes = 40,
            rule = rule,
            packageName = "com.example.notwatched",
        )
        assertTrue(interrupts.isEmpty())
    }

    @Test
    fun `a clock that jumps backwards does not erase a run`() {
        // The monotonic reading should never go backwards, but this is a self-control tool
        // and the user is the adversary. If it ever does, the safe answer is to treat it
        // as a new run rather than to produce a negative elapsed time and go quiet forever.
        val watcher = EndlessFeedWatcher()
        watcher.scrollSteadily(forMinutes = 11, rule = rule)

        val verdict = watcher.onScroll(minutes(2), instagram, rule)
        assertIs<FeedVerdict.Quiet>(verdict)

        val after = watcher.scrollSteadily(forMinutes = 11, from = minutes(2), rule = rule)
        assertTrue(after.isNotEmpty(), "it should still be counting after the jump")
    }
}
