package com.aegis.core.lockdown

import com.aegis.core.model.Category
import com.aegis.core.rules.AccountabilityPartner
import com.aegis.core.rules.AppRule
import com.aegis.core.rules.Budget
import com.aegis.core.rules.CategoryRule
import com.aegis.core.rules.DestinationRule
import com.aegis.core.rules.FocusSession
import com.aegis.core.rules.Profile
import com.aegis.core.rules.RuleSet
import com.aegis.core.util.LocalTime
import kotlinx.serialization.Serializable

@Serializable
enum class ChangeDirection {
    /** Makes the rules stricter. Applies immediately. */
    TIGHTENS,

    /** Makes them weaker. Waits out the cooling-off period. */
    LOOSENS,

    /** Neither — a relabel, a reordering. Applies immediately. */
    NEUTRAL,
}

/**
 * One reviewable unit of change.
 *
 * A settings edit is not treated as one indivisible act. It is split into these, each
 * classified on its own, so that "wall gambling and also give myself more TikTok" does
 * the first half now and makes you wait for the second — rather than the user learning
 * that bundling a loosening with a tightening gets it through faster.
 *
 * Serialisable because a pending change has to survive a reboot. A blocker whose 24-hour
 * delay can be erased by turning the phone off and on again is not a blocker.
 */
@Serializable
sealed interface RuleDelta {
    val direction: ChangeDirection

    /** One line, in the user's terms. Shown in the pending queue and to a partner. */
    val summary: String

    fun applyTo(rules: RuleSet): RuleSet

    @Serializable
    data class CategoryChange(
        val category: Category,
        val before: CategoryRule?,
        val after: CategoryRule?,
    ) : RuleDelta {
        override val direction: ChangeDirection
            get() = compare(before?.strictness ?: 0, after?.strictness ?: 0)

        override val summary: String
            get() {
                val label = category.label
                return when {
                    before == null && after != null -> "Start filtering $label as ${after.mode.label}"
                    after == null && before != null -> "Stop filtering $label"
                    before != null && after != null && before.mode != after.mode ->
                        "$label: ${before.mode.label} → ${after.mode.label}"
                    before != null && after != null && before.dailyBudgetMinutes != after.dailyBudgetMinutes ->
                        "$label budget: ${LocalTime.formatDuration(before.dailyBudgetMinutes)} → " +
                            LocalTime.formatDuration(after.dailyBudgetMinutes)
                    before != null && after != null && before.blockThreshold != after.blockThreshold ->
                        "$label sensitivity: ${pct(before.blockThreshold)} → ${pct(after.blockThreshold)}"
                    else -> "$label rule adjusted"
                }
            }

        override fun applyTo(rules: RuleSet): RuleSet =
            if (after == null) rules.copy(categoryRules = rules.categoryRules - category)
            else rules.copy(categoryRules = rules.categoryRules + (category to after))
    }

    @Serializable
    data class AppChange(
        val packageName: String,
        val before: AppRule?,
        val after: AppRule?,
    ) : RuleDelta {
        override val direction: ChangeDirection
            get() = compare(before?.strictness ?: 0, after?.strictness ?: 0)

        override val summary: String
            get() {
                val label = after?.label ?: before?.label ?: packageName
                return when {
                    before == null && after != null && after.blocked -> "Block $label"
                    before == null && after != null -> "Add a rule for $label"
                    after == null -> "Remove all limits on $label"
                    before != null && before.blocked && !after.blocked -> "Unblock $label"
                    before != null && !before.blocked && after.blocked -> "Block $label"
                    before != null && before.dailyBudgetMinutes != after.dailyBudgetMinutes ->
                        "$label limit: ${LocalTime.formatDuration(before.dailyBudgetMinutes)} → " +
                            LocalTime.formatDuration(after.dailyBudgetMinutes)
                    else -> "$label rule adjusted"
                }
            }

        override fun applyTo(rules: RuleSet): RuleSet =
            if (after == null) rules.copy(appRules = rules.appRules - packageName)
            else rules.copy(appRules = rules.appRules + (packageName to after))
    }

