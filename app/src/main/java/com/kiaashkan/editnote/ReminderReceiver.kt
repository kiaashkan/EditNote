package com.kiaashkan.editnote

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.kiaashkan.editnote.data.Reminders

/**
 * Fires a note reminder (item 110): posts the notification with
 * the snapshot text taken when the reminder was set, and drops
 * the reminder (reminders are one-shot). Tapping the
 * notification opens the note through the same external-intent
 * contract the recents list uses.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(EXTRA_URI) ?: return
        val store = Reminders(context)
        val reminder = store.get(uri) ?: return
        store.removeSilently(uri)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        Reminders.ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return
        // Item 113: build the full notification, but never let a
        // failure in the rich parts (open intent, big-text style)
        // eat the reminder entirely — fall back to a minimal one.
        try {
            val open = EditorActivity.externalIntent(
                context, Uri.parse(reminder.uri), reminder.name
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            val contentIntent = PendingIntent.getActivity(
                context, uri.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, Reminders.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(reminder.name)
                .setContentText(reminder.snippet)
                .setStyle(Notification.BigTextStyle().bigText(reminder.snippet))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            nm.notify(uri.hashCode(), notification)
        } catch (e: Exception) {
            try {
                nm.notify(
                    uri.hashCode(),
                    Notification.Builder(context, Reminders.CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_bell)
                        .setContentTitle(reminder.name)
                        .setContentText(reminder.snippet)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (ignored: Exception) {
                // Nothing left to do — the reminder is spent.
            }
        }
    }

    companion object {
        const val EXTRA_URI = "reminder_uri"
    }
}
