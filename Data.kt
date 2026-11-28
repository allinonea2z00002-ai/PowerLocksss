package com.powerlock.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import java.util.Locale

const val APP_VERSION = "3.0"
const val TAG = "PowerLock"

/** Longest allowed session: 15 days, in minutes. */
const val MAX_MIN = 15 * 24 * 60

/** Language helper: every visible text is written as (English, Bangla). */
object L {
    @Volatile
    var bn = true

    fun load(ctx: Context) {
        bn = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "bn") == "bn"
    }

    fun t(en: String, bnText: String): String = if (bn) bnText else en

    fun num(s: String): String {
        if (!bn) return s
        val sb = StringBuilder()
        for (c in s) {
            if (c in '0'..'9') sb.append('০' + (c - '0')) else sb.append(c)
        }
        return sb.toString()
    }

    fun dayShort(i: Int): String {
        val en = listOf("M", "T", "W", "T", "F", "S", "S")
        val b = listOf("সো", "মং", "বু", "বৃ", "শু", "শ", "র")
        return if (bn) b[i] else en[i]
    }

    fun dayLong(i: Int): String {
        val en = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val b = listOf("সোম", "মঙ্গল", "বুধ", "বৃহ", "শুক্র", "শনি", "রবি")
        return if (bn) b[i] else en[i]
    }

    fun month(i: Int): String {
        val en = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val b = listOf("জানু", "ফেব্রু", "মার্চ", "এপ্রি", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টে", "অক্টো", "নভে", "ডিসে")
        return if (bn) b[i] else en[i]
    }
}

enum class PStatus { READY, ACTIVE, SCHEDULED, COMPLETED }

/**
 * Lock-end quotes: short motivational lines, one per day on rotation.
 * Shown ONLY in the session-summary dialog when a lock ends — nowhere else.
 */
object Quotes {
    private val ALL = listOf(
        Pair(
            "You didn't just lock your phone — you unlocked your time.",
            "তুমি শুধু ফোন লক করোনি — নিজের সময়কে মুক্ত করেছ।"
        ),
        Pair(
            "Small wins, repeated daily, become a new life.",
            "ছোট ছোট জয়, প্রতিদিন পুনরাবৃত্তি হলে, নতুন জীবন হয়ে ওঠে।"
        ),
        Pair(
            "Focus is saying no to a hundred good distractions.",
            "ফোকাস মানে শত ভালো বিভ্রান্তিকে না বলা।"
        ),
        Pair(
            "The phone waited. Your goals didn't have to.",
            "ফোন অপেক্ষা করেছে। তোমার লক্ষ্যকে অপেক্ষা করতে হয়নি।"
        ),
        Pair(
            "Discipline is choosing what you want most over what you want now.",
            "শৃঙ্খলা মানে এখন যা চাও তার চেয়ে যা সবচেয়ে বেশি চাও সেটা বেছে নেওয়া।"
        ),
        Pair(
            "Every minute of focus is a brick in the life you're building.",
            "ফোকাসের প্রতিটি মিনিট তোমার গড়া জীবনের একটা ইট।"
        ),
        Pair(
            "You proved you can. Tomorrow, prove it again.",
            "তুমি প্রমাণ করেছ পারো। কাল আবার প্রমাণ করো।"
        ),
        Pair(
            "Calm mind, steady hands, clear goals.",
            "শান্ত মন, স্থির হাত, স্পষ্ট লক্ষ্য।"
        ),
        Pair(
            "Freedom is not doing everything — it's choosing what matters.",
            "স্বাধীনতা সবকিছু করা নয় — যা গুরুত্বপূর্ণ তা বেছে নেওয়া।"
        ),
        Pair(
            "One lock at a time, one day at a time.",
            "একবারে একটা লক, একদিনে একদিন।"
        )
    )

    /** Today's quote, in the current language. Same all day, a new one tomorrow. */
    fun today(): String {
        val day = System.currentTimeMillis() / 86_400_000L
        val q = ALL[(day % ALL.size).toInt()]
        return L.t(q.first, q.second)
    }

    /** Today's quote in both languages, so the voice player can speak the one
     * its installed voice can actually pronounce. */
    fun todayPair(): Pair<String, String> {
        val day = System.currentTimeMillis() / 86_400_000L
        return ALL[(day % ALL.size).toInt()]
    }
}

data class Schedule(val id: Long, val days: Int, val startMin: Int, val endMin: Int, val enabled: Boolean)

data class Session(val start: Long, val end: Long, val dur: Long, val ok: Boolean, val src: String)

/** What the last completed lock achieved: how long it ran and how many blocks fired. */
data class SessionSummary(val durMs: Long, val blocks: Int, val endMs: Long)

/** Focus report: current streak in days, total focus minutes, completed locks,
 * and focus minutes for the last 7 days (oldest first). */
data class FocusStats(val streak: Int, val totalMin: Long, val completed: Int, val last7: List<Int>)

/** Unlock condition for a running session: time only, location only, or either one. */
const val UNLOCK_TIME = "time"
const val UNLOCK_LOC = "loc"
const val UNLOCK_EITHER = "either"

