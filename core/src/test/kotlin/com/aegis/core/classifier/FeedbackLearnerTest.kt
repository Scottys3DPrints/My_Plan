package com.aegis.core.classifier

import com.aegis.core.model.Category
import com.aegis.core.model.ContentInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackLearnerTest {

    private val learner = FeedbackLearner()

    private val stakeholderPage = ContentInput(
        url = "https://consultancy.example/stakeholder-briefing",
        title = "Stakeholder wager on the accumulator odds of policy change",
        text = "A stake in the outcome. Our stakeholders wager that policy will shift. " +
            "The accumulator odds of reform passing this session are discussed below.",
    )

    @Test
    fun `saying a block was wrong actually changes the verdict`() {
        val before = LexicalClassifier().classify(stakeholderPage)
        val evidence = before.evidenceFor(Category.GAMBLING)
        assertTrue(before.score(Category.GAMBLING) > 0f, "test needs a misfire to correct")

        var overrides = TermWeightOverrides.NONE
        repeat(3) {
            overrides = learner.correctFalsePositive(overrides, Category.GAMBLING, evidence)
        }

        val after = LexicalClassifier(overrides = overrides).classify(stakeholderPage)
        assertTrue(
            after.score(Category.GAMBLING) < before.score(Category.GAMBLING),
            "correction did nothing: ${before.score(Category.GAMBLING)} → ${after.score(Category.GAMBLING)}",
        )
    }

    @Test
    fun `a correction is confined to the category it was made in`() {
        val overrides = learner.correctFalsePositive(
            TermWeightOverrides.NONE,
            Category.GAMBLING,
            listOf("stake", "wager"),
        )

        assertTrue(overrides.multipliers.keys.all { it.startsWith("gambling:") })
    }

    @Test
    fun `angry tapping cannot disable a category`() {
        // Bounded on purpose: a bad afternoon must not silently switch off the filter.
        var overrides = TermWeightOverrides.NONE
        repeat(50) {
            overrides = learner.correctFalsePositive(
                overrides, Category.ADULT, listOf("porn", "sex video"),
            )
        }

        assertTrue(overrides.multipliers.values.all { it >= TermWeightOverrides.MIN })

        val stillBlocked = LexicalClassifier(overrides = overrides).classify(
            ContentInput(
                url = "https://pornhub.example/",
                title = "Free porn",
                text = "Watch free porn videos, hardcore adult videos, uncensored.",
            ),
        )
        assertTrue(
            stillBlocked.score(Category.ADULT) > 0.6f,
            "hostname and remaining evidence should still carry it: ${stillBlocked.score(Category.ADULT)}",
        )
    }

    @Test
    fun `reporting a miss raises the terms that were present`() {
        val overrides = learner.correctMiss(
            TermWeightOverrides.NONE,
            Category.GAMBLING,
            text = "Claim your free spins and deposit bonus today",
        )

        assertTrue(overrides.multipliers.isNotEmpty())
        assertTrue(overrides.multipliers.values.all { it > 1f })
        assertTrue(overrides.multipliers.containsKey("gambling:free spins"))
    }

    @Test
    fun `everything learned can be listed, and undone`() {
        var overrides = learner.correctFalsePositive(
            TermWeightOverrides.NONE, Category.GAMBLING, listOf("stake"),
        )
        overrides = learner.correctMiss(overrides, Category.ADULT, text = "hentai")

        val described = learner.describe(overrides)
        assertEquals(2, described.size)
        assertTrue(described.any { it.category == Category.GAMBLING && it.describesWeakening })
        assertTrue(described.any { it.category == Category.ADULT && !it.describesWeakening })

        val cleared = learner.resetCategory(overrides, Category.GAMBLING)
        assertTrue(cleared.multipliers.keys.none { it.startsWith("gambling:") })
        assertTrue(cleared.multipliers.keys.any { it.startsWith("adult:") })
    }

    @Test
    fun `dampener evidence is never mistaken for a learnable term`() {
        // Evidence lists prefix dampeners with a minus sign; boosting those would invert
        // the correction the user just made.
        val overrides = learner.correctFalsePositive(
            TermWeightOverrides.NONE,
            Category.ADULT,
            listOf("−breast cancer", "porn"),
        )

        assertEquals(setOf("adult:porn"), overrides.multipliers.keys)
    }
}
