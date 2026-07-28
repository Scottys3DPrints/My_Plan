package com.aegis.core.feed

import kotlinx.serialization.Serializable

/**
 * The short-form video surfaces, named one by one.
 *
 * Not a category and not a classifier verdict. Reels and Shorts are specific screens
 * inside apps somebody has decided to keep, and the request they answer is not "judge
 * this" — it is "I never want to be on that screen at all". There is nothing to weigh up,
 * so nothing here weighs anything up.
 */
@Serializable
enum class ShortFormSurface(
    val id: String,
    val label: String,
    val packageName: String,
) {
    INSTAGRAM_REELS("instagram_reels", "Instagram Reels", "com.instagram.android"),
    FACEBOOK_REELS("facebook_reels", "Facebook Reels", "com.facebook.katana"),
    YOUTUBE_SHORTS("youtube_shorts", "YouTube Shorts", "com.google.android.youtube"),
    ;

    companion object {
        fun fromId(id: String): ShortFormSurface? = entries.firstOrNull { it.id == id }

        fun forPackage(packageName: String): List<ShortFormSurface> =
            entries.filter { it.packageName == packageName }
    }
}

/**
 * "That screen, never."
 *
 * Separate from [com.aegis.core.rules.FeedRule] because it is a different kind of
 * statement. The scroll rule interrupts a run that has gone on too long — it assumes the
 * app is fine and only the duration is the problem. This one says a particular screen is
 * not fine at any duration, so it acts on the first frame rather than after a count, and
 * there is nothing to tune.
 */
@Serializable
data class ShortFormRule(
    val enabled: Boolean = false,
    val surfaces: Set<ShortFormSurface> = emptySet(),
) {
    fun blocks(surface: ShortFormSurface): Boolean = enabled && surface in surfaces

    /** Whether it is worth looking at this app's screen at all. Keeps the hot path cheap. */
    fun watches(packageName: String): Boolean =
        enabled && surfaces.any { it.packageName == packageName }

    val strictness: Int get() = if (!enabled) 0 else 100_000 + surfaces.size
}

/**
 * Recognising the surface from what the screen exposes.
 *
 * Kept here, as string matching over values the caller collected, rather than in the
 * accessibility service — so the part that decides can be tested, and the part that walks
 * a node tree stays a dumb collector.
 *
 * ## Why substrings of view ids, and not exact ids
 *
 * Exact resource ids are the tempting choice and they are a trap: they change between app
 * versions, and when one changes the feature fails *silently* — the screen simply stops
 * being recognised and nothing says so. The stems below (`clips_viewer`, `reel_player`)
 * have survived years of releases because they name the internal feature, not the layout.
 *
 * ## Why a label alone is never enough
 *
 * "Reels" appears in Instagram's navigation bar on every screen in the app, so matching
 * the word would block the entire app. A label only counts when the thing carrying it was
 * *tapped* — see [matchTap] — which is a statement of intent rather than a description of
 * what happens to be on screen.
 */
object ShortFormSignals {

    /** View-id stems that only ever appear on the surface itself. */
    private val VIEW_ID_STEMS: Map<ShortFormSurface, List<String>> = mapOf(
        ShortFormSurface.INSTAGRAM_REELS to listOf(
            "clips_viewer",
            "clips_video",
            "clips_swipe_refresh",
            "reel_viewer",
        ),
        ShortFormSurface.FACEBOOK_REELS to listOf(
            "reels_video",
            "reels_viewer",
            "short_form_video",
        ),
        ShortFormSurface.YOUTUBE_SHORTS to listOf(
            "reel_recycler",
            "reel_player",
            "reel_watch",
            "shorts_video",
        ),
    )

    /** Labels that identify the way *in*. Only ever consulted for a tap. */
    private val TAP_LABELS: Map<ShortFormSurface, List<String>> = mapOf(
        ShortFormSurface.INSTAGRAM_REELS to listOf("reels", "clips_tab"),
        ShortFormSurface.FACEBOOK_REELS to listOf("reels"),
        ShortFormSurface.YOUTUBE_SHORTS to listOf("shorts", "shorts_tab"),
    )

    /**
     * Is this screen one of the named surfaces?
     *
     * @param viewIds every `viewIdResourceName` the caller found on screen, in any order.
     */
    fun matchScreen(packageName: String, viewIds: Sequence<String>): ShortFormSurface? {
        val candidates = ShortFormSurface.forPackage(packageName)
        if (candidates.isEmpty()) return null

        for (viewId in viewIds) {
            val id = viewId.substringAfterLast('/').lowercase()
            if (id.isEmpty()) continue
            for (surface in candidates) {
                if (VIEW_ID_STEMS[surface].orEmpty().any { it in id }) return surface
            }
        }
        return null
    }

    /**
     * Was this tap the way in?
     *
     * Acting on the tap is the difference between blocking the screen and blocking the
     * *decision to open it* — nothing renders, so there is nothing to catch a glimpse of.
     * Both paths exist because a tap is not the only way in: a deep link, a notification,
     * or a swipe within a pager all arrive without one.
     */
    fun matchTap(
        packageName: String,
        viewId: String?,
        description: String?,
    ): ShortFormSurface? {
        val candidates = ShortFormSurface.forPackage(packageName)
        if (candidates.isEmpty()) return null

        val id = viewId?.substringAfterLast('/')?.lowercase().orEmpty()
        val label = description?.trim()?.lowercase().orEmpty()

        for (surface in candidates) {
            val labels = TAP_LABELS[surface].orEmpty()
            if (id.isNotEmpty() && labels.any { it in id }) return surface
            // Exact on the label, not a substring: "Reels" is the tab, but "Reels and
            // short videos you might like" is a shelf inside an ordinary feed, and
            // blocking on that would take the whole home screen with it.
            if (label.isNotEmpty() && labels.any { it == label }) return surface
        }
        return null
    }
}