/** Destination that ends a location-unlock session when the device arrives within [radius] metres. */
data class UnlockTarget(val lat: Double, val lng: Double, val radius: Int, val name: String)

/** One milestone badge: earned once, shown with the date it was earned. */
data class BadgeDef(val id: String, val icon: Ic, val titleEn: String, val titleBn: String, val descEn: String, val descBn: String)

// ---------- formatting helpers ----------

fun dayIdx(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7

fun fmtMin(m: Int): String {
    val h = m / 60
    val mm = m % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    val ap = if (h < 12) "AM" else "PM"
    return L.num(String.format(Locale.US, "%d:%02d %s", h12, mm, ap))
}

fun fmtClock(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return fmtMin(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
}

fun fmtHms(ms: Long): String {
    val s = if (ms < 0) 0L else ms / 1000
    return L.num(String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60))
}

fun fmtDur(ms: Long): String {
    if (ms <= 0L) return L.num(L.t("0m", "0 মিনিট"))
    val totalMin = ms / 60000
    if (totalMin == 0L) return L.t("<1m", "১ মিনিটের কম")
    val days = totalMin / 1440
    val h = (totalMin % 1440) / 60
    val m = totalMin % 60
    val en = StringBuilder()
    val bnb = StringBuilder()
    if (days > 0) {
        en.append("${days}d ")
        bnb.append("$days দিন ")
    }
    if (h > 0) {
        en.append("${h}h ")
        bnb.append("$h ঘণ্টা ")
    }
    if (m > 0) {
        en.append("${m}m ")
        bnb.append("$m মিনিট ")
    }
    return L.num(L.t(en.toString().trim(), bnb.toString().trim()))
}

fun fmtDate(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return L.dayLong(dayIdx(c)) + ", " + L.num(c.get(Calendar.DAY_OF_MONTH).toString()) + " " + L.month(c.get(Calendar.MONTH))
}

fun statusOf(ctx: Context, active: Boolean, now: Long): PStatus {
    if (active) return PStatus.ACTIVE
    val last = Store.lock(ctx).getLong("lastDone", 0)
    if (last > 0 && now - last < 30 * 60_000L) return PStatus.COMPLETED
    if (Scheduler.nextStart(ctx, now) != null) return PStatus.SCHEDULED
    return PStatus.READY
}

// ---------- permissions ----------

object Perm {
    fun accessibilityOn(ctx: Context): Boolean {
        val e = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return e.contains(ctx.packageName + "/")
    }

    fun notificationsOn(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
    }

    fun batteryOk(ctx: Context): Boolean {
        return try {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            true
        }
    }

    fun exactAlarmOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    fun locationOn(ctx: Context): Boolean {
        return ctx.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED
    }

    fun backgroundLocationOn(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return ctx.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED
    }
}

// ---------- Do Not Disturb during a lock ----------

/**
 * Silences notifications (and calls) while a lock runs, and restores the
 * previous interruption mode when the lock ends. Alarms always ring
 * (INTERRUPTION_FILTER_ALARMS). If the user revokes the DND permission,
 * the feature turns itself off instead of crashing.
 */
object Dnd {
    fun isGranted(ctx: Context): Boolean {
        return try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        } catch (e: Exception) {
            false
        }
    }

    fun isOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean("dnd_on", false)

    /** Called when a lock starts: silence notifications/calls (alarms still ring). */
    fun apply(ctx: Context) {
        try {
            if (!isOn(ctx)) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                Store.settings(ctx).edit().putBoolean("dnd_on", false).apply()
                return
            }
            val p = Store.lock(ctx)
            if (!p.contains("dnd_prev")) {
                p.edit().putInt("dnd_prev", nm.currentInterruptionFilter).apply()
            }
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        } catch (e: Exception) {
            Log.e(TAG, "DND apply failed", e)
        }
    }

    /** Called when a lock ends: restore the previous interruption mode. */
    fun restore(ctx: Context) {
        try {
            val p = Store.lock(ctx)
            if (!p.contains("dnd_prev")) return
            val prev = p.getInt("dnd_prev", NotificationManager.INTERRUPTION_FILTER_ALL)
            p.edit().remove("dnd_prev").apply()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(prev)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DND restore failed", e)
        }
    }
}

// ---------- persistent storage ----------

/**
 * Network time (SNTP, build 111): the real time from the internet, so a
 * changed phone clock cannot end a lock early after a reboot. One tiny UDP
 * request to time.google.com — no library, no account, ~1 KB, and it runs
 * only when needed (lock start, service start after a boot), never
 * continuously, so the phone does not slow down. Runs on a background
 * thread; a failed check simply leaves the offset unset and the phone's
 * clock is used — a failed check never blocks the lock.
 */
object NtpTime {
    private const val NTP_HOST = "time.google.com"
    private const val NTP_PORT = 123
    private const val TIMEOUT_MS = 3000
    private const val NTP_EPOCH_OFFSET = 2208988800L // seconds 1900 -> 1970

    @Volatile private var syncing = false

