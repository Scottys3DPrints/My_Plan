package com.aegis.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * The apps Aegis will never block, whatever the rules say.
 *
 * This exists because of a specific, serious failure. A focus session blocks every app
 * not on its allow list and — by design — cannot be ended early. Applied literally, that
 * includes the launcher and the Settings app, which leaves a phone with no reachable home
 * screen and no way to reach the switch that would turn Aegis off, for as long as the
 * session runs. The same holds for a scheduled profile that blocks by package.
 *
 * A self-control tool is allowed to be difficult. It is not allowed to make the device
 * unusable, and it must never be able to prevent its own removal — an app that could do
 * that is indistinguishable from malware, and the user's ability to walk away is the
 * thing that makes the rest of the friction legitimate rather than coercive.
 *
 * Resolved from the platform rather than hard-coded, because the launcher, dialer and
 * settings package differ across manufacturers and a hard-coded list would silently fail
 * on exactly the devices it was not tested on.
 */
object CriticalPackages {

    /** Fallbacks for the few things that have no resolvable intent. */
    private val ALWAYS = setOf(
        "com.android.systemui",
        "android",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.emergency",
    )

    fun resolve(context: Context): Set<String> {
        val manager = context.packageManager
        val packages = mutableSetOf<String>()

        // Aegis itself: blocking our own settings screen would be a trap with no exit.
        packages += context.packageName
        packages += ALWAYS

        // Every installed launcher, not just the default — switching launcher must not be
        // a way to end up with nothing on screen.
        packages += resolveAll(manager, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

        packages += resolveAll(manager, Intent(Settings.ACTION_SETTINGS))
        packages += resolveAll(manager, Intent(Intent.ACTION_DIAL))
        packages += resolveAll(manager, Intent(Intent.ACTION_CALL, Uri.parse("tel:112")))
        packages += resolveAll(manager, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        packages += resolveAll(
            manager,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
        )

        return packages.filter { it.isNotBlank() }.toSet()
    }

    private fun resolveAll(manager: PackageManager, intent: Intent): Set<String> = try {
        manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    } catch (error: Exception) {
        emptySet()
    }
}
