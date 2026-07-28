package com.aegis.core.feed

import com.aegis.core.rules.FeedRule

/**
 * Noticing that a scroll has stopped being a choice.
 *
 * Every other rule in Aegis answers "may I reach this?". This one answers a question no
 * category or budget can: *you already decided this app was fine, and you have now been
 * flicking through it for twenty minutes without deciding anything else.* A time budget
 * cannot see that — it charges the same minute whether it was spent replying to somebody
 * or falling down a feed — and a classifier cannot see it either, because there is
 * nothing wrong with any individual post.
 *
 * ## What counts as a run
 *
 * A run is an unbroken stretch of scrolling in one app. "Unbroken" is deliberately
 * generous: people put the phone down mid-feed, glance at a message and come back, and
 * treating that as a fresh start would mean the counter never reaches anything. A gap
 * longer than [FeedRule.idleGapSeconds] ends the run; anything shorter continues it.
 *
 * ## Why it needs both a duration and a count
 *
 * Duration alone would fire on an app left open on a table. Count alone would fire on
 * somebody reading one long article, which is a perfectly deliberate act. Requiring both
 * is what separates "using this app" from "being used by it".
 *
 * Pure logic with an injected clock reading, so the whole behaviour is testable without a
 * device — which matters here more than usual, since the thing being modelled takes
 * twenty minutes to happen in real life.
 */
class EndlessFeedWatcher {

    private var runPackage: String? = null
    private var runStartedAt: Long = 0L
    private var lastScrollAt: Long = 0L
    private var scrollCount: Int = 0
    private var lastInterruptedAt: Long = 0L
    private var interruptions: Int = 0

    /** Which app the current run belongs to, if any. */
    val currentRunPackage: String? get() = runPackage

    /**
     * Feed one scroll event in and find out whether it is time to say something.
     *
     * @param nowMillis a monotonic reading. Wall-clock time would let a clock change
     *        either erase a run or invent one, and this is a feature people will be
     *        motivated to argue with.
     */
    fun onScroll(nowMillis: Long, packageName: String, rule: FeedRule): FeedVerdict {
        if (!rule.appliesTo(packageName)) {
            // Not a watched app. End any run rather than leaving it half-open, so coming
            // back to a watched app later starts from zero rather than mid-count.
            reset()
            return FeedVerdict.Quiet
        }

        val continuing = packageName == runPackage &&
            nowMillis >= lastScrollAt &&
            nowMillis - lastScrollAt <= rule.idleGapSeconds * 1_000L

        if (!continuing) startRun(packageName, nowMillis)

        lastScrollAt = nowMillis
        scrollCount++

        val elapsedMillis = nowMillis - runStartedAt
        if (scrollCount < rule.minScrolls) return FeedVerdict.Quiet
        if (elapsedMillis < rule.afterMinutes * 60_000L) return FeedVerdict.Quiet

        // After the first interruption, wait the reminder interval before the next one.
        // Without this the screen would reappear on the very next flick, which teaches
        // people to dismiss it without reading it — the opposite of the point.
        if (interruptions > 0 &&
            nowMillis - lastInterruptedAt < rule.remindEveryMinutes * 60_000L
        ) {
            return FeedVerdict.Quiet
        }

        val verdict = FeedVerdict.Interrupt(
            packageName = packageName,
            minutes = (elapsedMillis / 60_000L).toInt(),
            scrolls = scrollCount,
            repeat = interruptions > 0,
        )
        lastInterruptedAt = nowMillis
        interruptions++
        return verdict
    }

    private fun startRun(packageName: String, nowMillis: Long) {
        runPackage = packageName
        runStartedAt = nowMillis
        scrollCount = 0
        lastInterruptedAt = 0L
        interruptions = 0
    }

    fun reset() {
        runPackage = null
        runStartedAt = 0L
        lastScrollAt = 0L
        scrollCount = 0
        lastInterruptedAt = 0L
        interruptions = 0
    }
}

sealed interface FeedVerdict {
    /** Nothing to say. The overwhelmingly common answer, and it has to stay cheap. */
    data object Quiet : FeedVerdict

    data class Interrupt(
        val packageName: String,
        val minutes: Int,
        val scrolls: Int,
        /** Whether this is a repeat reminder rather than the first of the run. */
        val repeat: Boolean,
    ) : FeedVerdict
}
