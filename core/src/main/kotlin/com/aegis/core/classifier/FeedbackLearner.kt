package com.aegis.core.classifier

import com.aegis.core.model.Category

/**
 * "This was wrong" → the local model actually changes (§3.10).
 *
 * The learning rule is intentionally boring: the terms that caused a bad verdict get
 * their weight multiplied down, the terms present when something was missed get nudged
 * up, and everything is clamped. Boring is the point — the user has to be able to
 * predict what their correction did, and to undo it. A gradient step they cannot see
 * would trade one kind of arbitrariness for another.
 *
 * Two properties this guarantees, both of which matter more than accuracy:
 *
 * - **Bounded.** No term can be driven to zero or to dominance, so a run of angry taps
 *   cannot quietly disable a whole category.
 * - **Reversible.** Every adjustment is keyed by category and term, so "reset what
 *   Aegis learned from me" is a real button and not a reinstall.
 */
class FeedbackLearner(
    private val lexicons: Map<Category, Lexicon> = DefaultLexicons.ALL,
) {

    /**
     * The user says a block was wrong. Damp the terms that caused it.
     *
     * Only the evidence actually cited for that block is touched — the same words in
     * some other category are untouched, because they were not the ones that misfired.
     */
    fun correctFalsePositive(
        overrides: TermWeightOverrides,
        category: Category,
        evidence: List<String>,
    ): TermWeightOverrides {
        val lexicon = lexicons[category] ?: return overrides
        var updated = overrides
        for (term in matchingTerms(lexicon, evidence)) {
            val current = updated.multiplierFor(category, term)
            updated = updated.with(term.overrideKey(category), current * DAMP_FACTOR)
        }
        return updated
    }

    /**
     * The user says something should have been caught. Boost whichever of that
     * category's terms actually appeared in the content.
     */
    fun correctMiss(
        overrides: TermWeightOverrides,
        category: Category,
        text: String,
        url: String = "",
    ): TermWeightOverrides {
        val lexicon = lexicons[category] ?: return overrides
        val haystack = LexicalClassifier.normalise("$text $url")
        val tokens = LexicalClassifier.tokenise(haystack).toSet()

        var updated = overrides
        var boosted = 0
        for (term in lexicon.terms) {
            val present = if (term.isPhrase) haystack.contains(term.pattern) else term.pattern in tokens
            if (!present) continue
            val current = updated.multiplierFor(category, term)
            updated = updated.with(term.overrideKey(category), current * BOOST_FACTOR)
            boosted++
            if (boosted >= MAX_BOOSTS_PER_CORRECTION) break
        }
        return updated
    }

    /** Undo one learned adjustment. */
    fun reset(overrides: TermWeightOverrides, category: Category, pattern: String): TermWeightOverrides =
        overrides.without("${category.id}:$pattern")

    /** Undo everything learned for one category. */
    fun resetCategory(overrides: TermWeightOverrides, category: Category): TermWeightOverrides =
        TermWeightOverrides(overrides.multipliers.filterKeys { !it.startsWith("${category.id}:") })

    /** What the device has learned, in a form the settings screen can list. */
    fun describe(overrides: TermWeightOverrides): List<LearnedAdjustment> =
        overrides.multipliers.mapNotNull { (key, multiplier) ->
            val category = Category.fromId(key.substringBefore(':')) ?: return@mapNotNull null
            LearnedAdjustment(
                category = category,
                pattern = key.substringAfter(':'),
                multiplier = multiplier,
                key = key,
            )
        }.sortedWith(compareBy({ it.category.ordinal }, { it.pattern }))

    /**
     * Evidence strings come back from [Classification] as the term patterns themselves,
     * with dampeners prefixed by "−" and host hits phrased as sentences. Only the plain
     * positive terms are learnable.
     */
    private fun matchingTerms(lexicon: Lexicon, evidence: List<String>): List<Term> {
        val cited = evidence.filterNot { it.startsWith("−") }.toSet()
        return lexicon.terms.filter { it.pattern in cited }
    }

    companion object {
        /** One "this was wrong" roughly halves a term's influence. */
        const val DAMP_FACTOR = 0.55f
        const val BOOST_FACTOR = 1.45f
        const val MAX_BOOSTS_PER_CORRECTION = 6
    }
}

data class LearnedAdjustment(
    val category: Category,
    val pattern: String,
    val multiplier: Float,
    val key: String,
) {
    val describesWeakening: Boolean get() = multiplier < 1f

    val label: String
        get() = if (describesWeakening) {
            "\"$pattern\" counts for less in ${category.label}"
        } else {
            "\"$pattern\" counts for more in ${category.label}"
        }
}
