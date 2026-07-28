package com.aegis.core.classifier

import com.aegis.core.model.Category
import com.aegis.core.model.ContentInput
import com.aegis.core.model.ImageSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LexicalClassifierTest {

    private val classifier = LexicalClassifier()

    @Test
    fun `judges an unseen site by what it contains, not its name`() {
        // The whole premise: a domain nobody has ever listed, caught on content alone.
        val result = classifier.classify(
            ContentInput(
                url = "https://quiet-brook-4471.example/watch/2291",
                title = "Full length scenes — free streaming",
                text = "Watch free porn videos in HD. Thousands of adult videos, " +
                    "uncensored and updated daily. Hardcore scenes, no signup.",
            ),
        )

        assertTrue(
            result.score(Category.ADULT) > 0.8f,
            "expected a confident adult verdict, got ${result.score(Category.ADULT)}",
        )
    }

    @Test
    fun `does not block a cancer charity for using the words it has to use`() {
        val result = classifier.classify(
            ContentInput(
                url = "https://breastcancernow.org.example/information",
                title = "Breast cancer information and support",
                text = "Information about breast cancer symptoms, sexual health after " +
                    "treatment, and support for people affected by breast cancer.",
            ),
        )

        assertTrue(
            result.score(Category.ADULT) < 0.35f,
            "charity page scored ${result.score(Category.ADULT)} for adult content",
        )
    }

    @Test
    fun `does not block a gambling-addiction helpline`() {
        val result = classifier.classify(
            ContentInput(
                url = "https://gamcare.org.example/help",
                title = "Problem gambling support",
                text = "Free, confidential help for anyone affected by gambling addiction. " +
                    "Talk to an adviser about self-exclusion, or read about problem gambling.",
            ),
        )

        assertTrue(
            result.score(Category.GAMBLING) < 0.4f,
            "helpline scored ${result.score(Category.GAMBLING)} for gambling",
        )
    }

    @Test
    fun `does not fire on a word merely contained in a hostname label`() {
        // "sex" is an adult host token; "essex" must not match it.
        val result = classifier.classify(
            ContentInput(
                url = "https://essex.gov.example/bin-collections",
                title = "Bin collections",
                text = "Find your bin collection day for the borough.",
            ),
        )

        assertEquals(0f, result.score(Category.ADULT))
    }

    @Test
    fun `catches a hostname even with no page text at all`() {
        // This is the only signal the network filter gets through TLS.
        val result = classifier.classify(ContentInput(url = "https://pornhub.example/"))
        assertTrue(result.score(Category.ADULT) > 0.6f)
    }

    @Test
    fun `an adult-only top level domain is a self-declaration`() {
        val result = classifier.classify(ContentInput(url = "https://something.xxx/"))
        assertTrue(result.score(Category.ADULT) > 0.7f)
    }

    @Test
    fun `explicit imagery tips an otherwise ordinary forum page over the line`() {
        val page = ContentInput(
            url = "https://general-forum.example/thread/8812",
            title = "Weekend thread",
            text = "General discussion thread. Post anything here. Two hundred replies so far.",
        )

        val withoutImages = classifier.classify(page)
        val withImages = classifier.classify(
            page.copy(
                imageSignals = listOf(
                    ImageSignal(ref = "img-1", skinToneRatio = 0.82f, prominence = 0.7f),
                    ImageSignal(ref = "img-2", skinToneRatio = 0.77f, prominence = 0.6f),
                ),
            ),
        )

        assertEquals(0f, withoutImages.score(Category.ADULT))
        assertTrue(
            withImages.score(Category.ADULT) > withoutImages.score(Category.ADULT),
            "image signals should raise the score",
        )
    }

    @Test
    fun `image signals alone never produce a confident block`() {
        // A beach photo is not pornography, and the scorer must not pretend it can tell.
        val result = classifier.classify(
            ContentInput(
                url = "https://holiday-photos.example/album",
                title = "Beach album",
                text = "Photos from our trip.",
                imageSignals = (1..12).map {
                    ImageSignal(ref = "img-$it", skinToneRatio = 0.95f, prominence = 1f)
                },
            ),
        )

        assertTrue(
            result.score(Category.ADULT) < 0.6f,
            "images alone reached ${result.score(Category.ADULT)}, which would block a beach album",
        )
    }

    @Test
    fun `repeating one word cannot manufacture a verdict`() {
        val once = classifier.classify(
            ContentInput(url = "https://x.example/", text = "wager"),
        ).score(Category.GAMBLING)

        val fiftyTimes = classifier.classify(
            ContentInput(url = "https://x.example/", text = List(50) { "wager" }.joinToString(" ")),
        ).score(Category.GAMBLING)

        assertTrue(fiftyTimes > once)
        assertTrue(
            fiftyTimes < 0.6f,
            "one repeated word reached $fiftyTimes; saturation is not working",
        )
    }

    @Test
    fun `a word in the title counts for more than the same word in the body`() {
        val inTitle = classifier.classify(
            ContentInput(url = "https://a.example/", title = "roulette", text = "hello"),
        ).score(Category.GAMBLING)

        val inBody = classifier.classify(
            ContentInput(url = "https://a.example/", title = "hello", text = "roulette"),
        ).score(Category.GAMBLING)

        assertTrue(inTitle > inBody)
    }

    @Test
    fun `every verdict carries the evidence that produced it`() {
        val result = classifier.classify(
            ContentInput(
                url = "https://casino-royale.example/slots",
                title = "Free spins and deposit bonus",
                text = "Claim your welcome bonus. Play roulette and blackjack now.",
            ),
        )

        val evidence = result.evidenceFor(Category.GAMBLING)
        assertTrue(evidence.isNotEmpty(), "a block with no stated reason is not acceptable")
        assertTrue(evidence.any { it.contains("free spins") || it.contains("casino") })
    }

    @Test
    fun `a page that matches nothing scores exactly zero`() {
        // Not "8% adult" — noise the user would learn to ignore.
        val result = classifier.classify(
            ContentInput(
                url = "https://en.wikipedia.example/wiki/Fern",
                title = "Fern",
                text = "Ferns are a group of vascular plants that reproduce via spores.",
            ),
        )

        assertFalse(result.scores.values.any { it > 0.2f })
        assertEquals(0f, result.score(Category.ADULT))
    }

    @Test
    fun `self-harm recovery content is not treated as self-harm promotion`() {
        val result = classifier.classify(
            ContentInput(
                url = "https://beateatingdisorders.org.example/",
                title = "Eating disorder treatment and support",
                text = "If you are struggling, our helpline is open. Read about recovery, " +
                    "warning signs, and how to find a support group.",
            ),
        )

        assertTrue(result.score(Category.SELF_HARM) < 0.3f)
    }

    @Test
    fun `url path words are read as well as page text`() {
        val result = classifier.classify(
            ContentInput(url = "https://cdn-media-77.example/free-porn/clip.mp4"),
        )
        assertTrue(result.score(Category.ADULT) > 0.4f)
    }
}
