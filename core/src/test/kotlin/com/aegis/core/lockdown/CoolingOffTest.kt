package com.aegis.core.lockdown

import com.aegis.core.model.Category
import com.aegis.core.rules.AccountabilityPartner
import com.aegis.core.rules.AppRule
import com.aegis.core.rules.CategoryRule
import com.aegis.core.rules.RuleMode
import com.aegis.core.feed.ShortFormRule
import com.aegis.core.feed.ShortFormSurface
import com.aegis.core.rules.FeedRule
import com.aegis.core.rules.RuleSet
import com.aegis.core.util.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §3.6 is the feature the whole product rests on, so these tests are written as attacks
 * on it rather than as demonstrations of it.
 */
class CoolingOffTest {

    private val hour = 3_600_000L

    /** Armed, i.e. the state the lock is actually meant to be tested in. */
    private fun setup(): Triple<FakeClock, CoolingOff, RuleSet> {
        val clock = FakeClock(wallMillis = 1_700_000_000_000, monotonicMillis = 500_000)
        return Triple(clock, CoolingOff(clock), RuleSet.defaults().copy(armed = true))
    }

    @Test
    fun `tightening a rule takes effect immediately`() {
        val (_, coolingOff, rules) = setup()
        val proposed = rules.withCategoryRule(
            CategoryRule(Category.VIOLENCE, RuleMode.WALL),
        )

        val outcome = coolingOff.submit(rules, proposed, idSeed = "t1")

        assertNull(outcome.queued)
        assertEquals(RuleMode.WALL, outcome.appliedNow.ruleFor(Category.VIOLENCE).mode)
    }

    @Test
    fun `weakening a rule does not take effect now`() {
        val (_, coolingOff, rules) = setup()
        val proposed = rules.withCategoryRule(
            CategoryRule(Category.ADULT, RuleMode.OFF),
        )

        val outcome = coolingOff.submit(rules, proposed, idSeed = "t2")

        assertEquals(RuleMode.WALL, outcome.appliedNow.ruleFor(Category.ADULT).mode)
        assertNotNull(outcome.queued)
        assertEquals(1, outcome.queued!!.deltas.size)
    }

