package com.aegis.app.service

/**
 * The apps whose whole business model is the next item.
 *
 * Used for two things: attributing foreground time to the shared "Social" bucket, and
 * pre-selecting which apps the endless-scroll rule watches, so turning that rule on is one
 * tap rather than a hunt through a list of two hundred packages.
 *
 * A list of package names is exactly the kind of thing this app argues against everywhere
 * else — it is the URL blocklist problem in another costume, and it goes stale. It earns
 * its place here because it is only ever a *default*: nothing is enforced from this list.
 * Every entry is a suggestion the user can remove, and any app not on it can be added.
 * Being wrong about a name costs a tap, not a missed block.
 */
object SocialPackages {

    val KNOWN: Set<String> = setOf(
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

    operator fun contains(packageName: String): Boolean = packageName in KNOWN
}
