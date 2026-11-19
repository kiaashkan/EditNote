package com.kiaashkan.editnote.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kiaashkan.editnote.EditorActivity
import com.kiaashkan.editnote.R
import com.kiaashkan.editnote.ReminderReceiver

/**
 * Per-note reminders (item 110, KiA's order 2026-11-19): one
 * one-shot reminder per saved file, keyed by the file's URI
 * string — the same identity RecentFiles and bookmarks use.
 *
 * Storage is one preference per reminder (key "rem:" + the URI),
 * value "triggerAt<US>name<US>snippet" with backslash escaping, so
 * entries can never corrupt each other. Scheduling is plain
 * AlarmManager (framework only): exact when the system allows
 * it, doze-safe inexact otherwise. The notification text is a
 * snapshot taken when the reminder is set, so the reminder still
 * reads correctly even if the file is edited afterwards.
 */
class Reminders(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Reminder(
        val uri: String,
        val name: String,
        val snippet: String,
        val triggerAt: Long
    )

    fun get(uri: String): Reminder? {
        val raw = prefs.getString(key(uri), null) ?: return null
        val parts = splitEscaped(raw)
        if (parts.size < 3) return null
        val trigger = parts[0].toLongOrNull() ?: return null
        return Reminder(uri, parts[1], parts[2], trigger)
    }

    fun all(): List<Reminder> =
        prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { get(it.substring(KEY_PREFIX.length)) }

    fun put(reminder: Reminder) {
        val value = listOf(
            reminder.triggerAt.toString(),
            reminder.name,
            reminder.snippet
        ).joinToString(SEP) { escape(it) }
        prefs.edit().putString(key(reminder.uri), value).apply()
        schedule(appContext, reminder)
    }

    fun remove(uri: String) {
        prefs.edit().remove(key(uri)).apply()
        cancel(appContext, uri)
    }

    /** Drop a fired reminder WITHOUT touching alarms (the alarm
     *  already went off; there is nothing to cancel). */
    fun removeSilently(uri: String) {
        prefs.edit().remove(key(uri)).apply()
    }

    companion object {
        private const val PREFS = "editnote_reminders"
        private const val KEY_PREFIX = "rem:"
        private const val SEP = "\u0001"
        const val CHANNEL_ID = "editnote_reminders"

        private fun key(uri: String) = KEY_PREFIX + uri

        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace(SEP, "\\s")

        private fun splitEscaped(raw: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (raw[i + 1]) {
                        's' -> cur.append(SEP)
                        else -> cur.append(raw[i + 1])
                    }
                    i += 2
                } else if (c == SEP[0]) {
                    out.add(cur.toString())
                    cur.clear()
                    i++
                } else {
                    cur.append(c)
                    i++
                }
            }
            out.add(cur.toString())
            return out
        }

        private fun pendingIntent(context: Context, uri: String): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra(ReminderReceiver.EXTRA_URI, uri)
            return PendingIntent.getBroadcast(
                context, uri.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun schedule(context: Context, reminder: Reminder) {
            val am = context.getSystemService(AlarmManager::class.java)
                ?: return
            val pi = pendingIntent(context, reminder.uri)
            am.cancel(pi)
            // Item 113: reminders ride the ALARM-CLOCK mechanism
            // (the same class the phone's own Clock app uses).
            // KiA's phone test showed setExactAndAllowWhileIdle
            // silently never firing on MIUI — Xiaomi gates exact
            // alarms behind its own special access, and the
            // inexact fallback gets deferred by the battery
            // saver. Alarm-clock alarms need no exact-alarm
            // permission, are exempt from Doze and battery
            // savers, and MIUI respects them like a wake-up
            // alarm. The show intent opens the note, so the
            // status-bar alarm entry leads somewhere useful.
            val showIntent = PendingIntent.getActivity(
                context, reminder.uri.hashCode(),
                EditorActivity.externalIntent(
                    context, Uri.parse(reminder.uri), reminder.name
                ).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(reminder.triggerAt, showIntent),
                pi
            )
        }

        fun cancel(context: Context, uri: String) {
            val am = context.getSystemService(AlarmManager::class.java)
                ?: return
            am.cancel(pendingIntent(context, uri))
        }

        /** The reminders notification channel (created lazily —
         *  safe to call from anywhere, any number of times).
         *  Item 113 (KiA): the notification must RING — the
         *  sound is set explicitly, and a channel created
         *  without one (the first test build) is deleted and
         *  recreated, since channel settings are immutable. */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
                ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing != null && existing.sound != null) return
            if (existing != null) nm.deleteNotificationChannel(CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(
                    android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_NOTIFICATION
                    ),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(
                            android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION
                        )
                        .build()
                )
            }
            nm.createNotificationChannel(channel)
        }

        /** Re-arm every stored reminder after a reboot; reminders
         *  whose time passed while the phone was off are dropped. */
        fun rescheduleAll(context: Context) {
            val store = Reminders(context)
            val now = System.currentTimeMillis()
            store.all().forEach { r ->
                if (r.triggerAt > now) schedule(context, r)
                else store.removeSilently(r.uri)
            }
        }

        fun docKey(uri: Uri): String = uri.toString()
    }
}
