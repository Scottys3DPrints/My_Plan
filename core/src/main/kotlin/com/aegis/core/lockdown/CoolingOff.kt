package com.aegis.core.lockdown

import com.aegis.core.model.Category
import com.aegis.core.rules.RuleSet
import com.aegis.core.util.Clock
import kotlinx.serialization.Serializable

/**
 * A loosening change, waiting.
 *
 * The deadline is recorded twice on purpose. [effectiveAtMillis] is wall-clock, which is
 * what the user is shown and what survives a reboot. [minElapsedRealtime] is the same
 * deadline on the monotonic clock, which does not move when the system date does — so
 * the obvious trick of winding tomorrow's date forward in Settings does not release a
 * pending change. If the device has rebooted since the request, the monotonic reading is
 * meaningless and the wall clock is trusted; that is a deliberate, documented gap rather
 * than a silent one, and rebooting is a good deal more friction than opening Settings.
 */
@Serializable
data class PendingChange(
    val id: String,
    val deltas: List<RuleDelta>,
    val requestedAtMillis: Long,
    val effectiveAtMillis: Long,
    val requestedAtElapsed: Long,
    val minElapsedRealtime: Long,
    val note: String = "",
) {
    val summary: String get() = deltas.joinToString("; ") { it.summary }

    /** The controls this change is waiting on, so a screen can say so beside them. */
    val targetKeys: Set<String> get() = deltas.map { it.targetKey }.toSet()

    fun millisRemaining(nowMillis: Long): Long = (effectiveAtMillis - nowMillis).coerceAtLeast(0)
}

/**
 * What a submitted edit actually did.
 *
 * [appliedNow] is the rule set the app should immediately persist and enforce. It already
 * contains every tightening part of the edit. [queued], if present, is the rest.
 */
data class ChangeOutcome(
    val appliedNow: RuleSet,
    val queued: PendingChange?,
    val appliedDeltas: List<RuleDelta>,
    val queuedDeltas: List<RuleDelta>,
) {
    val hasPending: Boolean get() = queued != null
}

/**
 * Self-binding (§3.6) — the feature the rest of the app exists to protect.
 *
 * The rule is simple and absolute: **strengthening applies instantly, weakening waits.**
 * Everything subtle about this class is in service of not letting that rule be routed
 * around.
 *
 * - An edit is decomposed into independent deltas, so a loosening cannot be smuggled
 *   through attached to a tightening.
 * - Cancelling a queued loosening is itself a tightening, so it is always instant.
 * - Shortening the cooling-off period is a loosening, and therefore has to sit through
 *   the *current*, longer period before it takes effect.
 * - The deadline is checked against a monotonic clock as well as the wall clock.
 */
class CoolingOff(private val clock: Clock) {

    /**
     * Diff two rule sets into independent, individually-classified changes.
     */
    fun diff(current: RuleSet, proposed: RuleSet): List<RuleDelta> {
        val deltas = mutableListOf<RuleDelta>()

        for (category in Category.entries) {
            val before = current.categoryRules[category]
            val after = proposed.categoryRules[category]
            if (before != after) deltas += RuleDelta.CategoryChange(category, before, after)
        }

        for (packageName in current.appRules.keys + proposed.appRules.keys) {
            val before = current.appRules[packageName]
            val after = proposed.appRules[packageName]
            if (before != after) deltas += RuleDelta.AppChange(packageName, before, after)
        }

        val hosts = (current.destinationRules.map { it.host.lowercase() } +
            proposed.destinationRules.map { it.host.lowercase() }).toSet()
        for (host in hosts) {
            val before = current.destinationRules.firstOrNull { it.host.equals(host, true) }
            val after = proposed.destinationRules.firstOrNull { it.host.equals(host, true) }
            if (before != after) deltas += RuleDelta.DestinationChange(host, before, after)
        }

        val budgetIds = (current.budgets.map { it.id } + proposed.budgets.map { it.id }).toSet()
        for (id in budgetIds) {
            val before = current.budgets.firstOrNull { it.id == id }
            val after = proposed.budgets.firstOrNull { it.id == id }
            if (before != after) deltas += RuleDelta.BudgetChange(id, before, after)
        }

        val profileIds = (current.profiles.map { it.id } + proposed.profiles.map { it.id }).toSet()
        for (id in profileIds) {
            val before = current.profiles.firstOrNull { it.id == id }
            val after = proposed.profiles.firstOrNull { it.id == id }
            if (before != after) deltas += RuleDelta.ProfileChange(id, before, after)
        }

        if (current.armed != proposed.armed) {
            deltas += RuleDelta.ArmingChange(current.armed, proposed.armed)
        }

        if (current.focusSession != proposed.focusSession) {
            deltas += RuleDelta.FocusSessionChange(current.focusSession, proposed.focusSession)
        }

        if (current.partner != proposed.partner) {
            deltas += RuleDelta.PartnerChange(current.partner, proposed.partner)
        }

        if (current.coolingOffHours != proposed.coolingOffHours ||
            current.graceTapPauseSeconds != proposed.graceTapPauseSeconds ||
            current.graceTapMinutes != proposed.graceTapMinutes ||
            current.maxGraceTapsPerDay != proposed.maxGraceTapsPerDay ||
            current.blurFlaggedImages != proposed.blurFlaggedImages
        ) {
            deltas += RuleDelta.GuardSettingsChange(
                beforeCoolingOffHours = current.coolingOffHours,
                afterCoolingOffHours = proposed.coolingOffHours,
                beforeGracePauseSeconds = current.graceTapPauseSeconds,
                afterGracePauseSeconds = proposed.graceTapPauseSeconds,
                beforeGraceMinutes = current.graceTapMinutes,
                afterGraceMinutes = proposed.graceTapMinutes,
                beforeMaxGraceTaps = current.maxGraceTapsPerDay,
                afterMaxGraceTaps = proposed.maxGraceTapsPerDay,
                beforeBlurImages = current.blurFlaggedImages,
                afterBlurImages = proposed.blurFlaggedImages,
            )
        }

        return deltas
    }

