package com.aegis.core.rules

import com.aegis.core.budget.BudgetKeys
import com.aegis.core.budget.BudgetTracker
import com.aegis.core.budget.GraceClaimResult
import com.aegis.core.budget.GraceRequestResult
import com.aegis.core.budget.UsageState
import com.aegis.core.classifier.LexicalClassifier
import com.aegis.core.model.Category
import com.aegis.core.model.Classification
import com.aegis.core.model.ContentInput
import com.aegis.core.model.RouteContext
import com.aegis.core.util.Days
import com.aegis.core.util.FakeClock
import com.aegis.core.util.LocalTime
import com.aegis.core.util.TimeWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RulesEngineTest {

    private val day = LocalTime.MILLIS_PER_DAY
    private val classifier = LexicalClassifier()

    /** 2023-11-16 was a Thursday; 10:00 local with a zero offset. */
    private fun clockAt(hour: Int, minute: Int = 0, dayOffset: Long = 0) = FakeClock(
        wallMillis = (19_677L + dayOffset) * day + hour * 3_600_000L + minute * 60_000L,
        monotonicMillis = 5_000_000,
    )

    @Test
    fun `a walled category blocks, and says why`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val input = ContentInput(
            url = "https://unknown-site-9182.example/",
            title = "Free adult videos",
            text = "Watch free porn videos. Hardcore scenes, uncensored, updated daily.",
        )

        val decision = engine.evaluateContent(
            input, classifier.classify(input), RuleSet.defaults(), UsageState(),
        )

        assertEquals(Outcome.BLOCK, decision.outcome)
        assertEquals(BlockCause.CATEGORY_WALL, decision.cause)
        assertEquals(Category.ADULT, decision.category)
        assertTrue(decision.explanation.contains("adult"), decision.explanation)
        assertTrue(decision.evidence.isNotEmpty())
    }

    @Test
    fun `an ordinary page is allowed`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val input = ContentInput(
            url = "https://en.wikipedia.example/wiki/Fern",
            title = "Fern",
            text = "Ferns reproduce via spores and have neither seeds nor flowers.",
        )

        val decision = engine.evaluateContent(
            input, classifier.classify(input), RuleSet.defaults(), UsageState(),
        )

        assertEquals(Outcome.ALLOW, decision.outcome)
    }

    @Test
    fun `a never-reach destination is blocked through an in-app browser`() {
        // The Messenger to Facebook case. The app is gone; the side door is not.
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val rules = RuleSet.defaults().withDestinationRule(DestinationRule(host = "facebook.com"))

        val decision = engine.evaluateHost(
            host = "m.facebook.com",
            route = RouteContext.IN_APP_WEBVIEW,
            rules = rules,
            usage = UsageState(),
        )

        assertEquals(Outcome.BLOCK, decision.outcome)
        assertEquals(BlockCause.DESTINATION_RULE, decision.cause)
        assertTrue(decision.explanation.contains("in-app browser"), decision.explanation)
    }

    @Test
    fun `a destination rule beats the classifier having no opinion`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val rules = RuleSet.defaults().withDestinationRule(DestinationRule(host = "news.example"))

        // Nothing about this hostname looks like any category at all.
        val decision = engine.evaluateHost("news.example", RouteContext.DNS, rules, UsageState())
        assertEquals(Outcome.BLOCK, decision.outcome)
    }

    @Test
    fun `subdomains are covered unless the rule says otherwise`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)

        val broad = RuleSet.defaults().withDestinationRule(DestinationRule(host = "example.com"))
        assertEquals(
            Outcome.BLOCK,
            engine.evaluateHost("cdn.static.example.com", RouteContext.DNS, broad, UsageState()).outcome,
        )

        val exact = RuleSet.defaults()
            .withDestinationRule(DestinationRule(host = "example.com", exactHostOnly = true))
        assertEquals(
            Outcome.ALLOW,
            engine.evaluateHost("cdn.static.example.com", RouteContext.DNS, exact, UsageState()).outcome,
        )
    }

    @Test
    fun `a spent budget blocks and offers a grace tap`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val tracker = BudgetTracker(clock)
        val rules = RuleSet.defaults()
        val today = LocalTime.epochDay(clock.nowMillis(), 0)

        val spent = tracker.record(
            UsageState(epochDay = today),
            rules,
            seconds = 3_600,
            categories = setOf(Category.SOCIAL),
        )

        val input = ContentInput(url = "https://reddit.example/r/all", text = "upvote news feed")
        val decision = engine.evaluateContent(input, classifier.classify(input), rules, spent)

        assertEquals(Outcome.BLOCK, decision.outcome)
        assertEquals(BlockCause.CATEGORY_BUDGET_SPENT, decision.cause)
        assertTrue(decision.graceTapAvailable)
        assertEquals(BudgetKeys.forBudget("social"), decision.graceKey, "the block screen must credit the right bucket")
        assertNotNull(decision.liftsAtMillis)
    }

    @Test
    fun `a claimed grace tap unblocks for exactly as long as it bought`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val tracker = BudgetTracker(clock)
        val rules = RuleSet.defaults()
        val today = LocalTime.epochDay(clock.nowMillis(), 0)
        val key = BudgetKeys.forBudget("social")

        var usage = tracker.record(
            UsageState(epochDay = today), rules, 3_600, categories = setOf(Category.SOCIAL),
        )
        val input = ContentInput(url = "https://reddit.example/", text = "upvote news feed")
        assertEquals(Outcome.BLOCK, engine.evaluateContent(input, classifier.classify(input), rules, usage).outcome)

        usage = (tracker.requestGraceTap(usage, rules, key) as GraceRequestResult.Waiting).state
        clock.advance(60_000)
        usage = (tracker.claimGraceTap(usage, rules, key) as GraceClaimResult.Granted).state

        assertEquals(Outcome.ALLOW, engine.evaluateContent(input, classifier.classify(input), rules, usage).outcome)

        clock.advance(6 * 60_000)
        assertEquals(Outcome.BLOCK, engine.evaluateContent(input, classifier.classify(input), rules, usage).outcome)
    }

    @Test
    fun `nothing social before nine or after ten`() {
        val rules = RuleSet.defaults()
        val input = ContentInput(url = "https://reddit.example/", text = "upvote news feed")
        val classification = classifier.classify(input)

        val lateNight = RulesEngine(clockAt(23, 30))
        val late = lateNight.evaluateContent(input, classification, rules, UsageState())
        assertEquals(Outcome.BLOCK, late.outcome)
        assertEquals(BlockCause.OUTSIDE_ALLOWED_WINDOW, late.cause)

        val midMorning = RulesEngine(clockAt(10, 0))
        assertEquals(
            Outcome.ALLOW,
            midMorning.evaluateContent(input, classification, rules, UsageState()).outcome,
        )
    }

    @Test
    fun `a block screen can say when the block lifts`() {
        val engine = RulesEngine(clockAt(23, 30))
        val rules = RuleSet.defaults()
        val input = ContentInput(url = "https://reddit.example/", text = "upvote news feed")

        val decision = engine.evaluateContent(input, classifier.classify(input), rules, UsageState())
        val liftsAt = decision.liftsAtMillis
        assertNotNull(liftsAt)
        assertEquals(9 * 60, LocalTime.minuteOfDay(liftsAt, 0))
    }

    @Test
    fun `warn mode explains rather than refuses`() {
        val engine = RulesEngine(clockAt(10))
        val input = ContentInput(
            url = "https://shock-site.example/",
            title = "Graphic footage",
            text = "Graphic video of a brutal killing. Gore, dismembered bodies, nsfl.",
        )

        val decision = engine.evaluateContent(input, classifier.classify(input), RuleSet.defaults(), UsageState())

        assertEquals(Outcome.WARN, decision.outcome)
        assertEquals(Category.VIOLENCE, decision.category)
    }

    @Test
    fun `a focus session outranks everything else`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val rules = RuleSet.defaults().copy(
            focusSession = FocusSession(
                startedAtMillis = clock.nowMillis() - 60_000,
                endsAtMillis = clock.nowMillis() + 3_600_000,
                label = "Deep work",
                allowedHosts = setOf("docs.example"),
            ),
        )

        val blocked = engine.evaluateHost("en.wikipedia.example", RouteContext.DIRECT, rules, UsageState())
        assertEquals(BlockCause.FOCUS_SESSION, blocked.cause)

        val allowed = engine.evaluateHost("docs.example", RouteContext.DIRECT, rules, UsageState())
        assertEquals(Outcome.ALLOW, allowed.outcome)
    }

    @Test
    fun `a profile can only tighten`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val rules = RuleSet.defaults()
            .withCategoryRule(CategoryRule(Category.SOCIAL, RuleMode.OFF))
            .copy(
                profiles = listOf(
                    Profile(
                        id = "work",
                        label = "Work",
                        windows = listOf(TimeWindow.of(9, 0, 17, 0, Days.WEEKDAYS)),
                        categoryFloors = mapOf(Category.SOCIAL to RuleMode.WALL),
                    ),
                ),
            )

        assertEquals(RuleMode.WALL, engine.effectiveMode(Category.SOCIAL, rules))

        val evening = RulesEngine(clockAt(20))
        assertEquals(RuleMode.OFF, evening.effectiveMode(Category.SOCIAL, rules))
    }

    @Test
    fun `a blocked app is blocked`() {
        val engine = RulesEngine(clockAt(10))
        val rules = RuleSet.defaults().withAppRule(
            AppRule("com.example.social", "Chatter", blocked = true),
        )

        val decision = engine.evaluateApp("com.example.social", rules, UsageState())
        assertEquals(Outcome.BLOCK, decision.outcome)
        assertEquals(BlockCause.APP_BLOCKED, decision.cause)
        assertTrue(decision.explanation.contains("Chatter"))
    }

    @Test
    fun `an app past its own cap is locked`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val rules = RuleSet.defaults().withAppRule(
            AppRule("com.example.tiktok", "TikTok", dailyBudgetMinutes = 30),
        )
        val today = LocalTime.epochDay(clock.nowMillis(), 0)
        val usage = BudgetTracker(clock).record(
            UsageState(epochDay = today), rules, 1_800, packageName = "com.example.tiktok",
        )

        val decision = engine.evaluateApp("com.example.tiktok", rules, usage)
        assertEquals(BlockCause.APP_BUDGET_SPENT, decision.cause)
        assertEquals(0, decision.budgetRemainingMinutes)
    }

    @Test
    fun `an app under its cap reports what is left`() {
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val rules = RuleSet.defaults().withAppRule(
            AppRule("com.example.tiktok", "TikTok", dailyBudgetMinutes = 30),
        )
        val today = LocalTime.epochDay(clock.nowMillis(), 0)
        val usage = BudgetTracker(clock).record(
            UsageState(epochDay = today), rules, 600, packageName = "com.example.tiktok",
        )

        val decision = engine.evaluateApp("com.example.tiktok", rules, usage)
        assertEquals(Outcome.ALLOW, decision.outcome)
        assertEquals(20, decision.budgetRemainingMinutes)
    }

    @Test
    fun `the strongest verdict is the one the user is told about`() {
        // A page that trips two walled categories should talk about the one that actually
        // triggered it, not whichever the engine happened to iterate over first.
        val engine = RulesEngine(clockAt(10))
        val input = ContentInput(url = "https://mixed.example/")
        val classification = Classification(
            scores = mapOf(Category.GAMBLING to 0.71f, Category.ADULT to 0.93f),
            evidence = mapOf(
                Category.GAMBLING to listOf("casino"),
                Category.ADULT to listOf("porn"),
            ),
        )

        val decision = engine.evaluateContent(input, classification, RuleSet.defaults(), UsageState())

        assertEquals(Category.ADULT, decision.category)
        assertEquals(listOf("porn"), decision.evidence)
    }

    @Test
    fun `a neutrally-named site is caught in another browser by its text, not its name`() {
        // The reported failure: Chrome let explicit content through because only the
        // hostname was judged, and the name of a site is chosen by the people running it.
        val engine = RulesEngine(clockAt(10))
        val rules = RuleSet.defaults()

        val hostOnly = ContentInput(url = "https://quiet-brook-4471.example/watch", route = RouteContext.REDIRECT)
        assertEquals(
            Outcome.ALLOW,
            engine.evaluateContent(hostOnly, classifier.classify(hostOnly), rules, UsageState()).outcome,
            "the hostname alone gives nothing away — this is why reading the page matters",
        )

        // What an accessibility harvest of such a page actually looks like: the browser's
        // window title plus a screenful of link text and headings.
        val harvested = hostOnly.copy(
            title = "Free porn videos — full length adult videos",
            text = "Watch free porn videos in HD. Thousands of adult videos, uncensored and " +
                "updated daily. Hardcore scenes, no signup. Most viewed porn this week. " +
                "Trending adult videos. Longest porn clips. Categories. Live cams. " +
                "Recommended for you. More free porn.",
        )
        val decision = engine.evaluateContent(harvested, classifier.classify(harvested), rules, UsageState())

        assertEquals(Outcome.BLOCK, decision.outcome)
        assertEquals(Category.ADULT, decision.category)
        assertEquals(RouteContext.REDIRECT, decision.route, "the block should name the route it came in by")
    }

    @Test
    fun `a passing mention of adult content warns rather than blocks`() {
        // The other side of the same coin, pinned so it cannot drift: a page that merely
        // touches the subject must not be treated like the subject. In another browser a
        // warning takes no action at all, which is the correct outcome for "not sure".
        val engine = RulesEngine(clockAt(10))
        val input = ContentInput(
            url = "https://news.example/article/regulation",
            title = "Regulator publishes age-verification rules",
            text = "The regulator has published guidance for sites hosting adult videos, " +
                "setting out how age verification should work in practice.",
            route = RouteContext.REDIRECT,
        )

        val decision = engine.evaluateContent(input, classifier.classify(input), RuleSet.defaults(), UsageState())

        assertTrue(
            decision.outcome != Outcome.BLOCK,
            "a news article about the subject is not the subject: got ${decision.outcome}",
        )
    }

    @Test
    fun `a focus session never blocks the launcher or Settings`() {
        // Without this the phone has no reachable home screen for the whole session, and
        // a focus session cannot be ended early. That is a bricked device, not self-control.
        val clock = clockAt(10)
        val engine = RulesEngine(clock)
        val critical = setOf("com.android.launcher", "com.android.settings")
        val rules = RuleSet.defaults().copy(
            focusSession = FocusSession(
                startedAtMillis = clock.nowMillis() - 60_000,
                endsAtMillis = clock.nowMillis() + 3_600_000,
                label = "Deep work",
            ),
        )

        assertEquals(
            Outcome.ALLOW,
            engine.evaluateApp("com.android.launcher", rules, UsageState(), critical).outcome,
        )
        assertEquals(
            Outcome.ALLOW,
            engine.evaluateApp("com.android.settings", rules, UsageState(), critical).outcome,
        )
        assertEquals(
            Outcome.BLOCK,
            engine.evaluateApp("com.example.game", rules, UsageState(), critical).outcome,
            "everything else is still blocked",
        )
    }

    @Test
    fun `an exempt app cannot be blocked even by an explicit rule`() {
        val engine = RulesEngine(clockAt(10))
        val rules = RuleSet.defaults().withAppRule(
            AppRule("com.android.settings", "Settings", blocked = true),
        )

        assertEquals(
            Outcome.ALLOW,
            engine.evaluateApp("com.android.settings", rules, UsageState(), setOf("com.android.settings")).outcome,
        )
    }

    @Test
    fun `an empty classification against an unlisted host is simply allowed`() {
        val engine = RulesEngine(clockAt(10))
        val decision = engine.evaluateHost(
            "cdn.example", RouteContext.DNS, RuleSet.defaults(), UsageState(), Classification.EMPTY,
        )
        assertEquals(Outcome.ALLOW, decision.outcome)
    }
}
