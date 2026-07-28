package com.aegis.core.classifier

import com.aegis.core.model.Category
import com.aegis.core.model.Classification
import com.aegis.core.model.ContentInput
import com.aegis.core.model.ImageSignal
import com.aegis.core.util.Clock
import com.aegis.core.util.SystemClock
import com.aegis.core.util.Urls
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * The shipped on-device classifier.
 *
 * It is a weighted-evidence scorer over page text, title, metadata, hostname and coarse
 * image signals. It is not a neural network, and that is a deliberate v1 choice rather
 * than an accident: it needs no model download, runs in well under a millisecond on a
 * mid-range phone, is fully deterministic, and — most importantly for §3.10 — can always
 * say exactly which words caused a block. A learned model can be dropped in behind
 * [ContentClassifier] later without touching a single caller.
 *
 * Scoring, in one paragraph: each matching term contributes `weight × placement × learned`,
 * where repeated hits saturate logarithmically so a page cannot be pushed over the line by
 * repeating one word, and where a match in the hostname or title counts for more than the
 * same word buried in body text. Dampeners subtract on the same scale. The resulting raw
 * score is squashed into a 0..1 confidence that is exactly 0 when nothing matched.
 */
class LexicalClassifier(
    private val lexicons: Map<Category, Lexicon> = DefaultLexicons.ALL,
    private val overrides: TermWeightOverrides = TermWeightOverrides.NONE,
    private val clock: Clock = SystemClock,
) : ContentClassifier {

    override fun classify(input: ContentInput): Classification {
        val startedAt = clock.nowMillis()

        val host = hostOf(input.url)
        val hostLabels = hostLabels(host)
        val tld = host.substringAfterLast('.', "")

        val bodyText = normalise(input.text)
        val bodyTokens = tokenise(bodyText)
        val bodyCounts = bodyTokens.groupingBy { it }.eachCount()

        val titleText = normalise(input.title)
        val titleTokens = tokenise(titleText).toSet()

        val metaText = normalise(input.metaKeywords.joinToString(" "))
        val metaTokens = tokenise(metaText).toSet()

        val pathText = normalise(pathAndQueryOf(input.url).replace(Regex("[/_\\-?=&.]"), " "))
        val pathTokens = tokenise(pathText).toSet()

        val altText = normalise(input.imageSignals.joinToString(" ") { it.describedBy })
        val altTokens = tokenise(altText).toSet()
        val altCounts = tokenise(altText).groupingBy { it }.eachCount()

        val scores = LinkedHashMap<Category, Float>()
        val evidence = LinkedHashMap<Category, List<String>>()

        for ((category, lexicon) in lexicons) {
            val found = mutableListOf<String>()
            var raw = 0f

            for (term in lexicon.terms) {
                val learned = overrides.multiplierFor(category, term)
                if (learned <= 0f) continue

                val contribution = contributionOf(
                    term = term,
                    bodyText = bodyText,
                    bodyCounts = bodyCounts,
                    titleText = titleText,
                    titleTokens = titleTokens,
                    metaText = metaText,
                    metaTokens = metaTokens,
                    pathText = pathText,
                    pathTokens = pathTokens,
                    altText = altText,
                    altCounts = altCounts,
                    altTokens = altTokens,
                ) * learned

                if (contribution > 0f) {
                    raw += contribution
                    found += term.pattern
                }
            }

            val hostHit = hostContribution(lexicon, host, hostLabels, tld)
            if (hostHit.weight > 0f) {
                raw += hostHit.weight
                found += hostHit.reason
            }

            if (raw > 0f) {
                for (dampener in lexicon.dampeners) {
                    val hit = contributionOf(
                        term = dampener,
                        bodyText = bodyText,
                        bodyCounts = bodyCounts,
                        titleText = titleText,
                        titleTokens = titleTokens,
                        metaText = metaText,
                        metaTokens = metaTokens,
                        pathText = pathText,
                        pathTokens = pathTokens,
                        altText = altText,
                        altCounts = altCounts,
                        altTokens = altTokens,
                    )
                    if (hit > 0f) {
                        raw -= hit
                        found += "−${dampener.pattern}"
                    }
                }
            }

            if (category == Category.ADULT) {
                val imageHit = imageContribution(input.imageSignals)
                if (imageHit > 0f) {
                    raw += imageHit
                    found += "explicit-looking imagery"
                }
            }

            val confidence = squash(raw)
            if (confidence > 0f) {
                scores[category] = confidence
                evidence[category] = found.take(MAX_EVIDENCE)
            }
        }

        return Classification(
            scores = scores,
            evidence = evidence,
            elapsedMillis = clock.nowMillis() - startedAt,
        )
    }

    /**
     * A hostname alone is often enough to decide — and it is all a network-level filter
     * ever gets to see through TLS (§5). Matching is per label, so "sex" fires on
     * `sex.example.com` but never on `essex.gov.uk`.
     */
    private fun hostContribution(
        lexicon: Lexicon,
        host: String,
        labels: List<String>,
        tld: String,
    ): HostHit {
        if (host.isEmpty()) return HostHit.NONE

        for (substring in lexicon.hostSubstrings) {
            if (labels.any { it.contains(substring) }) {
                return HostHit(HOST_WEIGHT, "hostname contains \"$substring\"")
            }
        }
        for (token in lexicon.hostTokens) {
            if (labels.any { it == token }) {
                return HostHit(HOST_WEIGHT, "hostname label \"$token\"")
            }
        }
        for (candidate in lexicon.tlds) {
            if (tld == candidate) {
                return HostHit(TLD_WEIGHT, "top-level domain \".$candidate\"")
            }
        }
        return HostHit.NONE
    }

    /**
     * Images are a supporting witness, never the whole case.
     *
     * A skin-tone ratio is a crude signal that cannot tell a beach photo from
     * pornography, so its total possible contribution is capped below the level that
     * alone produces a confident block. In practice it moves a page from "allowed" to
     * "warn", and it tips pages that text has already made suspicious over the line —
     * which is exactly the §3.1 case of explicit imagery inside an ordinary forum.
     */
    private fun imageContribution(signals: List<ImageSignal>): Float {
        if (signals.isEmpty()) return 0f
        var total = 0f
        for (signal in signals) {
            if (signal.skinToneRatio < SKIN_RATIO_FLOOR) continue
            val excess = (signal.skinToneRatio - SKIN_RATIO_FLOOR) / (1f - SKIN_RATIO_FLOOR)
            total += excess * signal.prominence.coerceIn(0f, 1f)
        }
        return min(IMAGE_CONTRIBUTION_CAP, total * IMAGE_SCALE)
    }

    private fun contributionOf(
        term: Term,
        bodyText: String,
        bodyCounts: Map<String, Int>,
        titleText: String,
        titleTokens: Set<String>,
        metaText: String,
        metaTokens: Set<String>,
        pathText: String,
        pathTokens: Set<String>,
        altText: String,
        altCounts: Map<String, Int>,
        altTokens: Set<String>,
    ): Float {
        val pattern = term.pattern
        var placement = 0f

        if (term.isPhrase) {
            val bodyHits = countOccurrences(bodyText, pattern)
            if (bodyHits > 0) placement += BODY_PLACEMENT * saturate(bodyHits)
            if (titleText.contains(pattern)) placement += TITLE_PLACEMENT
            if (metaText.contains(pattern)) placement += META_PLACEMENT
            if (pathText.contains(pattern)) placement += PATH_PLACEMENT
            val altHits = countOccurrences(altText, pattern)
            if (altHits > 0) placement += ALT_PLACEMENT * saturate(altHits)
        } else {
            val bodyHits = bodyCounts[pattern] ?: 0
            if (bodyHits > 0) placement += BODY_PLACEMENT * saturate(bodyHits)
            if (pattern in titleTokens) placement += TITLE_PLACEMENT
            if (pattern in metaTokens) placement += META_PLACEMENT
            if (pattern in pathTokens) placement += PATH_PLACEMENT
            val altHits = altCounts[pattern] ?: 0
            if (altHits > 0) placement += ALT_PLACEMENT * saturate(altHits)
            if (pattern !in titleTokens && pattern !in metaTokens && pattern !in pathTokens &&
                bodyHits == 0 && altHits == 0 && pattern.length >= COMPOUND_MIN_LENGTH
            ) {
                // Catch glued-together forms like "freeporn" that tokenisation splits apart.
                if (bodyText.contains(pattern) || titleText.contains(pattern)) {
                    placement += COMPOUND_PLACEMENT
                }
            }
        }

        if (placement == 0f) return 0f
        return term.weight * min(placement, PLACEMENT_CAP)
    }

    /**
     * Repeated hits count for less and less. Without this, one word repeated in a page
     * footer forty times would outweigh genuine evidence spread across a page.
     */
    private fun saturate(count: Int): Float =
        min(1f, (ln(1.0 + count) / ln(1.0 + SATURATION_CAP)).toFloat())

    /**
     * Raw evidence to 0..1 confidence. Anchored so that a page with no evidence at all
     * scores exactly 0 rather than the logistic's non-zero floor — "12% adult" on a page
     * that matched nothing would be noise the user learns to ignore.
     */
    private fun squash(raw: Float): Float {
        if (raw <= 0f) return 0f
        val zero = logistic(0f)
        return ((logistic(raw) - zero) / (1f - zero)).coerceIn(0f, 1f)
    }

    private fun logistic(raw: Float): Float =
        (1.0 / (1.0 + exp(-(raw - LOGISTIC_MIDPOINT) * LOGISTIC_STEEPNESS))).toFloat()

    private data class HostHit(val weight: Float, val reason: String) {
        companion object {
            val NONE = HostHit(0f, "")
        }
    }

    companion object {
        /** Placement multipliers: where a word appears changes how much it means. */
        private const val BODY_PLACEMENT = 1.0f
        private const val TITLE_PLACEMENT = 1.6f
        private const val META_PLACEMENT = 1.2f
        private const val PATH_PLACEMENT = 1.4f
        private const val ALT_PLACEMENT = 1.1f
        private const val COMPOUND_PLACEMENT = 0.9f
        private const val COMPOUND_MIN_LENGTH = 4
        private const val PLACEMENT_CAP = 3.0f

        private const val HOST_WEIGHT = 2.2f
        private const val TLD_WEIGHT = 2.6f

        private const val SATURATION_CAP = 8.0
        private const val LOGISTIC_MIDPOINT = 1.0f
        private const val LOGISTIC_STEEPNESS = 2.2f

        private const val SKIN_RATIO_FLOOR = 0.35f
        private const val IMAGE_SCALE = 1.6f
        private const val IMAGE_CONTRIBUTION_CAP = 0.85f

        private const val MAX_EVIDENCE = 8

        internal fun normalise(raw: String): String =
            raw.lowercase().replace(Regex("\\s+"), " ").trim()

        internal fun tokenise(normalised: String): List<String> =
            normalised.split(Regex("[^\\p{L}\\p{Nd}+]+")).filter { it.isNotEmpty() }

        internal fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var index = haystack.indexOf(needle)
            while (index >= 0) {
                count++
                index = haystack.indexOf(needle, index + needle.length)
            }
            return count
        }

        internal fun hostOf(url: String): String = Urls.host(url)

        internal fun hostLabels(host: String): List<String> = Urls.hostLabels(host)

        internal fun pathAndQueryOf(url: String): String = Urls.pathAndQuery(url)
    }
}
