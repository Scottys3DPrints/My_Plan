package com.aegis.core.classifier

import com.aegis.core.model.Category
import kotlinx.serialization.Serializable

/**
 * A weighted term.
 *
 * [pattern] is matched case-insensitively. A pattern containing a space is matched as
 * a phrase against the normalised text; a single word is matched against whole tokens,
 * so "sex" never fires on "Essex".
 */
@Serializable
data class Term(
    val pattern: String,
    val weight: Float,
) {
    val isPhrase: Boolean get() = pattern.contains(' ')

    /** Key used to store user corrections against this term. */
    fun overrideKey(category: Category): String = "${category.id}:$pattern"
}

/**
 * The evidence base for one category.
 *
 * This is data, not code, so it can be edited, shipped as an asset, extended by the
 * user, and eventually replaced by a learned model behind the same interface.
 */
@Serializable
data class Lexicon(
    val category: Category,
    /** Terms that raise the score. */
    val terms: List<Term> = emptyList(),
    /**
     * Terms that lower it. These are what stop a breast-cancer charity or a
     * gambling-addiction helpline from being blocked by the words it must use.
     */
    val dampeners: List<Term> = emptyList(),
    /** Substrings that are decisive inside a single hostname label ("porn" in "pornhub"). */
    val hostSubstrings: List<String> = emptyList(),
    /** Whole hostname labels that count ("casino" in "grand.casino.example"). */
    val hostTokens: List<String> = emptyList(),
    /** Top-level domains that are effectively a self-declaration. */
    val tlds: List<String> = emptyList(),
)

/**
 * Per-term multipliers learned on-device from the user's "this was wrong" taps (§3.10).
 *
 * Kept separate from [Lexicon] so corrections survive a lexicon update, and so the
 * user can inspect and reset exactly what their device has learned.
 */
@Serializable
data class TermWeightOverrides(
    val multipliers: Map<String, Float> = emptyMap(),
) {
    fun multiplierFor(category: Category, term: Term): Float =
        multipliers[term.overrideKey(category)] ?: 1f

    fun with(key: String, multiplier: Float): TermWeightOverrides =
        TermWeightOverrides(multipliers + (key to multiplier.coerceIn(MIN, MAX)))

    fun without(key: String): TermWeightOverrides =
        TermWeightOverrides(multipliers - key)

    companion object {
        const val MIN = 0.05f
        const val MAX = 3f
        val NONE = TermWeightOverrides()
    }
}