    fun sync(ctx: Context) {
        if (syncing) return
        syncing = true
        Thread {
            try {
                val offset = queryOffset()
                if (offset != null) {
                    Store.lock(ctx).edit()
                        .putLong("ntp_offset", offset)
                        .putLong("ntp_elapsed", SystemClock.elapsedRealtime())
                        .putLong("ntp_at", System.currentTimeMillis())
                        .apply()
                }
            } catch (_: Exception) {
            } finally {
                syncing = false
            }
        }.apply { isDaemon = true }.start()
    }

    /** NTP time minus wall clock, in ms — null when the query fails. */
    private fun queryOffset(): Long? {
        val buffer = ByteArray(48)
        buffer[0] = 0x1B // SNTP v3, client mode
        val address = InetAddress.getByName(NTP_HOST)
        val socket = DatagramSocket()
        socket.soTimeout = TIMEOUT_MS
        try {
            val sentAt = System.currentTimeMillis()
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val receivedAt = System.currentTimeMillis()
            // transmit timestamp: bytes 40-47 (seconds + fraction since 1900)
            val seconds = ((buffer[40].toLong() and 0xFF) shl 24) or
                ((buffer[41].toLong() and 0xFF) shl 16) or
                ((buffer[42].toLong() and 0xFF) shl 8) or
                (buffer[43].toLong() and 0xFF)
            val fraction = ((buffer[44].toLong() and 0xFF) shl 24) or
                ((buffer[45].toLong() and 0xFF) shl 16) or
                ((buffer[46].toLong() and 0xFF) shl 8) or
                (buffer[47].toLong() and 0xFF)
            val ntpMillis = (seconds - NTP_EPOCH_OFFSET) * 1000 +
                (fraction * 1000L / 0x100000000L)
            return ntpMillis - (sentAt + receivedAt) / 2
        } finally {
            socket.close()
        }
    }
}