    @Test
    fun `a loosening cannot be smuggled through attached to a tightening`() {
        // The obvious attack: bundle "turn off adult" with "wall gambling" and hope the
        // whole edit is judged as one act.
        val (_, coolingOff, rules) = setup()
        val proposed = rules
            .withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF))
            .withCategoryRule(CategoryRule(Category.VIOLENCE, RuleMode.WALL))

        val outcome = coolingOff.submit(rules, proposed, idSeed = "t3")

        assertEquals(RuleMode.WALL, outcome.appliedNow.ruleFor(Category.VIOLENCE).mode, "tightening should land")
        assertEquals(RuleMode.WALL, outcome.appliedNow.ruleFor(Category.ADULT).mode, "loosening must not land")
        assertEquals(1, outcome.queuedDeltas.size)
    }

    @Test
    fun `the delay actually has to pass`() {
        val (clock, coolingOff, rules) = setup()
        val outcome = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "t4",
        )
        val pending = outcome.queued!!

        assertFalse(coolingOff.isReady(pending))

        clock.advance(23 * hour)
        assertFalse(coolingOff.isReady(pending), "23 hours into a 24-hour wait")

        clock.advance(1 * hour)
        assertTrue(coolingOff.isReady(pending))

        val due = coolingOff.applyDue(outcome.appliedNow, listOf(pending))
        assertEquals(RuleMode.OFF, due.rules.ruleFor(Category.ADULT).mode)
        assertTrue(due.stillPending.isEmpty())
    }

    @Test
    fun `winding the system clock forward does not release a pending change`() {
        // The one-minute version of the attack this feature exists to survive.
        val (clock, coolingOff, rules) = setup()
        val pending = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "t5",
        ).queued!!

        clock.tamperWallClock(48 * hour)

        assertFalse(
            coolingOff.isReady(pending),
            "the wall clock moved but no real time passed; the change must stay queued",
        )
        assertTrue(coolingOff.minutesRemaining(pending) > 23 * 60)
    }

    @Test
    fun `after a reboot the wall clock is trusted`() {
        val (clock, coolingOff, rules) = setup()
        val pending = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "t6",
        ).queued!!

        // A reboot resets the monotonic clock; real time still passed.
        clock.setWallClock(pending.effectiveAtMillis + 1000)
        clock.setMonotonicForReboot(2_000)

        assertTrue(coolingOff.isReady(pending))
    }

    @Test
    fun `shortening the cooling-off period must sit through the current one`() {
        // Otherwise the first move in any weak moment is "set the delay to zero".
        val (clock, coolingOff, rules) = setup()
        val outcome = coolingOff.submit(rules, rules.copy(coolingOffHours = 0), idSeed = "t7")

        assertEquals(24, outcome.appliedNow.coolingOffHours)
        val pending = outcome.queued!!
        assertEquals(pending.requestedAtMillis + 24 * hour, pending.effectiveAtMillis)

        clock.advance(24 * hour)
        val due = coolingOff.applyDue(outcome.appliedNow, listOf(pending))
        assertEquals(0, due.rules.coolingOffHours)
    }

    @Test
    fun `lengthening the cooling-off period is immediate`() {
        val (_, coolingOff, rules) = setup()
        val outcome = coolingOff.submit(rules, rules.copy(coolingOffHours = 72), idSeed = "t8")

        assertNull(outcome.queued)
        assertEquals(72, outcome.appliedNow.coolingOffHours)
    }

    @Test
    fun `removing an accountability partner is a weakening`() {
        val (_, coolingOff, base) = setup()
        val rules = base.copy(partner = AccountabilityPartner("Sam", "sam@example.com"))

        val outcome = coolingOff.submit(rules, rules.copy(partner = null), idSeed = "t9")

        assertNotNull(outcome.queued)
        assertNotNull(outcome.appliedNow.partner)
    }

    @Test
    fun `cancelling a queued weakening is instant`() {
        val (_, coolingOff, rules) = setup()
        val pending = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "t10",
        ).queued!!

        val remaining = coolingOff.cancel(listOf(pending), "t10")
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `unblocking an app waits, blocking one does not`() {
        val (_, coolingOff, base) = setup()
        val rules = base.withAppRule(AppRule("com.example.social", "Social", blocked = true))

        val unblock = coolingOff.submit(
            rules,
            rules.withAppRule(AppRule("com.example.social", "Social", blocked = false)),
            idSeed = "t11",
        )
        assertNotNull(unblock.queued)
        assertTrue(unblock.appliedNow.ruleFor("com.example.social")!!.blocked)

        val blockAnother = coolingOff.submit(
            rules,
            rules.withAppRule(AppRule("com.example.other", "Other", blocked = true)),
            idSeed = "t12",
        )
        assertNull(blockAnother.queued)
    }

    @Test
    fun `raising a daily budget waits, lowering it does not`() {
        val (_, coolingOff, rules) = setup()
        val social = rules.budgets.first { it.id == "social" }

        val raise = coolingOff.submit(
            rules,
            rules.copy(budgets = listOf(social.copy(dailyMinutes = 180))),
            idSeed = "t13",
        )
        assertNotNull(raise.queued)

        val lower = coolingOff.submit(
            rules,
            rules.copy(budgets = listOf(social.copy(dailyMinutes = 20))),
            idSeed = "t14",
        )
        assertNull(lower.queued)
        assertEquals(20, lower.appliedNow.budgets.first { it.id == "social" }.dailyMinutes)
    }

    @Test
    fun `removing a never-reach destination waits`() {
        val (_, coolingOff, base) = setup()
        val rules = base.withDestinationRule(
            com.aegis.core.rules.DestinationRule(host = "facebook.com"),
        )

        val outcome = coolingOff.submit(rules, rules.withoutDestination("facebook.com"), idSeed = "t15")

        assertNotNull(outcome.queued)
        assertEquals(1, outcome.appliedNow.destinationRules.size)
    }

    @Test
    fun `an edit that changes nothing queues nothing`() {
        val (_, coolingOff, rules) = setup()
        val outcome = coolingOff.submit(rules, rules, idSeed = "t16")

        assertNull(outcome.queued)
        assertTrue(outcome.appliedDeltas.isEmpty())
    }

    // ------------------------------------------------------------------ setup mode

    @Test
    fun `before the lock is armed, every change applies immediately`() {
        // The bug this exists to prevent: a fresh install where the first edit to any
        // setting silently queues for 24 hours, so the app cannot be configured at all.
        val clock = FakeClock(wallMillis = 1_700_000_000_000, monotonicMillis = 500_000)
        val coolingOff = CoolingOff(clock)
        val rules = RuleSet.defaults()

        assertFalse(rules.armed, "a fresh install must start unarmed")

        val outcome = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "s1",
        )

        assertNull(outcome.queued, "setup-mode changes must not queue")
        assertEquals(RuleMode.OFF, outcome.appliedNow.ruleFor(Category.ADULT).mode)
    }

    @Test
    fun `arming is instant, disarming is not`() {
        val clock = FakeClock(wallMillis = 1_700_000_000_000, monotonicMillis = 500_000)
        val coolingOff = CoolingOff(clock)
        val unarmed = RuleSet.defaults()

        val armed = coolingOff.submit(unarmed, unarmed.copy(armed = true), idSeed = "s2")
        assertNull(armed.queued)
        assertTrue(armed.appliedNow.armed)

        val disarm = coolingOff.submit(armed.appliedNow, armed.appliedNow.copy(armed = false), idSeed = "s3")
        assertNotNull(disarm.queued, "unlocking must wait, or the lock means nothing")
        assertTrue(disarm.appliedNow.armed)
        assertEquals("Unlock rules for editing", disarm.queued!!.summary)
    }

    @Test
    fun `once armed, weakening is delayed again`() {
        val (_, coolingOff, rules) = setup()
        val outcome = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "s4",
        )
        assertNotNull(outcome.queued)
    }

    // --------------------------------------------------------------- superseding

    @Test
    fun `tapping the same control repeatedly leaves one queued change, not four`() {
        val (_, coolingOff, rules) = setup()
        var pending = emptyList<PendingChange>()

        for ((index, mode) in listOf(RuleMode.WARN, RuleMode.TIMED, RuleMode.OFF).withIndex()) {
            val outcome = coolingOff.submit(
                rules,
                rules.withCategoryRule(CategoryRule(Category.ADULT, mode)),
                idSeed = "tap$index",
            )
            pending = coolingOff.enqueue(pending, outcome.queued!!)
        }

        assertEquals(1, pending.size, "one target should have one queued change")
        assertEquals(
            "Adult / sexual content: Never → Allowed",
            pending.single().summary,
            "the last tap wins",
        )
    }

    @Test
    fun `changing your mind does not restart the countdown`() {
        // Otherwise fiddling with a control resets the wait forever, and the delay is
        // only ever as long as the gap between two taps.
        val (clock, coolingOff, rules) = setup()
        val first = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.WARN)),
            idSeed = "m1",
        ).queued!!
        var pending = coolingOff.enqueue(emptyList(), first)

        clock.advance(20 * hour)

        val second = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "m2",
        ).queued!!
        pending = coolingOff.enqueue(pending, second)

        assertEquals(1, pending.size)
        assertEquals(
            first.effectiveAtMillis,
            pending.single().effectiveAtMillis,
            "the original deadline should be inherited",
        )

        clock.advance(4 * hour)
        assertTrue(coolingOff.isReady(pending.single()), "should land 24h after the first attempt")
    }

    @Test
    fun `changes to different controls queue independently`() {
        val (_, coolingOff, rules) = setup()
        val adult = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "d1",
        ).queued!!
        val gambling = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.GAMBLING, RuleMode.OFF)),
            idSeed = "d2",
        ).queued!!

        val pending = coolingOff.enqueue(coolingOff.enqueue(emptyList(), adult), gambling)
        assertEquals(2, pending.size)
    }

    @Test
    fun `a queued change names the control it is waiting on`() {
        val (_, coolingOff, rules) = setup()
        val queued = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "k1",
        ).queued!!

        assertEquals(setOf("category:adult"), queued.targetKeys)
    }

    @Test
    fun `pending changes are described in the user's own terms`() {
        val (_, coolingOff, rules) = setup()
        val pending = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "t17",
        ).queued!!

        assertEquals("Adult / sexual content: Never → Allowed", pending.summary)
    }

    @Test
    fun `giving yourself more scrolling waits, taking it away does not`() {
        // The sentence this feature exists to sit in front of is "just twenty more
        // minutes", said while scrolling. If that edit landed instantly the rule would be
        // a snooze button with extra steps.
        val (_, coolingOff, base) = setup()
        val armed = base.copy(
            feedRule = FeedRule(enabled = true, afterMinutes = 10, packageNames = setOf("com.example.feed")),
        )

        val looser = coolingOff.submit(
            current = armed,
            proposed = armed.copy(feedRule = armed.feedRule.copy(afterMinutes = 30)),
            idSeed = "looser",
        )
        assertEquals(10, looser.appliedNow.feedRule.afterMinutes, "loosening must not land now")
        assertNotNull(looser.queued)

        val tighter = coolingOff.submit(
            current = armed,
            proposed = armed.copy(feedRule = armed.feedRule.copy(afterMinutes = 5)),
            idSeed = "tighter",
        )
        assertEquals(5, tighter.appliedNow.feedRule.afterMinutes, "tightening lands immediately")
        assertNull(tighter.queued)
    }

    @Test
    fun `switching the scroll rule off is a loosening`() {
        val (_, coolingOff, base) = setup()
        val armed = base.copy(
            feedRule = FeedRule(enabled = true, packageNames = setOf("com.example.feed")),
        )
        val outcome = coolingOff.submit(
            current = armed,
            proposed = armed.copy(feedRule = armed.feedRule.copy(enabled = false)),
            idSeed = "off",
        )

        assertTrue(outcome.appliedNow.feedRule.enabled, "it should still be on until the wait is over")
        assertNotNull(outcome.queued)
    }

    @Test
    fun `letting Reels back in waits, shutting it off does not`() {
        // The rule someone is most likely to want to undo in the ten seconds after it
        // stops them — which is exactly the ten seconds it must not be undoable in.
        val (_, coolingOff, base) = setup()
        val blocked = base.copy(
            shortFormRule = ShortFormRule(
                enabled = true,
                surfaces = setOf(ShortFormSurface.INSTAGRAM_REELS),
            ),
        )

        val allowAgain = coolingOff.submit(
            current = blocked,
            proposed = blocked.copy(shortFormRule = ShortFormRule(enabled = false)),
            idSeed = "allow",
        )
        assertTrue(
            allowAgain.appliedNow.shortFormRule.blocks(ShortFormSurface.INSTAGRAM_REELS),
            "Reels must stay blocked until the wait is over",
        )
        assertNotNull(allowAgain.queued)

        val alsoShorts = coolingOff.submit(
            current = blocked,
            proposed = blocked.copy(
                shortFormRule = blocked.shortFormRule.copy(
                    surfaces = blocked.shortFormRule.surfaces + ShortFormSurface.YOUTUBE_SHORTS,
                ),
            ),
            idSeed = "more",
        )
        assertTrue(
            alsoShorts.appliedNow.shortFormRule.blocks(ShortFormSurface.YOUTUBE_SHORTS),
            "adding a surface is a tightening and lands at once",
        )
        assertNull(alsoShorts.queued)
    }
}