    @Serializable
    data class DestinationChange(
        val host: String,
        val before: DestinationRule?,
        val after: DestinationRule?,
    ) : RuleDelta {
        override val direction: ChangeDirection
            get() = compare(before?.strictness ?: 0, after?.strictness ?: 0)

        override val summary: String
            get() = when {
                before == null && after != null -> "Never reach $host, by any route"
                after == null -> "Allow $host again"
                else -> "Change how $host is blocked"
            }

        override fun applyTo(rules: RuleSet): RuleSet {
            val without = rules.destinationRules.filterNot { it.host.equals(host, true) }
            return rules.copy(destinationRules = if (after == null) without else without + after)
        }
    }

    @Serializable
    data class BudgetChange(
        val budgetId: String,
        val before: Budget?,
        val after: Budget?,
    ) : RuleDelta {
        override val direction: ChangeDirection
            get() = compare(before?.strictness ?: Int.MIN_VALUE, after?.strictness ?: Int.MIN_VALUE)

        override val summary: String
            get() {
                val label = after?.label ?: before?.label ?: budgetId
                return when {
                    before == null && after != null ->
                        "New budget: $label, ${LocalTime.formatDuration(after.dailyMinutes)} a day"
                    after == null -> "Remove the $label budget"
                    before != null && before.dailyMinutes != after.dailyMinutes ->
                        "$label: ${LocalTime.formatDuration(before.dailyMinutes)} → " +
                            LocalTime.formatDuration(after.dailyMinutes) + " a day"
                    else -> "$label budget adjusted"
                }
            }

        override fun applyTo(rules: RuleSet): RuleSet {
            val without = rules.budgets.filterNot { it.id == budgetId }
            return rules.copy(budgets = if (after == null) without else without + after)
        }
    }

    @Serializable
    data class ProfileChange(
        val profileId: String,
        val before: Profile?,
        val after: Profile?,
    ) : RuleDelta {
        override val direction: ChangeDirection
            get() {
                val beforeRank = before?.let { profileStrictness(it) } ?: 0
                val afterRank = after?.let { profileStrictness(it) } ?: 0
                return compare(beforeRank, afterRank)
            }

        override val summary: String
            get() {
                val label = after?.label ?: before?.label ?: profileId
                return when {
                    before == null -> "Add the \"$label\" profile"
                    after == null -> "Delete the \"$label\" profile"
                    before.enabled && !after.enabled -> "Turn off the \"$label\" profile"
                    !before.enabled && after.enabled -> "Turn on the \"$label\" profile"
                    else -> "Edit the \"$label\" profile"
                }
            }

        override fun applyTo(rules: RuleSet): RuleSet {
            val without = rules.profiles.filterNot { it.id == profileId }
            return rules.copy(profiles = if (after == null) without else without + after)
        }

        private fun profileStrictness(profile: Profile): Int =
            (if (profile.enabled) 1_000 else 0) +
                profile.categoryFloors.values.sumOf { it.strictness } * 10 +
                profile.additionalBlockedPackages.size +
                profile.additionalBlockedHosts.size
    }

    @Serializable
    data class FocusSessionChange(
        val before: FocusSession?,
        val after: FocusSession?,
    ) : RuleDelta {
        override val direction: ChangeDirection
            get() = when {
                before == null && after != null -> ChangeDirection.TIGHTENS
                before != null && after == null ->
                    // Ending a session early is the loosening this feature exists to prevent.
                    if (before.allowEarlyExit) ChangeDirection.NEUTRAL else ChangeDirection.LOOSENS
                before != null && after != null && after.endsAtMillis < before.endsAtMillis ->
                    ChangeDirection.LOOSENS
                else -> ChangeDirection.TIGHTENS
            }

        override val summary: String
            get() = when {
                after != null && before == null -> "Start \"${after.label}\""
                after == null && before != null -> "End \"${before.label}\" early"
                else -> "Change the running focus session"
            }

        override fun applyTo(rules: RuleSet): RuleSet = rules.copy(focusSession = after)
    }

