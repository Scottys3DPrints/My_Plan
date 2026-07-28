package com.aegis.core.log

import com.aegis.core.model.Category
import com.aegis.core.model.RouteContext
import com.aegis.core.rules.BlockCause
import com.aegis.core.rules.Decision
import com.aegis.core.rules.Outcome
import kotlinx.serialization.Serializable

/**
 * One line of "why was this blocked?" (§3.10).
 *
 * Note what is *not* here: no page text, no full URL query strings, no screenshots. The
 * log records a hostname, a verdict and the words that caused it — enough to judge
 * whether a block was fair, and not enough to reconstruct a browsing history worth
 * stealing. It never leaves the device.
 */
@Serializable
data class LogEntry(
    val id: String,
    val atMillis: Long,
    val host: String,
    val route: RouteContext,
    val outcome: Outcome,
    val cause: BlockCause? = null,
    val category: Category? = null,
    val confidence: Float = 0f,
    val explanation: String = "",
    val evidence: List<String> = emptyList(),
    /** Set when the user pressed "this was wrong". */
    val correction: Correction? = null,
    /** True when the user chose to continue past a warning. */
    val proceededAnyway: Boolean = false,
) {
    val wasBlocked: Boolean get() = outcome == Outcome.BLOCK
}

@Serializable
enum class Correction(val id: String, val label: String) {
    /** Blocked, but should not have been. */
    FALSE_POSITIVE("false_positive", "This was wrong"),

    /** Allowed, but should not have been. */
    MISSED("missed", "This should have been blocked"),
}

/**
 * A bounded, newest-first ring of log entries.
 *
 * Bounded because an unbounded local log of everything you looked at is a liability, not
 * a feature. [LIMIT] entries is enough to answer "why did that just get blocked?" and to
 * spot a misbehaving lexicon, which is all this is for.
 */
@Serializable
data class TransparencyLog(
    val entries: List<LogEntry> = emptyList(),
) {
    fun record(entry: LogEntry): TransparencyLog =
        TransparencyLog((listOf(entry) + entries).take(LIMIT))

    fun withCorrection(id: String, correction: Correction?): TransparencyLog =
        TransparencyLog(entries.map { if (it.id == id) it.copy(correction = correction) else it })

    fun markProceeded(id: String): TransparencyLog =
        TransparencyLog(entries.map { if (it.id == id) it.copy(proceededAnyway = true) else it })

    fun blocked(): List<LogEntry> = entries.filter { it.wasBlocked }

    fun corrections(): List<LogEntry> = entries.filter { it.correction != null }

    fun since(millis: Long): List<LogEntry> = entries.filter { it.atMillis >= millis }

    companion object {
        const val LIMIT = 500

        fun entryFor(
            id: String,
            atMillis: Long,
            host: String,
            decision: Decision,
        ): LogEntry = LogEntry(
            id = id,
            atMillis = atMillis,
            host = host,
            route = decision.route,
            outcome = decision.outcome,
            cause = decision.cause,
            category = decision.category,
            confidence = decision.confidence,
            explanation = decision.explanation,
            evidence = decision.evidence,
        )
    }
}
