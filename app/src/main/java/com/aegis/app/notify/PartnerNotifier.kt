package com.aegis.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aegis.app.AegisApplication
import com.aegis.app.R
import com.aegis.core.lockdown.PendingChange
import com.aegis.core.rules.AccountabilityPartner
import com.aegis.core.util.LocalTime

/**
 * The accountability partner (§3.7), without a backend.
 *
 * §4 lists this feature as depending on "backend + notifications", and that is the one
 * place this implementation deliberately departs from the concept. A server that receives
 * a message every time someone tries to weaken their porn filter is a database of the most
 * sensitive thing this app touches, and building it would contradict §2.2 far more than it
 * would serve §3.7.
 *
 * So: Aegis composes the message and hands it to whatever mail or messaging app the user
 * already has. The friction is the same — you cannot quietly loosen a rule without
 * telling someone — and there is no third party holding the record.
 *
 * The trade-off, stated plainly rather than glossed: this is *prompted*, not enforced. A
 * determined user can dismiss the notification without sending it. Enforced delivery
 * requires the server this refuses to build; a future version could add one as an opt-in
 * for people who want it, which is the right way round.
 */
object PartnerNotifier {

    private const val NOTIFICATION_ID = 8123

    fun notifyWeakening(context: Context, partner: AccountabilityPartner, change: PendingChange) {
        if (!partner.enabled || !partner.notifyOnWeakening) return

        val body = buildString {
            append("I've asked Aegis to weaken a rule:\n\n")
            append(change.summary)
            append("\n\nIt takes effect in ")
            append(LocalTime.formatDuration(((change.effectiveAtMillis - change.requestedAtMillis) / 60_000L).toInt()))
            append(", unless I cancel it.")
        }

        val share = Intent(Intent.ACTION_SENDTO).apply {
            data = if (partner.contact.contains("@")) {
                Uri.parse("mailto:${partner.contact}")
            } else {
                Uri.parse("smsto:${partner.contact}")
            }
            putExtra(Intent.EXTRA_SUBJECT, "Aegis: a rule is being weakened")
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra("sms_body", body)
        }

        val pendingShare = PendingIntent.getActivity(
            context,
            change.id.hashCode(),
            Intent.createChooser(share, "Tell ${partner.name}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(context, AegisApplication.ALERTS_CHANNEL_ID)
            .setContentTitle("Tell ${partner.name}?")
            .setContentText(change.summary)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingShare)
            .setAutoCancel(true)
            .build()

        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID + change.id.hashCode() % 1000, notification)
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS not granted. Nothing to do but carry on; the queued
            // change is still visible on the Home screen.
        }
    }
}
