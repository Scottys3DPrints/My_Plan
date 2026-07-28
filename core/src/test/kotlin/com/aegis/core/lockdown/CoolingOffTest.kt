package com.aegis.core.lockdown

import com.aegis.core.model.Category
import com.aegis.core.rules.AccountabilityPartner
import com.aegis.core.rules.AppRule
import com.aegis.core.rules.CategoryRule
import com.aegis.core.rules.RuleMode
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

    private fun setup(): Triple<FakeClock, CoolingOff, RuleSet> {
        val clock = FakeClock(wallMillis = 1_700_000_000_000, monotonicMillis = 500_000)
        return Triple(clock, CoolingOff(clock), RuleSet.defaults())
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

    @Test
    fun `pending changes are described in the user's own terms`() {
        val (_, coolingOff, rules) = setup()
        val pending = coolingOff.submit(
            rules,
            rules.withCategoryRule(CategoryRule(Category.ADULT, RuleMode.OFF)),
            idSeed = "t17",
        ).queued!!

        assertEquals("Adult / sexual content: Wall → Off", pending.summary)
    }
}
