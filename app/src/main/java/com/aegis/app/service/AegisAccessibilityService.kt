package com.aegis.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aegis.app.block.BlockActivity
import com.aegis.app.block.BlockOverlay
import com.aegis.app.engine.AegisEngine
import com.aegis.core.model.Category
import com.aegis.core.model.RouteContext
import com.aegis.core.rules.BlockCause
import com.aegis.core.rules.Decision
import com.aegis.core.rules.Outcome
import com.aegis.core.budget.GraceClaimResult
import com.aegis.core.budget.GraceRequestResult
import com.aegis.core.util.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App blocking, time budgets, and the side-door guard (§3.3, §3.4, §3.5).
 *
 * Three jobs, all of which need the same one signal — what is on screen right now:
 *
 * 1. **Which app is in front.** Drives app blocks and is what makes a time budget tick.
 * 2. **How long it has been in front.** Accrued in seconds and handed to the budget
 *    tracker, which is why leaving and re-entering an app does not reset anything.
 * 3. **What another browser is showing** — both the address and, on a page that survives
 *    the hostname check, the rendered text. Judging a foreign page on its name alone lets
 *    through anything with a neutral one, which is most of what people need blocked: the
 *    name of a site is chosen by the people who run it, and choosing an innocuous one is
 *    free. Reading the text is what makes Chrome behave like the built-in browser.
 *
 * ## On the cost of this permission
 *
 * An accessibility service can read the screen, and this one reads page text. That is an
 * enormous amount of trust and pretending otherwise would be dishonest, so here is exactly
 * what happens to what it reads:
 *
 * - It is classified in this process, in memory, and discarded on the next scan. No page
 *   text is written to storage, and none of it leaves the device.
 * - The only thing kept is what already appears in the transparency log: a hostname, a
 *   verdict, and the matched terms.
 * - Text is only read from a window that is already showing an address — a browser or a
 *   webview. Aegis does not walk the screen of a messaging app or a notes app.
 * - The walk is hard-bounded and throttled, so it cannot become a battery or jank problem.
 */
class AegisAccessibilityService : AccessibilityService() {

    private lateinit var engine: AegisEngine
    private var overlay: BlockOverlay? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var foregroundPackage: String? = null
    private var foregroundSince: Long = 0L

