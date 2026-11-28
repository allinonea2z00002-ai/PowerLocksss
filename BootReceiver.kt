package com.powerlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Runs after reboot, app update and time changes: restores the protection state and schedules. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            L.load(context)
            Scheduler.onAlarm(context)
            MorningReminder.reschedule(context) // reboot / time change: re-arm the morning reminder
            CapsuleAlarms.rescheduleAll(context) // reboot / time change: re-arm time-capsule alarms
            Watchdog.enqueue(context) // safety net: restart the service every 15 min if it dies
            // the location lock has no session, so it must restart the service too
            if (Store.isActive(context) || Store.isLocProtecting(context)) Store.kickService(context)
            // build 138: after a reboot / update the icon must stay hidden
            // (the alias is disabled in the manifest, and a component
            // state set by setComponentEnabledSetting survives an update,
            // so force it off again here)
            Store.syncLauncherIcon(context)
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver failed", e)
        }
    }
}
