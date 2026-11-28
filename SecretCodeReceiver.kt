package com.powerlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Build 132 (user 2026-11-28 13:26): dial *#*#1#*#* in the phone
 * dialer to open PowerLock 2, even while its launcher icon is hidden
 * during a lock. The dialer broadcasts
 * android.provider.Telephony.SECRET_CODE with the data URI
 * android_secret_code://1; this receiver launches MainActivity via
 * an explicit intent. MainActivity itself stays enabled — only the
 * .LauncherAlias is disabled to hide the icon — so an explicit launch
 * still works. If the dialer does not broadcast SECRET_CODE, or the
 * code is forgotten, the icon returns on its own when the lock ends.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(i)
        } catch (_: Exception) { }
    }
}
