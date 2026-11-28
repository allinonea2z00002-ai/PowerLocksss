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
 * still works.
 *
 * Build 138 (user 2026-11-28 18:20): the icon NEVER returns — the
 * alias is disabled permanently, lock or no lock. The dialer code is
 * now one of the only ways into the app (the others are the KeepAlive
 * notification tap and the secret-shape window during a lock).
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
