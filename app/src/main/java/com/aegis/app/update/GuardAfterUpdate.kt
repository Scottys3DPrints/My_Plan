package com.aegis.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.aegis.app.BuildConfig
import com.aegis.app.data.AegisStore
import com.aegis.app.service.AegisAccessibilityService
import kotlinx.coroutines.flow.first

/**
 * Did the update turn the guard off?
 *
 * Android applies "restricted settings" to apps installed outside the Play Store, and an
 * accessibility service can be switched off as a side effect of an install. It gives no
 * notice when this happens, in either direction — which makes it the worst class of
 * failure this app can have. Everything still looks right: the app opens, the rules are
 * all there, the Record has entries in it. They are last week's entries. Nothing has been
 * filtered since the update and there was never a moment where anything said so.
 *
 * There is no API to ask "am I currently restricted?", and no way for an app to waive the
 * restriction — that is the entire point of it, and an app that could waive it would be
 * exactly the app the restriction exists to stop. So this does the only honest thing
 * available: remembers the state before, compares it after, and says so out loud.
 *
 * Two settings screens are involved and people reliably get sent to the wrong one, so
 * both are offered by name rather than described in prose.
 */
object GuardAfterUpdate {

    data class Verdict(
        /** True when the guard was on before this version and is off now. */
        val lostToUpdate: Boolean,
        val previousVersionCode: Long,
    )

    /**
     * Compare, then record. Call once when the app is opened.
     *
     * Recording afterwards is deliberate: if it recorded first, the very act of checking
     * would erase the evidence.
     */
    suspend fun check(context: Context, store: AegisStore): Verdict {
        val previous = store.lastRunVersionCode.first()
        val wasOn = store.guardOnAtLastRun.first()
        val current = BuildConfig.VERSION_CODE.toLong()
        val nowOn = AegisAccessibilityService.isEnabled(context)

        val updated = previous != 0L && previous != current
        val lost = updated && wasOn && !nowOn

        if (updated && wasOn) {
            // Recorded rather than merely displayed. Android's behaviour here varies by
            // version and by manufacturer, and one observation from this phone settles
            // what no amount of reasoning about it can.
            store.recordUpdateGuardOutcome(
                if (nowOn) "survived the update to $current"
                else "switched off by the update to $current",
            )
        }

        store.recordRun(current, nowOn)
        return Verdict(lostToUpdate = lost, previousVersionCode = previous)
    }

    /** Where the switch is. */
    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Where "Allow restricted settings" is — the app's own detail page, behind the ⋮ menu.
     *
     * Worth a button of its own because it is not where anybody looks. The switch you want
     * is in Accessibility settings, greyed out, with nothing on that screen explaining
     * that the reason lives in a menu on a different screen entirely.
     */
    fun openAppInfo(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
