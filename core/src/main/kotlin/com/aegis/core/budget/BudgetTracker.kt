package com.aegis.core.budget

import com.aegis.core.model.Category
import com.aegis.core.rules.Budget
import com.aegis.core.rules.RuleSet
import com.aegis.core.util.Clock
import com.aegis.core.util.LocalTime

/**
 * Time budgets (§3.4), including the part that matters most: the grace tap.
 *
 * Every function here is pure — state in, state out — so the whole of budgeting can be
 * tested at any hour of any day without waiting for one.
 *
 * Two anti-cheat decisions are baked in and worth stating:
 *
 * - Usage accrues in *seconds* and is folded into minutes, so switching apps every
 *   fifty seconds does not spend a free afternoon.
 * - Grace pauses and grace grants are measured on the monotonic clock, so changing the
 *   device's date and time neither skips the pause nor extends the reward.
 */
class BudgetTracker(private val clock: Clock) {

    /** Roll [state] onto today if it belongs to an earlier day, applying any rollover. */
    fun normalise(state: UsageState, rules: RuleSet): UsageState {
        val today = LocalTime.epochDay(clock.nowMillis(), clock.utcOffsetMinutes())
        if (state.epochDay == today) return state

        // A clock wound *backwards* must not resurrect yesterday's spent budget.
        if (state.epochDay > today) return state.copy(epochDay = today)

        val isYesterday = state.epochDay == today - 1
        val carried = if (!isYesterday) emptyMap() else rules.budgets
            .filter { it.rolloverEnabled }
            .mapNotNull { budget ->
                val unspent = remainingFor(budget, state).coerceAtLeast(0)
                val carry = minOf(unspent, budget.maxRolloverMinutes)
                if (carry > 0) budget.id to carry else null
            }
            .toMap()

        return UsageState(epochDay = today, rolloverMinutes = carried)
    }

    /** Total minutes a budget may spend today, including anything carried over. */
    fun allowanceFor(budget: Budget, state: UsageState): Int =
        budget.dailyMinutes + (state.rolloverMinutes[budget.id] ?: 0)

    fun remainingFor(budget: Budget, state: UsageState): Int =
        allowanceFor(budget, state) - state.budgetMinutes(budget.id)

    /**
     * Fold a stretch of foreground time into the counters.
     *
     * Charges every budget the activity belongs to — the app's own cap and any shared
     * bucket both tick, because "30 min of TikTok inside 1 hr of Social" has to mean
     * both things at once.
     */
    fun record(
        state: UsageState,
        rules: RuleSet,
        seconds: Int,
        packageName: String? = null,
        categories: Set<Category> = emptySet(),
    ): UsageState {
        if (seconds <= 0) return state
        val normalised = normalise(state, rules)

        val totalSeconds = normalised.carrySeconds + seconds
        val wholeMinutes = totalSeconds / 60
        val leftover = totalSeconds % 60
        if (wholeMinutes == 0) return normalised.copy(carrySeconds = leftover)

        val byPackage = normalised.minutesByPackage.toMutableMap()
        if (packageName != null) {
            byPackage[packageName] = (byPackage[packageName] ?: 0) + wholeMinutes
        }

        val byCategory = normalised.minutesByCategory.toMutableMap()
        for (category in categories) {
            byCategory[category] = (byCategory[category] ?: 0) + wholeMinutes
        }

        val byBudget = normalised.minutesByBudgetId.toMutableMap()
        for (budget in rules.budgets) {
            val touched = (packageName != null && packageName in budget.packageNames) ||
                categories.any { it in budget.categories }
            if (touched) {
                byBudget[budget.id] = (byBudget[budget.id] ?: 0) + wholeMinutes
            }
        }

        return normalised.copy(
            minutesByPackage = byPackage,
            minutesByCategory = byCategory,
            minutesByBudgetId = byBudget,
            carrySeconds = leftover,
        )
    }

    /** True while a grace grant bought for [key] is still running. */
    fun hasActiveGrace(state: UsageState, key: String): Boolean {
        val until = state.graceGrantsUntilElapsed[key] ?: return false
        return clock.elapsedRealtimeMillis() < until
    }

    fun graceSecondsRemaining(state: UsageState, key: String): Int {
        val until = state.graceGrantsUntilElapsed[key] ?: return 0
        return (((until - clock.elapsedRealtimeMillis()).coerceAtLeast(0)) / 1000L).toInt()
    }

    fun graceTapsRemaining(state: UsageState, rules: RuleSet): Int =
        (rules.maxGraceTapsPerDay - state.graceTapsUsed).coerceAtLeast(0)

    /**
     * Start the mandatory pause. Nothing is granted yet — that is the entire mechanism.
     */
    fun requestGraceTap(state: UsageState, rules: RuleSet, key: String): GraceRequestResult {
        val normalised = normalise(state, rules)
        if (graceTapsRemaining(normalised, rules) <= 0) {
            return GraceRequestResult.Refused(normalised, "No grace taps left today.")
        }
        val existing = normalised.pendingGraceTap
        if (existing != null && existing.key == key && !existing.isReady(clock.elapsedRealtimeMillis())) {
            return GraceRequestResult.Waiting(normalised, existing)
        }
        val now = clock.elapsedRealtimeMillis()
        val pending = PendingGraceTap(
            key = key,
            requestedAtElapsed = now,
            releaseAtElapsed = now + rules.graceTapPauseSeconds * 1000L,
        )
        return GraceRequestResult.Waiting(normalised.copy(pendingGraceTap = pending), pending)
    }

    /**
     * Redeem a pause that has run its course.
     *
     * Deliberately not automatic: the user has to come back and ask a second time, after
     * the minute has passed, which is precisely the moment most re-opens die.
     */
    fun claimGraceTap(state: UsageState, rules: RuleSet, key: String): GraceClaimResult {
        val normalised = normalise(state, rules)
        val pending = normalised.pendingGraceTap
            ?: return GraceClaimResult.Refused(normalised, "Nothing is waiting.")
        if (pending.key != key) {
            return GraceClaimResult.Refused(normalised, "That pause was for something else.")
        }
        val now = clock.elapsedRealtimeMillis()
        if (!pending.isReady(now)) {
            return GraceClaimResult.NotReady(normalised, pending.secondsRemaining(now))
        }
        if (graceTapsRemaining(normalised, rules) <= 0) {
            return GraceClaimResult.Refused(normalised.copy(pendingGraceTap = null), "No grace taps left today.")
        }
        val granted = normalised.copy(
            pendingGraceTap = null,
            graceTapsUsed = normalised.graceTapsUsed + 1,
            graceGrantsUntilElapsed = normalised.graceGrantsUntilElapsed +
                (key to now + rules.graceTapMinutes * 60_000L),
        )
        return GraceClaimResult.Granted(granted, rules.graceTapMinutes)
    }

    fun cancelGraceTap(state: UsageState): UsageState = state.copy(pendingGraceTap = null)
}

sealed interface GraceRequestResult {
    val state: UsageState

    data class Waiting(override val state: UsageState, val pending: PendingGraceTap) : GraceRequestResult
    data class Refused(override val state: UsageState, val reason: String) : GraceRequestResult
}

sealed interface GraceClaimResult {
    val state: UsageState

    data class Granted(override val state: UsageState, val minutes: Int) : GraceClaimResult
    data class NotReady(override val state: UsageState, val secondsRemaining: Int) : GraceClaimResult
    data class Refused(override val state: UsageState, val reason: String) : GraceClaimResult
}
