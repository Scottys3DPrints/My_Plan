package com.aegis.core.budget

import com.aegis.core.model.Category
import kotlinx.serialization.Serializable

/**
 * Everything spent today, plus the grace machinery.
 *
 * Held as one serialisable value so the day roll-over is a single explicit transition
 * rather than a scattering of "is it still today?" checks.
 */
@Serializable
data class UsageState(
    /** Local day this state describes. A different day means everything below is stale. */
    val epochDay: Long = 0,
    val minutesByBudgetId: Map<String, Int> = emptyMap(),
    val minutesByPackage: Map<String, Int> = emptyMap(),
    val minutesByCategory: Map<Category, Int> = emptyMap(),
    /** Minutes carried in from yesterday, per budget, where rollover is enabled. */
    val rolloverMinutes: Map<String, Int> = emptyMap(),
    val graceTapsUsed: Int = 0,
    /**
     * Grants bought with a grace tap: key → monotonic time the extra minutes run out.
     * Monotonic, so winding the system clock forward does not extend them.
     */
    val graceGrantsUntilElapsed: Map<String, Long> = emptyMap(),
    /** A grace tap that is currently sitting through its mandatory pause. */
    val pendingGraceTap: PendingGraceTap? = null,
    /** Seconds of the current foreground stretch not yet folded into the minute counters. */
    val carrySeconds: Int = 0,
) {
    fun budgetMinutes(budgetId: String): Int = minutesByBudgetId[budgetId] ?: 0
    fun packageMinutes(packageName: String): Int = minutesByPackage[packageName] ?: 0
    fun categoryMinutes(category: Category): Int = minutesByCategory[category] ?: 0
}

/**
 * The 60-second pause that kills the reflex re-open (§3.4).
 *
 * Timed on the monotonic clock, and holding the key it was requested for, so a pause
 * started against TikTok cannot be spent on something else.
 */
@Serializable
data class PendingGraceTap(
    val key: String,
    val requestedAtElapsed: Long,
    val releaseAtElapsed: Long,
) {
    fun secondsRemaining(nowElapsed: Long): Int =
        (((releaseAtElapsed - nowElapsed).coerceAtLeast(0)) / 1000L).toInt()

    fun isReady(nowElapsed: Long): Boolean = nowElapsed >= releaseAtElapsed
}

/** Keys for the various things a budget can be attached to, kept in one place. */
object BudgetKeys {
    fun forBudget(budgetId: String) = "budget:$budgetId"
    fun forPackage(packageName: String) = "app:$packageName"
    fun forCategory(category: Category) = "category:${category.id}"
}
