package com.aegis.core.budget

import com.aegis.core.model.Category
import com.aegis.core.rules.Budget
import com.aegis.core.rules.RuleSet
import com.aegis.core.util.FakeClock
import com.aegis.core.util.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BudgetTrackerTest {

    private val day = LocalTime.MILLIS_PER_DAY

    private fun setup(dailyMinutes: Int = 60, rollover: Boolean = false): Triple<FakeClock, BudgetTracker, RuleSet> {
        val clock = FakeClock(wallMillis = 10 * day, monotonicMillis = 1_000_000)
        val rules = RuleSet(
            budgets = listOf(
                Budget(
                    id = "social",
                    label = "Social",
                    dailyMinutes = dailyMinutes,
                    categories = setOf(Category.SOCIAL),
                    packageNames = setOf("com.example.tiktok"),
                    rolloverEnabled = rollover,
                    maxRolloverMinutes = 30,
                ),
            ),
        )
        return Triple(clock, BudgetTracker(clock), rules)
    }

    @Test
    fun `usage accrues in seconds so app-switching does not buy free time`() {
        val (_, tracker, rules) = setup()
        var state = UsageState(epochDay = 10)

        // Six fifty-second visits: under a minute each, but five minutes in total.
        repeat(6) {
            state = tracker.record(state, rules, seconds = 50, packageName = "com.example.tiktok")
        }

        assertEquals(5, state.packageMinutes("com.example.tiktok"))
        assertEquals(5, state.budgetMinutes("social"))
    }

    @Test
    fun `one stretch charges the app and every bucket it belongs to`() {
        val (_, tracker, rules) = setup()
        val state = tracker.record(
            UsageState(epochDay = 10),
            rules,
            seconds = 600,
            packageName = "com.example.tiktok",
            categories = setOf(Category.SOCIAL),
        )

        assertEquals(10, state.packageMinutes("com.example.tiktok"))
        assertEquals(10, state.categoryMinutes(Category.SOCIAL))
        assertEquals(10, state.budgetMinutes("social"))
    }

    @Test
    fun `a new day resets the counters`() {
        val (clock, tracker, rules) = setup()
        var state = tracker.record(UsageState(epochDay = 10), rules, 3600, packageName = "com.example.tiktok")
        assertEquals(60, state.budgetMinutes("social"))

        clock.advance(day)
        state = tracker.normalise(state, rules)

        assertEquals(0, state.budgetMinutes("social"))
        assertEquals(11, state.epochDay)
    }

    @Test
    fun `unspent time rolls over, capped`() {
        val (clock, tracker, rules) = setup(dailyMinutes = 60, rollover = true)
        var state = tracker.record(UsageState(epochDay = 10), rules, 600, packageName = "com.example.tiktok")

        clock.advance(day)
        state = tracker.normalise(state, rules)

        // 50 minutes unspent, capped at 30.
        assertEquals(30, state.rolloverMinutes["social"])
        assertEquals(90, tracker.allowanceFor(rules.budgets.first(), state))
    }

    @Test
    fun `rollover only carries from the immediately preceding day`() {
        val (clock, tracker, rules) = setup(dailyMinutes = 60, rollover = true)
        var state = UsageState(epochDay = 10)

        clock.advance(5 * day)
        state = tracker.normalise(state, rules)

        assertTrue(state.rolloverMinutes.isEmpty(), "a week away should not bank five hours")
    }

    @Test
    fun `winding the clock back does not resurrect spent budget`() {
        val (clock, tracker, rules) = setup()
        var state = tracker.record(UsageState(epochDay = 10), rules, 3600, packageName = "com.example.tiktok")

        clock.tamperWallClock(-2 * day)
        state = tracker.normalise(state, rules)

        assertEquals(0, tracker.remainingFor(rules.budgets.first(), state), "budget should still be spent")
    }

    @Test
    fun `a grace tap grants nothing until the pause has been sat through`() {
        val (clock, tracker, rules) = setup()
        val key = BudgetKeys.forBudget("social")
        val state = UsageState(epochDay = 10)

        val requested = tracker.requestGraceTap(state, rules, key)
        assertIs<GraceRequestResult.Waiting>(requested)
        assertFalse(tracker.hasActiveGrace(requested.state, key))

        val early = tracker.claimGraceTap(requested.state, rules, key)
        assertIs<GraceClaimResult.NotReady>(early)
        assertEquals(60, early.secondsRemaining)

        clock.advance(59_000)
        assertIs<GraceClaimResult.NotReady>(tracker.claimGraceTap(requested.state, rules, key))

        clock.advance(1_000)
        val granted = tracker.claimGraceTap(requested.state, rules, key)
        assertIs<GraceClaimResult.Granted>(granted)
        assertEquals(5, granted.minutes)
        assertTrue(tracker.hasActiveGrace(granted.state, key))
    }

    @Test
    fun `the grace pause cannot be skipped by changing the system clock`() {
        val (clock, tracker, rules) = setup()
        val key = BudgetKeys.forBudget("social")

        val requested = tracker.requestGraceTap(UsageState(epochDay = 10), rules, key)
        clock.tamperWallClock(10 * 60_000)

        assertIs<GraceClaimResult.NotReady>(tracker.claimGraceTap(requested.state, rules, key))
    }

    @Test
    fun `a pause started for one thing cannot be spent on another`() {
        val (clock, tracker, rules) = setup()
        val requested = tracker.requestGraceTap(UsageState(epochDay = 10), rules, BudgetKeys.forBudget("social"))
        clock.advance(60_000)

        val stolen = tracker.claimGraceTap(requested.state, rules, BudgetKeys.forPackage("com.example.other"))
        assertIs<GraceClaimResult.Refused>(stolen)
    }

    @Test
    fun `grace taps run out`() {
        val (clock, tracker, rules) = setup()
        val key = BudgetKeys.forBudget("social")
        var state = UsageState(epochDay = 10)

        repeat(rules.maxGraceTapsPerDay) {
            state = (tracker.requestGraceTap(state, rules, key) as GraceRequestResult.Waiting).state
            clock.advance(60_000)
            state = (tracker.claimGraceTap(state, rules, key) as GraceClaimResult.Granted).state
        }

        assertEquals(0, tracker.graceTapsRemaining(state, rules))
        assertIs<GraceRequestResult.Refused>(tracker.requestGraceTap(state, rules, key))
    }

    @Test
    fun `a grace grant expires`() {
        val (clock, tracker, rules) = setup()
        val key = BudgetKeys.forBudget("social")
        val requested = tracker.requestGraceTap(UsageState(epochDay = 10), rules, key)
        clock.advance(60_000)
        val granted = (tracker.claimGraceTap(requested.state, rules, key) as GraceClaimResult.Granted).state

        clock.advance(4 * 60_000)
        assertTrue(tracker.hasActiveGrace(granted, key))

        clock.advance(2 * 60_000)
        assertFalse(tracker.hasActiveGrace(granted, key))
    }
}
