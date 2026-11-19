package com.kiaashkan.editnote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kiaashkan.editnote.data.Reminders

/**
 * Re-arms note reminders after a reboot (item 110): alarms do
 * not survive a restart, so every stored reminder is scheduled
 * again; ones whose time passed while the phone was off are
 * dropped by [Reminders.rescheduleAll].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Reminders.rescheduleAll(context)
    }
}
