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
import com.aegis.app.engine.AegisEngine
import com.aegis.core.model.Category
import com.aegis.core.model.RouteContext
import com.aegis.core.rules.Decision
import com.aegis.core.util.Urls

/**
 * App blocking, time budgets, and the side-door guard (§3.3, §3.4, §3.5).
 *
 * Three jobs, all of which need the same one signal — what is on screen right now:
 *
 * 1. **Which app is in front.** Drives app blocks and is what makes a time budget tick.
 * 2. **How long it has been in front.** Accrued in seconds and handed to the budget
 *    tracker, which is why leaving and re-entering an app does not reset anything.
 * 3. **What address another browser is showing.** Reading the address bar is the only way
 *    to catch "you deleted Facebook, but Messenger's in-app browser still opens it" at
 *    the moment it happens rather than after the fact.
 *
 * ## On the cost of this permission
 *
 * An accessibility service can read the screen. That is an enormous amount of trust, and
 * pretending otherwise would be dishonest. Three things bound it: the work happens
 * entirely in this process, nothing read here is stored beyond the hostname that appears
 * in the transparency log, and everything except the URL check is throttled to a glance
 * at the foreground package rather than a traversal of the screen.
 */
class AegisAccessibilityService : AccessibilityService() {

    private lateinit var engine: AegisEngine

    private var foregroundPackage: String? = null
    private var foregroundSince: Long = 0L

    private var lastUrlScanAt: Long = 0L
    private var lastBlockedTarget: String? = null
    private var lastBlockAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = AegisEngine.get(this)
        isConnected = true
    }

    override fun onDestroy() {
        flushUsage(SystemClock.elapsedRealtime())
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
                onForegroundChanged(packageName)
                checkForegroundApp(packageName)
                scanForBlockedAddress(packageName)
            }

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
     */
    private fun scanForBlockedAddress(packageName: String) {
        val root = try {
            rootInActiveWindow
        } catch (error: Exception) {
            null
        } ?: return

        try {
            val url = addressFromKnownBrowser(root, packageName) ?: addressFromAnyTextNode(root)
            if (url.isNullOrBlank()) return

            val host = Urls.host(url)
            if (host.isEmpty()) return

            val route = if (packageName in KNOWN_BROWSER_VIEW_IDS.keys) {
                RouteContext.REDIRECT
            } else {
                RouteContext.IN_APP_WEBVIEW
            }

            val verdict = engine.evaluateHost(host, route)
            if (verdict.decision.isBlocked) {
                block(packageName, verdict.decision, target = host, logEntryId = verdict.logEntryId)
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
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

    private fun block(
        packageName: String,
        decision: Decision,
        target: String,
        logEntryId: String? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        // An app that relaunches itself would otherwise produce a strobe of block screens.
        if (target == lastBlockedTarget && now - lastBlockAt < BLOCK_COOLDOWN_MILLIS) return
        lastBlockedTarget = target
        lastBlockAt = now

        // Leave the offending screen first, then explain. The order matters: showing the
        // explanation over a still-loading page means the page is still there behind it.
        performGlobalAction(GLOBAL_ACTION_BACK)

        startActivity(
            BlockActivity.intentFor(
                context = this,
                decision = decision,
                target = target,
                blockedPackage = packageName,
                logEntryId = logEntryId,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    companion object {
        @Volatile
        var isConnected: Boolean = false

        private const val URL_SCAN_INTERVAL_MILLIS = 1_000L
        private const val BLOCK_COOLDOWN_MILLIS = 2_500L
        private const val MAX_NODES_SCANNED = 220
        private const val MAX_SCAN_DEPTH = 14
        private const val MAX_URL_LENGTH = 2_000

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
