package com.aegis.core.rules

import com.aegis.core.model.Category
import com.aegis.core.model.RouteContext
import com.aegis.core.util.Days
import com.aegis.core.util.TimeWindow
import kotlinx.serialization.Serializable

/**
 * How firmly a category is held.
 *
 * The ordering is meaningful and load-bearing: [ordinal] is the strictness rank that
 * [com.aegis.core.lockdown.CoolingOff] uses to decide whether an edit tightens the
 * user's own rules or loosens them.
 */
@Serializable
enum class RuleMode(val id: String, val label: String, val description: String) {
    OFF("off", "Off", "Not filtered."),
    WARN("warn", "Warn", "Shows an interstitial before the content loads."),
    TIMED("timed", "Timed", "Allowed within a daily budget, then locked."),
    WALL("wall", "Wall", "Never allowed."),
    ;

    val strictness: Int get() = ordinal

    companion object {
        fun fromId(id: String): RuleMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * One category's rule.
 *
 * [blockThreshold] is the confidence at which the mode engages, and [warnThreshold] the
 * point below it where a page is merely flagged. Exposing both is a §2.5 obligation:
 * a classifier that misfires silently at a threshold the user cannot see or move is not
 * being honest about its mistakes.
 */
@Serializable
data class CategoryRule(
    val category: Category,
    val mode: RuleMode = RuleMode.OFF,
    val blockThreshold: Float = DEFAULT_BLOCK_THRESHOLD,
    val warnThreshold: Float = DEFAULT_WARN_THRESHOLD,
    /** Only meaningful for [RuleMode.TIMED]. */
    val dailyBudgetMinutes: Int = 0,
) {
    /** Higher is stricter. Used to tell a tightening edit from a loosening one. */
    val strictness: Int
        get() {
            val modeRank = mode.strictness * 10_000
            // A lower threshold catches more, so it is stricter.
            val thresholdRank = ((1f - blockThreshold) * 1_000).toInt()
            val budgetRank = if (mode == RuleMode.TIMED) -dailyBudgetMinutes else 0
            return modeRank + thresholdRank + budgetRank
        }

    companion object {
        const val DEFAULT_BLOCK_THRESHOLD = 0.60f
        const val DEFAULT_WARN_THRESHOLD = 0.35f
    }
}

/**
 * A rule about an installed app.
 *
 * [budgetId] lets several apps share one bucket, which is what makes "1 hr of Social,
 * spend it how you like" expressible rather than a per-app cap that misses the point.
 */
@Serializable
data class AppRule(
    val packageName: String,
    val label: String = packageName,
    val blocked: Boolean = false,
    val dailyBudgetMinutes: Int = 0,
    val budgetId: String? = null,
    /** Windows during which the app is allowed at all. Empty means any time. */
    val allowedWindows: List<TimeWindow> = emptyList(),
    /** Categories to enforce inside the app where the platform lets us see inside it. */
    val enforcedCategories: Set<Category> = emptySet(),
) {
    val strictness: Int
        get() = (if (blocked) 100_000 else 0) +
            (if (dailyBudgetMinutes > 0) 10_000 - dailyBudgetMinutes else 0) +
            allowedWindows.size * 100 +
            enforcedCategories.size
}

/**
 * "I never want to reach this, by any route." (§3.5)
 *
 * The Messenger→Facebook case generalised: the destination is the rule, and the route
 * is part of what gets matched, so deleting the app is not the same as being unable to
 * reach the site through someone else's in-app browser.
 */
@Serializable
data class DestinationRule(
    /** Bare hostname. Matches the host and, unless [exactHostOnly], its subdomains. */
    val host: String,
    val label: String = host,
    val exactHostOnly: Boolean = false,
    /** Which routes this applies to. Every route, by default — that is the whole point. */
    val routes: Set<RouteContext> = RouteContext.entries.toSet(),
) {
    fun matches(candidateHost: String, route: RouteContext): Boolean {
        if (route !in routes) return false
        val normalised = candidateHost.lowercase().removePrefix("www.")
        val target = host.lowercase().removePrefix("www.")
        return if (exactHostOnly) normalised == target
        else normalised == target || normalised.endsWith(".$target")
    }

    val strictness: Int get() = routes.size + if (exactHostOnly) 0 else 1
}

/**
 * A shared time bucket (§3.4).
 */
@Serializable
data class Budget(
    val id: String,
    val label: String,
    val dailyMinutes: Int,
    val categories: Set<Category> = emptySet(),
    val packageNames: Set<String> = emptySet(),
    /** Windows in which spending is permitted at all. Empty means any time. */
    val allowedWindows: List<TimeWindow> = emptyList(),
    val rolloverEnabled: Boolean = false,
    val maxRolloverMinutes: Int = 30,
) {
    val strictness: Int
        get() = -dailyMinutes + allowedWindows.size * 100 + if (rolloverEnabled) -50 else 0
}

/**
 * A named rule overlay that switches itself on by time (§3.8).
 *
 * A profile may only ever make things stricter. That is not a simplification — a
 * profile that could loosen rules on a schedule would be a cooling-off bypass with a
 * calendar attached.
 */
@Serializable
data class Profile(
    val id: String,
    val label: String,
    val windows: List<TimeWindow> = emptyList(),
    val enabled: Boolean = true,
    /** Minimum mode to apply to these categories while active. */
    val categoryFloors: Map<Category, RuleMode> = emptyMap(),
    val additionalBlockedPackages: Set<String> = emptySet(),
    val additionalBlockedHosts: Set<String> = emptySet(),
) {
    fun isActiveAt(utcMillis: Long, utcOffsetMinutes: Int): Boolean =
        enabled && windows.any { it.contains(utcMillis, utcOffsetMinutes) }
}

/**
 * An on-demand total lockdown (§3.9). Ends at [endsAtMillis] and cannot be ended early
 * unless [allowEarlyExit] was set when it started — the decision is made by the version
 * of you that started the session, not the one that wants out of it.
 */
@Serializable
data class FocusSession(
    val startedAtMillis: Long,
    val endsAtMillis: Long,
    val label: String = "Focus session",
    val allowedPackages: Set<String> = emptySet(),
    val allowedHosts: Set<String> = emptySet(),
    val allowEarlyExit: Boolean = false,
) {
    fun isActiveAt(utcMillis: Long): Boolean = utcMillis in startedAtMillis until endsAtMillis
}

/**
 * The accountability relationship (§3.7). A contact address and nothing more —
 * Aegis never sends browsing history anywhere, only the fact that a rule was weakened.
 */
@Serializable
data class AccountabilityPartner(
    val name: String,
    val contact: String,
    val notifyOnWeakening: Boolean = true,
    val notifyOnBlockedAttempt: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * The complete, serialisable statement of what the user has asked Aegis to enforce.
 *
 * One object, so a rule change is a single atomic swap, and so the cooling-off logic can
 * diff two whole states rather than trying to intercept every individual setter.
 */
@Serializable
data class RuleSet(
    val categoryRules: Map<Category, CategoryRule> = emptyMap(),
    val appRules: Map<String, AppRule> = emptyMap(),
    val destinationRules: List<DestinationRule> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val focusSession: FocusSession? = null,
    val partner: AccountabilityPartner? = null,
    /** How long a loosening change waits before it takes effect (§3.6). */
    val coolingOffHours: Int = DEFAULT_COOLING_OFF_HOURS,
    /** Seconds a grace tap must sit through before it grants more time (§3.4). */
    val graceTapPauseSeconds: Int = DEFAULT_GRACE_PAUSE_SECONDS,
    val graceTapMinutes: Int = DEFAULT_GRACE_MINUTES,
    val maxGraceTapsPerDay: Int = DEFAULT_MAX_GRACE_TAPS,
    /** Blur flagged images rather than blocking the whole page where we render it. */
    val blurFlaggedImages: Boolean = true,
    val version: Long = 0,
) {
    fun ruleFor(category: Category): CategoryRule =
        categoryRules[category] ?: CategoryRule(category)

    fun ruleFor(packageName: String): AppRule? = appRules[packageName]

    fun budgetById(id: String): Budget? = budgets.firstOrNull { it.id == id }

    fun withCategoryRule(rule: CategoryRule): RuleSet =
        copy(categoryRules = categoryRules + (rule.category to rule))

    fun withAppRule(rule: AppRule): RuleSet =
        copy(appRules = appRules + (rule.packageName to rule))

    fun withDestinationRule(rule: DestinationRule): RuleSet =
        copy(destinationRules = destinationRules.filterNot { it.host.equals(rule.host, true) } + rule)

    fun withoutDestination(host: String): RuleSet =
        copy(destinationRules = destinationRules.filterNot { it.host.equals(host, true) })

    fun activeProfiles(utcMillis: Long, utcOffsetMinutes: Int): List<Profile> =
        profiles.filter { it.isActiveAt(utcMillis, utcOffsetMinutes) }

    companion object {
        const val DEFAULT_COOLING_OFF_HOURS = 24
        const val DEFAULT_GRACE_PAUSE_SECONDS = 60
        const val DEFAULT_GRACE_MINUTES = 5
        const val DEFAULT_MAX_GRACE_TAPS = 3

        /**
         * What Aegis ships with (§8: "how opinionated is the default").
         *
         * Opinionated, because a self-control tool that starts as a blank configuration
         * screen gets abandoned on that screen. The categories almost nobody wants are
         * walled, the ones that are genuinely a matter of degree are budgeted, and the
         * user loosens from there — which, by design, costs them a cooling-off wait.
         */
        fun defaults(): RuleSet = RuleSet(
            categoryRules = mapOf(
                Category.ADULT to CategoryRule(Category.ADULT, RuleMode.WALL),
                Category.SELF_HARM to CategoryRule(Category.SELF_HARM, RuleMode.WALL, blockThreshold = 0.5f),
                Category.EXTREMIST to CategoryRule(Category.EXTREMIST, RuleMode.WALL),
                Category.GAMBLING to CategoryRule(Category.GAMBLING, RuleMode.WALL),
                Category.VIOLENCE to CategoryRule(Category.VIOLENCE, RuleMode.WARN),
                Category.DRUGS to CategoryRule(Category.DRUGS, RuleMode.WARN),
                Category.SOCIAL to CategoryRule(
                    Category.SOCIAL,
                    RuleMode.TIMED,
                    blockThreshold = 0.5f,
                    dailyBudgetMinutes = 60,
                ),
            ),
            budgets = listOf(
                Budget(
                    id = "social",
                    label = "Social",
                    dailyMinutes = 60,
                    categories = setOf(Category.SOCIAL),
                    allowedWindows = listOf(
                        TimeWindow.of(9, 0, 22, 0, Days.ALL),
                    ),
                ),
            ),
        )
    }
}