    @Serializable
    data class PartnerChange(
        val before: AccountabilityPartner?,
        val after: AccountabilityPartner?,
    ) : RuleDelta {
        override val direction: ChangeDirection
            get() = when {
                before == null && after != null -> ChangeDirection.TIGHTENS
                // Removing your accountability partner is the classic weak-moment move.
                before != null && after == null -> ChangeDirection.LOOSENS
                before != null && after != null && before.enabled && !after.enabled -> ChangeDirection.LOOSENS
                else -> ChangeDirection.TIGHTENS
            }

        override val summary: String
            get() = when {
                before == null && after != null -> "Add ${after.name} as accountability partner"
                after == null && before != null -> "Remove ${before.name} as accountability partner"
                else -> "Change accountability settings"
            }

        override fun applyTo(rules: RuleSet): RuleSet = rules.copy(partner = after)
    }

    @Serializable
    data class GuardSettingsChange(
        val beforeCoolingOffHours: Int,
        val afterCoolingOffHours: Int,
        val beforeGracePauseSeconds: Int,
        val afterGracePauseSeconds: Int,
        val beforeGraceMinutes: Int,
        val afterGraceMinutes: Int,
        val beforeMaxGraceTaps: Int,
        val afterMaxGraceTaps: Int,
        val beforeBlurImages: Boolean,
        val afterBlurImages: Boolean,
    ) : RuleDelta {
        override val direction: ChangeDirection
            get() {
                val before = beforeCoolingOffHours * 100 + beforeGracePauseSeconds -
                    beforeGraceMinutes * 10 - beforeMaxGraceTaps * 10 + if (beforeBlurImages) 5 else 0
                val after = afterCoolingOffHours * 100 + afterGracePauseSeconds -
                    afterGraceMinutes * 10 - afterMaxGraceTaps * 10 + if (afterBlurImages) 5 else 0
                return compare(before, after)
            }

        override val summary: String
            get() = when {
                beforeCoolingOffHours != afterCoolingOffHours ->
                    "Cooling-off delay: ${beforeCoolingOffHours}h → ${afterCoolingOffHours}h"
                beforeGracePauseSeconds != afterGracePauseSeconds ->
                    "Grace pause: ${beforeGracePauseSeconds}s → ${afterGracePauseSeconds}s"
                beforeGraceMinutes != afterGraceMinutes ->
                    "Grace grant: ${beforeGraceMinutes}m → ${afterGraceMinutes}m"
                beforeMaxGraceTaps != afterMaxGraceTaps ->
                    "Grace taps per day: $beforeMaxGraceTaps → $afterMaxGraceTaps"
                beforeBlurImages != afterBlurImages ->
                    if (afterBlurImages) "Blur flagged images" else "Stop blurring flagged images"
                else -> "Guard settings adjusted"
            }

        override fun applyTo(rules: RuleSet): RuleSet = rules.copy(
            coolingOffHours = afterCoolingOffHours,
            graceTapPauseSeconds = afterGracePauseSeconds,
            graceTapMinutes = afterGraceMinutes,
            maxGraceTapsPerDay = afterMaxGraceTaps,
            blurFlaggedImages = afterBlurImages,
        )
    }

    companion object {
        internal fun compare(before: Int, after: Int): ChangeDirection = when {
            after > before -> ChangeDirection.TIGHTENS
            after < before -> ChangeDirection.LOOSENS
            else -> ChangeDirection.NEUTRAL
        }

        internal fun pct(value: Float): String = "${(value * 100).toInt()}%"
    }
}
