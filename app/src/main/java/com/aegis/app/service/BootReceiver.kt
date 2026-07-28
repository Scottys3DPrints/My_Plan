package com.aegis.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.aegis.app.engine.AegisEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bring the guard back after a reboot or an app update.
 *
 * Rebooting must not be a way to switch Aegis off for a while. Two things happen here:
 * the filter is restarted if the user has already granted VPN permission, and any
 * cooling-off change that came due while the device was off is applied — so the queue
 * does not silently stall until the app is next opened.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val engine = AegisEngine.get(context)
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                engine.applyDueChanges()

                // prepare() returns null once the user has granted VPN permission, and it
                // is remembered across reboots — so this needs no interaction.
                if (VpnService.prepare(context) == null) {
                    AegisVpnService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
