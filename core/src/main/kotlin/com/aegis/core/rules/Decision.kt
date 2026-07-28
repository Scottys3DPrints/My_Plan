package com.aegis.core.rules

import com.aegis.core.model.Category
import com.aegis.core.model.RouteContext
import kotlinx.serialization.Serializable

@Serializable
enum class Outcome {
    ALLOW,

    /** Show an interstitial. The user may continue, and the fact that they did is logged. */
    WARN,

    /** Refuse. */
    BLOCK,
}

/**
 * Why something was blocked, in the user's terms rather than the engine's.
 *
 * This exists because "blocked" on its own is the answer that makes people uninstall
 * a filter. Every block Aegis shows names its cause.
 */
@Serializable
enum class BlockCause(val id: String, val headline: String) {
    CATEGORY_WALL("category_wall", "This is walled off"),
    CATEGORY_BUDGET_SPENT("category_budget_spent", "Today's time is spent"),
    OUTSIDE_ALLOWED_WINDOW("outside_window", "Not during these hours"),
    DESTINATION_RULE("destination_rule", "You asked never to reach this"),
    APP_BLOCKED("app_blocked", "This app is blocked"),
    APP_BUDGET_SPENT("app_budget_spent", "Today's time is spent"),
    FOCUS_SESSION("focus_session", "Focus session in progress"),
    PROFILE("profile", "A profile is active"),
    ENDLESS_SCROLL("endless_scroll", "You have been scrolling a while"),
    ;

    companion object {
        fun fromId(id: String): BlockCause? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The engine's answer about one thing the user is trying to reach.
 */
@Serializable
data class Decision(
    val outcome: Outcome,
    val cause: BlockCause? = null,
    val category: Category? = null,
    val confidence: Float = 0f,
    val route: RouteContext = RouteContext.DIRECT,
    /** Plain-language explanation, ready to show on the block screen. */
    val explanation: String = "",
    /** The specific signals that drove this, for the transparency log (§3.10). */
    val evidence: List<String> = emptyList(),
    /** Minutes left in the relevant budget, when one applies. */
    val budgetRemainingMinutes: Int? = null,
    /** Set when the block lifts on its own, e.g. a window opening or a session ending. */
    val liftsAtMillis: Long? = null,
    /** True when a grace tap could buy more time here (§3.4). */
    val graceTapAvailable: Boolean = false,
    /**
     * Which budget a grace tap would be spent against. Carried on the decision so the
     * block screen cannot guess wrong and credit the wrong bucket.
     */
    val graceKey: String? = null,
    /** Images the renderer should blur even though the page itself is allowed. */
    val blurRefs: List<String> = emptyList(),
) {
    val isBlocked: Boolean get() = outcome == Outcome.BLOCK
    val isAllowed: Boolean get() = outcome == Outcome.ALLOW

    companion object {
        fun allow(route: RouteContext = RouteContext.DIRECT, blurRefs: List<String> = emptyList()) =
            Decision(Outcome.ALLOW, route = route, blurRefs = blurRefs)
    }
}
