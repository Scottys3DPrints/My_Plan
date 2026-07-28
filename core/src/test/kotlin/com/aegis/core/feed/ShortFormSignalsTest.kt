package com.aegis.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortFormSignalsTest {

    @Test
    fun `recognises the Instagram Reels viewer`() {
        val surface = ShortFormSignals.matchScreen(
            packageName = "com.instagram.android",
            viewIds = sequenceOf(
                "com.instagram.android:id/root_view",
                "com.instagram.android:id/clips_viewer_view_pager",
            ),
        )
        assertEquals(ShortFormSurface.INSTAGRAM_REELS, surface)
    }

    @Test
    fun `recognises the YouTube Shorts player`() {
        val surface = ShortFormSignals.matchScreen(
            packageName = "com.google.android.youtube",
            viewIds = sequenceOf(
                "com.google.android.youtube:id/watch_while_root",
                "com.google.android.youtube:id/reel_recycler",
            ),
        )
        assertEquals(ShortFormSurface.YOUTUBE_SHORTS, surface)
    }

    @Test
    fun `the ordinary Instagram home feed is not Reels`() {
        // The one that matters. Getting this wrong does not mean a missed block, it means
        // the entire app is unusable and the feature gets switched off within a minute.
        val surface = ShortFormSignals.matchScreen(
            packageName = "com.instagram.android",
            viewIds = sequenceOf(
                "com.instagram.android:id/feed_recycler_view",
                "com.instagram.android:id/tab_bar",
                "com.instagram.android:id/action_bar_title",
            ),
        )
        assertNull(surface)
    }

    @Test
    fun `an app with no named surface is never matched`() {
        val surface = ShortFormSignals.matchScreen(
            packageName = "com.example.notes",
            viewIds = sequenceOf("com.example.notes:id/clips_viewer_view_pager"),
        )
        assertNull(surface, "a view id stem must not match outside the app it belongs to")
    }

    @Test
    fun `a tap on the Reels tab is the way in`() {
        assertEquals(
            ShortFormSurface.INSTAGRAM_REELS,
            ShortFormSignals.matchTap(
                packageName = "com.instagram.android",
                viewId = "com.instagram.android:id/clips_tab",
                description = null,
            ),
        )
    }

    @Test
    fun `a tap labelled Shorts is the way in even with no useful id`() {
        assertEquals(
            ShortFormSurface.YOUTUBE_SHORTS,
            ShortFormSignals.matchTap(
                packageName = "com.google.android.youtube",
                viewId = "com.google.android.youtube:id/image_view",
                description = "Shorts",
            ),
        )
    }

    @Test
    fun `a shelf that merely mentions reels is not the way in`() {
        // "Reels and short videos you might like" is a row inside an ordinary feed.
        // Matching it as a substring would block the home screen.
        assertNull(
            ShortFormSignals.matchTap(
                packageName = "com.facebook.katana",
                viewId = "com.facebook.katana:id/feed_story",
                description = "Reels and short videos you might like",
            ),
        )
    }

    @Test
    fun `matching is case-insensitive on the label`() {
        assertEquals(
            ShortFormSurface.FACEBOOK_REELS,
            ShortFormSignals.matchTap(
                packageName = "com.facebook.katana",
                viewId = null,
                description = "reels",
            ),
        )
    }

    @Test
    fun `the rule only watches apps whose surfaces are selected`() {
        val rule = ShortFormRule(
            enabled = true,
            surfaces = setOf(ShortFormSurface.YOUTUBE_SHORTS),
        )
        assertEquals(true, rule.watches("com.google.android.youtube"))
        assertEquals(false, rule.watches("com.instagram.android"))
        assertEquals(false, rule.blocks(ShortFormSurface.INSTAGRAM_REELS))
        assertEquals(true, rule.blocks(ShortFormSurface.YOUTUBE_SHORTS))
    }

    @Test
    fun `a disabled rule watches nothing`() {
        val rule = ShortFormRule(enabled = false, surfaces = ShortFormSurface.entries.toSet())
        assertEquals(false, rule.watches("com.instagram.android"))
        assertEquals(false, rule.blocks(ShortFormSurface.INSTAGRAM_REELS))
    }
}