object Store {
    fun lock(ctx: Context): SharedPreferences = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)
    fun settings(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun soundVib(ctx: Context): Boolean = settings(ctx).getBoolean("sound", true)

    /** The NTP offset (network time minus wall clock) when it was measured
     * on THIS boot, else null. An offset from a previous boot is untrusted:
     * the clock may have been changed since. */
    fun ntpOffset(ctx: Context): Long? {
        if (!settings(ctx).getBoolean("ntp_on", true)) return null
        val p = lock(ctx)
        val atElapsed = p.getLong("ntp_elapsed", 0)
        if (atElapsed <= 0) return null
        // elapsed time went backwards since the check = a reboot happened
        if (SystemClock.elapsedRealtime() < atElapsed) return null
        return p.getLong("ntp_offset", 0)
    }

    /** The best estimate of the real wall-clock time: the phone's clock
     * corrected by the network-time offset, when one exists for this boot. */
    fun realNow(ctx: Context): Long {
        val off = ntpOffset(ctx)
        return if (off != null) System.currentTimeMillis() + off else System.currentTimeMillis()
    }

    /** True when the session's fate rests on the wall clock after a reboot
     * and no network-time offset exists yet for this boot — the lock must
     * wait for the network time instead of ending on a possibly-tampered
     * clock. Gives up after 120 s of uptime without a sync (no internet),
     * so the lock cannot run forever. */
    fun needsNtpCheck(ctx: Context): Boolean {
        if (!settings(ctx).getBoolean("ntp_on", true)) return false
        val p = lock(ctx)
        if (!p.getBoolean("active", false)) return false
        val startElapsed = p.getLong("startElapsed", 0)
        if (startElapsed <= 0) return false
        // no reboot: the elapsed clock decides, NTP is irrelevant
        if (SystemClock.elapsedRealtime() >= startElapsed) return false
        // fresh offset already? nothing to wait for
        if (ntpOffset(ctx) != null) return false
        // give up after 120 s of uptime without a sync (no internet)
        if (SystemClock.elapsedRealtime() >= 120_000) return false
        return true
    }

    /** Real remaining time of the running session in ms — from the elapsed
     * clock when possible (build 110+), else the NTP-corrected wall clock.
     * Every countdown display and timer must use this, NOT (end - wall
     * clock): changing the phone's clock would otherwise show 00:00:00
     * while the lock is still really on. */
    fun remainingMs(ctx: Context): Long {
        val p = lock(ctx)
        if (!p.getBoolean("active", false)) return 0
        val endElapsed = p.getLong("endElapsed", 0)
        val startElapsed = p.getLong("startElapsed", 0)
        if (endElapsed > 0 && startElapsed > 0) {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (nowElapsed >= startElapsed) return (endElapsed - nowElapsed).coerceAtLeast(0)
        }
        return (p.getLong("end", 0) - realNow(ctx)).coerceAtLeast(0)
    }

    /**
     * True while the running session's time has not run out — measured on the
     * monotonic elapsed clock (SystemClock.elapsedRealtime), NOT the phone's
     * date/time. Changing the date/time forward can no longer end a lock
     * early: the elapsed clock never jumps when the clock is changed, and it
     * works offline (no internet needed). Falls back to the wall clock for
     * sessions started by an older build, or after a reboot (the elapsed
     * clock resets at boot, so a backwards jump means reboot). The wall
     * clock is corrected by the network time when one exists (build 111).
     */
    fun sessionTimeLeft(ctx: Context): Boolean {
        val p = lock(ctx)
        if (!p.getBoolean("active", false)) return false
        val endElapsed = p.getLong("endElapsed", 0)
        val startElapsed = p.getLong("startElapsed", 0)
        if (endElapsed > 0 && startElapsed > 0) {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (nowElapsed >= startElapsed) return nowElapsed < endElapsed
            // reboot since the session started: fall back to the wall clock
        }
        return realNow(ctx) < p.getLong("end", 0)
    }

    fun isActive(ctx: Context): Boolean {
        finalizeIfExpired(ctx)
        return lock(ctx).getBoolean("active", false) && sessionTimeLeft(ctx)
    }

    /** Records the session once when its time is over. Safe to call many times. */
    @Synchronized
    fun finalizeIfExpired(ctx: Context) {
        val p = lock(ctx)
        if (!p.getBoolean("active", false)) return
        val end = p.getLong("end", 0)
        // Build 111: after a reboot the wall clock is the fallback — but if
        // the clock was changed, it lies. Wait for the network time instead
        // of ending the lock on a possibly-tampered clock.
        if (needsNtpCheck(ctx)) {
            NtpTime.sync(ctx)
            return
        }
        if (sessionTimeLeft(ctx)) return
        val src = p.getString("src", "manual") ?: "manual"
        addSession(ctx, Session(p.getLong("start", end), end, p.getLong("total", 0), true, src))
        val e = p.edit().putBoolean("active", false).putLong("lastDone", end)
        clearUnlock(e)
        e.apply()
        Dnd.restore(ctx) // lock over: notifications come back
        FocusSound.stop() // lock over: the focus sound stops
        SiteBlockService.stopIfRunning(ctx) // lock over: the site-block VPN stops
        LockWidget.refresh(ctx)
        PlaceWatcher.sync(ctx) // no session left: stop watching unless the location lock needs it
        syncLauncherIcon(ctx) // build 132: lock over — show the launcher icon again (unless the location lock still protects)
    }

    /** The running session's unlock destination, or null for a time-only session. */
    fun unlockTarget(ctx: Context): UnlockTarget? {
        val p = lock(ctx)
        if (p.getString("u_mode", UNLOCK_TIME) == UNLOCK_TIME) return null
        if (!p.contains("u_lat")) return null
        return UnlockTarget(
            Double.fromBits(p.getLong("u_lat", 0)),
            Double.fromBits(p.getLong("u_lng", 0)),
            p.getInt("u_radius", 200),
            p.getString("u_name", "") ?: ""
        )
    }

    /** Saves the picked unlock destination (for the next location lock).
     * Stored in settings, separate from the running session's target. */
    fun saveUnlockDest(ctx: Context, t: UnlockTarget?) {
        val e = settings(ctx).edit()
        if (t == null) {
            e.remove("unlock_dest").apply()
            return
        }
        val o = JSONObject()
        o.put("la", t.lat)
        o.put("lo", t.lng)
        o.put("r", t.radius)
        o.put("n", t.name)
        e.putString("unlock_dest", o.toString()).apply()
    }

    /** The running session's unlock mode: "time", "loc" or "either". */
    fun unlockMode(ctx: Context): String = lock(ctx).getString("u_mode", UNLOCK_TIME) ?: UNLOCK_TIME

    /**
     * Ends the running session because the device arrived at its unlock destination.
     * Works like a time expiry (a completed session) and bypasses strict mode, because
     * arriving was the agreed unlock condition. Returns true when a session was ended.
     */
    @Synchronized
    fun finalizeByLocation(ctx: Context): Boolean {
        val p = lock(ctx)
        if (!p.getBoolean("active", false)) return false
        if (unlockTarget(ctx) == null) return false
        val now = System.currentTimeMillis()
        val start = p.getLong("start", now)
        val src = p.getString("src", "manual") ?: "manual"
        addSession(ctx, Session(start, now, now - start, true, src))
        val e = p.edit().putBoolean("active", false).putLong("lastDone", now).putLong("end", now)
        clearUnlock(e)
        e.apply()
        Dnd.restore(ctx) // lock over: notifications come back
        FocusSound.stop() // lock over: the focus sound stops
        SiteBlockService.stopIfRunning(ctx) // lock over: the site-block VPN stops
        LockWidget.refresh(ctx)
        PlaceWatcher.sync(ctx) // no session left: stop watching unless the location lock needs it
        kickService(ctx) // refresh the notification: the lock is off now
        return true
    }

    private fun clearUnlock(e: SharedPreferences.Editor) {
        e.remove("u_mode").remove("u_lat").remove("u_lng").remove("u_radius").remove("u_name")
    }

    fun startSession(ctx: Context, durationMs: Long, src: String): Boolean =
        startSession(ctx, durationMs, src, null, UNLOCK_TIME)

    /**
     * Starts a session. [unlock] + [mode] make it a location session: "loc" ends only on
     * arrival (durationMs is then just the 15-day safety cap), "either" ends on arrival
     * or when the time runs out, whichever comes first.
     */
    fun startSession(ctx: Context, durationMs: Long, src: String, unlock: UnlockTarget?, mode: String): Boolean {
        if (isActive(ctx)) return false
        val now = System.currentTimeMillis()
        val st = settings(ctx)
        val e = lock(ctx).edit()
            .putBoolean("s_strict", st.getBoolean("strict", false))
            .putBoolean("s_guard", st.getBoolean("guard", true))
            .putBoolean("active", true)
            .putLong("start", now)
            .putLong("end", now + durationMs)
            .putLong("startElapsed", SystemClock.elapsedRealtime())
            .putLong("endElapsed", SystemClock.elapsedRealtime() + durationMs)
            .putLong("total", durationMs)
            .putString("src", src)
            .putLong("lastDone", 0)
        if (unlock != null && mode != UNLOCK_TIME) {
            e.putString("u_mode", mode)
                .putLong("u_lat", unlock.lat.toRawBits())
                .putLong("u_lng", unlock.lng.toRawBits())
                .putInt("u_radius", unlock.radius)
                .putString("u_name", unlock.name)
        } else {
            clearUnlock(e)
        }
        e.apply()
        NtpTime.sync(ctx) // fresh network-time offset for this lock (build 111)
        Dnd.apply(ctx) // lock started: silence notifications if the user wants it
        FocusSound.sync(ctx) // lock started: play the chosen focus sound, if any
        SiteBlockService.sync(ctx) // lock started: start the site-block VPN, if enabled
        LockWidget.refresh(ctx)
        kickService(ctx)
        syncLauncherIcon(ctx) // build 132: lock on — hide the launcher icon
        return true
    }

    /** True while a running session was started in strict mode (no early stop). */
    fun isStrict(ctx: Context): Boolean = isActive(ctx) && lock(ctx).getBoolean("s_strict", false)

    /** True when the running session was started by the location lock (only those may be auto-stopped). */
    fun isLocationSession(ctx: Context): Boolean =
        isActive(ctx) && (lock(ctx).getString("src", "manual") ?: "manual") == "location"

    /**
     * Location protection: the power menu is blocked while at a saved place, without any Home
     * session. The single location switch controls it — the main lock does not need to be on.
     */
    fun setLocProtecting(ctx: Context, on: Boolean) {
        lock(ctx).edit().putBoolean("loc_protecting", on).apply()
        syncLauncherIcon(ctx)
    }

    fun isLocProtecting(ctx: Context): Boolean = lock(ctx).getBoolean("loc_protecting", false)

    /**
     * Build 132 (user 2026-11-28 13:26): hide the launcher icon while a
     * lock protects, show it again when no lock is left — so there is no
     * icon to long-press (and no long-press uninstall menu) during a
     * lock. The icon is an <activity-alias> (.LauncherAlias) targeting
     * MainActivity; disabling the ALIAS hides the icon but the activity
     * stays enabled, so the dialer secret code (*#*#1234#*#*) can still
     * launch it via an explicit intent. Reads the raw lock state
     * directly (NOT isActive, which would recurse through
     * finalizeIfExpired). Only touches the PackageManager when the
     * desired state differs from the current one, so it is cheap to
     * call from the lock lifecycle (start / expire / stop / location
     * change / boot).
     */
    fun syncLauncherIcon(ctx: Context) {
        try {
            val pm = ctx.packageManager
            val alias = ComponentName(ctx, "com.powerlock.app.LauncherAlias")
            val p = lock(ctx)
            val sessionActive = p.getBoolean("active", false) && sessionTimeLeft(ctx)
            val protecting = sessionActive || p.getBoolean("loc_protecting", false)
            val want = if (protecting) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                       else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val cur = try { pm.getComponentEnabledSetting(alias) } catch (_: Exception) { PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }
            // DEFAULT follows the manifest (enabled), so only DISABLED counts as hidden.
            val curHidden = cur == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val wantHidden = want == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (curHidden != wantHidden) {
                pm.setComponentEnabledSetting(alias, want, PackageManager.DONT_KILL_APP)
            }
        } catch (_: Exception) { }
    }

    /**
     * [force] is only for the location lock: leaving/arriving ends the session even in strict
     * mode, because the lock follows presence. The manual Stop button never passes force.
     */
    fun stopSession(ctx: Context, force: Boolean = false) {
        if (!isActive(ctx)) return
        if (!force && lock(ctx).getBoolean("s_strict", false)) return
        val p = lock(ctx)
        val now = System.currentTimeMillis()
        val start = p.getLong("start", now)
        val src = p.getString("src", "manual") ?: "manual"
        addSession(ctx, Session(start, now, now - start, false, src))
        val w = Scheduler.activeWindowEnd(ctx, now)
        val e = p.edit().putBoolean("active", false).putLong("end", now)
        if (w != null) e.putLong("suppress", w)
        clearUnlock(e)
        e.apply()
        Dnd.restore(ctx) // lock over: notifications come back
        FocusSound.stop() // lock over: the focus sound stops
        SiteBlockService.stopIfRunning(ctx) // lock over: the site-block VPN stops
        LockWidget.refresh(ctx)
        PlaceWatcher.sync(ctx) // no session left: stop watching unless the location lock needs it
        kickService(ctx)
        syncLauncherIcon(ctx) // build 132: lock over — show the launcher icon again (unless the location lock still protects)
    }

    fun kickService(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "kickService failed", e)
        }
    }

    // ----- route (30 days) + entry/exit history (30 days) -----

    fun locData(ctx: Context): SharedPreferences = ctx.getSharedPreferences("locdata", Context.MODE_PRIVATE)

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Day key ("yyyyMMdd") for N days ago (0 = today). */
    fun dayKey(daysAgo: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Day key ("yyyyMMdd") for an arbitrary timestamp. */
    fun dayKeyFor(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Stored day keys, newest first (max 30). */
    fun routeDays(ctx: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(locData(ctx).getString("route_days", "[]"))
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {
            Log.e(TAG, "route days read failed", e)
        }
        return out
    }

    /** Points recorded on one day ("yyyyMMdd"). */
    fun routeForDay(ctx: Context, day: String): List<RoutePoint> {
        val out = ArrayList<RoutePoint>()
        try {
            // one-time fallback: the first version kept only today under "route"
            val raw = locData(ctx).getString("route_$day", null)
                ?: if (day == todayKey()) locData(ctx).getString("route", null) else null
            if (raw == null) return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(RoutePoint(o.getDouble("la"), o.getDouble("lo"), o.getLong("t")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "route read failed", e)
        }
        return out
    }

    /** Points recorded today. */
    fun todayRoute(ctx: Context): List<RoutePoint> = routeForDay(ctx, todayKey())

    /** Distance of one day's route, in metres. */
    fun distanceForDay(ctx: Context, day: String): Float {
        var total = 0f
        val pts = routeForDay(ctx, day)
        for (i in 1 until pts.size) {
            val res = FloatArray(1)
            android.location.Location.distanceBetween(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng, res)
            total += res[0]
        }
        return total
    }

    /** Total distance walked/driven today, in metres. */
    fun todayDistanceM(ctx: Context): Float = distanceForDay(ctx, todayKey())

    /** Total distance of all stored days (up to 30), in metres. */
    fun monthDistanceM(ctx: Context): Float {
        var total = 0f
        for (day in routeDays(ctx)) total += distanceForDay(ctx, day)
        return total
    }

    /** Record a point (at most one per 20 m / 60 s, max 2000 points a day, kept 30 days). */
    fun addRoutePoint(ctx: Context, lat: Double, lng: Double) {
        try {
            val p = locData(ctx)
            val day = todayKey()
            val pts = routeForDay(ctx, day).toMutableList()
            val now = System.currentTimeMillis()
            val last = pts.lastOrNull()
            if (last != null) {
                val res = FloatArray(1)
                android.location.Location.distanceBetween(last.lat, last.lng, lat, lng, res)
                if (res[0] < 20f && now - last.t < 60_000L) return
            }
            pts.add(RoutePoint(lat, lng, now))
            while (pts.size > 2000) pts.removeAt(0)
            val arr = JSONArray()
            for (pt in pts) {
                val o = JSONObject()
                o.put("la", pt.lat)
                o.put("lo", pt.lng)
                o.put("t", pt.t)
                arr.put(o)
            }
            val e = p.edit().putString("route_$day", arr.toString())
            // keep the day index (newest first, max 30) and drop older days
            val days = ArrayList<String>()
            days.add(day)
            for (d in routeDays(ctx)) if (d != day) days.add(d)
            val kept = days.take(30)
            for (d in days.drop(30)) e.remove("route_$d")
            val daysArr = JSONArray()
            for (d in kept) daysArr.put(d)
            e.putString("route_days", daysArr.toString())
            e.apply()
        } catch (e: Exception) {
            Log.e(TAG, "route save failed", e)
        }
    }

    /** Entry/exit events, newest first (max 500, kept 30 days). outTime 0 means still inside. */
    fun placeEvents(ctx: Context): List<PlaceEvent> {
        val out = ArrayList<PlaceEvent>()
        try {
            val arr = JSONArray(locData(ctx).getString("events", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(PlaceEvent(o.getLong("id"), o.getString("n"), o.getLong("in"), o.optLong("out", 0)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "events read failed", e)
        }
        return out
    }

    private fun saveEvents(ctx: Context, list: List<PlaceEvent>) {
        // drop closed events older than 30 days; an open visit is always kept
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val kept = list.filter { it.outTime == 0L || it.inTime > cutoff }.take(500)
        val arr = JSONArray()
        for (e in kept) {
            val o = JSONObject()
            o.put("id", e.placeId)
            o.put("n", e.name)
            o.put("in", e.inTime)
            o.put("out", e.outTime)
            arr.put(o)
        }
        locData(ctx).edit().putString("events", arr.toString()).apply()
    }

    /** A place was entered: open a new event (only if none is open for it). */
    fun recordEnter(ctx: Context, pl: Place) {
        val list = placeEvents(ctx).toMutableList()
        if (list.any { it.placeId == pl.id && it.outTime == 0L }) return
        list.add(0, PlaceEvent(pl.id, pl.name, System.currentTimeMillis(), 0))
        saveEvents(ctx, list)
    }

    /** A place was left: close the open event for it. */
    fun recordExit(ctx: Context, pl: Place) {
        val list = placeEvents(ctx).toMutableList()
        val idx = list.indexOfFirst { it.placeId == pl.id && it.outTime == 0L }
        if (idx < 0) return
        list[idx] = list[idx].copy(outTime = System.currentTimeMillis())
        saveEvents(ctx, list)
    }

    fun clearEvents(ctx: Context) {
        locData(ctx).edit().remove("events").apply()
    }

    // ----- sessions -----

    fun sessions(ctx: Context): List<Session> {
        val out = ArrayList<Session>()
        try {
            val arr = JSONArray(lock(ctx).getString("sessions", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Session(o.getLong("s"), o.getLong("e"), o.getLong("d"), o.getBoolean("ok"), o.optString("src", "manual")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sessions read failed", e)
        }
        return out
    }

    private fun addSession(ctx: Context, s: Session) {
        val list = sessions(ctx).toMutableList()
        list.add(s)
        val trimmed = if (list.size > 500) list.subList(list.size - 500, list.size) else list
        val arr = JSONArray()
        for (x in trimmed) {
            val o = JSONObject()
            o.put("s", x.start)
            o.put("e", x.end)
            o.put("d", x.dur)
            o.put("ok", x.ok)
            o.put("src", x.src)
            arr.put(o)
        }
        lock(ctx).edit().putString("sessions", arr.toString()).apply()
        if (s.ok) recordFocus(ctx, s)
        if (s.ok) saveSessionSummary(ctx, s)
        if (s.ok) Badges.check(ctx)
        if (s.ok) Garden.onLockCompleted(ctx)
    }

    /** The last completed lock's summary (duration + blocks fired in its window),
     * shown once as a dialog and kept as a line on Home. */
    private fun saveSessionSummary(ctx: Context, s: Session) {
        try {
            val blocks = Extra.attempts(ctx).count { it.first >= s.start && it.first <= s.end }
            val o = JSONObject()
            o.put("d", s.dur)
            o.put("b", blocks)
            o.put("e", s.end)
            lock(ctx).edit().putString("last_summary", o.toString()).putBoolean("sum_pending", true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "summary save failed", e)
        }
    }

    fun lastSummary(ctx: Context): SessionSummary? {
        return try {
            val raw = lock(ctx).getString("last_summary", null) ?: return null
            val o = JSONObject(raw)
            SessionSummary(o.getLong("d"), o.getInt("b"), o.getLong("e"))
        } catch (e: Exception) {
            null
        }
    }

    /** True when a fresh summary is waiting to be shown as a dialog. */
    fun summaryPending(ctx: Context): Boolean = lock(ctx).getBoolean("sum_pending", false)

    fun clearSummaryPending(ctx: Context) {
        lock(ctx).edit().putBoolean("sum_pending", false).apply()
    }

    /** A completed lock counts toward the streak + focus totals (date-keyed, kept 90 days). */
    private fun recordFocus(ctx: Context, s: Session) {
        try {
            val p = lock(ctx)
            val day = dayKeyFor(s.end)
            val min = (s.dur / 60000L).toInt().coerceAtLeast(1)
            val obj = JSONObject(p.getString("focus_days", "{}"))
            obj.put(day, obj.optInt(day, 0) + min)
            val keep = (0 until 90).map { dayKey(it) }.toSet()
            val keys = obj.keys()
            val drop = ArrayList<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k !in keep) drop.add(k)
            }
            for (k in drop) obj.remove(k)
            p.edit().putString("focus_days", obj.toString())
                .putLong("focus_total_min", p.getLong("focus_total_min", 0) + min)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "focus record failed", e)
        }
    }

    /** Streak (consecutive days with a completed lock, ending today or yesterday),
     * total focus minutes, completed locks, and focus minutes for the last 7 days
     * (oldest first). */
    fun focusStats(ctx: Context): FocusStats {
        val p = lock(ctx)
        // one-time backfill: older builds recorded sessions but no focus store
        if (!p.contains("focus_days")) {
            try {
                val all = sessions(ctx)
                val obj = JSONObject()
                var total = 0L
                for (s in all) {
                    if (!s.ok) continue
                    val day = dayKeyFor(s.end)
                    val min = (s.dur / 60000L).toInt().coerceAtLeast(1)
                    obj.put(day, obj.optInt(day, 0) + min)
                    total += min
                }
                p.edit().putString("focus_days", obj.toString()).putLong("focus_total_min", total).apply()
            } catch (e: Exception) {
                Log.e(TAG, "focus backfill failed", e)
                p.edit().putString("focus_days", "{}").apply()
            }
        }
        val obj = try {
            JSONObject(p.getString("focus_days", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        fun hasDay(daysAgo: Int) = obj.optInt(dayKey(daysAgo), 0) > 0
        var streak = 0
        if (hasDay(0) || hasDay(1)) {
            var i = if (hasDay(0)) 0 else 1
            while (hasDay(i)) {
                streak++
                i++
            }
        }
        val last7 = (6 downTo 0).map { obj.optInt(dayKey(it), 0) }
        return FocusStats(streak, p.getLong("focus_total_min", 0), sessions(ctx).count { it.ok }, last7)
    }

    /** Focus minutes per day key (a day counts when a lock completed on it). */
    fun focusDayMap(ctx: Context): Map<String, Int> {
        val p = lock(ctx)
        if (!p.contains("focus_days")) focusStats(ctx) // one-time backfill, then cheap reads
        val out = HashMap<String, Int>()
        try {
            val obj = JSONObject(p.getString("focus_days", "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optInt(k, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "focus days read failed", e)
        }
        return out
    }

    /** Lifetime block count (the attempts list is capped at 1000): seeded once from the list. */
    fun totalBlocks(ctx: Context): Long {
        val p = lock(ctx)
        if (!p.contains("blocks_total")) {
            p.edit().putLong("blocks_total", Extra.attempts(ctx).size.toLong()).apply()
        }
        return p.getLong("blocks_total", 0)
    }

    fun dayStart(ms: Long, offsetDays: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        return c.timeInMillis
    }

    fun monthStart(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun sumRange(list: List<Session>, from: Long, to: Long): Long {
        var total = 0L
        for (s in list) {
            if (s.start >= from && s.start < to) total += s.dur
        }
        return total
    }

    // ----- schedules -----

    fun schedules(ctx: Context): List<Schedule> {
        val out = ArrayList<Schedule>()
        try {
            val arr = JSONArray(settings(ctx).getString("schedules", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Schedule(o.getLong("id"), o.getInt("d"), o.getInt("s"), o.getInt("e"), o.getBoolean("on")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "schedules read failed", e)
        }
        return out
    }

    fun saveSchedules(ctx: Context, list: List<Schedule>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("d", x.days)
            o.put("s", x.startMin)
            o.put("e", x.endMin)
            o.put("on", x.enabled)
            arr.put(o)
        }
        settings(ctx).edit().putString("schedules", arr.toString()).apply()
    }
}

// ---------- milestone badges ----------

object Badges {
    val ALL = listOf(
        BadgeDef("first_lock", Ic.CHECK, "First lock", "প্রথম লক", "Complete your first lock", "প্রথম লক সম্পন্ন করুন"),
        BadgeDef("streak_7", Ic.CAL, "7-day streak", "৭ দিনের স্ট্রিক", "Lock every day for 7 days", "টানা ৭ দিন প্রতিদিন লক করুন"),
        BadgeDef("streak_30", Ic.CAL, "30-day streak", "৩০ দিনের স্ট্রিক", "Lock every day for 30 days", "টানা ৩০ দিন প্রতিদিন লক করুন"),
        BadgeDef("locks_10", Ic.LOCK, "10 locks", "১০টা লক", "Complete 10 locks", "১০টা লক সম্পন্ন করুন"),
        BadgeDef("locks_50", Ic.LOCK, "50 locks", "৫০টা লক", "Complete 50 locks", "৫০টা লক সম্পন্ন করুন"),
        BadgeDef("focus_100h", Ic.CLOCK, "100 hours", "১০০ ঘণ্টা", "100 hours of total focus", "মোট ১০০ ঘণ্টা ফোকাস"),
        BadgeDef("blocks_1000", Ic.BARS, "1000 blocks", "১০০০ বার ব্লক", "1000 blocked attempts", "১০০০ বার ব্লক হয়েছে")
    )

    fun byId(id: String): BadgeDef? = ALL.firstOrNull { it.id == id }

    /** Earned badges: id → timestamp earned. */
    fun earned(ctx: Context): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val obj = JSONObject(Store.lock(ctx).getString("badges", "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getLong(k)
            }
        } catch (e: Exception) {
            Log.e(TAG, "badges read failed", e)
        }
        return out
    }

    /** Awards a badge the first time it is earned; sets the one-time dialog flag. */
    fun award(ctx: Context, id: String): Boolean {
        val got = earned(ctx)
        if (got.containsKey(id)) return false
        try {
            val obj = JSONObject(Store.lock(ctx).getString("badges", "{}"))
            obj.put(id, System.currentTimeMillis())
            Store.lock(ctx).edit().putString("badges", obj.toString()).putString("badge_pending", id).apply()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "badge award failed", e)
            return false
        }
    }

    /** Checks every lock-completion milestone. Called whenever a lock completes. */
    fun check(ctx: Context) {
        try {
            val fs = Store.focusStats(ctx)
            if (fs.completed >= 1) award(ctx, "first_lock")
            if (fs.streak >= 7) award(ctx, "streak_7")
            if (fs.streak >= 30) award(ctx, "streak_30")
            if (fs.completed >= 10) award(ctx, "locks_10")
            if (fs.completed >= 50) award(ctx, "locks_50")
            if (fs.totalMin >= 6000) award(ctx, "focus_100h")
            if (Store.totalBlocks(ctx) >= 1000) award(ctx, "blocks_1000")
        } catch (e: Exception) {
            Log.e(TAG, "badge check failed", e)
        }
    }

    /** A freshly earned badge waiting for its one-time dialog, or null. */
    fun pending(ctx: Context): BadgeDef? {
        val id = Store.lock(ctx).getString("badge_pending", null) ?: return null
        return byId(id)
    }

    fun clearPending(ctx: Context) {
        Store.lock(ctx).edit().remove("badge_pending").apply()
    }
}
