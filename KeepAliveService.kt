package com.powerlock.app

import android.graphics.drawable.Icon
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Bitmap
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Foreground service: keeps the process alive and shows the countdown notification. */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishTask = Runnable { onFinished() }

    /** Writes a fresh timestamp every 60s while alive; the accessibility service revives us if it goes stale. */
    private val heartbeatTask = Runnable { onHeartbeat() }

    private fun onHeartbeat() {
        try {
            Store.lock(this).edit().putLong("kas_heartbeat", System.currentTimeMillis()).apply()
            Store.syncLauncherIcon(this) // build 138: keep the icon hidden, every 60s
            // backup arrival check for a location-unlock session (cheap, last known fix only)
            PlaceWatcher.checkUnlockLastKnown(this)
            LockWidget.refresh(this) // keep the widget countdown fresh while the app is closed
        } catch (e: Exception) {
            Log.e(TAG, "heartbeat failed", e)
        }
        handler.postDelayed(heartbeatTask, 60_000)
    }

    private val smallIcon: Icon by lazy { buildStatusIcon() }
    private val largeIcon: Bitmap? by lazy { buildLogo() }

    /** Status-bar icon: a shield with a lock cut-out (single colour, the system tints it). */
    private fun buildStatusIcon(): Icon {
        val s = 96
        val u = s / 24f
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.style = Paint.Style.FILL
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        path.moveTo(12f * u, 2f * u)
        path.lineTo(20f * u, 5f * u)
        path.lineTo(20f * u, 11.5f * u)
        path.cubicTo(20f * u, 16.5f * u, 16.5f * u, 20f * u, 12f * u, 22f * u)
        path.cubicTo(7.5f * u, 20f * u, 4f * u, 16.5f * u, 4f * u, 11.5f * u)
        path.lineTo(4f * u, 5f * u)
        path.close()
        // lock body (cut out)
        path.addRect(9f * u, 11f * u, 15f * u, 16.2f * u, Path.Direction.CW)
        // lock shackle (cut out)
        path.moveTo(9.8f * u, 11f * u)
        path.lineTo(9.8f * u, 9.4f * u)
        path.arcTo(RectF(9.8f * u, 7.2f * u, 14.2f * u, 11.6f * u), 180f, 180f)
        path.lineTo(14.2f * u, 11f * u)
        path.lineTo(13f * u, 11f * u)
        path.lineTo(13f * u, 9.4f * u)
        path.arcTo(RectF(11f * u, 8.4f * u, 13f * u, 10.4f * u), 0f, -180f)
        path.lineTo(11f * u, 11f * u)
        path.close()
        c.drawPath(path, p)
        return Icon.createWithBitmap(bmp)
    }

    /**
     * Large icon: the app logo (dark tile, glowing green shield, white padlock), drawn here directly.
     * It does not depend on the launcher icon or on any icon cache of the phone.
     */
    private fun buildLogo(): Bitmap? {
        return try {
            val size = dp(64)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val dark = Color.parseColor("#0B1F16")
            val green = Color.parseColor("#1FCB85")
            val milk = Color.parseColor("#F6F3EA")

            val tile = Paint(Paint.ANTI_ALIAS_FLAG)
            tile.color = dark
            c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.24f, size * 0.24f, tile)

            // soft glow behind the shield (radial gradient, same as the launcher icon)
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            glowPaint.shader = RadialGradient(
                size / 2f, size / 2f, size * 0.48f,
                intArrayOf(Color.argb(140, 0x1F, 0xCB, 0x85), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(size / 2f, size / 2f, size * 0.48f, glowPaint)

            // same shapes as the launcher icon (108 x 108 viewport), zoomed on the shield
            c.save()
            c.scale(size / 72f, size / 72f)
            c.translate(-18f, -18f)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            fill.style = Paint.Style.FILL
            fill.color = green
            val shield = Path()
            shield.moveTo(54f, 28f)
            shield.lineTo(74f, 36f)
            shield.lineTo(74f, 53f)
            shield.cubicTo(74f, 67f, 64f, 74.5f, 54f, 80f)
            shield.cubicTo(44f, 74.5f, 34f, 67f, 34f, 53f)
            shield.lineTo(34f, 36f)
            shield.close()
            c.drawPath(shield, fill)

            fill.color = milk
            c.drawRoundRect(RectF(44f, 53f, 64f, 67.5f), 2.5f, 2.5f, fill)

            val line = Paint(Paint.ANTI_ALIAS_FLAG)
            line.style = Paint.Style.STROKE
            line.strokeWidth = 2.6f
            line.strokeCap = Paint.Cap.ROUND
            line.color = milk
            val shackle = Path()
            shackle.moveTo(48.5f, 53f)
            shackle.lineTo(48.5f, 49.5f)
            shackle.arcTo(RectF(48.5f, 44f, 59.5f, 55f), 180f, 180f)
            shackle.lineTo(59.5f, 53f)
            c.drawPath(shackle, line)

            fill.color = green
            c.drawCircle(54f, 58.5f, 1.8f, fill)
            c.restore()
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "logo failed", e)
            null
        }
    }

    /** MIUI / HyperOS hint: use the PowerLock logo as the notification's app icon (ignored by other phones). */
    private fun addLogoExtras(b: Notification.Builder) {
        try {
            val bmp = largeIcon ?: return
            val ex = Bundle()
            ex.putParcelable("miui.appIcon", Icon.createWithBitmap(bmp))
            b.addExtras(ex)
        } catch (e: Exception) {
            Log.e(TAG, "logo extras failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            L.load(this)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("powerlock", "PowerLock 2", NotificationManager.IMPORTANCE_LOW)
            )
            val done = NotificationChannel("powerlock_done", "Session finished", NotificationManager.IMPORTANCE_HIGH)
            done.enableVibration(true)
            nm.createNotificationChannel(done)
            nm.createNotificationChannel(
                NotificationChannel("powerlock_done_quiet", "Session finished (quiet)", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel("powerlock_alert", "Boundary alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
            refresh()
            handler.removeCallbacks(heartbeatTask)
            handler.post(heartbeatTask) // fresh heartbeat every 60s so the accessibility service can tell we are alive
            FocusSound.sync(this) // resume the focus sound after a restart if a lock is running
            // location lock no longer uses sessions: end any old location session, the flag takes over
            if (Store.isLocationSession(this)) Store.stopSession(this, force = true)
            PlaceWatcher.sync(this) // resume location watching after a restart/reboot
            PlaceWatcher.checkNow(this) // already at a saved place? lock right away
            PlaceWatcher.checkUnlockNow(this) // location-unlock session? check arrival right away
            Watchdog.enqueue(this) // safety net: restart the service every 15 min if it dies
        } catch (e: Exception) {
            Log.e(TAG, "KeepAliveService start failed", e)
        }
        return START_STICKY
    }

    /**
     * Build 137: during a lock the notification opens the secret-shape
     * window (draw the shape -> window with the app, no uninstall option);
     * when no lock runs it opens MainActivity directly, so there is no
     * extra step. Different request codes so the two PendingIntents do
     * not overwrite each other when the lock state changes.
     */
    private fun openApp(): PendingIntent {
        val protecting = Store.isActive(this) || Store.isLocProtecting(this)
        val target = if (protecting) SecretWindowActivity::class.java else MainActivity::class.java
        val req = if (protecting) 1 else 0
        return PendingIntent.getActivity(this, req, Intent(this, target), PendingIntent.FLAG_IMMUTABLE)
    }

    private fun refresh() {
        handler.removeCallbacks(finishTask)
        Store.finalizeIfExpired(this)
        SiteBlockService.sync(this) // keep the site-block VPN in step with the lock
        val p = Store.lock(this)
        // Build 113: `active` and every timer/display must use the REAL
        // remaining time, not the wall clock — changing the phone's clock
        // made the notification say "Protection is off" and the finish
        // task fire early while the lock was still really on.
        val active = Store.isActive(this)
        val remaining = Store.remainingMs(this)
        val realEnd = Store.realNow(this) + remaining

        val b = Notification.Builder(this, "powerlock")
            .setSmallIcon(smallIcon)
            .setLargeIcon(largeIcon)
            .setColor(Color.parseColor("#16C784"))
            .setOngoing(true)
            .setContentIntent(openApp())
        addLogoExtras(b)

        if (active) {
            val mode = Store.unlockMode(this)
            val target = Store.unlockTarget(this)
            b.setContentTitle(L.t("Protection active", "প্রটেকশন সক্রিয়"))
            when {
                mode == UNLOCK_LOC && target != null -> {
                    // no countdown: arrival ends it (the 15-day end is only a safety cap)
                    b.setContentText(L.t("Opens at ${target.name}", "${target.name} পৌঁছালে খুলবে"))
                }
                mode == UNLOCK_EITHER && target != null -> {
                    b.setContentText(L.t("Ends at ", "শেষ হবে ") + fmtClock(realEnd) + L.t(" or at ${target.name}", " অথবা ${target.name} পৌঁছালে"))
                        .setUsesChronometer(true)
                        .setChronometerCountDown(true)
                        .setShowWhen(true)
                        .setWhen(realEnd)
                }
                else -> {
                    b.setContentText(L.t("Ends at ", "শেষ হবে ") + fmtClock(realEnd))
                        .setUsesChronometer(true)
                        .setChronometerCountDown(true)
                        .setShowWhen(true)
                        .setWhen(realEnd)
                }
            }
            handler.postDelayed(finishTask, remaining)
        } else {
            b.setContentTitle(L.t("PowerLock 2 is ready", "PowerLock 2 প্রস্তুত"))
                .setContentText(L.t("Protection is off", "প্রটেকশন বন্ধ আছে"))
        }
        startForeground(1, b.build())
        LockWidget.refresh(this) // the notification and the widget show the same state
    }

    private fun onFinished() {
        try {
            L.load(this)
            if (Store.isActive(this)) { // clock changed, not finished yet
                refresh()
                return
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = if (Store.soundVib(this)) "powerlock_done" else "powerlock_done_quiet"
            val nb = Notification.Builder(this, channel)
                .setSmallIcon(smallIcon)
                .setLargeIcon(largeIcon)
                .setColor(Color.parseColor("#16C784"))
                .setContentTitle(L.t("Protection finished", "প্রটেকশন শেষ"))
                .setContentText(L.t("You can use the power menu now.", "এখন পাওয়ার মেনু ব্যবহার করতে পারবেন।"))
                .setAutoCancel(true)
                .setContentIntent(openApp())
            addLogoExtras(nb)
            nm.notify(2, nb.build())
            refresh()
        } catch (e: Exception) {
            Log.e(TAG, "onFinished failed", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            // swiped away from Recents: restart so the lock keeps working
            try {
                startForegroundService(Intent(this, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "onTaskRemoved start failed", e)
            }
            scheduleRestart(500)
        } catch (e: Exception) {
            Log.e(TAG, "onTaskRemoved restart failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Restart via an alarm-clock alarm: unlike setExactAndAllowWhileIdle it needs NO
     * "exact alarms" permission, is always exact, and the system lets the alarm start a
     * foreground service from the background when it fires. This is the restart that
     * survives a Recents swipe even when the user never granted exact alarms.
     * Falls back to the exact/inexact alarm when setAlarmClock throws.
     */
    private fun scheduleRestart(delayMs: Long) {
        try {
            val intent = Intent(this, KeepAliveService::class.java)
            val pi = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + delayMs
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp()), pi)
                return
            } catch (e: Exception) {
                Log.e(TAG, "setAlarmClock failed, falling back", e)
            }
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "scheduleRestart failed", e)
        }
    }

    companion object {
        /** Any component (Watchdog, accessibility service, receiver) can ask for a restart. */
        fun restartSoon(ctx: Context, delayMs: Long) {
            try {
                val intent = Intent(ctx, KeepAliveService::class.java)
                val pi = PendingIntent.getService(
                    ctx, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val show = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val at = System.currentTimeMillis() + delayMs
                try {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                } catch (e: Exception) {
                    Log.e(TAG, "restartSoon alarm-clock failed", e)
                    if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "restartSoon failed", e)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishTask)
        handler.removeCallbacks(heartbeatTask)
        try {
            // system stopped the service (e.g. notification dismissed): come back
            scheduleRestart(2000)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy restart failed", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
