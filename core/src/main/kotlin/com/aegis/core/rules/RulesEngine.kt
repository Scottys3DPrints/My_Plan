package com.aegis.core.rules

import com.aegis.core.budget.BudgetKeys
import com.aegis.core.budget.BudgetTracker
import com.aegis.core.budget.UsageState
import com.aegis.core.model.Category
import com.aegis.core.model.Classification
import com.aegis.core.model.ContentInput
import com.aegis.core.model.RouteContext
import com.aegis.core.util.Clock
import com.aegis.core.util.LocalTime
import com.aegis.core.util.TimeWindow
import com.aegis.core.util.Urls

/**
 * Turns category verdicts into allow / warn / block (§7).
 *
 * The engine is stateless and pure: everything it needs arrives as arguments and it
 * returns a [Decision]. That is what lets the same code answer for a page in our own
 * browser, a DNS question from the VPN, and a foreground app change from the
 * accessibility service, without three subtly different notions of "blocked".
 *
 * Precedence, highest first — the order is a policy statement, not an implementation
 * detail. A focus session outranks everything, because the whole point of starting one
 * is to remove later judgement calls. A named destination outranks the classifier,
 * because "never, by any route" was an explicit instruction and should not be second-
 * guessed by a confidence score.
 */
class RulesEngine(
    private val clock: Clock,
    private val tracker: BudgetTracker = BudgetTracker(clock),
) {

    fun evaluateContent(
        input: ContentInput,
        classification: Classification,
        rules: RuleSet,
        usage: UsageState,
    ): Decision {
        val now = clock.nowMillis()
        val offset = clock.utcOffsetMinutes()
        val host = Urls.host(input.url)
        val route = input.route

        focusSessionBlock(rules, now, host = host)?.let { return it.copy(route = route) }
        destinationBlock(rules, host, route, now, offset)?.let { return it }

        val activeProfiles = rules.activeProfiles(now, offset)

        // Strongest verdict first: if a page is both 0.9 adult and 0.4 gambling, the
        // block screen should talk about the thing that actually triggered it.
        val ranked = classification.scores.entries
            .sortedByDescending { it.value }

        var warn: Decision? = null

        for ((category, confidence) in ranked) {
            val rule = rules.ruleFor(category)
            val mode = effectiveMode(category, rule.mode, activeProfiles)
            if (mode == RuleMode.OFF) continue

            val evidence = classification.evidenceFor(category)

            if (confidence >= rule.blockThreshold) {
                when (mode) {
                    RuleMode.WALL -> return Decision(
                        outcome = Outcome.BLOCK,
                        cause = BlockCause.CATEGORY_WALL,
                        category = category,
                        confidence = confidence,
                        route = route,
                        explanation = wallExplanation(category, confidence, route),
                        evidence = evidence,
                    )

                    RuleMode.TIMED -> {
                        val verdict = timedVerdict(category, rules, usage, now, offset)
                        if (verdict != null) {
                            return verdict.copy(
                                category = category,
                                confidence = confidence,
                                route = route,
                                evidence = evidence,
                            )
                        }
                    }

                    RuleMode.WARN -> if (warn == null) {
                        warn = Decision(
                            outcome = Outcome.WARN,
                            category = category,
                            confidence = confidence,
                            route = route,
                            explanation = warnExplanation(category, confidence),
                            evidence = evidence,
                        )
                    }

                    RuleMode.OFF -> Unit
                }
            } else if (confidence >= rule.warnThreshold && warn == null) {
                // Below the line the user drew, but not by much. Say so rather than
                // silently allowing it — this is the band where misfires live.
                warn = Decision(
                    outcome = Outcome.WARN,
                    category = category,
                    confidence = confidence,
                    route = route,
                    explanation = borderlineExplanation(category, confidence),
                    evidence = evidence,
                )
            }
        }

        warn?.let { return it.copy(blurRefs = imagesToBlur(input, rules, activeProfiles)) }
        return Decision.allow(route = route, blurRefs = imagesToBlur(input, rules, activeProfiles))
    }

    /**
     * The decision the network filter and in-app-webview interception ask for, where all
     * that is known is a hostname (§5, the encryption limit).
     */
    fun evaluateHost(
        host: String,
        route: RouteContext,
        rules: RuleSet,
        usage: UsageState,
        classification: Classification = Classification.EMPTY,
    ): Decision {
        val now = clock.nowMillis()
        val offset = clock.utcOffsetMinutes()

        focusSessionBlock(rules, now, host = host)?.let { return it.copy(route = route) }
        destinationBlock(rules, host, route, now, offset)?.let { return it }

        if (classification.scores.isEmpty()) return Decision.allow(route = route)
        return evaluateContent(
            input = ContentInput(url = "https://$host", route = route),
            classification = classification,
            rules = rules,
            usage = usage,
        )
    }

    /**
     * [exemptPackages] can never be blocked, whatever the rules say.
     *
     * This is a safety floor, not a convenience. Without it a focus session — which by
     * design cannot be ended early — blocks the launcher and the Settings app, leaving a
     * phone that cannot reach its own home screen and an app that cannot be turned off or
     * uninstalled until the session expires. A self-control tool is allowed to be
     * difficult; it is not allowed to brick the device it runs on.
     *
     * The caller supplies the set because resolving "which app is the launcher" needs the
     * platform, and this module deliberately has no access to it.
     */
    fun evaluateApp(
        packageName: String,
        rules: RuleSet,
        usage: UsageState,
        exemptPackages: Set<String> = emptySet(),
    ): Decision {
        if (packageName in exemptPackages) return Decision.allow(route = RouteContext.APP)

        val now = clock.nowMillis()
        val offset = clock.utcOffsetMinutes()
        val route = RouteContext.APP

        rules.focusSession?.takeIf { it.isActiveAt(now) }?.let { session ->
            if (packageName !in session.allowedPackages) {
                return Decision(
                    outcome = Outcome.BLOCK,
                    cause = BlockCause.FOCUS_SESSION,
                    route = route,
                    explanation = "\"${session.label}\" is running until " +
                        LocalTime.formatMinuteOfDay(LocalTime.minuteOfDay(session.endsAtMillis, offset)) + ".",
                    liftsAtMillis = session.endsAtMillis,
                )
            }
        }

        val activeProfiles = rules.activeProfiles(now, offset)
        activeProfiles.firstOrNull { packageName in it.additionalBlockedPackages }?.let { profile ->
            return Decision(
                outcome = Outcome.BLOCK,
                cause = BlockCause.PROFILE,
                route = route,
                explanation = "The \"${profile.label}\" profile is active.",
            )
        }

        val rule = rules.ruleFor(packageName) ?: return Decision.allow(route = route)

        if (rule.blocked) {
            return Decision(
                outcome = Outcome.BLOCK,
                cause = BlockCause.APP_BLOCKED,
                route = route,
                explanation = "You blocked ${rule.label}.",
            )
        }

        if (rule.allowedWindows.isNotEmpty() &&
            rule.allowedWindows.none { it.contains(now, offset) }
        ) {
            return Decision(
                outcome = Outcome.BLOCK,
                cause = BlockCause.OUTSIDE_ALLOWED_WINDOW,
                route = route,
                explanation = "${rule.label} is only allowed " +
                    rule.allowedWindows.joinToString(" or ") { it.describe() } + ".",
                liftsAtMillis = nextWindowOpening(rule.allowedWindows, now, offset),
            )
        }

        // The app's own cap.
        if (rule.dailyBudgetMinutes > 0) {
            val key = BudgetKeys.forPackage(packageName)
            val spent = usage.packageMinutes(packageName)
            val remaining = rule.dailyBudgetMinutes - spent
            if (remaining <= 0 && !tracker.hasActiveGrace(usage, key)) {
                return Decision(
                    outcome = Outcome.BLOCK,
                    cause = BlockCause.APP_BUDGET_SPENT,
                    route = route,
                    explanation = "${LocalTime.formatDuration(rule.dailyBudgetMinutes)} of ${rule.label} is spent for today.",
                    budgetRemainingMinutes = 0,
                    graceTapAvailable = tracker.graceTapsRemaining(usage, rules) > 0,
                    graceKey = key,
                    liftsAtMillis = startOfTomorrow(now, offset),
                )
            }
        }

        // Any shared bucket it belongs to.
        val bucket = rules.budgets.firstOrNull {
            packageName in it.packageNames || it.id == rule.budgetId
        }
        if (bucket != null) {
            bucketBlock(bucket, rules, usage, now, offset, route)?.let { return it }
        }

        val remaining = listOfNotNull(
            rule.dailyBudgetMinutes.takeIf { it > 0 }?.minus(usage.packageMinutes(packageName)),
            bucket?.let { tracker.remainingFor(it, usage) },
        ).minOrNull()

        return Decision(
            outcome = Outcome.ALLOW,
            route = route,
            budgetRemainingMinutes = remaining,
        )
    }

    /** The mode actually in force, once any active profile's floor is applied. */
    fun effectiveMode(category: Category, declared: RuleMode, activeProfiles: List<Profile>): RuleMode {
        var mode = declared
        for (profile in activeProfiles) {
            val floor = profile.categoryFloors[category] ?: continue
            if (floor.strictness > mode.strictness) mode = floor
        }
        return mode
    }

    fun effectiveMode(category: Category, rules: RuleSet): RuleMode {
        val now = clock.nowMillis()
        return effectiveMode(
            category,
            rules.ruleFor(category).mode,
            rules.activeProfiles(now, clock.utcOffsetMinutes()),
        )
    }

    private fun timedVerdict(
        category: Category,
        rules: RuleSet,
        usage: UsageState,
        now: Long,
        offset: Int,
    ): Decision? {
        val bucket = rules.budgets.firstOrNull { category in it.categories }
        if (bucket != null) {
            return bucketBlock(bucket, rules, usage, now, offset, RouteContext.DIRECT)
        }

        val rule = rules.ruleFor(category)
        if (rule.dailyBudgetMinutes <= 0) return null

        val key = BudgetKeys.forCategory(category)
        val remaining = rule.dailyBudgetMinutes - usage.categoryMinutes(category)
        if (remaining > 0 || tracker.hasActiveGrace(usage, key)) return null

        return Decision(
            outcome = Outcome.BLOCK,
            cause = BlockCause.CATEGORY_BUDGET_SPENT,
            explanation = "${LocalTime.formatDuration(rule.dailyBudgetMinutes)} of ${category.label} is spent for today.",
            budgetRemainingMinutes = 0,
            graceTapAvailable = tracker.graceTapsRemaining(usage, rules) > 0,
            graceKey = key,
            liftsAtMillis = startOfTomorrow(now, offset),
        )
    }

    private fun bucketBlock(
        bucket: Budget,
        rules: RuleSet,
        usage: UsageState,
        now: Long,
        offset: Int,
        route: RouteContext,
    ): Decision? {
        if (bucket.allowedWindows.isNotEmpty() &&
            bucket.allowedWindows.none { it.contains(now, offset) }
        ) {
            return Decision(
                outcome = Outcome.BLOCK,
                cause = BlockCause.OUTSIDE_ALLOWED_WINDOW,
                route = route,
                explanation = "${bucket.label} is only open " +
                    bucket.allowedWindows.joinToString(" or ") { it.describe() } + ".",
                liftsAtMillis = nextWindowOpening(bucket.allowedWindows, now, offset),
            )
        }

        val key = BudgetKeys.forBudget(bucket.id)
        val remaining = tracker.remainingFor(bucket, usage)
        if (remaining > 0 || tracker.hasActiveGrace(usage, key)) return null

        return Decision(
            outcome = Outcome.BLOCK,
            cause = BlockCause.CATEGORY_BUDGET_SPENT,
            route = route,
            explanation = "${LocalTime.formatDuration(tracker.allowanceFor(bucket, usage))} of ${bucket.label} is spent for today.",
            budgetRemainingMinutes = 0,
            graceTapAvailable = tracker.graceTapsRemaining(usage, rules) > 0,
            graceKey = key,
            liftsAtMillis = startOfTomorrow(now, offset),
        )
    }

    private fun focusSessionBlock(rules: RuleSet, now: Long, host: String): Decision? {
        val session = rules.focusSession?.takeIf { it.isActiveAt(now) } ?: return null
        if (host.isNotEmpty() && session.allowedHosts.any { allowed ->
                host == allowed || host.endsWith(".$allowed")
            }
        ) {
            return null
        }
        return Decision(
            outcome = Outcome.BLOCK,
            cause = BlockCause.FOCUS_SESSION,
            explanation = "\"${session.label}\" is running. Nothing outside the allow list until it ends.",
            liftsAtMillis = session.endsAtMillis,
        )
    }

    private fun destinationBlock(
        rules: RuleSet,
        host: String,
        route: RouteContext,
        now: Long,
        offset: Int,
    ): Decision? {
        if (host.isEmpty()) return null

        rules.destinationRules.firstOrNull { it.matches(host, route) }?.let { rule ->
            return Decision(
                outcome = Outcome.BLOCK,
                cause = BlockCause.DESTINATION_RULE,
                route = route,
                explanation = "You asked never to reach ${rule.label}. This request came " +
                    "via ${route.label.lowercase()}, and that counts.",
                evidence = listOf("destination rule: ${rule.host}", "route: ${route.label}"),
            )
        }

        rules.activeProfiles(now, offset).firstOrNull { profile ->
            profile.additionalBlockedHosts.any { host == it || host.endsWith(".$it") }
        }?.let { profile ->
            return Decision(
                outcome = Outcome.BLOCK,
                cause = BlockCause.PROFILE,
                route = route,
                explanation = "The \"${profile.label}\" profile blocks $host.",
            )
        }
        return null
    }

    /**
     * Images to blur on a page that is otherwise allowed (§3.1).
     *
     * Blurring rather than blocking is the honest response to a weak signal: the page
     * stays usable, the user can still tap through, and a false positive costs a tap
     * instead of an argument with the app.
     */
    private fun imagesToBlur(
        input: ContentInput,
        rules: RuleSet,
        activeProfiles: List<Profile>,
    ): List<String> {
        if (!rules.blurFlaggedImages) return emptyList()
        val mode = effectiveMode(Category.ADULT, rules.ruleFor(Category.ADULT).mode, activeProfiles)
        if (mode == RuleMode.OFF) return emptyList()
        return input.imageSignals
            .filter { it.skinToneRatio >= BLUR_SKIN_RATIO }
            .map { it.ref }
    }

    private fun wallExplanation(category: Category, confidence: Float, route: RouteContext): String {
        val certainty = percent(confidence)
        val via = if (route == RouteContext.DIRECT) "" else " (reached via ${route.label.lowercase()})"
        return "This looks like ${category.label.lowercase()} — $certainty confidence$via. You set that to Wall."
    }

    private fun warnExplanation(category: Category, confidence: Float): String =
        "This looks like ${category.label.lowercase()} — ${percent(confidence)} confidence."

    private fun borderlineExplanation(category: Category, confidence: Float): String =
        "Possibly ${category.label.lowercase()} — ${percent(confidence)} confidence, below your block threshold."

    private fun percent(confidence: Float): String = "${(confidence * 100).toInt()}%"

    private fun startOfTomorrow(now: Long, offset: Int): Long {
        val day = LocalTime.epochDay(now, offset)
        return (day + 1) * LocalTime.MILLIS_PER_DAY - offset * LocalTime.MILLIS_PER_MINUTE
    }

    /** When the next of these windows opens, so a block screen can say "back at 09:00". */
    private fun nextWindowOpening(windows: List<TimeWindow>, now: Long, offset: Int): Long? {
        if (windows.isEmpty()) return null
        var probe = now
        val limit = now + 8 * LocalTime.MILLIS_PER_DAY
        // Step a minute at a time from the next minute boundary. Bounded to eight days,
        // which is enough for any weekly schedule and cheap enough to run on a block screen.
        probe += LocalTime.MILLIS_PER_MINUTE
        while (probe < limit) {
            if (windows.any { it.contains(probe, offset) }) return probe
            probe += LocalTime.MILLIS_PER_MINUTE
        }
        return null
    }

    companion object {
        private const val BLUR_SKIN_RATIO = 0.5f
    }
}