    private var lastUrlScanAt: Long = 0L
    private var lastHarvestedUrl: String? = null
    private var lastHarvestAt: Long = 0L
    private var harvestAttempts: Int = 0
    private var lastWindowTitle: String = ""
    private var lastBlockedTarget: String? = null
    private var lastBlockAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = AegisEngine.get(this)
        // Resolved here rather than once at install: the user can change launcher at any
        // time, and a stale answer would mean blocking the home screen.
        engine.refreshCriticalPackages()
        overlay = BlockOverlay(this)
        running = this
        isConnected = true
    }

    override fun onDestroy() {
        flushUsage(SystemClock.elapsedRealtime())
        overlay?.hide()
        overlay = null
        running = null
        isConnected = false
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::engine.isInitialized) engine = AegisEngine.get(this)

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Browsers put the page title here. It is a strong signal the harvested
                // body text cannot supply on its own.
                rememberWindowTitle(event)
                onForegroundChanged(packageName)
                checkForegroundApp(packageName)
                scanForBlockedAddress(packageName)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content changes fire constantly; a URL check on every one would be a
                // battery bug. Once a second is enough to catch a navigation.
                val now = SystemClock.elapsedRealtime()
                if (now - lastUrlScanAt >= URL_SCAN_INTERVAL_MILLIS) {
                    lastUrlScanAt = now
                    scanForBlockedAddress(packageName)
                }
            }
        }
    }

    /**
     * Keep whatever the window announced as its title.
     *
     * Discarded when it is just the app's own name, which is what browsers report before a
     * page has settled — using that as a page title would be noise fed to the classifier.
     */
    private fun rememberWindowTitle(event: AccessibilityEvent) {
        val announced = event.text.orEmpty()
            .filterNotNull()
            .joinToString(" ") { it.toString() }
            .trim()
        if (announced.isBlank() || announced.length > MAX_TITLE_LENGTH) return
        if (announced.equals("Chrome", ignoreCase = true)) return
        lastWindowTitle = announced
    }

    // ------------------------------------------------------------- foreground tracking

    private fun onForegroundChanged(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        if (packageName == foregroundPackage) return
        flushUsage(now)
        foregroundPackage = packageName
        foregroundSince = now
    }

    /**
     * Charge the time spent in the app that just left the foreground.
     *
     * Every app is charged, not only the ones with a rule, so that turning a budget on
     * tomorrow does not start from a blank slate — and so the usage screen can show where
     * the day actually went.
     */
    private fun flushUsage(now: Long) {
        val previous = foregroundPackage ?: return
        val seconds = ((now - foregroundSince) / 1000L).toInt()
        if (seconds <= 0) return

        val categories = if (previous in SOCIAL_PACKAGES) setOf(Category.SOCIAL) else emptySet()
        engine.recordUsage(seconds, previous, categories)
        foregroundSince = now
    }

    private fun checkForegroundApp(packageName: String) {
        val decision = engine.evaluateApp(packageName)
        if (decision.isBlocked) {
            block(packageName, decision, target = packageName)
        }
    }

    // ------------------------------------------------------------------- the side door

    /**
     * Look for an address on screen and hold it to the same rules as our own browser.
     *
     * Two passes. Known browsers expose their address bar under a stable view id, which is
     * cheap and exact. Everything else — an in-app browser inside a chat app, a link
     * preview — gets a bounded traversal looking for something that reads like a URL.
     *
     * ## Why the event's package is not the package that gets blocked
     *
     * [eventPackage] is who *fired* the event. `rootInActiveWindow` is whatever is *in
     * front*, and those are routinely different apps: the status bar clock ticking, a
     * notification arriving, a media widget repainting all emit `TYPE_WINDOW_CONTENT_CHANGED`
     * from `com.android.systemui` while Chrome is the window on screen.
     *
     * The old code took the event's package and used it for everything — which route to
     * apply, which package to blame, and which package to check against the protected list.
     * So a systemui tick would scan Chrome's page, classify it correctly, write the block to
     * the record, and then hand `"com.android.systemui"` to [block], which refuses to act on
     * protected packages and returned without drawing anything. Detection worked, the log
     * filled up, and nothing ever appeared on screen — and the record even mislabelled the
     * route as "in-app browser", because systemui is not in the known-browser map.
     *
     * The page belongs to the window it was read from. Take the package from there.
     */
    private fun scanForBlockedAddress(eventPackage: String) {
        val root = try {
            rootInActiveWindow
        } catch (error: Exception) {
            null
        } ?: return

        try {
            val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() }
                ?: eventPackage
            // Our own browser judges its pages before it renders them; scanning it here
            // would double-block and attribute the block to Aegis.
            if (packageName == this.packageName) return
            if (packageName != eventPackage) {
                engine.diagnostics.recordWindowMismatch(eventPackage, packageName)
            }

            val url = addressFromKnownBrowser(root, packageName) ?: addressFromAnyTextNode(root)
            if (url.isNullOrBlank()) {
                engine.diagnostics.recordNoAddress(packageName)
                return
            }

            val host = Urls.host(url)
            // While the omnibox has focus its text is whatever is being typed, not an
            // address. Treating "eva elfie" as a hostname is harmless but it poisons the
            // change-detection below, so a value that cannot be a host is discarded.
            if (host.isEmpty() || !host.contains('.') || host.contains(' ')) {
                engine.diagnostics.recordNoAddress(packageName)
                return
            }

            engine.diagnostics.recordAddress(packageName, url)

            val route = if (packageName in KNOWN_BROWSER_VIEW_IDS.keys) {
                RouteContext.REDIRECT
            } else {
                RouteContext.IN_APP_WEBVIEW
            }

            // 1. The hostname. Cheap, so it runs on every scan and catches the obvious
            //    cases before any traversal happens.
            val byHost = engine.evaluateHost(host, route)
            if (byHost.decision.isBlocked) {
                block(
                    packageName = packageName,
                    decision = byHost.decision,
                    target = host,
                    logEntryId = byHost.logEntryId,
                    kind = BlockKind.PAGE,
                )
                return
            }

            // 2. The page itself. Expensive, so it runs when the address changes or after
            //    a pause — pages fill in progressively, and the first tree after
            //    navigation is often still empty.
            val now = SystemClock.elapsedRealtime()
            if (url != lastHarvestedUrl) {
                lastHarvestedUrl = url
                harvestAttempts = 0
                lastHarvestAt = 0L
            }
            if (lastHarvestAt != 0L && now - lastHarvestAt < CONTENT_RESCAN_MILLIS) return

            val text = harvestVisibleText(root)

            // A page fills in after it loads, and Chrome rebuilds its accessibility tree
            // lazily, so the first walk after navigation is routinely almost empty. Do not
            // treat that as the answer — keep looking for a few more passes rather than
            // marking the page judged and waiting out the timer on nothing.
            if (text.length < MIN_TEXT_TO_JUDGE) {
                engine.diagnostics.recordHarvest(text.length, "", 0f, blocked = false)
                harvestAttempts++
                if (harvestAttempts >= MAX_HARVEST_ATTEMPTS) lastHarvestAt = now
                return
            }
            lastHarvestAt = now

            val byContent = engine.evaluateForeignPage(url, lastWindowTitle, text, route)
            val top = byContent.classification.topCategory
            engine.diagnostics.recordHarvest(
                characters = text.length,
                topCategory = top?.label.orEmpty(),
                confidence = top?.let { byContent.classification.score(it) } ?: 0f,
                blocked = byContent.decision.isBlocked,
            )
            // Only a block acts here. A warning would mean throwing an interstitial over
            // somebody else's browser, which is both ugly and easy to get wrong.
            if (byContent.decision.isBlocked) {
                block(
                    packageName = packageName,
                    decision = byContent.decision,
                    target = host,
                    logEntryId = byContent.logEntryId,
                    kind = BlockKind.PAGE,
                )
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    /**
     * Collect the rendered text of the page from the accessibility tree.
     *
     * Chrome and the other Chromium browsers expose web content to accessibility services
     * — it is how a screen reader reads a page — so the same tree that yields the address
     * bar also yields the paragraph text. That makes it possible to judge a foreign page
     * on what it contains rather than on what it is called.
     *
     * Hard bounds on nodes, depth and characters. An unbounded walk of a long article on a
     * mid-range phone is a visible stall, and a filter that makes the browser feel broken
     * gets switched off — which protects nobody. Missing some text is recoverable; missing
     * it on the next scan a second later usually is not, because the bounds are generous
     * enough that the visible screen fits inside them.
     */
    private fun harvestVisibleText(root: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_HARVEST_NODES && builder.length < MAX_HARVEST_CHARS) {
            val (node, depth) = queue.removeFirst()
            visited++

            val piece = node.text ?: node.contentDescription
            if (!TextUtils.isEmpty(piece)) {
                val value = piece.toString().trim()
                // Single tokens are usually chrome — button labels, tab counts. Phrases
                // are what the classifier can actually reason about.
                if (value.length in 2..MAX_HARVEST_CHARS) {
                    builder.append(value).append(' ')
                }
            }

            if (depth < MAX_HARVEST_DEPTH) {
                for (index in 0 until node.childCount) {
                    val child = try {
                        node.getChild(index)
                    } catch (error: Exception) {
                        null
                    } ?: continue
                    queue.add(child to depth + 1)
                }
            }
        }

        return builder.toString().take(MAX_HARVEST_CHARS)
    }

    private fun addressFromKnownBrowser(root: AccessibilityNodeInfo, packageName: String): String? {
        val viewId = KNOWN_BROWSER_VIEW_IDS[packageName] ?: return null
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(viewId)
        } catch (error: Exception) {
            null
        } ?: return null

        for (node in nodes) {
            val text = node.text?.toString()
            @Suppress("DEPRECATION")
            node.recycle()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /**
     * Bounded breadth-first scan for URL-ish text.
     *
     * Bounded hard: a screen with thousands of nodes must not turn a scroll into a stall.
     * Missing an address is recoverable — the DNS filter is the second line — but making
     * the phone feel broken is not.
     */
    private fun addressFromAnyTextNode(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES_SCANNED) {
            val (node, depth) = queue.removeFirst()
            visited++

            val candidate = node.text ?: node.contentDescription
            if (!TextUtils.isEmpty(candidate)) {
                val text = candidate.toString().trim()
                if (looksLikeUrl(text)) return text
            }

            if (depth < MAX_SCAN_DEPTH) {
                for (index in 0 until node.childCount) {
                    val child = try {
                        node.getChild(index)
                    } catch (error: Exception) {
                        null
                    } ?: continue
                    queue.add(child to depth + 1)
                }
            }
        }
        return null
    }

    private fun looksLikeUrl(text: String): Boolean {
        if (text.length > MAX_URL_LENGTH || text.contains(' ')) return false
        if (Urls.isHttpUrl(text)) return true
        // Bare "example.com/page" as shown by browsers that hide the scheme.
        val host = text.substringBefore('/')
        return host.count { it == '.' } in 1..4 &&
            host.length >= 4 &&
            host.all { it.isLetterOrDigit() || it == '.' || it == '-' } &&
            host.substringAfterLast('.').length in 2..24
    }

    // -------------------------------------------------------------------- blocking act

    /**
     * Actually stop it.
     *
     * The screen is drawn as an accessibility overlay rather than launched as an activity.
     * An earlier version called `startActivity` here, which Android 10 and later silently
     * refuse from a service with no visible window — so the page was detected, the block
     * was written to the record, and nothing whatsoever appeared on screen. Nothing in the
     * API reports that failure, which is why it looked like the classifier was broken when
     * the classifier was working perfectly.
     */
    private fun block(
        packageName: String,
        decision: Decision,
        target: String,
        logEntryId: String? = null,
        kind: BlockKind = BlockKind.APP,
    ) {
        // Belt and braces. The engine already refuses to block these, but this is the one
        // code path that can take the screen away from the user, so it checks again here.
        //
        // The list means two different things depending on what is being blocked, and
        // conflating them is what made page blocks disappear. Refusing to block *the
        // launcher as an app* is what stops a focus session from leaving a phone with no
        // home screen. Refusing to block *a web page* because the app showing it is on the
        // list protects nothing — the page is still on screen, and the block screen has a
        // Close button. So a page block only steps aside for windows that are not somebody
        // browsing: our own UI, and the system's.
        val protected = when (kind) {
            BlockKind.APP -> engine.isCritical(packageName)
            BlockKind.PAGE -> packageName in NEVER_OVERLAY
        }
        if (protected) {
            engine.diagnostics.recordEnforcement("skipped — $packageName is protected")
            return
        }

        val now = SystemClock.elapsedRealtime()
        // An app that relaunches itself would otherwise produce a strobe of block screens.
        if (target == lastBlockedTarget && now - lastBlockAt < BLOCK_COOLDOWN_MILLIS) {
            engine.diagnostics.recordEnforcement("skipped — just blocked $target")
            return
        }
        lastBlockedTarget = target
        lastBlockAt = now

        // Leave the offending screen first, then explain. The order matters: the overlay
        // is focusable, so a back action issued after it is showing would go to the
        // overlay rather than to the page underneath.
        performGlobalAction(GLOBAL_ACTION_BACK)

        val graceKey = decision.graceKey
        val grace = if (decision.graceTapAvailable && graceKey != null) {
            BlockOverlay.GraceActions(
                tapsRemaining = engine.graceTapsRemaining(),
                pauseSeconds = 60,
                onRequest = { scope.launch { engine.requestGraceTap(graceKey) } },
                onClaim = { scope.launch { engine.claimGraceTap(graceKey) } },
            )
        } else {
            null
        }

        val activeOverlay = overlay
        val shown = activeOverlay?.show(
            decision = decision,
            target = target,
            onClose = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onMarkedWrong = logEntryId?.let { id ->
                {
                    scope.launch {
                        engine.log.value.entries.firstOrNull { it.id == id }?.let { entry ->
                            engine.correct(entry, com.aegis.core.log.Correction.FALSE_POSITIVE)
                        }
                    }
                }
            },
            grace = grace,
        ) ?: false

        if (shown) {
            engine.diagnostics.recordEnforcement("blocked $target")
            return
        }

        // The overlay could not be attached. Try the activity anyway — on older releases
        // it still works, and a screen that might appear beats one that certainly will not.
        engine.diagnostics.recordEnforcement(
            "overlay refused (" + (activeOverlay?.lastError ?: "no overlay") + ")",
        )
        try {
            startActivity(
                BlockActivity.intentFor(
                    context = this,
                    decision = decision,
                    target = target,
                    blockedPackage = packageName,
                    logEntryId = logEntryId,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        } catch (error: Exception) {
            engine.diagnostics.recordEnforcement("could not show a block screen for $target")
        }
    }

    /**
     * Show the block screen on demand, with a made-up verdict.
     *
     * Exists so the overlay can be tested on its own. "Chrome is not blocking" has meant,
     * at different times, a blind harvest, a refused activity launch and a rejected window
     * token — and from the outside all three look identical. One button that proves the
     * screen can be drawn at all separates "we never detected it" from "we detected it and
     * could not show you".
     */
    fun showTestOverlay(): String {
        val overlay = this.overlay ?: return "The guard is not running."
        val shown = overlay.show(
            decision = Decision(
                outcome = Outcome.BLOCK,
                cause = BlockCause.CATEGORY_WALL,
                explanation = "This is a test. Nothing was actually blocked — if you can " +
                    "read this, Aegis can put a block screen over other apps.",
                evidence = listOf("test", "not a real block"),
            ),
            target = "test",
            onClose = { },
            onMarkedWrong = null,
            grace = null,
        )
        return if (shown) "" else overlay.lastError.ifBlank { "The overlay was refused." }
    }

    companion object {
        /** Set while the service is running, so the settings screen can reach it. */
        @Volatile
        var running: AegisAccessibilityService? = null

        @Volatile
        var isConnected: Boolean = false

        private const val URL_SCAN_INTERVAL_MILLIS = 1_000L
        private const val BLOCK_COOLDOWN_MILLIS = 2_500L
        private const val MAX_NODES_SCANNED = 220
        private const val MAX_SCAN_DEPTH = 14
        private const val MAX_URL_LENGTH = 2_000

        /**
         * Bounds on reading a foreign page. Generous enough that a screenful of article
         * text fits, tight enough that the walk stays well under a frame.
         */
        private const val MAX_HARVEST_NODES = 600
        private const val MAX_HARVEST_DEPTH = 32
        private const val MAX_HARVEST_CHARS = 6_000

        private const val MAX_TITLE_LENGTH = 300

        /** Below this there is not enough to judge, and guessing would misfire. */
        private const val MIN_TEXT_TO_JUDGE = 80

        /** How long before the same address is read again, once it has been judged. */
        private const val CONTENT_RESCAN_MILLIS = 6_000L

        /** Passes to allow on a page that keeps coming back empty before giving it a rest. */
        private const val MAX_HARVEST_ATTEMPTS = 8

        /** Address-bar view ids for the browsers most people actually have installed. */
        private val KNOWN_BROWSER_VIEW_IDS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.chrome.dev" to "com.chrome.dev:id/url_bar",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.focus" to "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        )

        /**
         * The only windows a *page* block will step aside for.
         *
         * Deliberately much shorter than the critical-package list. That list stops a
         * focus session from blocking the launcher or Settings *as apps*, which would
         * leave the phone unusable. It has no business suppressing a block screen over a
         * web page — the page is on screen either way, and the block screen closes with a
         * button. Reusing it there is what made Chrome blocks vanish.
         */
        private val NEVER_OVERLAY = setOf(
            "com.android.systemui",
            "android",
        )

        /** Used to attribute foreground time to the shared "Social" bucket. */
        private val SOCIAL_PACKAGES = setOf(
            "com.facebook.katana",
            "com.facebook.lite",
            "com.instagram.android",
            "com.instagram.lite",
            "com.zhiliaoapp.musically", // TikTok
            "com.ss.android.ugc.trill", // TikTok, some regions
            "com.twitter.android",
            "com.x.android",
            "com.reddit.frontpage",
            "com.snapchat.android",
            "com.pinterest",
            "com.linkedin.android",
            "com.tumblr",
            "com.google.android.youtube",
        )

        /** Whether the user has switched the service on in Android's own settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${AegisAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}

/**
 * What is being stopped, which decides how much the protected-package list is allowed to say.
 *
 * [APP] — the app itself is off limits under a rule or a focus session. Here the protected
 * list is absolute: blocking the launcher or Settings would leave the phone with nowhere to
 * go and no way to reach the switch that turns Aegis off.
 *
 * [PAGE] — a web page inside some app is off limits. The app is not the problem and is not
 * being taken away; a screen is being put in front of one page, with a Close button on it.
 */
enum class BlockKind { APP, PAGE }