    /**
     * Submit an edit. Tightening parts land immediately; loosening parts are queued.
     *
     * [idSeed] is supplied by the caller so the id is deterministic in tests and stable
     * across a retry.
     */
    fun submit(
        current: RuleSet,
        proposed: RuleSet,
        idSeed: String,
        note: String = "",
    ): ChangeOutcome {
        val deltas = diff(current, proposed)
        if (deltas.isEmpty()) {
            return ChangeOutcome(current, null, emptyList(), emptyList())
        }

        // Setup mode: nothing is bound yet, so nothing is held back. See RuleSet.armed
        // for why this is not a way around the lock.
        val immediate = if (!current.armed) deltas else deltas.filter { it.direction != ChangeDirection.LOOSENS }
        val deferred = if (!current.armed) emptyList() else deltas.filter { it.direction == ChangeDirection.LOOSENS }

        var applied = current
        for (delta in immediate) {
            applied = delta.applyTo(applied)
        }
        applied = applied.copy(version = current.version + 1)

        if (deferred.isEmpty()) {
            return ChangeOutcome(applied, null, immediate, emptyList())
        }

        val now = clock.nowMillis()
        val elapsed = clock.elapsedRealtimeMillis()
        // The *current* delay, not the proposed one. Shortening the wait cannot shorten
        // its own wait.
        val delayMillis = current.coolingOffHours.coerceAtLeast(0) * 3_600_000L

        val pending = PendingChange(
            id = idSeed,
            deltas = deferred,
            requestedAtMillis = now,
            effectiveAtMillis = now + delayMillis,
            requestedAtElapsed = elapsed,
            minElapsedRealtime = elapsed + delayMillis,
            note = note,
        )
        return ChangeOutcome(applied, pending, immediate, deferred)
    }

    /**
     * Is this change allowed to land yet?
     *
     * Both clocks must agree, unless the device has rebooted since the request — see the
     * note on [PendingChange].
     */
    fun isReady(pending: PendingChange): Boolean {
        val now = clock.nowMillis()
        val elapsed = clock.elapsedRealtimeMillis()
        val wallClockReady = now >= pending.effectiveAtMillis
        val rebooted = elapsed < pending.requestedAtElapsed
        val monotonicReady = rebooted || elapsed >= pending.minElapsedRealtime
        return wallClockReady && monotonicReady
    }

    /**
     * The honest countdown to show the user, in whole minutes.
     *
     * Takes the longer of the two clocks' answers, so a wound-forward system clock shows
     * the real remaining time rather than a satisfying but false "ready now".
     */
    fun minutesRemaining(pending: PendingChange): Int {
        val byWall = pending.effectiveAtMillis - clock.nowMillis()
        val elapsed = clock.elapsedRealtimeMillis()
        val byMonotonic = if (elapsed < pending.requestedAtElapsed) 0L
        else pending.minElapsedRealtime - elapsed
        val remaining = maxOf(byWall, byMonotonic).coerceAtLeast(0L)
        return ((remaining + 59_999) / 60_000).toInt()
    }

    /** Apply everything that has come due, returning the new rules and what landed. */
    fun applyDue(rules: RuleSet, pending: List<PendingChange>): DueResult {
        val due = pending.filter { isReady(it) }
        if (due.isEmpty()) return DueResult(rules, pending, emptyList())

        var updated = rules
        for (change in due) {
            for (delta in change.deltas) {
                updated = delta.applyTo(updated)
            }
        }
        return DueResult(
            rules = updated.copy(version = rules.version + 1),
            stillPending = pending - due.toSet(),
            applied = due,
        )
    }

    /**
     * Cancel a queued loosening. Always immediate — changing your mind back towards the
     * stricter option is exactly the decision this system wants to make cheap.
     */
    fun cancel(pending: List<PendingChange>, id: String): List<PendingChange> =
        pending.filterNot { it.id == id }

    /**
     * Add [change] to the queue, dropping anything it supersedes.
     *
     * Without this, tapping a control four times leaves four contradictory entries in the
     * queue, each of which will land in turn — so the setting the user finally sees is
     * whichever tap happened to be last in the list, twenty-four hours later. One target,
     * one queued change.
     *
     * Superseding does **not** restart the clock. The wait belongs to the decision to
     * weaken this thing, not to the last time the user fiddled with it — otherwise
     * repeatedly adjusting a slider would reset the countdown forever, and cancelling by
     * accident would be easier than cancelling on purpose.
     */
    fun enqueue(pending: List<PendingChange>, change: PendingChange): List<PendingChange> {
        val targets = change.targetKeys
        val superseded = pending.filter { it.targetKeys.any { key -> key in targets } }
        if (superseded.isEmpty()) return pending + change

        val inheritedDeadline = superseded.minOf { it.effectiveAtMillis }
        val inheritedMonotonic = superseded.minOf { it.minElapsedRealtime }
        val rebased = change.copy(
            effectiveAtMillis = minOf(change.effectiveAtMillis, inheritedDeadline),
            minElapsedRealtime = minOf(change.minElapsedRealtime, inheritedMonotonic),
            requestedAtElapsed = minOf(change.requestedAtElapsed, superseded.minOf { it.requestedAtElapsed }),
        )
        return pending.filterNot { it in superseded } + rebased
    }
}

data class DueResult(
    val rules: RuleSet,
    val stillPending: List<PendingChange>,
    val applied: List<PendingChange>,
)
