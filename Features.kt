package com.powerlock.app

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import kotlin.math.sqrt
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =====================================================================
// DATA
// =====================================================================

const val PLACE_MODE_INSIDE = 0 // lock while inside the place
const val PLACE_MODE_OUTSIDE = 1 // lock while outside the place

data class Place(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Int,
    val min: Int,
    val enabled: Boolean,
    val mode: Int = PLACE_MODE_INSIDE
)

/** One recorded point of today's route. */
data class RoutePoint(val lat: Double, val lng: Double, val t: Long)

/** One visit to a place: entered at [inTime], left at [outTime] (0 = still inside). */
data class PlaceEvent(val placeId: Long, val name: String, val inTime: Long, val outTime: Long)

/** A named set of apps (Protection tab). Blocking stays per-app; a group is a shortcut. */
data class AppGroup(val id: Long, val name: String, val pkgs: List<String>)

/**
 * A named, saved lock configuration: duration, strict on/off, and unlock
 * mode. Applying one fills the Home lock setup with its values. This is NOT
 * the old Preset feature: profiles are a separate named list with their own
 * storage key and no chips row on Home. The unlock destination is not part
 * of a profile — it stays the global destination picked on the map.
 */
data class LockProfile(val id: Long, val name: String, val minutes: Int, val strict: Boolean, val mode: String)

object Extra {

    /** Build number, bumped on every push — shown in Settings so the user can
     * confirm which build is installed. A String because L.num takes a String. */
    const val BUILD_ID = "122"

    // ---------- extend time (never reduce) ----------

    /** 0 = extended, 1 = no running session, 2 = would pass the 15 day limit. */
    fun extend(ctx: Context, addMs: Long): Int {
        if (addMs <= 0L) return 1
        if (!Store.isActive(ctx)) return 1
        val p = Store.lock(ctx)
        val start = p.getLong("start", 0)
        val end = p.getLong("end", 0)
        val newEnd = end + addMs
        if (newEnd - start > MAX_MIN * 60_000L) return 2
        p.edit().putLong("end", newEnd).putLong("total", newEnd - start).apply()
        Store.kickService(ctx)
        return 0
    }

    // ---------- blocked attempts ----------

    private val lastLog = HashMap<String, Long>()

    /** One diagnostics log entry: when, which check caught it, which app, what matched. */
    data class DiagEvent(val t: Long, val k: String, val p: String, val d: String)

    /**
     * kind: "power", "settings", "focus", "appblock", "reels", "msgsearch", "limit",
     * "uninstall", "site" (DNS), "url" (address-bar URL), "kw_url" (keyword in a URL),
     * "kw_typed" (keyword typed in a text field) or "kw_visible" (keyword in
     * visible screen text). At most one entry per kind every 3 seconds.
     * [pkg] is the app the user was kicked out of; [detail] is what matched
     * (the domain, the keyword or the host). Old rows have neither.
     */
    @Synchronized
    fun logAttempt(ctx: Context, kind: String, pkg: String = "", detail: String = "") {
        try {
            val now = System.currentTimeMillis()
            val last = lastLog[kind] ?: 0L
            if (now - last < 3000L) return
            lastLog[kind] = now
            val p = Store.lock(ctx)
            val old = try {
                JSONArray(p.getString("attempts", "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            val out = JSONArray()
            val from = if (old.length() >= 1000) old.length() - 999 else 0
            for (i in from until old.length()) out.put(old.get(i))
            val o = JSONObject()
            o.put("t", now)
            o.put("k", kind)
            o.put("p", pkg)
            o.put("d", detail)
            out.put(o)
            // lifetime block counter (the attempts list is capped at 1000): seeds once from the list
            val total = if (p.contains("blocks_total")) p.getLong("blocks_total", 0) else old.length().toLong()
            p.edit().putString("attempts", out.toString()).putLong("blocks_total", total + 1).apply()
            if (total + 1 == 1000L) Badges.award(ctx, "blocks_1000")
        } catch (e: Exception) {
            Log.e(TAG, "logAttempt failed", e)
        }
    }

    fun attempts(ctx: Context): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        try {
            val arr = JSONArray(Store.lock(ctx).getString("attempts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getLong("t"), o.getString("k")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "attempts read failed", e)
        }
        return out
    }

    /** Full diagnostics rows (time, kind, app package, what matched). Old rows yield "" for p/d. */
    fun attemptsFull(ctx: Context): List<DiagEvent> {
        val out = ArrayList<DiagEvent>()
        try {
            val arr = JSONArray(Store.lock(ctx).getString("attempts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(DiagEvent(o.getLong("t"), o.getString("k"), o.optString("p", ""), o.optString("d", "")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "attemptsFull read failed", e)
        }
        return out
    }

    /** Wipes the diagnostics log (the lifetime blocks_total counter is kept). */
    fun clearAttempts(ctx: Context) {
        try {
            Store.lock(ctx).edit().remove("attempts").apply()
        } catch (e: Exception) {
            Log.e(TAG, "clearAttempts failed", e)
        }
    }

    // ---------- uninstall protection (instant, no lock needed) ----------

    /** Apps whose uninstall is blocked instantly (comma-separated packages in settings). */
    fun uninstallProtectApps(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("uninstall_protect_apps", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setUninstallProtectApps(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("uninstall_protect_apps", apps.joinToString(",")).apply()
    }

    // ---------- focus mode ----------

    fun focusApps(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("focus_apps", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setFocusApps(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("focus_apps", apps.joinToString(",")).apply()
    }

    // ---------- app block (Protection) ----------

    /** Master switch for the Protection section's app block. On by default. */
    fun appBlockOn(ctx: Context): Boolean =
        Store.settings(ctx).getBoolean("ab_on", true)

    /** Packages that are fully blocked: opening one jumps straight back to Home. */
    fun appBlockFull(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ab_full", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setAppBlockFull(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ab_full", apps.joinToString(",")).apply()
    }

    /** Packages whose Reels / Shorts feed is blocked; the app itself stays usable. */
    fun appBlockReels(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ab_reels", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setAppBlockReels(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ab_reels", apps.joinToString(",")).apply()
    }

    /** Packages whose in-app search is blocked: opening search shows the protection screen. */
    fun appBlockMsg(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ab_msg", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setAppBlockMsg(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ab_msg", apps.joinToString(",")).apply()
    }

    // ---------- whitelist mode (Protection) ----------

    /** Master switch for Whitelist mode. Off by default. */
    fun whitelistOn(ctx: Context): Boolean =
        Store.settings(ctx).getBoolean("whitelist_on", false)

    /** Packages that are ALLOWED during a lock: everything else is blocked.
     * The launcher, the dialer and PowerLock itself are always allowed. */
    fun whitelistApps(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("whitelist_apps", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setWhitelistApps(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("whitelist_apps", apps.joinToString(",")).apply()
    }

    /** Packages allowed to be installed or updated during a lock (build 120).
     * The user adds package names here; those apps can be installed or
     * updated even while a lock runs. PowerLock itself is never allowed —
     * updating it mid-lock would remove the lock. */
    fun installAllowApps(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("install_allow_apps", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setInstallAllowApps(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("install_allow_apps", apps.joinToString(",")).apply()
    }

    /** Packages added by typing the package name (not from the picker): they
     * stay in the block list even while the app is not installed, so
     * installing the app later still blocks it. */
    fun manualPackages(ctx: Context): Set<String> {
        val s = Store.settings(ctx).getString("ab_manual", "") ?: ""
        return s.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setManualPackages(ctx: Context, apps: Set<String>) {
        Store.settings(ctx).edit().putString("ab_manual", apps.joinToString(",")).apply()
    }

    // ---------- daily app limits (work without a lock) ----------

    /** Per-app daily minute limits: {package: minutes}. 0 / absent = no limit. */
    fun dailyLimits(ctx: Context): Map<String, Int> {
        val out = HashMap<String, Int>()
        try {
            val obj = JSONObject(Store.settings(ctx).getString("dl_limits", "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optInt(k, 0)
                if (v > 0) out[k] = v
            }
        } catch (e: Exception) {
            Log.e(TAG, "daily limits read failed", e)
        }
        return out
    }

    /** Set a daily minute limit for one app (min <= 0 removes it). */
    fun setDailyLimit(ctx: Context, pkg: String, min: Int) {
        try {
            val obj = JSONObject(Store.settings(ctx).getString("dl_limits", "{}"))
            if (min > 0) obj.put(pkg, min) else obj.remove(pkg)
            Store.settings(ctx).edit().putString("dl_limits", obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "daily limit save failed", e)
        }
    }

    fun dailyLimit(ctx: Context, pkg: String): Int = dailyLimits(ctx)[pkg] ?: 0

    // ---------- daily usage (foreground seconds per app, keyed by day) ----------

    private fun usagePrefs(ctx: Context) = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)

    /** Add foreground seconds for one app today (prunes days older than 30). */
    fun addUsage(ctx: Context, pkg: String, seconds: Long) {
        if (seconds <= 0) return
        try {
            val p = usagePrefs(ctx)
            val day = Store.dayKey(0)
            val obj = JSONObject(p.getString("u_$day", "{}"))
            obj.put(pkg, obj.optLong(pkg, 0) + seconds)
            val e = p.edit().putString("u_$day", obj.toString())
            val keep = (0 until 30).map { "u_" + Store.dayKey(it) }.toSet()
            for (k in p.all.keys) if (k.startsWith("u_") && k !in keep) e.remove(k)
            e.apply()
        } catch (e: Exception) {
            Log.e(TAG, "usage save failed", e)
        }
    }

    /** Today's foreground seconds for every tracked app. */
    fun usageTodayAll(ctx: Context): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val obj = JSONObject(usagePrefs(ctx).getString("u_" + Store.dayKey(0), "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optLong(k, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "usage read failed", e)
        }
        return out
    }

    fun usageTodaySec(ctx: Context, pkg: String): Long = usageTodayAll(ctx)[pkg] ?: 0L

    /** Foreground seconds per app for one day key ("yyyyMMdd"). */
    fun usageForDay(ctx: Context, dayKey: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val obj = JSONObject(usagePrefs(ctx).getString("u_$dayKey", "{}"))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optLong(k, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "usage read failed", e)
        }
        return out
    }

    /** Total foreground seconds for one day key. */
    fun usageDayTotal(ctx: Context, dayKey: String): Long = usageForDay(ctx, dayKey).values.sum()

    /** Foreground seconds per app summed over the last 7 days (what is stored). */
    fun usage7DayTotal(ctx: Context): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (i in 0 until 7) {
            for ((pkg, sec) in usageForDay(ctx, Store.dayKey(i))) {
                out[pkg] = (out[pkg] ?: 0L) + sec
            }
        }
        return out
    }

    /** Seconds left today for an app with a limit, or null when it has none. */
    fun remainingTodaySec(ctx: Context, pkg: String): Long? {
        val lim = dailyLimit(ctx, pkg)
        if (lim <= 0) return null
        return (lim * 60L - usageTodaySec(ctx, pkg)).coerceAtLeast(0L)
    }

    // ---------- daily focus score (0–100) ----------

    /** Today's focus score and the numbers behind it, so the UI can explain it.
     *
     * Formula (fixed, so the score means the same thing every day):
     *  - up to 50 pts: today's completed-lock minutes vs a 60-minute daily goal
     *    (proportional — 30 minutes earns 25)
     *  - up to 20 pts: blocks resisted today (2 pts each, capped at 10 blocks)
     *  - up to 20 pts: current streak (2 pts per day, capped at 10 days)
     *  - 10 pts: completed at least one lock today
     * Total 100. A day with no lock and no blocks scores 0 + streak points. */
    data class FocusScore(
        val score: Int,
        val focusMinToday: Int,
        val goalMin: Int,
        val blocksToday: Int,
        val streak: Int,
        val completedToday: Boolean
    )

    fun focusScore(ctx: Context): FocusScore {
        val goalMin = 60
        val focusMinToday = Store.focusDayMap(ctx)[Store.dayKey(0)] ?: 0
        val todayStart = Store.dayStart(System.currentTimeMillis(), 0)
        val blocksToday = attempts(ctx).count { it.first >= todayStart }
        val streak = Store.focusStats(ctx).streak
        val completedToday = focusMinToday > 0
        val focusPts = (focusMinToday * 50 / goalMin).coerceIn(0, 50)
        val blockPts = (blocksToday.coerceAtMost(10) * 2)
        val streakPts = (streak.coerceAtMost(10) * 2)
        val donePts = if (completedToday) 10 else 0
        val score = (focusPts + blockPts + streakPts + donePts).coerceIn(0, 100)
        return FocusScore(score, focusMinToday, goalMin, blocksToday, streak, completedToday)
    }

    // ---------- custom block-screen message ----------

    /** The user's own motivational message, shown on the block screen (blank = default). */
    fun blockMessage(ctx: Context): String = Store.settings(ctx).getString("block_msg", "") ?: ""

    fun setBlockMessage(ctx: Context, msg: String) {
        Store.settings(ctx).edit().putString("block_msg", msg).apply()
    }

    // ---------- site block (DNS VPN; the VPN itself lives in SiteBlockService.kt) ----------

    /** Starter blocklist, used until the user edits the list. Subdomains are blocked too. */
    val SITE_DEFAULT_DOMAINS = listOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "brazzers.com", "onlyfans.com", "chaturbate.com", "livejasmin.com"
    )

    fun siteOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean("site_on", false)

    fun setSiteOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean("site_on", on).apply()
    }

    /** URL-detection block (AccessibilityService): watches the browser
     * address bar during a lock. Independent of the DNS/VPN option. */
    fun siteUrlOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean("site_url_on", false)

    fun setSiteUrlOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean("site_url_on", on).apply()
    }

    /** Blocked domains (lowercase, no scheme/path). Defaults apply until the user saves a list. */
    fun siteDomains(ctx: Context): List<String> {
        val raw = Store.settings(ctx).getString("site_domains", null) ?: return SITE_DEFAULT_DOMAINS
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val d = normalizeDomain(arr.optString(i, "")) ?: continue
                if (d !in out) out.add(d)
            }
            out.sorted()
        } catch (e: Exception) {
            Log.e(TAG, "site domains read failed", e)
            SITE_DEFAULT_DOMAINS
        }
    }

    fun setSiteDomains(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        for (d in list) {
            val n = normalizeDomain(d) ?: continue
            arr.put(n)
        }
        Store.settings(ctx).edit().putString("site_domains", arr.toString()).apply()
    }

    /** Adds one domain. Returns false when it is invalid or already listed. */
    fun addSiteDomain(ctx: Context, raw: String): Boolean {
        val d = normalizeDomain(raw) ?: return false
        val list = siteDomains(ctx).toMutableList()
        if (d in list) return false
        list.add(d)
        setSiteDomains(ctx, list)
        return true
    }

    fun removeSiteDomain(ctx: Context, domain: String) {
        setSiteDomains(ctx, siteDomains(ctx).filter { it != domain })
    }

    // ---------- keyword block (any keyword anywhere in the URL/domain blocks it) ----------

    /** Starter keywords, used until the user edits the list (all lowercase).
     * Includes the major site names as well as generic words, so a site
     * like xnxx.tv is caught by the keyword "xnxx" even if domain blocking
     * fails for any reason. */
    val SITE_DEFAULT_KEYWORDS = listOf(
        "porn", "xxx", "sex", "nude", "naked", "hentai", "porno",
        "erotic", "adult", "camgirl", "boobs", "nsfw", "horny",
        "xnxx", "xvideos", "pornhub", "xhamster"
    )

    /** Keyword block: matches the full URL (accessibility path), the
     * queried domain (DNS path), and text typed in any text field, in
     * any app (accessibility path — the user's choice: keywords block
     * wherever they are typed on the phone). Independent of the DNS/VPN
     * switch for the accessibility paths — they work during a lock even
     * when DNS is off. Defaults ON: it is the hard fallback when domain
     * blocking fails. */
    fun siteKwOn(ctx: Context): Boolean = Store.settings(ctx).getBoolean("site_kw_on", true)

    fun setSiteKwOn(ctx: Context, on: Boolean) {
        Store.settings(ctx).edit().putBoolean("site_kw_on", on).apply()
    }

    // ---------- art pad trick lock (hides the site block section) ----------

    /** The stored secret drawing, as 32 normalized points "x,y;x,y;..." — null when not set yet. */
    fun artSecret(ctx: Context): String? = Store.settings(ctx).getString("art_secret", null)

    fun setArtSecret(ctx: Context, secret: String?) {
        val e = Store.settings(ctx).edit()
        if (secret == null) e.remove("art_secret") else e.putString("art_secret", secret)
        e.apply()
    }

    /** True while the site block section is revealed (until the user hides it again). */
    fun artRevealed(ctx: Context): Boolean = Store.settings(ctx).getBoolean("art_revealed", false)

    fun setArtRevealed(ctx: Context, v: Boolean) {
        Store.settings(ctx).edit().putBoolean("art_revealed", v).apply()
    }

    /** Blocked keywords (lowercase, trimmed). Defaults apply until the user saves a list. */
    fun siteKeywords(ctx: Context): List<String> {
        val raw = Store.settings(ctx).getString("site_keywords", null) ?: return SITE_DEFAULT_KEYWORDS
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val k = normalizeKeyword(arr.optString(i, "")) ?: continue
                if (k !in out) out.add(k)
            }
            out.sorted()
        } catch (e: Exception) {
            Log.e(TAG, "site keywords read failed", e)
            SITE_DEFAULT_KEYWORDS
        }
    }

    fun setSiteKeywords(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        for (k in list) {
            val n = normalizeKeyword(k) ?: continue
            arr.put(n)
        }
        Store.settings(ctx).edit().putString("site_keywords", arr.toString()).apply()
    }

    /** Adds one keyword. Returns false when it is empty or already listed. */
    fun addSiteKeyword(ctx: Context, raw: String): Boolean {
        val k = normalizeKeyword(raw) ?: return false
        val list = siteKeywords(ctx).toMutableList()
        if (k in list) return false
        list.add(k)
        setSiteKeywords(ctx, list)
        return true
    }

    fun removeSiteKeyword(ctx: Context, keyword: String) {
        setSiteKeywords(ctx, siteKeywords(ctx).filter { it != keyword })
    }

    /**
     * Normalizes keyword input: trimmed, lowercased. Returns null for empty
     * or over-long input. Spaces are allowed (a phrase keyword), and Bengali
     * or mixed words work the same way — matching is a plain substring test.
     */
    fun normalizeKeyword(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty() || s.length > 64) return null
        return s
    }

    /**
     * Normalizes user input ("https://www.Example.com/path" -> "example.com").
     * Returns null for anything that is not a plain domain name.
     */
    fun normalizeDomain(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.startsWith("http://")) s = s.substring(7)
        if (s.startsWith("https://")) s = s.substring(8)
        if (s.startsWith("www.")) s = s.substring(4)
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        s = s.trimEnd('.')
        if (s.isEmpty() || s.length > 253) return null
        if (!s.contains('.')) return null
        for (part in s.split('.')) {
            if (part.isEmpty() || part.length > 63) return null
            if (part.startsWith('-') || part.endsWith('-')) return null
            for (c in part) {
                if (!(c in 'a'..'z' || c in '0'..'9' || c == '-')) return null
            }
        }
        return s
    }

    // ---------- app groups (named sets; blocking itself stays per-app) ----------

    /** A named set of apps. Groups never block by themselves — "block all" just
     * adds every app in the group to the full-block list at once. */
    fun appGroups(ctx: Context): List<AppGroup> {
        val raw = Store.settings(ctx).getString("app_groups", null)
        if (raw == null) {
            // first read: seed the two starter groups, keeping only installed apps
            val seeded = starterGroups(ctx)
            saveAppGroups(ctx, seeded)
            return seeded
        }
        val out = ArrayList<AppGroup>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkgs = ArrayList<String>()
                val pa = o.optJSONArray("pkgs")
                if (pa != null) for (j in 0 until pa.length()) pkgs.add(pa.getString(j))
                out.add(AppGroup(o.getLong("id"), o.getString("name"), pkgs))
            }
        } catch (e: Exception) {
            Log.e(TAG, "app groups read failed", e)
        }
        return out
    }

    fun saveAppGroups(ctx: Context, list: List<AppGroup>) {
        try {
            val arr = JSONArray()
            for (g in list) {
                val o = JSONObject()
                o.put("id", g.id)
                o.put("name", g.name)
                val pa = JSONArray()
                for (p in g.pkgs) pa.put(p)
                o.put("pkgs", pa)
                arr.put(o)
            }
            Store.settings(ctx).edit().putString("app_groups", arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "app groups save failed", e)
        }
    }

    fun addAppGroup(ctx: Context, name: String, pkgs: List<String>): AppGroup {
        val g = AppGroup(System.currentTimeMillis(), name, pkgs)
        val list = appGroups(ctx).toMutableList()
        list.add(g)
        saveAppGroups(ctx, list)
        return g
    }

    fun updateAppGroup(ctx: Context, id: Long, name: String, pkgs: List<String>) {
        val list = appGroups(ctx).map { if (it.id == id) it.copy(name = name, pkgs = pkgs) else it }
        saveAppGroups(ctx, list)
    }

    fun deleteAppGroup(ctx: Context, id: Long) {
        saveAppGroups(ctx, appGroups(ctx).filter { it.id != id })
    }

    // ---------- lock profiles (named, saved lock setups) ----------

    /** At most 10 saved profiles. */
    const val MAX_PROFILES = 10

    fun lockProfiles(ctx: Context): List<LockProfile> {
        val out = ArrayList<LockProfile>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString("lock_profiles", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val mode = o.optString("m", UNLOCK_TIME)
                out.add(
                    LockProfile(
                        o.getLong("id"),
                        o.getString("n"),
                        o.getInt("min").coerceIn(1, MAX_MIN),
                        o.getBoolean("s"),
                        if (mode == UNLOCK_LOC || mode == UNLOCK_EITHER) mode else UNLOCK_TIME
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "lock profiles read failed", e)
        }
        return out
    }

    fun saveLockProfiles(ctx: Context, list: List<LockProfile>) {
        val arr = JSONArray()
        for (x in list.take(MAX_PROFILES)) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("n", x.name)
            o.put("min", x.minutes)
            o.put("s", x.strict)
            o.put("m", x.mode)
            arr.put(o)
        }
        Store.settings(ctx).edit().putString("lock_profiles", arr.toString()).apply()
    }

    /** Adds a profile; returns null when the list is full. */
    fun addLockProfile(ctx: Context, name: String, minutes: Int, strict: Boolean, mode: String): LockProfile? {
        val list = lockProfiles(ctx).toMutableList()
        if (list.size >= MAX_PROFILES) return null
        val p = LockProfile(System.currentTimeMillis(), name, minutes.coerceIn(1, MAX_MIN), strict, mode)
        list.add(p)
        saveLockProfiles(ctx, list)
        return p
    }

    fun updateLockProfile(ctx: Context, id: Long, name: String, minutes: Int, strict: Boolean, mode: String) {
        saveLockProfiles(
            ctx,
            lockProfiles(ctx).map {
                if (it.id == id) it.copy(name = name, minutes = minutes.coerceIn(1, MAX_MIN), strict = strict, mode = mode) else it
            }
        )
    }

    fun deleteLockProfile(ctx: Context, id: Long) {
        saveLockProfiles(ctx, lockProfiles(ctx).filter { it.id != id })
    }

    /**
     * Applies a profile to the Home lock setup: duration, strict mode and
     * unlock mode. Returns false when a session is running (setup is locked
     * then). The unlock destination is untouched — it stays the global one.
     */
    fun applyLockProfile(ctx: Context, p: LockProfile): Boolean {
        if (Store.isActive(ctx)) return false
        val st = Store.settings(ctx)
        st.edit()
            .putInt("sel_min", p.minutes)
            .putBoolean("strict", p.strict)
            .putString("unlock_mode", p.mode)
            .apply()
        return true
    }

    /** Starter groups by well-known package names; only installed apps are kept. */
    private fun starterGroups(ctx: Context): List<AppGroup> {
        val pm = ctx.packageManager
        fun installed(pkgs: List<String>): List<String> {
            return pkgs.filter {
                try {
                    pm.getApplicationInfo(it, 0)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
        val out = ArrayList<AppGroup>()
        val social = installed(
            listOf(
                "com.facebook.katana", "com.facebook.lite", "com.instagram.android",
                "com.whatsapp", "com.whatsapp.w4b", "com.zhiliaoapp.musically",
                "com.ss.android.ugc.trill", "org.telegram.messenger",
                "com.twitter.android", "com.snapchat.android"
            )
        )
        if (social.isNotEmpty()) out.add(AppGroup(1L, L.t("Social", "সোশ্যাল"), social))
        val video = installed(
            listOf(
                "com.google.android.youtube", "com.netflix.mediaclient",
                "com.amazon.avod", "com.google.android.apps.youtube.mango"
            )
        )
        if (video.isNotEmpty()) out.add(AppGroup(2L, L.t("Video", "ভিডিও"), video))
        return out
    }

    /** All launchable apps (label + icon), loaded once per session: loading ~150 icons is slow. */
    var appListCache: List<Triple<String, String, Drawable>>? = null

    /** Packages with a Reels / Shorts / Status feed — only these are offered for Reels block. */
    val reelsPackages = setOf(
        "com.instagram.android",      // Instagram Reels
        "com.facebook.katana",        // Facebook Reels
        "com.facebook.lite",          // Facebook Lite Reels
        "com.google.android.youtube", // YouTube Shorts
        "com.zhiliaoapp.musically",   // TikTok
        "com.ss.android.ugc.trill",   // TikTok (Asia)
        "com.snapchat.android",       // Snapchat Spotlight / Stories
        "com.whatsapp",               // WhatsApp Status
        "com.whatsapp.w4b",           // WhatsApp Business Status
        "org.telegram.messenger",     // Telegram Stories
        "com.twitter.android",        // X short videos
        "in.mohalla.sharechat",       // ShareChat short videos
        "com.versebrain.josh"         // Josh short videos
    )

    // ---------- places ----------

    fun places(ctx: Context): List<Place> {
        val out = ArrayList<Place>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString("places", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Place(
                        o.getLong("id"), o.getString("n"), o.getDouble("la"), o.getDouble("lo"),
                        o.getInt("r"), o.getInt("m"), o.getBoolean("on"),
                        o.optInt("md", PLACE_MODE_INSIDE)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "places read failed", e)
        }
        return out
    }

    fun savePlaces(ctx: Context, list: List<Place>) {
        val arr = JSONArray()
        for (x in list) {
            val o = JSONObject()
            o.put("id", x.id)
            o.put("n", x.name)
            o.put("la", x.lat)
            o.put("lo", x.lng)
            o.put("r", x.radius)
            o.put("m", x.min)
            o.put("on", x.enabled)
            o.put("md", x.mode)
            arr.put(o)
        }
        Store.settings(ctx).edit().putString("places", arr.toString()).apply()
    }

    /** Best recent location, or a fresh single fix (up to 20 s). Calls back on the main thread. */
    @SuppressLint("MissingPermission")
    fun currentLocation(ctx: Context, cb: (Location?) -> Unit) {
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val l = m.getLastKnownLocation(p)
                val b = best
                if (l != null && (b == null || l.time > b.time)) best = l
            }
        } catch (e: Exception) {
            Log.e(TAG, "last location failed", e)
        }
        val last: Location? = best
        if (last != null && System.currentTimeMillis() - last.time < 120_000L) {
            cb(last)
            return
        }
        var done = false
        val handler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                try {
                    m.removeUpdates(this)
                } catch (e: Exception) {
                    Log.e(TAG, "remove updates failed", e)
                }
                cb(location)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }
        }
        var asked = false
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.isProviderEnabled(p)) {
                    m.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    asked = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "request location failed", e)
        }
        if (!asked) {
            cb(last)
            return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                try {
                    m.removeUpdates(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "remove updates failed", e)
                }
                cb(last)
            }
        }, 20_000L)
    }
}

// =====================================================================
// LOCATION LOCK ENGINE (runs inside the always-on foreground service)
// =====================================================================

object PlaceWatcher {
    private var appCtx: Context? = null
    private var lm: LocationManager? = null
    private var on = false

    // per-place memory for history, boundary alerts and ETA
    private val lastInside = HashMap<Long, Boolean>()
    private val lastDist = HashMap<Long, Float>()
    private val lastAlert = HashMap<Long, String>() // "enter" or "leave"

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val c = appCtx ?: return
            handle(c, location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    /** Starts or stops location updates to match the settings and permissions. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        val placeWatch = Store.settings(app).getBoolean("loc_on", false) &&
                Extra.places(app).any { it.enabled }
        // a location-unlock session also needs watching, even when the location lock is off
        val unlockWatch = Store.unlockTarget(app) != null && Store.isActive(app)
        val want = (placeWatch || unlockWatch) && Perm.locationOn(app)
        if (want && !on) start(app) else if (!want && on) stop()
        // not watching means not protected: master off, no enabled place, or no permission
        if (!want && Store.isLocProtecting(app)) Store.setLocProtecting(app, false)
    }

    /** One-shot check right now (used when the master switch or a place is turned on). */
    fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!Store.settings(app).getBoolean("loc_on", false)) return
        if (!Perm.locationOn(app)) return
        Extra.currentLocation(app) { loc ->
            if (loc != null) handle(app, loc)
        }
    }

    private var lastUnlockCheck = 0L

    /**
     * One-shot arrival check for a location-unlock session. Works even when the
     * location lock is off. Throttled to once per 30 s (a fresh fix can take up to 20 s).
     */
    fun checkUnlockNow(ctx: Context) {
        val app = ctx.applicationContext
        if (Store.unlockTarget(app) == null || !Store.isActive(app)) return
        if (!Perm.locationOn(app)) return
        val now = System.currentTimeMillis()
        if (now - lastUnlockCheck < 30_000L) return
        lastUnlockCheck = now
        Extra.currentLocation(app) { loc ->
            if (loc == null) return@currentLocation
            if (loc.hasAccuracy() && loc.accuracy > 200f) return@currentLocation
            checkUnlockArrival(app, loc)
        }
    }

    /**
     * Cheap arrival check using only the last known fix (no fresh request): safe to call
     * every second from the Home tick. A fix older than 2 minutes is never trusted to unlock.
     */
    @SuppressLint("MissingPermission")
    fun checkUnlockLastKnown(ctx: Context) {
        val app = ctx.applicationContext
        if (Store.unlockTarget(app) == null || !Store.isActive(app)) return
        if (!Perm.locationOn(app)) return
        try {
            val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val l = m.getLastKnownLocation(p)
                val b = best
                if (l != null && (b == null || l.time > b.time)) best = l
            }
            val loc = best ?: return
            if (System.currentTimeMillis() - loc.time > 120_000L) return
            if (loc.hasAccuracy() && loc.accuracy > 200f) return
            checkUnlockArrival(app, loc)
        } catch (e: Exception) {
            Log.e(TAG, "unlock last-known check failed", e)
        }
    }

    /** Ends the session when the device is inside the unlock destination's radius. */
    private fun checkUnlockArrival(ctx: Context, loc: Location) {
        val target = Store.unlockTarget(ctx) ?: return
        if (!Store.isActive(ctx)) return
        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, target.lat, target.lng, res)
        if (res[0] <= target.radius) {
            if (Store.finalizeByLocation(ctx)) notifyUnlocked(ctx, target)
        }
    }

    /** True when any enabled place is currently demanding protection. Null when no location is available. */
    fun isAnyPlaceActiveNow(ctx: Context, cb: (Boolean?) -> Unit) {
        Extra.currentLocation(ctx.applicationContext) { loc ->
            if (loc == null || (loc.hasAccuracy() && loc.accuracy > 200f)) { cb(null); return@currentLocation }
            var protect = false
            for (pl in Extra.places(ctx)) {
                if (!pl.enabled) continue
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
                val d = res[0]
                if (pl.mode == PLACE_MODE_OUTSIDE) { if (d > pl.radius * 1.3f) protect = true }
                else if (d <= pl.radius) protect = true
            }
            cb(protect)
        }
    }

    /** True when this place is currently demanding protection. Null when no location is available. */
    fun isPlaceActiveNow(ctx: Context, pl: Place, cb: (Boolean?) -> Unit) {
        Extra.currentLocation(ctx.applicationContext) { loc ->
            if (loc == null || (loc.hasAccuracy() && loc.accuracy > 200f)) { cb(null); return@currentLocation }
            val res = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
            val d = res[0]
            cb(if (pl.mode == PLACE_MODE_OUTSIDE) d > pl.radius * 1.3f else d <= pl.radius)
        }
    }

    @SuppressLint("MissingPermission")
    private fun start(app: Context) {
        try {
            val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 20f, listener, Looper.getMainLooper())
            }
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 30f, listener, Looper.getMainLooper())
            }
            on = true
        } catch (e: Exception) {
            Log.e(TAG, "place watcher start failed", e)
            on = false
        }
    }

    fun stop() {
        try {
            lm?.removeUpdates(listener)
        } catch (e: Exception) {
            Log.e(TAG, "place watcher stop failed", e)
        }
        on = false
    }

    private fun handle(ctx: Context, loc: Location) {
        try {
            if (loc.hasAccuracy() && loc.accuracy > 200f) return // too vague to trust
            // Presence-based protection: at a saved place the power menu is blocked, away it is not.
            // No Home session is involved at all — the single location switch is the only setting.
            var protect = false
            for (pl in Extra.places(ctx)) {
                if (!pl.enabled) continue
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, pl.lat, pl.lng, res)
                val d = res[0]
                val inside = d <= pl.radius
                val outside = d > pl.radius * 1.3f
                if (pl.mode == PLACE_MODE_OUTSIDE) {
                    // Lock while away: outside the place = blocked, back inside = unblocked.
                    if (outside) protect = true
                } else {
                    // Lock while here: inside the place = blocked, left = unblocked.
                    if (inside) protect = true
                }
                // ---- entry / exit history (hysteresis so GPS jitter at the edge does not flap) ----
                val prevIn = lastInside[pl.id]
                val nowIn = if (prevIn == true) d <= pl.radius * 1.3f else d <= pl.radius
                if (prevIn == null) lastInside[pl.id] = nowIn
                else if (prevIn != nowIn) {
                    lastInside[pl.id] = nowIn
                    if (nowIn) Store.recordEnter(ctx, pl) else Store.recordExit(ctx, pl)
                }
                // ---- boundary alert: 10 m before entering / leaving ----
                val edge = 10f
                val aboutEnter = d > pl.radius && d <= pl.radius + edge
                val aboutLeave = d >= pl.radius - edge && d <= pl.radius
                val prevD = lastDist[pl.id]
                lastDist[pl.id] = d
                val movingIn = prevD != null && d < prevD
                val movingOut = prevD != null && d > prevD
                val speed = if (loc.hasSpeed()) loc.speed else 0f
                if (aboutEnter && movingIn) {
                    if (lastAlert[pl.id] != "enter") {
                        lastAlert[pl.id] = "enter"
                        val eta = if (speed > 0.5f) ((d - pl.radius) / speed / 60f).toInt().coerceAtLeast(1) else null
                        notifyBoundary(ctx, pl, entering = true, turningOn = pl.mode != PLACE_MODE_OUTSIDE, etaMin = eta)
                    }
                } else if (aboutLeave && movingOut) {
                    if (lastAlert[pl.id] != "leave") {
                        lastAlert[pl.id] = "leave"
                        val eta = if (speed > 0.5f) ((pl.radius - d) / speed / 60f).toInt().coerceAtLeast(1) else null
                        notifyBoundary(ctx, pl, entering = false, turningOn = pl.mode == PLACE_MODE_OUTSIDE, etaMin = eta)
                    }
                } else if (d > pl.radius + edge || d < pl.radius - edge) {
                    lastAlert.remove(pl.id) // away from the edge: arm the alert again
                }
            }
            if (Store.isLocProtecting(ctx) != protect) Store.setLocProtecting(ctx, protect)
            // location-unlock session: arrived at the destination? end the session
            checkUnlockArrival(ctx, loc)
            // today's route: record where you went while the location lock watches
            Store.addRoutePoint(ctx, loc.latitude, loc.longitude)
        } catch (e: Exception) {
            Log.e(TAG, "place check failed", e)
        }
    }

    /** "লক খুলেছে — বাড়ি পৌঁছেছো" when a location session ends by arrival. */
    private fun notifyUnlocked(ctx: Context, target: UnlockTarget) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val openApp = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nb = Notification.Builder(ctx, "powerlock_alert")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(L.t("Lock opened", "লক খুলেছে"))
                .setContentText(L.num(L.t("You arrived at ${target.name}", "${target.name} পৌঁছেছো")))
                .setAutoCancel(true)
                .setContentIntent(openApp)
            nm.notify(2100, nb.build())
        } catch (e: Exception) {
            Log.e(TAG, "unlock notify failed", e)
        }
    }

    /** "বাড়ির সীমানায় ঢুকছো — লক চালু হবে" (10 m before the boundary), with ETA when moving. */
    private fun notifyBoundary(ctx: Context, pl: Place, entering: Boolean, turningOn: Boolean, etaMin: Int?) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dir = if (entering) L.t("entering the boundary", "সীমানায় ঢুকছো") else L.t("leaving the boundary", "সীমানা ছাড়ছো")
            val action = if (turningOn) L.t("lock turns on", "লক চালু হবে") else L.t("lock turns off", "লক বন্ধ হবে")
            val title = pl.name + ": " + dir + " — " + action
            val text = if (etaMin != null) {
                if (entering) L.t("Arriving in ~$etaMin min", "পৌঁছাতে ~$etaMin মিনিট")
                else L.t("Leaving in ~$etaMin min", "ছাড়তে ~$etaMin মিনিট")
            } else {
                L.t("Boundary within 10 m", "সীমানা ১০ মিটারের মধ্যে")
            }
            val openApp = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nb = Notification.Builder(ctx, "powerlock_alert")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(L.num(title))
                .setContentText(L.num(text))
                .setAutoCancel(true)
                .setContentIntent(openApp)
            nm.notify(2000 + (pl.id % 1000).toInt(), nb.build())
        } catch (e: Exception) {
            Log.e(TAG, "boundary notify failed", e)
        }
    }
}

// =====================================================================
// UI: extend time
// =====================================================================

private fun MainActivity.addLabel(min: Int): String = "+ " + fmtDur(min * 60_000L)

fun MainActivity.extendFlow(done: () -> Unit) {
    if (!Store.isActive(this)) return
    val mins = intArrayOf(10, 30, 60, 180, 1440, -1)
    val labels = Array(mins.size) { i ->
        if (mins[i] > 0) addLabel(mins[i]) else L.t("Custom…", "কাস্টম…")
    }
    dlg()
        .setTitle(L.t("Add time", "সময় বাড়ান"))
        .setItems(labels) { _, which ->
            if (mins[which] > 0) confirmExtend(mins[which], done) else customExtend(done)
        }
        .show()
}

private fun MainActivity.confirmExtend(min: Int, done: () -> Unit) {
    val newEnd = Store.lock(this).getLong("end", 0) + min * 60_000L
    val at = fmtClock(newEnd)
    dlg()
        .setTitle(addLabel(min))
        .setMessage(
            L.t(
                "The lock will end at $at. Time can only be added, never reduced.",
                "লক চলবে $at পর্যন্ত। সময় শুধু বাড়ানো যায়, কমানো যায় না।"
            )
        )
        .setPositiveButton(L.t("Add", "বাড়ান")) { _, _ ->
            when (Extra.extend(this, min * 60_000L)) {
                0 -> toast(L.t("Time added", "সময় বাড়ানো হয়েছে"))
                2 -> toast(L.t("Maximum 15 days", "সর্বোচ্চ ১৫ দিন"))
                else -> toast(L.t("No running session", "কোনো সেশন চলছে না"))
            }
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.customExtend(done: () -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val r = LinearLayout(this)
    val dEt = edit(L.t("Days", "দিন"), true)
    val hEt = edit(L.t("Hours", "ঘণ্টা"), true)
    val mEt = edit(L.t("Minutes", "মিনিট"), true)
    r.addView(dEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(hEt, lp(0, WRAP, r = dp(8), weight = 1f))
    r.addView(mEt, lp(0, WRAP, weight = 1f))
    box.addView(r)
    val d = dlg()
        .setTitle(L.t("Add custom time", "নিজের সময় যোগ"))
        .setView(box)
        .setPositiveButton(L.t("Next", "পরবর্তী"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val total = (dEt.text.toString().toLongOrNull() ?: 0L) * 1440L +
                (hEt.text.toString().toLongOrNull() ?: 0L) * 60L +
                (mEt.text.toString().toLongOrNull() ?: 0L)
        if (total < 1L || total > MAX_MIN.toLong()) {
            mEt.error = L.t("1 minute to 15 days", "১ মিনিট থেকে ১৫ দিন")
        } else {
            d.dismiss()
            confirmExtend(total.toInt(), done)
        }
    }
}

// =====================================================================
// UI: site block (Settings)
// =====================================================================

/**
 * Site block card: on/off switch + editable blocked-domain list. The VPN
 * itself runs only while a lock is active. While a session runs the list is
 * add-only, like the app-block lists: domains can be added, not removed.
 */
fun MainActivity.siteBlockCard(): View {
    val c = card()
    c.addView(section(L.t("Site block", "সাইট ব্লক")))
    val locked = Store.isActive(this)
    val on = Extra.siteOn(this)
    lateinit var sw: PremiumSwitch
    sw = toggle(on) { v ->
        if (v) {
            Extra.setSiteOn(this, true)
            if (SiteBlockService.vpnGranted(this)) {
                SiteBlockService.sync(this)
                toast(
                    if (Store.isActive(this)) L.t("Site block is on", "সাইট ব্লক চালু হয়েছে")
                    else L.t("Site block will start with the next lock", "সাইট ব্লক পরের লক থেকে চালু হবে")
                )
                go(3)
            } else {
                // first enable: Android asks once for VPN permission
                val pi = SiteBlockService.prepareIntent(this)
                if (pi != null) {
                    try {
                        startActivityForResult(pi, REQ_VPN_CONSENT)
                    } catch (e: Exception) {
                        Log.e(TAG, "vpn consent launch failed", e)
                        Extra.setSiteOn(this, false)
                        sw.setCheckedImmediate(false)
                        toast(L.t("Could not open the VPN permission screen", "VPN অনুমতির স্ক্রিন খোলা যায়নি"))
                    }
                } else {
                    SiteBlockService.sync(this)
                    go(3)
                }
            }
        } else {
            Extra.setSiteOn(this, false)
            SiteBlockService.sync(this) // stops the VPN right away
            go(3)
        }
    }
    c.addView(
        row(
            L.t("Block websites during a lock", "লক চলাকালীন ওয়েবসাইট ব্লক"),
            when {
                !on -> L.t("Off — blocked sites open normally.", "বন্ধ — ব্লক করা সাইট সাধারণভাবে খুলবে।")
                SiteBlockService.isRunning -> L.t("Active now — blocked sites cannot open.", "এখন সক্রিয় — ব্লক করা সাইট খুলবে না।")
                locked -> L.t("On, but the VPN did not start — check the VPN permission.", "চালু, কিন্তু VPN শুরু হয়নি — VPN অনুমতি দেখুন।")
                else -> L.t("On — starts when the next lock starts.", "চালু — পরের লক শুরু হলে চালু হবে।")
            },
            sw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    // second option: URL detection via the accessibility service — works
    // during a lock even when the DNS/VPN option above is off
    val urlOn = Extra.siteUrlOn(this)
    val urlSw = toggle(urlOn) { v ->
        Extra.setSiteUrlOn(this, v)
        if (v) SiteBlockService.preloadBlocklist(this)
        if (v && !Perm.accessibilityOn(this)) {
            toast(L.t("URL detection needs accessibility — turn it on in Settings", "URL ডিটেকশনের জন্য অ্যাক্সেসিবিলিটি লাগবে — Settings থেকে চালু করুন"))
        } else {
            toast(
                if (v) L.t("URL detection is on — blocked sites are covered during a lock", "URL ডিটেকশন চালু — লক চলাকালীন ব্লক করা সাইট ঢাকা পড়বে")
                else L.t("URL detection is off", "URL ডিটেকশন বন্ধ")
            )
        }
        go(3)
    }
    c.addView(
        row(
            L.t("URL detection block", "URL ডিটেকশন ব্লক"),
            L.t("Watches the browser address bar during a lock and covers blocked sites. Works even when the DNS option above is off. Needs accessibility.", "লক চলাকালীন ব্রাউজারের অ্যাড্রেস বার দেখে ব্লক করা সাইট ঢেকে দেয়। ওপরের DNS অপশন বন্ধ থাকলেও কাজ করে। অ্যাক্সেসিবিলিটি লাগবে।"),
            urlSw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    // third option: keyword block — any keyword anywhere in the URL blocks
    // the site, and a keyword typed in any text field, in any app, blocks
    // too. The accessibility paths work during a lock even when the DNS
    // option above is off; the DNS path runs in the VPN when it runs.
    val kwOn = Extra.siteKwOn(this)
    val kwSw = toggle(kwOn) { v ->
        Extra.setSiteKwOn(this, v)
        if (v && !Perm.accessibilityOn(this)) {
            toast(L.t("Keyword block needs accessibility — turn it on in Settings", "কিওয়ার্ড ব্লকের জন্য অ্যাক্সেসিবিলিটি লাগবে — Settings থেকে চালু করুন"))
        } else {
            toast(
                if (v) L.t("Keyword block is on — keywords are blocked in URLs and wherever you type during a lock", "কিওয়ার্ড ব্লক চালু — লক চলাকালীন URL-এ বা যেকোনো জায়গায় লিখলে কিওয়ার্ড ব্লক হবে")
                else L.t("Keyword block is off", "কিওয়ার্ড ব্লক বন্ধ")
            )
        }
        SiteBlockService.sync(this) // reloads the keyword list in the running VPN
        go(3)
    }
    c.addView(
        row(
            L.t("Keyword block", "কিওয়ার্ড ব্লক"),
            L.t("Blocks any site whose address contains a keyword — yours or one of the built-in bad words (Bengali + English) — anywhere in the URL, even a single word — and blocks text typed anywhere on the phone (any app, messages included) that contains a keyword. Works during a lock even when the DNS option above is off. Needs accessibility. Password fields are never read.", "যে সাইটের ঠিকানায় কিওয়ার্ড আছে তা ব্লক করে — তোমার নিজের বা বিল্ট-ইন খারাপ শব্দের (বাংলা + ইংরেজি) যেকোনো একটা — URL-এর যেকোনো জায়গায় একটা শব্দ মিললেই হবে — আর ফোনের যেকোনো জায়গায় (যেকোনো অ্যাপ, মেসেজসহ) কিওয়ার্ড লিখলেও ব্লক করে। ওপরের DNS অপশন বন্ধ থাকলেও লক চলাকালীন কাজ করে। অ্যাক্সেসিবিলিটি লাগবে। পাসওয়ার্ডের ঘর কখনো পড়া হয় না।"),
            kwSw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    c.addView(
        tv(
            L.t(
                "Blocked sites get no answer (NXDOMAIN). SafeSearch is enforced for Google, Bing, DuckDuckGo and YouTube. Only DNS is handled — your other traffic never goes through the VPN.",
                "ব্লক করা সাইটের কোনো উত্তর আসে না। Google, Bing, DuckDuckGo ও YouTube-এ SafeSearch চালু থাকে। শুধু DNS দেখা হয় — বাকি ট্রাফিক VPN দিয়ে যায় না।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    // built-in blocklist size (0 when the asset could not be read)
    val blCount = SiteBlockService.blocklistCount(this)
    if (blCount > 0) {
        c.addView(
            tv(
                L.t(
                    "${L.num(String.format(Locale.US, "%,d", blCount))} adult sites auto-blocked, plus your own list below.",
                    "${L.num(String.format(Locale.US, "%,d", blCount))}টি খারাপ সাইট অটো ব্লক — নিচে তোমার নিজের তালিকাও আছে।"
                ),
                12f, C.primary
            ),
            lp(MATCH, WRAP, t = dp(6))
        )
        val viewList = outlineBtn(L.t("View blocklist", "ব্লকলিস্ট দেখুন"), C.primary)
        viewList.setOnClickListener {
            try {
                startActivity(Intent(this, BlocklistActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "blocklist viewer launch failed", e)
                toast(L.t("Could not open the blocklist", "ব্লকলিস্ট খোলা যায়নি"))
            }
        }
        c.addView(viewList, lp(MATCH, dp(48), t = dp(10)))
    }
    val domains = Extra.siteDomains(this)
    for (d in domains) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        r.addView(tv(d, 15f, C.text), lp(0, WRAP, weight = 1f))
        val remove = tv(L.t("Remove", "সরান"), 13f, if (locked) C.muted else C.warn)
        remove.setPadding(dp(8), dp(8), dp(8), dp(8))
        remove.pressFeedback()
        remove.setOnClickListener {
            if (Store.isActive(this)) {
                toast(L.t("Locked while a session is active — you can still add domains", "সেশন চলাকালীন সরানো যাবে না — তবে নতুন ডোমেইন যোগ করা যাবে"))
            } else {
                Extra.removeSiteDomain(this, d)
                go(3)
            }
        }
        r.addView(remove)
        c.addView(r, lp(MATCH, WRAP, t = dp(4)))
    }
    if (locked) {
        c.addView(
            tv(L.t("While a session runs, domains cannot be removed here — but new ones can be added below.", "সেশন চলাকালীন এখান থেকে সরানো যাবে না — তবে নিচে নতুন যোগ করা যাবে।"), 12f, C.warn),
            lp(MATCH, WRAP, t = dp(8))
        )
    }
    val add = outlineBtn(L.t("Add a domain", "ডোমেইন যোগ করুন"), C.primary)
    add.setOnClickListener { addSiteDomainDialog() }
    c.addView(add, lp(MATCH, dp(48), t = dp(10)))
    // keyword list (same rules as the domain list: add-only while a session runs)
    c.addView(
        tv(L.t("Blocked keywords", "ব্লক করা কিওয়ার্ড"), 15f, C.text),
        lp(MATCH, WRAP, t = dp(16))
    )
    c.addView(
        tv(L.t("Any one of these words anywhere in a URL blocks the site.", "এই শব্দগুলোর যেকোনো একটা URL-এর যেকোনো জায়গায় থাকলেই সাইট ব্লক হয়।"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(4))
    )
    // built-in keyword count (0 when the asset could not be read)
    val kwCount = KeywordBlock.builtInCount(this)
    if (kwCount > 0) {
        c.addView(
            tv(
                L.t(
                    "${L.num(String.format(Locale.US, "%,d", kwCount))} built-in bad keywords (Bengali + English) are also blocked automatically.",
                    "${L.num(String.format(Locale.US, "%,d", kwCount))}টি বিল্ট-ইন খারাপ কিওয়ার্ড (বাংলা + ইংরেজি) অটোমেটিক ব্লক হয়।"
                ),
                12f, C.primary
            ),
            lp(MATCH, WRAP, t = dp(4))
        )
        // keyword viewer: the built-in list, A to Z, with search — next to
        // the blocklist viewer above, so both viewers sit in this one card
        val viewKw = outlineBtn(L.t("View keywords", "কিওয়ার্ড দেখুন"), C.primary)
        viewKw.setOnClickListener {
            try {
                startActivity(Intent(this, KeywordListActivity::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "keyword viewer launch failed", e)
                toast(L.t("Could not open the keywords", "কিওয়ার্ড খোলা যায়নি"))
            }
        }
        c.addView(viewKw, lp(MATCH, dp(48), t = dp(10)))
    }
    val keywords = Extra.siteKeywords(this)
    for (k in keywords) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        r.addView(tv(k, 15f, C.text), lp(0, WRAP, weight = 1f))
        val remove = tv(L.t("Remove", "সরান"), 13f, if (locked) C.muted else C.warn)
        remove.setPadding(dp(8), dp(8), dp(8), dp(8))
        remove.pressFeedback()
        remove.setOnClickListener {
            if (Store.isActive(this)) {
                toast(L.t("Locked while a session is active — you can still add keywords", "সেশন চলাকালীন সরানো যাবে না — তবে নতুন কিওয়ার্ড যোগ করা যাবে"))
            } else {
                Extra.removeSiteKeyword(this, k)
                SiteBlockService.sync(this) // reload the list in the running VPN
                go(3)
            }
        }
        r.addView(remove)
        c.addView(r, lp(MATCH, WRAP, t = dp(4)))
    }
    if (locked) {
        c.addView(
            tv(L.t("While a session runs, keywords cannot be removed here — but new ones can be added below.", "সেশন চলাকালীন এখান থেকে সরানো যাবে না — তবে নিচে নতুন যোগ করা যাবে।"), 12f, C.warn),
            lp(MATCH, WRAP, t = dp(8))
        )
    }
    val addKw = outlineBtn(L.t("Add a keyword", "কিওয়ার্ড যোগ করুন"), C.primary)
    addKw.setOnClickListener { addSiteKeywordDialog() }
    c.addView(addKw, lp(MATCH, dp(48), t = dp(10)))
    return c
}

// =====================================================================
// UI: art pad trick lock — hides the site block card behind a drawing pad
// =====================================================================

/** A drawing is stored as 32 normalized points. Two drawings match when
 * their average point distance is small. No rotation normalisation: the
 * user draws the same way each time, and rotating would let a different
 * shape match. */
private const val ART_POINTS = 32
private const val ART_MATCH_DIST = 30f // on a 200x200 box
private const val ART_MIN_POINTS = 10

private fun artDist(a: PointF, b: PointF): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/** Resample a freehand path to exactly [n] evenly spaced points. */
private fun artResample(pts: List<PointF>, n: Int = ART_POINTS): List<PointF> {
    if (pts.isEmpty()) return emptyList()
    if (pts.size == 1) return List(n) { PointF(pts[0].x, pts[0].y) }
    val work = pts.toMutableList()
    var total = 0f
    for (i in 1 until work.size) total += artDist(work[i - 1], work[i])
    if (total <= 0f) return List(n) { PointF(work[0].x, work[0].y) }
    val step = total / (n - 1)
    val out = ArrayList<PointF>(n)
    out.add(PointF(work[0].x, work[0].y))
    var d = 0f
    var i = 1
    while (out.size < n - 1 && i < work.size) {
        val prev = work[i - 1]
        val cur = work[i]
        val seg = artDist(prev, cur)
        if (seg == 0f) {
            i++
            continue
        }
        if (d + seg >= step) {
            val t = (step - d) / seg
            val np = PointF(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
            out.add(np)
            work.add(i, np)
            d = 0f
            i++
        } else {
            d += seg
            i++
        }
    }
    while (out.size < n) out.add(PointF(work.last().x, work.last().y))
    return out
}

/** Move the centroid to (0,0) and scale the bounding box to 200x200. */
private fun artNormalize(pts: List<PointF>): List<PointF> {
    if (pts.isEmpty()) return pts
    var cx = 0f
    var cy = 0f
    for (p in pts) {
        cx += p.x
        cy += p.y
    }
    cx /= pts.size
    cy /= pts.size
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in pts) {
        val x = p.x - cx
        val y = p.y - cy
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
    val s = 200f / maxOf(maxX - minX, maxY - minY, 1f)
    return pts.map { PointF((it.x - cx) * s, (it.y - cy) * s) }
}

private fun artEncode(pts: List<PointF>): String =
    pts.joinToString(";") { "${String.format(Locale.US, "%.1f", it.x)},${String.format(Locale.US, "%.1f", it.y)}" }

private fun artDecode(s: String): List<PointF> {
    val out = ArrayList<PointF>()
    for (part in s.split(";")) {
        val xy = part.split(",")
        if (xy.size != 2) continue
        val x = xy[0].toFloatOrNull() ?: continue
        val y = xy[1].toFloatOrNull() ?: continue
        out.add(PointF(x, y))
    }
    return out
}

/** Average point distance between a fresh drawing and the stored secret. */
private fun artMatchScore(drawn: List<PointF>, secret: List<PointF>): Float {
    if (drawn.size != ART_POINTS || secret.size != ART_POINTS) return Float.MAX_VALUE
    var sum = 0f
    for (i in drawn.indices) sum += artDist(drawn[i], secret[i])
    return sum / ART_POINTS
}

/** A plain drawing pad. Reports the accumulated points on every finger-up;
 * the caller decides what a drawing means (set the secret, or unlock). */
private class ArtPadView(ctx: Context) : View(ctx) {
    var onStrokeDone: ((List<PointF>) -> Unit)? = null
    private val paths = ArrayList<Path>()
    private var curPath: Path? = null
    private val pts = ArrayList<PointF>()
    private val paint = Paint().apply {
        color = C.primary
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    fun clear() {
        paths.clear()
        curPath = null
        pts.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in paths) canvas.drawPath(p, paint)
        curPath?.let { canvas.drawPath(it, paint) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                curPath = Path().apply { moveTo(e.x, e.y) }
                pts.add(PointF(e.x, e.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val p = curPath ?: return true
                for (h in 0 until e.historySize) {
                    val hx = e.getHistoricalX(h)
                    val hy = e.getHistoricalY(h)
                    p.lineTo(hx, hy)
                    pts.add(PointF(hx, hy))
                }
                p.lineTo(e.x, e.y)
                pts.add(PointF(e.x, e.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                curPath?.let { paths.add(it) }
                curPath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    onStrokeDone?.invoke(pts.toList())
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/**
 * Art pad trick lock: a harmless-looking drawing pad that stands where the
 * site block card used to be. First use: draw your secret art, then draw
 * it again to confirm — that sets the secret. After that, drawing the same
 * art brings the site block section back; anything else just looks like a
 * doodle, so nobody can tell the section is hidden behind it.
 */
fun MainActivity.artPadCard(): View {
    val c = card()
    c.addView(section(L.t("Art pad", "আর্ট প্যাড")))
    val secret = Extra.artSecret(this)
    val status = tv(
        if (secret == null) L.t("Draw your secret art, then draw it again to set it.", "আপনার গোপন আর্ট আঁকুন, তারপর আবার আঁকুন।")
        else L.t("Draw here.", "এখানে আঁকুন।"),
        12f, C.muted
    )
    c.addView(status, lp(MATCH, WRAP, t = dp(4)))
    val pad = ArtPadView(this)
    pad.background = rounded(C.card2, dp(12).toFloat())
    c.addView(pad, lp(MATCH, dp(220), t = dp(8)))
    var pending: List<PointF>? = null
    pad.onStrokeDone = { raw ->
        if (raw.size < ART_MIN_POINTS) {
            // too short to be a drawing (a tap) — ignore it
            pad.clear()
        } else {
            val drawn = artNormalize(artResample(raw))
            val saved = Extra.artSecret(this)
            if (saved == null) {
                // setup: first draw, then confirm with the same drawing
                val first = pending
                if (first == null) {
                    pending = drawn
                    pad.clear()
                    toast(L.t("Draw the same art again to set it", "আবার একই আর্ট আঁকুন"))
                } else {
                    val score = artMatchScore(drawn, first)
                    pending = null
                    pad.clear()
                    if (score < ART_MATCH_DIST) {
                        Extra.setArtSecret(this, artEncode(drawn))
                        status.text = L.t("Draw here.", "এখানে আঁকুন।")
                        toast(L.t("Secret art set", "গোপন আর্ট সেট হয়েছে"))
                    } else {
                        toast(L.t("Did not match — draw your art again", "মিলেনি — আবার আঁকুন"))
                    }
                }
            } else {
                val secretPts = artDecode(saved)
                if (secretPts.size == ART_POINTS && artMatchScore(drawn, secretPts) < ART_MATCH_DIST) {
                    Extra.setArtRevealed(this, true)
                    go(3)
                }
                // wrong art: nothing happens — it just looks like a doodle
            }
        }
    }
    val clear = outlineBtn(L.t("Clear", "মুছুন"), C.muted)
    clear.setOnClickListener {
        pending = null
        pad.clear()
    }
    c.addView(clear, lp(MATCH, dp(48), t = dp(10)))
    return c
}

private fun MainActivity.addSiteDomainDialog() {
    val et = edit(L.t("example.com", "example.com"), false)
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(et, lp(MATCH, WRAP))
    body.addView(
        tv(L.t("Subdomains are blocked too (mail.example.com as well as example.com).", "সাবডোমেইনও ব্লক হয় (example.com-এর সঙ্গে mail.example.com-ও)।"), 13f, C.muted),
        lp(MATCH, WRAP, t = dp(10))
    )
    dlg()
        .setTitle(L.t("Block a website", "ওয়েবসাইট ব্লক করুন"))
        .setView(body)
        .setPositiveButton(L.t("Add", "যোগ করুন")) { _, _ ->
            val raw = et.text.toString()
            // the blocklist check reads a 1.8 MB asset: run it off the UI thread
            val t = Thread({
                val d = Extra.normalizeDomain(raw)
                if (d == null) {
                    runOnUiThread { toast(L.t("Not a valid domain", "সঠিক ডোমেইন নয়")) }
                    return@Thread
                }
                if (d in Extra.siteDomains(this)) {
                    runOnUiThread { toast(L.t("Already in your list", "তোমার তালিকায় আগেই আছে")) }
                    return@Thread
                }
                val inBlocklist = try {
                    SiteBlockService.isBlockedByBuiltInList(this, d)
                } catch (e: Exception) {
                    Log.e(TAG, "blocklist check failed", e)
                    false
                }
                runOnUiThread {
                    if (inBlocklist) {
                        toast(L.t("This site is already in the blocklist", "এই সাইট আগে থেকেই ব্লকলিস্টে আছে"))
                    } else if (Extra.addSiteDomain(this, d)) {
                        SiteBlockService.sync(this) // reload the list in the running VPN
                        toast(L.t("Domain added", "ডোমেইন যোগ হয়েছে"))
                        go(3)
                    } else {
                        toast(L.t("Not a valid domain, or already in the list", "সঠিক ডোমেইন নয়, অথবা তালিকায় আগেই আছে"))
                    }
                }
            }, "siteblock-add-check")
            t.isDaemon = true
            t.start()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.addSiteKeywordDialog() {
    val et = edit(L.t("keyword", "কিওয়ার্ড"), false)
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(et, lp(MATCH, WRAP))
    body.addView(
        tv(L.t("Any URL containing this word — anywhere in it — will be blocked during a lock.", "এই শব্দ থাকা যেকোনো URL — তার যেকোনো জায়গায় — লক চলাকালীন ব্লক হবে।"), 13f, C.muted),
        lp(MATCH, WRAP, t = dp(10))
    )
    dlg()
        .setTitle(L.t("Block a keyword", "কিওয়ার্ড ব্লক করুন"))
        .setView(body)
        .setPositiveButton(L.t("Add", "যোগ করুন")) { _, _ ->
            if (Extra.addSiteKeyword(this, et.text.toString())) {
                SiteBlockService.sync(this) // reload the list in the running VPN
                toast(L.t("Keyword added", "কিওয়ার্ড যোগ হয়েছে"))
                go(3)
            } else {
                toast(L.t("Empty, or already in the list", "খালি, অথবা তালিকায় আগেই আছে"))
            }
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

// =====================================================================
// UI: uninstall block (Settings)
// =====================================================================

/**
 * Uninstall block card: shows whether the device admin is active and offers
 * to enable it (system flow) or remove it. Removing is disabled while a
 * session runs — protections may only be strengthened during a lock.
 */
fun MainActivity.uninstallBlockCard(): View {
    val c = card()
    c.addView(section(L.t("Uninstall block", "আনইনস্টল ব্লক")))
    val locked = Store.isActive(this)
    val active = UninstallBlock.isActive(this)
    c.addView(
        row(
            if (active) L.t("Uninstall block is active", "আনইনস্টল ব্লক সক্রিয়")
            else L.t("Uninstall block is not active", "আনইনস্টল ব্লক সক্রিয় নয়"),
            if (active) L.t("The app cannot be uninstalled until the admin is removed.", "অ্যাডমিন না সরানো পর্যন্ত অ্যাপ আনইনস্টল করা যাবে না।")
            else L.t("The app can be uninstalled normally right now.", "এখন অ্যাপ সাধারণভাবে আনইনস্টল করা যাবে।"),
            tv(if (active) "✔" else "✘", 18f, if (active) C.primary else C.muted, 1)
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    c.addView(
        tv(
            L.t(
                "Enabling this makes PowerLock 2 a device admin (Android asks once). It requests no policies — it only stops uninstalls.",
                "এটি চালু করলে PowerLock 2 ডিভাইস অ্যাডমিন হবে (Android একবার জিজ্ঞেস করবে)। কোনো পলিসি চাওয়া হয় না — শুধু আনইনস্টল আটকায়।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    if (active) {
        val remove = outlineBtn(L.t("Remove admin", "অ্যাডমিন সরান"), C.warn)
        if (locked) remove.alpha = 0.4f
        remove.setOnClickListener {
            if (Store.isActive(this)) {
                toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
                return@setOnClickListener
            }
            dlg()
                .setTitle(L.t("Remove uninstall block?", "আনইনস্টল ব্লক সরাবেন?"))
                .setMessage(L.t("After this, the app can be uninstalled normally.", "এরপর অ্যাপ সাধারণভাবে আনইনস্টল করা যাবে।"))
                .setPositiveButton(L.t("Remove", "সরান")) { _, _ ->
                    if (UninstallBlock.remove(this)) {
                        toast(L.t("Uninstall block removed", "আনইনস্টল ব্লক সরানো হয়েছে"))
                    } else {
                        toast(L.t("Could not remove the admin — try in system Settings", "অ্যাডমিন সরানো যায়নি — সিস্টেম Settings থেকে চেষ্টা করুন"))
                    }
                    go(3)
                }
                .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                .show()
        }
        c.addView(remove, lp(MATCH, dp(48), t = dp(10)))
    } else {
        val enable = outlineBtn(L.t("Enable uninstall block", "আনইনস্টল ব্লক চালু করুন"), C.primary)
        enable.setOnClickListener {
            try {
                startActivity(UninstallBlock.enableIntent(this))
            } catch (e: Exception) {
                Log.e(TAG, "device admin enable failed", e)
                toast(L.t("Could not open the device admin screen", "ডিভাইস অ্যাডমিন স্ক্রিন খোলা যায়নি"))
            }
        }
        c.addView(enable, lp(MATCH, dp(48), t = dp(10)))
    }
    return c
}

// UI: uninstall protection for other apps (Settings) — instant, no lock needed.
// Pick the apps; if someone tries to uninstall one of them, the uninstall
// dialog is blocked the moment it appears (the service watches the
// installer, not the lock). This is separate from the device-admin
// uninstall block above, which only protects PowerLock 2 itself.
fun MainActivity.uninstallProtectCard(): View {
    val c = card()
    c.addView(section(L.t("Uninstall protection", "আনইনস্টল সুরক্ষা")))
    c.addView(
        tv(
            L.t(
                "If someone tries to uninstall a protected app, it is blocked instantly — no lock needed.",
                "সুরক্ষিত অ্যাপ আনইনস্টল করতে গেলে সাথে সাথে ব্লক হবে — লক লাগবে না।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    val count = Extra.uninstallProtectApps(this).size
    val countBn = L.num(count.toString())
    c.addView(
        row(
            L.t("Protected apps", "সুরক্ষিত অ্যাপ"),
            L.t("$countBn app(s) protected", "$countBn টি অ্যাপ সুরক্ষিত"),
            tv(countBn, 18f, C.primary, 1)
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    // Which apps cannot be uninstalled — the actual list, not just the count.
    val pkgs = Extra.uninstallProtectApps(this).sorted()
    if (pkgs.isEmpty()) {
        c.addView(
            tv(
                L.t("No app protected yet — choose apps below.", "এখনো কোনো অ্যাপ সুরক্ষিত নয় — নিচে থেকে বাছুন।"),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = dp(8))
        )
    } else {
        for (pkg in pkgs) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val icon = ImageView(this)
            try { icon.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { }
            row.addView(icon, lp(dp(40), dp(40), r = dp(12)))
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(tv(appLabelFor(pkg), 14f, C.text), lp(MATCH, WRAP))
            texts.addView(tv(pkg, 11f, C.muted), lp(MATCH, WRAP))
            row.addView(texts, lp(0, WRAP, weight = 1f))
            // Remove this app from the protected list.
            val remove = tv("✕", 18f, C.danger)
            remove.setPadding(dp(8), dp(8), dp(8), dp(8))
            remove.setOnClickListener {
                val newSet = Extra.uninstallProtectApps(this).toMutableSet()
                newSet.remove(pkg)
                Extra.setUninstallProtectApps(this, newSet)
                go(3)
            }
            row.addView(remove, lp(WRAP, WRAP, l = dp(8)))
            c.addView(row, lp(MATCH, WRAP, t = dp(8)))
        }
    }
    val pick = outlineBtn(L.t("Choose apps", "অ্যাপ বাছুন"), C.primary)
    pick.setOnClickListener {
        groupAppPicker(Extra.uninstallProtectApps(this)) { newSet ->
            Extra.setUninstallProtectApps(this, newSet)
            go(3)
        }
    }
    c.addView(pick, lp(MATCH, dp(48), t = dp(10)))
    return c
}

// =====================================================================
// UI: lock profiles (Settings)
// =====================================================================

private fun profileModeLabel(mode: String): String {
    return when (mode) {
        UNLOCK_LOC -> L.t("Location only", "শুধু লোকেশন")
        UNLOCK_EITHER -> L.t("Either one", "যেকোনো একটা")
        else -> L.t("Time only", "শুধু সময়")
    }
}

/** Saved lock setups: apply one to fill the Home lock setup, or create/edit/delete. */
fun MainActivity.lockProfilesCard(): View {
    val c = card()
    c.addView(section(L.t("Lock profiles", "লক প্রোফাইল")))
    val profiles = Extra.lockProfiles(this)
    if (profiles.isEmpty()) {
        c.addView(
            tv(L.t("Save a lock setup — duration, strict mode and unlock mode — and apply it in one tap.", "লক সেটআপ সংরক্ষণ করুন — সময়, স্ট্রিক্ট মোড ও খোলার শর্ত — এক চাপে চালু করুন।"), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(6))
        )
    }
    for (p in profiles) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(10), 0, dp(10))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(p.name, 15f, C.text, 1))
        val strictTxt = if (p.strict) L.t("Strict", "স্ট্রিক্ট") else L.t("Not strict", "স্ট্রিক্ট নয়")
        col.addView(tv(fmtDur(p.minutes * 60_000L) + " · " + strictTxt + " · " + profileModeLabel(p.mode), 12f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        val edit = tv(L.t("Edit", "এডিট"), 13f, C.muted)
        edit.setPadding(dp(8), dp(8), dp(8), dp(8))
        edit.pressFeedback()
        edit.setOnClickListener { profileEditorDialog(p) }
        r.addView(edit)
        val apply = tv(L.t("Apply", "চালু করুন"), 13f, C.mint, 1)
        apply.setPadding(dp(8), dp(8), dp(8), dp(8))
        apply.pressFeedback()
        apply.setOnClickListener {
            if (Store.isActive(this)) {
                toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
                return@setOnClickListener
            }
            if (!Extra.applyLockProfile(this, p)) {
                toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
                return@setOnClickListener
            }
            if (p.mode != UNLOCK_TIME && Store.settings(this).getString("unlock_dest", null) == null) {
                toast(L.t("Profile applied — pick a destination on the map before starting", "প্রোফাইল চালু হয়েছে — শুরু করার আগে ম্যাপে গন্তব্য বাছুন"))
            } else {
                toast(L.t("Profile applied", "প্রোফাইল চালু হয়েছে"))
            }
            go(0)
        }
        r.addView(apply)
        c.addView(r, lp(MATCH, WRAP, t = dp(4)))
    }
    val add = outlineBtn(L.t("New profile", "নতুন প্রোফাইল"), C.primary)
    add.setOnClickListener {
        if (Extra.lockProfiles(this).size >= Extra.MAX_PROFILES) {
            toast(L.t("At most ${Extra.MAX_PROFILES} profiles", "সর্বোচ্চ ${Extra.MAX_PROFILES}টি প্রোফাইল"))
            return@setOnClickListener
        }
        profileEditorDialog(null)
    }
    c.addView(add, lp(MATCH, dp(48), t = dp(10)))
    return c
}

/** Create-or-edit dialog for one profile. [existing] is null for a new profile. */
private fun MainActivity.profileEditorDialog(existing: LockProfile?) {
    val nameEt = edit(L.t("Profile name", "প্রোফাইলের নাম"), false)
    nameEt.setText(existing?.name ?: "")
    val minEt = edit(L.t("Minutes (1–21600)", "মিনিট (১–২১৬০০)"), true)
    minEt.setText((existing?.minutes ?: 30).toString())
    var strictVal = existing?.strict ?: false
    var modeVal = existing?.mode ?: UNLOCK_TIME
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(nameEt, lp(MATCH, WRAP))
    body.addView(minEt, lp(MATCH, WRAP, t = dp(10)))
    val strictRow = LinearLayout(this)
    strictRow.gravity = Gravity.CENTER_VERTICAL
    strictRow.setPadding(0, dp(14), 0, dp(6))
    strictRow.addView(tv(L.t("Strict mode", "স্ট্রিক্ট মোড"), 15f, C.text, 1), lp(0, WRAP, weight = 1f))
    strictRow.addView(toggle(strictVal) { v -> strictVal = v })
    body.addView(strictRow)
    body.addView(tv(L.t("When does the lock open?", "লক খুলবে কখন?"), 13f, C.muted, 1), lp(MATCH, WRAP, t = dp(10)))
    val modeRow = LinearLayout(this)
    val modeChips = ArrayList<TextView>()
    val modes = listOf(UNLOCK_TIME, UNLOCK_LOC, UNLOCK_EITHER)
    for ((i, m) in modes.withIndex()) {
        val ch = chip(profileModeLabel(m), m == modeVal)
        ch.setOnClickListener {
            modeVal = m
            for ((j, other) in modeChips.withIndex()) styleChip(other, modes[j] == modeVal)
        }
        modeChips.add(ch)
        modeRow.addView(ch, lp(0, WRAP, r = if (i < 2) dp(8) else 0, t = dp(8), weight = 1f))
    }
    body.addView(modeRow)
    val d = dlg()
        .setTitle(if (existing == null) L.t("New profile", "নতুন প্রোফাইল") else L.t("Edit profile", "প্রোফাইল এডিট"))
        .setView(body)
        .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .create()
    if (existing != null) {
        d.setButton(AlertDialog.BUTTON_NEUTRAL, L.t("Delete", "মুছুন")) { _, _ ->
            Extra.deleteLockProfile(this, existing.id)
            go(3)
        }
    }
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameEt.text.toString().trim()
        val min = minEt.text.toString().toIntOrNull() ?: 0
        if (name.isEmpty()) {
            nameEt.error = L.t("Give the profile a name", "প্রোফাইলের নাম দিন")
            return@setOnClickListener
        }
        if (min < 1 || min > MAX_MIN) {
            minEt.error = L.t("1 minute to 15 days", "১ মিনিট থেকে ১৫ দিন")
            return@setOnClickListener
        }
        if (existing == null) {
            if (Extra.addLockProfile(this, name, min, strictVal, modeVal) == null) {
                toast(L.t("At most ${Extra.MAX_PROFILES} profiles", "সর্বোচ্চ ${Extra.MAX_PROFILES}টি প্রোফাইল"))
                return@setOnClickListener
            }
        } else {
            Extra.updateLockProfile(this, existing.id, name, min, strictVal, modeVal)
        }
        d.dismiss()
        go(3)
    }
}

// =====================================================================
// UI: focus mode (Settings)
// =====================================================================

fun MainActivity.focusCard(): View {
    val c = card()
    c.addView(section(L.t("Focus mode", "ফোকাস মোড")))
    val st = Store.settings(this)
    val locked = Store.isActive(this)
    val sw = toggle(st.getBoolean("focus_on", false)) { v -> st.edit().putBoolean("focus_on", v).apply() }
    if (locked) sw.isEnabled = false
    c.addView(
        row(
            L.t("Block selected apps", "নির্বাচিত অ্যাপ ব্লক"),
            L.t("While the lock runs, these apps jump straight back to Home.", "লক চলাকালীন এই অ্যাপ খুললেই সঙ্গে সঙ্গে হোমে ফিরে যাবে।"),
            sw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val count = Extra.focusApps(this).size
    val pick = outlineBtn(L.t("Choose apps ($count selected)", "অ্যাপ বাছুন ($count টি নির্বাচিত)"), C.primary)
    pick.setOnClickListener {
        if (Store.isActive(this)) {
            toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
        } else {
            appPicker { go(3) }
        }
    }
    c.addView(pick, lp(MATCH, dp(48), t = dp(10)))
    c.addView(
        tv(
            if (locked) L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
            else L.t("Phone, dialer and emergency apps are never listed. Changes apply from the next session.", "ফোন, ডায়ালার ও জরুরি অ্যাপ তালিকায় আসে না। পরিবর্তন পরের সেশন থেকে কার্যকর।"),
            12f, if (locked) C.warn else C.muted
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

private fun MainActivity.appPicker(done: () -> Unit) {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN)
    intent.addCategory(Intent.CATEGORY_LAUNCHER)
    val skip = listOf("dialer", "incallui", "emergency", "telecom", "contacts")
    val apps = ArrayList<Triple<String, String, Drawable>>()
    val seen = HashSet<String>()
    try {
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            if (skip.any { pkg.contains(it) }) continue
            apps.add(Triple(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
        }
    } catch (e: Exception) {
        Log.e(TAG, "app list failed", e)
    }
    apps.sortBy { it.second.lowercase() }
    if (apps.isEmpty()) {
        toast(L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি"))
        return
    }
    val chosen = HashSet<String>(Extra.focusApps(this))
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    list.setPadding(dp(20), dp(4), dp(20), dp(4))
    for (info in apps) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        val icon = ImageView(this)
        icon.setImageDrawable(info.third)
        r.addView(icon, lp(dp(40), dp(40), r = dp(14)))
        r.addView(tv(info.second, 15f, C.text), lp(0, WRAP, weight = 1f))
        val pkg = info.first
        r.addView(toggle(chosen.contains(pkg)) { v ->
            if (v) chosen.add(pkg) else chosen.remove(pkg)
        })
        list.addView(r)
    }
    val sv = ScrollView(this)
    sv.addView(list)
    dlg()
        .setTitle(L.t("Choose apps to block", "ব্লক করার অ্যাপ বাছুন"))
        .setView(sv)
        .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
            Extra.setFocusApps(this, chosen)
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

// =====================================================================
// UI: app block (Protection)
// =====================================================================

/** Master switch + how app block works. */
fun MainActivity.protectionIntroCard(): View {
    val c = card()
    c.addView(section(L.t("App block", "অ্যাপ ব্লক")))
    val st = Store.settings(this)
    val locked = Store.isActive(this)
    val sw = toggle(Extra.appBlockOn(this)) { v -> st.edit().putBoolean("ab_on", v).apply() }
    if (locked) sw.isEnabled = false
    c.addView(
        row(
            L.t("Block apps while the lock runs", "লক চলাকালীন অ্যাপ ব্লক"),
            L.t("Choose apps below. Full block covers the app with a protection screen at once; Reels block only stops the Reels / Shorts feed; search block closes search as soon as you start typing and clears what was typed.", "নিচে অ্যাপ বাছুন। সম্পূর্ণ ব্লক সঙ্গে সঙ্গে সুরক্ষা স্ক্রিন দেখায়; রিলস ব্লক শুধু রিলস / শর্টস ফিড আটকায়; সার্চ ব্লক লিখতে শুরু করলেই সার্চ বন্ধ করে লেখা মুছে দেয়।"),
            sw
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val accOn = Perm.accessibilityOn(this)
    c.addView(
        tv(
            when {
                locked -> L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
                !accOn -> L.t("Accessibility is off — app block needs it. Turn it on from Settings.", "Accessibility বন্ধ — অ্যাপ ব্লকের জন্য এটা লাগবে। সেটিংস থেকে চালু করুন।")
                else -> L.t("Blocks apply while a lock session (or location lock) is running.", "লক সেশন (বা লোকেশন লক) চলাকালীন ব্লক কাজ করবে।")
            },
            12f, if (locked || !accOn) C.warn else C.muted
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

/** One mode card: [mode] is "full", "reels" or "msg". */
fun MainActivity.appBlockModeCard(mode: String): View {
    val c = card()
    c.addView(
        section(
            when (mode) {
                "full" -> L.t("Full app block", "সম্পূর্ণ অ্যাপ ব্লক")
                "msg" -> L.t("Search block", "সার্চ ব্লক")
                else -> L.t("Reels block", "রিলস ব্লক")
            }
        )
    )
    c.addView(
        tv(
            when (mode) {
                "full" -> L.t("Opening a selected app instantly shows a protection screen — the app stays covered.", "নির্বাচিত অ্যাপ খুললেই সঙ্গে সঙ্গে সুরক্ষা স্ক্রিন আসবে — অ্যাপের ভেতরে ঢোকা যাবে না।")
                "msg" -> L.t("Search in a selected app opens normally, but as soon as you start typing it closes and clears what was typed — the app itself stays usable. Searching inside a chat or channel is not blocked. Choose any app, e.g. Telegram or WhatsApp.", "নির্বাচিত অ্যাপে সার্চ খুলবে, কিন্তু লিখতে শুরু করলেই তা বন্ধ হয়ে যাবে আর লেখা মুছে যাবে — অ্যাপ ব্যবহার করা যাবে। চ্যাট বা চ্যানেলের ভিতরে খুঁজলে ব্লক হবে না। যেকোনো অ্যাপ বাছা যাবে, যেমন Telegram বা WhatsApp।")
                else -> L.t("The app stays usable, but its Reels / Shorts feed is closed. Only apps with Reels, Shorts or Status are listed.", "অ্যাপ ব্যবহার করা যাবে, কিন্তু এর রিলস / শর্টস ফিড বন্ধ হয়ে যাবে। শুধু রিলস, শর্টস বা স্ট্যাটাস আছে এমন অ্যাপ তালিকায় আসবে।")
            },
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val count = when (mode) {
        "full" -> Extra.appBlockFull(this).size
        "msg" -> Extra.appBlockMsg(this).size
        else -> Extra.appBlockReels(this).size
    }
    val locked = Store.isActive(this)
    val pick = outlineBtn(L.t("Choose apps ($count selected)", "অ্যাপ বাছুন ($count টি নির্বাচিত)"), C.primary)
    pick.setOnClickListener {
        // adding only strengthens the lock, so it stays allowed during a
        // session — the picker just won't let an already-blocked app be unchecked
        appBlockPicker(mode, Store.isActive(this)) { go(4) }
    }
    c.addView(pick, lp(MATCH, dp(48), t = dp(10)))
    val pkgBtn = outlineBtn(L.t("Add by package name", "প্যাকেজ নাম দিয়ে অ্যাড করুন"), C.primary)
    pkgBtn.setOnClickListener { addPackageDialog(mode) { go(4) } }
    c.addView(pkgBtn, lp(MATCH, dp(48), t = dp(8)))
    if (locked) {
        c.addView(tv(L.t("While a session runs, apps can only be added, not removed.", "সেশন চলাকালীন শুধু অ্যাপ যোগ করা যাবে, সরানো যাবে না।"), 12f, C.warn), lp(MATCH, WRAP, t = dp(8)))
    }
    return c
}

/** Whitelist mode card (Protection tab): during a lock only the chosen apps
 * open — everything else is blocked. The inverse of the full app block, and
 * stricter: instead of picking apps to block, you pick the few apps that
 * stay allowed. The toggle and the picker are locked while a session runs
 * (adding an app mid-session would weaken the lock), so change the list
 * before starting a lock. */
fun MainActivity.whitelistCard(): View {
    val c = card()
    c.addView(section(L.t("Whitelist mode", "হোয়াইটলিস্ট মোড")))
    c.addView(
        tv(
            L.t("During a lock, only the apps you choose can open — every other app is blocked. This is stricter than the app block list: instead of picking apps to block, you pick the few apps that stay allowed. The home screen, the phone dialer and PowerLock itself always stay allowed, so you can always make calls and stop the lock.",
                "লক চলাকালীন শুধু তোমার বাছা অ্যাপগুলোই খুলবে — বাকি সব অ্যাপ ব্লক হবে। এটা অ্যাপ ব্লক লিস্টের উল্টো আর আরও কঠোর: ব্লক করার অ্যাপ বাছার বদলে শুধু যে কয়টা অ্যাপ চালু থাকবে সেগুলো বাছো। হোম স্ক্রিন, ফোন ডায়ালার আর PowerLock নিজে সবসময় চালু থাকে, তাই কল করা আর লক বন্ধ করা সবসময় যাবে।"),
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val locked = Store.isActive(this)
    val row = LinearLayout(this)
    row.gravity = Gravity.CENTER_VERTICAL
    row.addView(tv(L.t("Whitelist mode", "হোয়াইটলিস্ট মোড"), 16f, C.text, 1), lp(0, WRAP, weight = 1f))
    val sw = toggle(Extra.whitelistOn(this)) { v ->
        Store.settings(this).edit().putBoolean("whitelist_on", v).apply()
        go(4)
    }
    if (locked) sw.isEnabled = false
    row.addView(sw)
    c.addView(row, lp(MATCH, WRAP, t = dp(10)))
    val count = Extra.whitelistApps(this).size
    val pick = outlineBtn(L.t("Choose allowed apps ($count selected)", "চালু থাকবে এমন অ্যাপ বাছুন ($count টি নির্বাচিত)"), C.primary)
    if (locked) pick.isEnabled = false
    pick.setOnClickListener {
        groupAppPicker(Extra.whitelistApps(this)) { newSet ->
            Extra.setWhitelistApps(this, newSet)
            go(4)
        }
    }
    c.addView(pick, lp(MATCH, dp(48), t = dp(10)))
    // The chosen apps, listed under the whitelist with their package names
    // (build 119, user request) — so it is visible which apps stay allowed.
    val wlPkgs = Extra.whitelistApps(this).sorted()
    if (wlPkgs.isNotEmpty()) {
        c.addView(tv(L.t("Allowed apps", "চালু থাকবে এমন অ্যাপ"), 14f, C.text), lp(MATCH, WRAP, t = dp(12)))
        for (pkg in wlPkgs) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val icon = ImageView(this)
            try { icon.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { }
            row.addView(icon, lp(dp(40), dp(40), r = dp(12)))
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(tv(appLabelFor(pkg), 14f, C.text), lp(MATCH, WRAP))
            texts.addView(tv(pkg, 11f, C.muted), lp(MATCH, WRAP))
            row.addView(texts, lp(0, WRAP, weight = 1f))
            // Remove this app from the whitelist (not during a session).
            val remove = tv("✕", 18f, C.danger)
            remove.setPadding(dp(8), dp(8), dp(8), dp(8))
            if (locked) remove.alpha = 0.3f
            remove.setOnClickListener {
                if (locked) return@setOnClickListener
                val newSet = Extra.whitelistApps(this).toMutableSet()
                newSet.remove(pkg)
                Extra.setWhitelistApps(this, newSet)
                go(4)
            }
            row.addView(remove, lp(WRAP, WRAP, l = dp(8)))
            c.addView(row, lp(MATCH, WRAP, t = dp(8)))
        }
    }
    if (count == 0) {
        c.addView(tv(L.t("No apps chosen yet — only the home screen and the phone dialer will work during a lock.", "এখনো কোনো অ্যাপ বাছা হয়নি — লক চলাকালীন শুধু হোম স্ক্রিন আর ফোন ডায়ালার চলবে।"), 12f, C.warn), lp(MATCH, WRAP, t = dp(8)))
    }
    if (locked) {
        c.addView(tv(L.t("While a session runs, the whitelist cannot be changed.", "সেশন চলাকালীন হোয়াইটলিস্ট বদলানো যাবে না।"), 12f, C.warn), lp(MATCH, WRAP, t = dp(8)))
    }
    return c
}

/** Install allowlist (build 120): packages that may be installed or updated
 * even during a lock. Shown under the install-block toggle. PowerLock
 * itself is never allowed here — updating it mid-lock would remove the lock. */
fun MainActivity.installAllowCard(): View {
    val c = card()
    // Build 121: the allowlist cannot be changed during a session — adding
    // a package mid-lock would let it be installed, and removing one would
    // weaken the install block. Same rule as the whitelist card.
    val locked = Store.isActive(this)
    c.addView(section(L.t("Install allowlist", "ইনস্টল অনুমতি তালিকা")))
    c.addView(
        tv(
            L.t("Packages listed here can be installed or updated even during a lock. Play Store installs are always allowed. PowerLock itself can never be added — updating it during a lock would remove the lock.",
                "এখানে থাকা প্যাকেজগুলো লক চলাকালীনও ইনস্টল বা আপডেট করা যাবে। Play Store থেকে ইনস্টল সবসময় চালু থাকে। PowerLock নিজেকে এখানে অ্যাড করা যাবে না — লক চলাকালীন ওটা আপডেট করলে লকটাই শেষ হয়ে যাবে।"),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    val pkgs = Extra.installAllowApps(this).sorted()
    if (pkgs.isEmpty()) {
        c.addView(tv(L.t("No package added yet.", "এখনো কোনো প্যাকেজ অ্যাড করা হয়নি।"), 12f, C.muted), lp(MATCH, WRAP, t = dp(8)))
    } else {
        for (pkg in pkgs) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val icon = ImageView(this)
            try { icon.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { }
            row.addView(icon, lp(dp(40), dp(40), r = dp(12)))
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(tv(appLabelFor(pkg), 14f, C.text), lp(MATCH, WRAP))
            texts.addView(tv(pkg, 11f, C.muted), lp(MATCH, WRAP))
            row.addView(texts, lp(0, WRAP, weight = 1f))
            val remove = tv("✕", 18f, C.danger)
            remove.setPadding(dp(8), dp(8), dp(8), dp(8))
            if (locked) remove.alpha = 0.3f
            remove.setOnClickListener {
                if (locked) return@setOnClickListener
                val newSet = Extra.installAllowApps(this).toMutableSet()
                newSet.remove(pkg)
                Extra.setInstallAllowApps(this, newSet)
                go(4)
            }
            row.addView(remove, lp(WRAP, WRAP, l = dp(8)))
            c.addView(row, lp(MATCH, WRAP, t = dp(8)))
        }
    }
    val add = outlineBtn(L.t("Add by package name", "প্যাকেজ নাম দিয়ে অ্যাড করুন"), C.primary)
    if (locked) add.isEnabled = false
    add.setOnClickListener {
        if (locked) return@setOnClickListener
        val input = edit(L.t("Package name, e.g. com.example.app", "প্যাকেজ নাম, যেমন com.example.app"), false)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(12), dp(20), dp(4))
        body.addView(input, lp(MATCH, WRAP))
        dlg()
            .setTitle(L.t("Add package", "প্যাকেজ অ্যাড করুন"))
            .setView(body)
            .setPositiveButton(L.t("Add", "যোগ করুন")) { _, _ ->
                val pkg = input.text.toString().trim().lowercase()
                if (pkg.isEmpty()) { toast(L.t("Type a package name", "প্যাকেজ নাম লিখুন")); return@setPositiveButton }
                if (pkg == packageName) { toast(L.t("PowerLock itself cannot be added", "PowerLock নিজেকে অ্যাড করা যাবে না")); return@setPositiveButton }
                val newSet = Extra.installAllowApps(this).toMutableSet()
                newSet.add(pkg)
                Extra.setInstallAllowApps(this, newSet)
                go(4)
            }
            .setNegativeButton(L.t("Cancel", "বাতিল করুন"), null)
            .show()
    }
    c.addView(add, lp(MATCH, dp(48), t = dp(10)))
    return c
}

/** Add a package name by typing it — the app need not be installed yet, it is
 * blocked as soon as it is installed. [mode] is "full", "reels" or "msg". */
private fun MainActivity.addPackageDialog(mode: String, done: () -> Unit) {
    val input = edit(L.t("Package name, e.g. com.facebook.katana", "প্যাকেজ নাম, যেমন com.facebook.katana"), false)
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(input, lp(MATCH, WRAP))
    body.addView(
        tv(L.t("The app does not need to be installed yet — once it is installed, the block applies automatically.", "অ্যাপ এখন ইনস্টল না থাকলেও চলবে — ইনস্টল করলেই ব্লক কাজ করবে।"), 13f, C.muted),
        lp(MATCH, WRAP, t = dp(10))
    )
    dlg()
        .setTitle(L.t("Add by package name", "প্যাকেজ নাম দিয়ে অ্যাড করুন"))
        .setView(body)
        .setPositiveButton(L.t("Add", "যোগ করুন")) { _, _ ->
            val pkg = input.text.toString().trim().lowercase()
            if (pkg.isEmpty()) {
                toast(L.t("Type a package name", "প্যাকেজ নাম লিখুন"))
                return@setPositiveButton
            }
            if (!pkg.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$"))) {
                toast(L.t("Not a valid package name", "সঠিক প্যাকেজ নাম নয়"))
                return@setPositiveButton
            }
            // an app can only be in one list: full block wins over reels block;
            // the search list is independent
            when (mode) {
                "full" -> {
                    Extra.setAppBlockFull(this, Extra.appBlockFull(this) + pkg)
                    Extra.setAppBlockReels(this, Extra.appBlockReels(this) - pkg)
                }
                "msg" -> Extra.setAppBlockMsg(this, Extra.appBlockMsg(this) + pkg)
                else -> {
                    Extra.setAppBlockReels(this, Extra.appBlockReels(this) + pkg)
                    Extra.setAppBlockFull(this, Extra.appBlockFull(this) - pkg)
                }
            }
            Extra.setManualPackages(this, Extra.manualPackages(this) + pkg)
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.appBlockPicker(mode: String, addOnly: Boolean, done: () -> Unit) {
    // the app list (with icons) is loaded once per session on a background
    // thread: loading ~150 icons on the main thread froze the UI
    val cached = Extra.appListCache
    if (cached != null) {
        showAppBlockPicker(mode, cached, addOnly, done)
        return
    }
    val loading = dlg()
        .setTitle(L.t("Loading apps…", "অ্যাপ লোড হচ্ছে…"))
        .setView(tv(L.t("Please wait…", "একটু অপেক্ষা করুন…"), 14f, C.muted).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
        })
        .setCancelable(false)
        .show()
    Thread {
        val apps = loadLaunchableApps()
        runOnUiThread {
            try { loading.dismiss() } catch (_: Exception) { }
            if (apps.isEmpty()) {
                toast(L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি"))
                return@runOnUiThread
            }
            Extra.appListCache = apps
            showAppBlockPicker(mode, apps, addOnly, done)
        }
    }.start()
}

/** All launchable apps (label + icon), sorted by name. Runs off the main thread. */
private fun MainActivity.loadLaunchableApps(): List<Triple<String, String, Drawable>> {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN)
    intent.addCategory(Intent.CATEGORY_LAUNCHER)
    val skip = listOf("dialer", "incallui", "emergency", "telecom", "contacts")
    val apps = ArrayList<Triple<String, String, Drawable>>()
    val seen = HashSet<String>()
    try {
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            if (skip.any { pkg.contains(it) }) continue
            apps.add(Triple(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
        }
    } catch (e: Exception) {
        Log.e(TAG, "app list failed", e)
    }
    apps.sortBy { it.second.lowercase() }
    return apps
}

private fun MainActivity.showAppBlockPicker(
    mode: String, allApps: List<Triple<String, String, Drawable>>, addOnly: Boolean, done: () -> Unit
) {
    // Reels block is only offered for apps that actually have Reels / Shorts / Status;
    // search block lists every app, so all Telegram-type apps (and any other) show up
    val apps = if (mode == "reels") allApps.filter { it.first in Extra.reelsPackages } else allApps
    if (apps.isEmpty()) {
        toast(
            when (mode) {
                "reels" -> L.t("No app with Reels / Shorts / Status found", "রিলস / শর্টস / স্ট্যাটাস আছে এমন কোনো অ্যাপ পাওয়া যায়নি")
                else -> L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি")
            }
        )
        return
    }
    val chosen = HashSet<String>(
        when (mode) {
            "full" -> Extra.appBlockFull(this)
            "msg" -> Extra.appBlockMsg(this)
            else -> Extra.appBlockReels(this)
        }
    )
    var query = ""
    // rows are built once; the search filter only toggles visibility (fast typing)
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    val rows = ArrayList<Pair<View, String>>() // (row, lowercase label)
    for (info in apps) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        val icon = ImageView(this)
        icon.setImageDrawable(info.third)
        r.addView(icon, lp(dp(40), dp(40), r = dp(14)))
        r.addView(tv(info.second, 15f, C.text), lp(0, WRAP, weight = 1f))
        val pkg = info.first
        val wasChosen = chosen.contains(pkg)
        val sw = toggle(wasChosen) { v ->
            if (v) chosen.add(pkg) else if (!(addOnly && wasChosen)) chosen.remove(pkg)
        }
        if (addOnly && wasChosen) {
            // add-only during a lock: an already-blocked app cannot be unchecked
            sw.isEnabled = false
            r.alpha = 0.6f
        }
        r.addView(sw)
        list.addView(r)
        rows.add(r to info.second.lowercase())
    }
    val emptyView = tv(L.t("No matching apps", "মিলছে এমন কোনো অ্যাপ নেই"), 13f, C.muted)
    list.addView(emptyView, lp(MATCH, WRAP, t = dp(8)))
    emptyView.visibility = View.GONE
    // A-Z fast-scroll index beside the list: touch or drag a letter to jump
    // to the first app starting with it (only for long lists, and hidden
    // while searching)
    val showIndex = apps.size > 20
    val lettersCol = LinearLayout(this)
    lettersCol.orientation = LinearLayout.VERTICAL
    lettersCol.gravity = Gravity.CENTER
    val letters = ('A'..'Z').toList()
    val letterViews = ArrayList<TextView>()
    for (ch in letters) {
        val lt = tv(ch.toString(), 10f, C.muted)
        lt.gravity = Gravity.CENTER
        lt.setPadding(0, dp(1), 0, dp(1))
        lettersCol.addView(lt, lp(MATCH, WRAP))
        letterViews.add(lt)
    }
    fun applyFilter() {
        val q = query.trim().lowercase()
        var shown = 0
        for ((row, label) in rows) {
            val vis = q.isEmpty() || label.contains(q)
            row.visibility = if (vis) View.VISIBLE else View.GONE
            if (vis) shown++
        }
        emptyView.visibility = if (shown == 0) View.VISIBLE else View.GONE
        lettersCol.visibility = if (showIndex && q.isEmpty()) View.VISIBLE else View.GONE
    }
    val sv = ScrollView(this)
    sv.addView(list)
    // smooth animated scroll to a letter (a motion animation, not a jump):
    // the previous animation is cancelled so a fast drag chases the finger
    var scrollAnimator: ObjectAnimator? = null
    // animate on a tap (smooth motion); instant while dragging so the list
    // follows the finger in real time instead of lagging behind it
    fun jumpToLetter(ch: Char, animate: Boolean) {
        // first app whose name starts with this letter (or the next letter that has apps)
        val target = ch.lowercaseChar()
        for ((row, label) in rows) {
            val first = label.firstOrNull() ?: continue
            if (first >= target) {
                scrollAnimator?.cancel()
                scrollAnimator = null
                if (animate) {
                    scrollAnimator = ObjectAnimator.ofInt(sv, "scrollY", row.top).apply {
                        duration = 200
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                } else {
                    sv.scrollTo(0, row.top)
                }
                return
            }
        }
    }
    // the letter under the finger pops a bit bigger (animated) while touched
    var enlargedIdx = -1
    fun setLetterPop(idx: Int, popped: Boolean) {
        val v = letterViews.getOrNull(idx) ?: return
        v.setTextColor(if (popped) C.primary else C.muted)
        val s = if (popped) 1.5f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
    }
    // big letter bubble in the middle while dragging the index (like Contacts)
    val bubble = tv("", 30f, C.onPrimary)
    bubble.gravity = Gravity.CENTER
    bubble.background = rounded(C.primary, dp(32).toFloat())
    bubble.visibility = View.GONE
    bubble.alpha = 0f
    fun showBubble(ch: Char) {
        bubble.text = ch.toString()
        if (bubble.visibility != View.VISIBLE) {
            bubble.animate().cancel()
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
            bubble.scaleX = 0.6f
            bubble.scaleY = 0.6f
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }
    }
    fun hideBubble() {
        if (bubble.visibility != View.VISIBLE) return
        bubble.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150).withEndAction {
            bubble.visibility = View.GONE
        }.start()
    }
    lettersCol.setOnTouchListener { _, ev ->
        when (ev.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val h = lettersCol.height
                if (h > 0) {
                    val idx = ((ev.y / h) * letters.size).toInt().coerceIn(0, letters.size - 1)
                    if (idx != enlargedIdx) {
                        if (enlargedIdx >= 0) setLetterPop(enlargedIdx, false)
                        setLetterPop(idx, true)
                        enlargedIdx = idx
                    }
                    showBubble(letters[idx])
                    jumpToLetter(letters[idx], ev.action == MotionEvent.ACTION_DOWN)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (enlargedIdx >= 0) {
                    setLetterPop(enlargedIdx, false)
                    enlargedIdx = -1
                }
                hideBubble()
                true
            }
            else -> false
        }
    }
    val search = edit(L.t("Search apps…", "অ্যাপ খুঁজুন…"), false)
    search.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            query = s?.toString() ?: ""
            applyFilter()
        }
    })
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(search, lp(MATCH, WRAP, b = dp(8)))
    val contentRow = LinearLayout(this)
    contentRow.orientation = LinearLayout.HORIZONTAL
    contentRow.addView(sv, lp(0, MATCH, weight = 1f))
    contentRow.addView(lettersCol, lp(dp(22), MATCH, l = dp(4)))
    val contentFrame = FrameLayout(this)
    contentFrame.addView(contentRow, FrameLayout.LayoutParams(MATCH, MATCH))
    val bubbleSize = dp(64)
    val bubbleParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
    bubbleParams.gravity = Gravity.CENTER
    contentFrame.addView(bubble, bubbleParams)
    body.addView(contentFrame, lp(MATCH, dp(400)))
    applyFilter()
    dlg()
        .setTitle(
            when (mode) {
                "full" -> L.t("Choose apps to block fully", "সম্পূর্ণ ব্লক করার অ্যাপ বাছুন")
                "msg" -> L.t("Choose apps for search block", "সার্চ ব্লকের অ্যাপ বাছুন")
                else -> L.t("Choose apps for Reels block", "রিলস ব্লকের অ্যাপ বাছুন")
            }
        )
        .setView(body)
        .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
            // an app can only be in one list: full block wins over reels block;
            // the search list is independent (an app can block both its feed and its search),
            // manually added package names (not installed, so not in the picker)
            // are kept — saving must not wipe them
            val manual = Extra.manualPackages(this)
            when (mode) {
                "full" -> {
                    val keep = Extra.appBlockFull(this).filter { it in manual }
                    Extra.setAppBlockFull(this, chosen + keep)
                    Extra.setAppBlockReels(this, Extra.appBlockReels(this) - chosen)
                }
                "msg" -> {
                    val keep = Extra.appBlockMsg(this).filter { it in manual }
                    Extra.setAppBlockMsg(this, chosen + keep)
                }
                else -> {
                    val keep = Extra.appBlockReels(this).filter { it in manual }
                    Extra.setAppBlockReels(this, chosen + keep)
                    Extra.setAppBlockFull(this, Extra.appBlockFull(this) - chosen)
                }
            }
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

/** Diagnostic card: what the service sees on a reels screen, so a missed block
 * can be traced instead of guessed. The log is viewed here and copied to the
 * clipboard to be sent for analysis. */
fun MainActivity.diagnosticCard(): View {
    val c = card()
    c.addView(section(L.t("Diagnostic", "ডায়াগনস্টিক")), lp(MATCH, WRAP, b = dp(6)))
    c.addView(tv(L.t("When a reels app is open during a lock, the service records a simple summary of what it sees on screen. Open the log, copy it and send it to find out why a reel was not blocked.", "লক চলাকালীন রিলস অ্যাপ খুললে সার্ভিস স্ক্রিনে কী দেখছে তার সহজ সারাংশ এখানে দেখা যাবে। লগ দেখে কপি করে পাঠালে বোঝা যাবে কেন রিলস ব্লক হয়নি।"), 13f, C.muted), lp(MATCH, WRAP, b = dp(10)))
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    val viewBtn = outlineBtn(L.t("View log", "লগ দেখুন"), C.primary)
    viewBtn.setOnClickListener {
        // the dialog shows the plain summary; copying includes the full
        // technical log too, so the details are available when sent for analysis
        val summary = PowerBlockService.debugSummary
        val text = if (summary.isEmpty()) L.t("No log yet — open a reels app during a lock first.", "এখনো লগ নেই — আগে লক চলাকালীন রিলস অ্যাপ খুলুন।") else summary.joinToString("\n")
        val detail = PowerBlockService.debugLog.joinToString("\n")
        val copyText = if (detail.isEmpty()) text else text + "\n\n--- " + L.t("Details", "বিস্তারিত") + " ---\n" + detail
        val tvLog = tv(text, 11f, C.text)
        val svLog = ScrollView(this)
        svLog.addView(tvLog)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(20), dp(12), dp(20), dp(4))
        body.addView(svLog, lp(MATCH, dp(400)))
        dlg()
            .setTitle(L.t("Diagnostic log", "ডায়াগনস্টিক লগ"))
            .setView(body)
            .setPositiveButton(L.t("Copy", "কপি করুন")) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PowerLock debug", copyText))
                Toast.makeText(this, L.t("Copied", "কপি হয়েছে"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L.t("Close", "বন্ধ"), null)
            .show()
    }
    row.addView(viewBtn, lp(0, WRAP, r = dp(8), weight = 1f))
    val clearBtn = outlineBtn(L.t("Clear", "মুছুন"), C.primary)
    clearBtn.setOnClickListener {
        PowerBlockService.debugLog.clear()
        PowerBlockService.debugSummary.clear()
        Toast.makeText(this, L.t("Log cleared", "লগ মুছে ফেলা হয়েছে"), Toast.LENGTH_SHORT).show()
    }
    row.addView(clearBtn, lp(0, WRAP, weight = 1f))
    c.addView(row)
    return c
}

/** The live list of locked apps for one mode: [mode] is "full", "reels" or "msg". */
fun MainActivity.lockedAppsCard(mode: String): View {
    val c = card()
    c.addView(
        section(
            when (mode) {
                "full" -> L.t("Fully blocked apps", "সম্পূর্ণ ব্লক করা অ্যাপ")
                "msg" -> L.t("Search blocked apps", "সার্চ ব্লক করা অ্যাপ")
                else -> L.t("Reels blocked apps", "রিলস ব্লক করা অ্যাপ")
            }
        )
    )
    val locked = Store.isActive(this)
    val pm = packageManager
    var pkgs = when (mode) {
        "full" -> Extra.appBlockFull(this)
        "msg" -> Extra.appBlockMsg(this)
        else -> Extra.appBlockReels(this)
    }
    if (pkgs.isEmpty()) {
        c.addView(
            tv(L.t("No apps here yet. Choose apps above.", "এখানে এখনো কোনো অ্যাপ নেই। ওপরে অ্যাপ বাছুন।"), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(6))
        )
        return c
    }
    // drop apps that were uninstalled since they were blocked — except
    // manually added package names, which stay blocked for a later install
    val manual = Extra.manualPackages(this)
    val missing = pkgs.filter { pkg ->
        pkg !in manual && try {
            pm.getApplicationInfo(pkg, 0)
            false
        } catch (e: Exception) {
            true
        }
    }.toSet()
    if (missing.isNotEmpty()) {
        pkgs -= missing
        when (mode) {
            "full" -> Extra.setAppBlockFull(this, pkgs)
            "msg" -> Extra.setAppBlockMsg(this, pkgs)
            else -> Extra.setAppBlockReels(this, pkgs)
        }
    }
    data class Row(val pkg: String, val label: String, val icon: Drawable?, val installed: Boolean)
    val rows = ArrayList<Row>()
    for (pkg in pkgs) {
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            rows.add(Row(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), true))
        } catch (e: Exception) {
            // a manually added package that is not installed (yet)
            if (pkg in manual) rows.add(Row(pkg, pkg, null, false))
        }
    }
    rows.sortBy { it.label.lowercase() }
    for (row in rows) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        if (row.icon != null) {
            val icon = ImageView(this)
            icon.setImageDrawable(row.icon)
            r.addView(icon, lp(dp(36), dp(36), r = dp(12)))
        } else {
            r.addView(View(this), lp(dp(36), dp(36), r = dp(12)))
        }
        val labelCol = LinearLayout(this)
        labelCol.orientation = LinearLayout.VERTICAL
        labelCol.addView(tv(row.label, 15f, C.text))
        if (!row.installed) {
            labelCol.addView(tv(L.t("Not installed — blocked once installed", "ইনস্টল হয়নি — ইনস্টল করলেই ব্লক হবে"), 12f, C.muted))
        }
        r.addView(labelCol, lp(0, WRAP, weight = 1f))
        val remove = tv(L.t("Remove", "সরান"), 13f, C.warn)
        remove.setPadding(dp(8), dp(8), dp(8), dp(8))
        remove.pressFeedback()
        remove.setOnClickListener {
            if (Store.isActive(this)) {
                toast(L.t("Locked while a session is active", "সেশন চলাকালীন বদলানো যাবে না"))
            } else {
                when (mode) {
                    "full" -> Extra.setAppBlockFull(this, Extra.appBlockFull(this) - row.pkg)
                    "msg" -> Extra.setAppBlockMsg(this, Extra.appBlockMsg(this) - row.pkg)
                    else -> Extra.setAppBlockReels(this, Extra.appBlockReels(this) - row.pkg)
                }
                Extra.setManualPackages(this, Extra.manualPackages(this) - row.pkg)
                go(4)
            }
        }
        r.addView(remove)
        c.addView(r, lp(MATCH, WRAP, t = dp(4)))
    }
    if (locked) {
        c.addView(tv(L.t("While a session runs, apps cannot be removed here — but new ones can be added above.", "সেশন চলাকালীন এখান থেকে সরানো যাবে না — তবে ওপরে নতুন যোগ করা যাবে।"), 12f, C.warn), lp(MATCH, WRAP, t = dp(8)))
    }
    return c
}

// =====================================================================
// UI: daily app limits (Protection — work without a lock)
// =====================================================================

/** Per-app daily minute limits, with today's remaining time. Works without a lock. */
fun MainActivity.dailyLimitsCard(): View {
    val c = card()
    c.addView(section(L.t("Daily limits", "দৈনিক লিমিট")))
    c.addView(
        tv(
            L.t("Works without a lock: when an app's daily time runs out, it stays blocked until midnight.", "লক না চললেও কাজ করে: অ্যাপের দৈনিক সময় শেষ হলে রাত ১২টা পর্যন্ত ব্লক থাকবে।"),
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val limits = Extra.dailyLimits(this)
    val pm = packageManager
    if (limits.isEmpty()) {
        c.addView(
            tv(L.t("No limits yet. Add an app below.", "এখনো কোনো লিমিট নেই। নিচে অ্যাপ যোগ করুন।"), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(6))
        )
    } else {
        data class Row(val pkg: String, val label: String, val icon: Drawable?, val min: Int)
        val rows = ArrayList<Row>()
        for ((pkg, min) in limits) {
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                rows.add(Row(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), min))
            } catch (e: Exception) {
                // uninstalled since the limit was set: drop the stale limit
                Extra.setDailyLimit(this, pkg, 0)
            }
        }
        rows.sortBy { it.label.lowercase() }
        for (row in rows) {
            val r = LinearLayout(this)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(8), 0, dp(8))
            if (row.icon != null) {
                val icon = ImageView(this)
                icon.setImageDrawable(row.icon)
                r.addView(icon, lp(dp(36), dp(36), r = dp(12)))
            } else {
                r.addView(View(this), lp(dp(36), dp(36), r = dp(12)))
            }
            val labelCol = LinearLayout(this)
            labelCol.orientation = LinearLayout.VERTICAL
            labelCol.addView(tv(row.label, 15f, C.text))
            val left = Extra.remainingTodaySec(this, row.pkg) ?: 0L
            val used = Extra.usageTodaySec(this, row.pkg)
            val sub = if (left <= 0) {
                L.t("Limit reached — blocked until midnight", "লিমিট শেষ — রাত ১২টা পর্যন্ত ব্লক")
            } else {
                L.t("Left today: ", "আজ বাকি: ") + fmtDur(left * 1000L) + " · " +
                    L.t("used ", "ব্যবহৃত ") + fmtDur(used * 1000L) + " / " + fmtDur(row.min * 60_000L)
            }
            labelCol.addView(tv(sub, 12f, if (left <= 0) C.warn else C.muted))
            r.addView(labelCol, lp(0, WRAP, weight = 1f))
            r.addView(tv("›", 22f, C.muted))
            r.pressFeedback()
            r.setOnClickListener { limitChoiceDialog(row.pkg, row.label, row.min) { go(4) } }
            c.addView(r, lp(MATCH, WRAP, t = dp(4)))
        }
    }
    val add = outlineBtn(L.t("Add an app", "অ্যাপ যোগ করুন"), C.primary)
    add.setOnClickListener { dailyLimitPicker { go(4) } }
    c.addView(add, lp(MATCH, dp(48), t = dp(10)))
    if (!Perm.accessibilityOn(this)) {
        c.addView(tv(L.t("Accessibility is off — limits need it. Turn it on from Settings.", "Accessibility বন্ধ — লিমিটের জন্য এটা লাগবে। সেটিংস থেকে চালু করুন।"), 12f, C.warn), lp(MATCH, WRAP, t = dp(8)))
    }
    return c
}

/** Pick an app, then choose its daily limit. Reuses the cached launchable-app list. */
private fun MainActivity.dailyLimitPicker(done: () -> Unit) {
    val cached = Extra.appListCache
    if (cached != null) {
        showDailyLimitPicker(cached, done)
        return
    }
    val loading = dlg()
        .setTitle(L.t("Loading apps…", "অ্যাপ লোড হচ্ছে…"))
        .setView(tv(L.t("Please wait…", "একটু অপেক্ষা করুন…"), 14f, C.muted).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
        })
        .setCancelable(false)
        .show()
    Thread {
        val apps = loadLaunchableApps()
        runOnUiThread {
            try { loading.dismiss() } catch (_: Exception) { }
            if (apps.isEmpty()) {
                toast(L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি"))
                return@runOnUiThread
            }
            Extra.appListCache = apps
            showDailyLimitPicker(apps, done)
        }
    }.start()
}

private fun MainActivity.showDailyLimitPicker(apps: List<Triple<String, String, Drawable>>, done: () -> Unit) {
    lateinit var pickerDlg: AlertDialog
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    list.setPadding(dp(20), dp(4), dp(20), dp(4))
    for (info in apps) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        val icon = ImageView(this)
        icon.setImageDrawable(info.third)
        r.addView(icon, lp(dp(40), dp(40), r = dp(14)))
        r.addView(tv(info.second, 15f, C.text), lp(0, WRAP, weight = 1f))
        val cur = Extra.dailyLimit(this, info.first)
        if (cur > 0) r.addView(tv(fmtDur(cur * 60_000L) + L.t(" / day", " / দিন"), 13f, C.primary, 1))
        r.pressFeedback()
        r.setOnClickListener {
            pickerDlg.dismiss()
            limitChoiceDialog(info.first, info.second, cur, done)
        }
        list.addView(r)
    }
    val sv = ScrollView(this)
    sv.addView(list)
    pickerDlg = dlg()
        .setTitle(L.t("Choose an app", "অ্যাপ বাছুন"))
        .setView(sv)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .create()
    pickerDlg.show()
}

/** Minute options for one app's daily limit (0 = remove the limit). */
/** Choose a daily limit for one app (also used from the usage dashboard). */
fun MainActivity.limitChoiceDialog(pkg: String, label: String, current: Int, done: () -> Unit) {
    val options = intArrayOf(15, 30, 60, 90, 120, 180, 240)
    val items = ArrayList<String>()
    for (m in options) items.add(fmtDur(m * 60_000L) + " " + L.t("per day", "প্রতিদিন"))
    if (current > 0) items.add(L.t("Remove limit", "লিমিট তুলে দিন"))
    dlg()
        .setTitle(label)
        .setItems(items.toTypedArray()) { _, which ->
            if (which < options.size) {
                Extra.setDailyLimit(this, pkg, options[which])
                toast(L.t("Limit set: ", "লিমিট দেওয়া হয়েছে: ") + label + " — " + fmtDur(options[which] * 60_000L) + L.t(" / day", " / দিন"))
            } else {
                Extra.setDailyLimit(this, pkg, 0)
                toast(L.t("Limit removed: ", "লিমিট তোলা হয়েছে: ") + label)
            }
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

// =====================================================================
// UI: app groups (Protection)
// =====================================================================

/** Named sets of apps. A group never blocks by itself — "block all" just adds
 * every app in the group to the full-block list at once. Editing a group never
 * removes a block, so groups can be edited any time, even during a session. */
fun MainActivity.appGroupsCard(): View {
    val c = card()
    c.addView(section(L.t("App groups", "অ্যাপ গ্রুপ")))
    c.addView(
        tv(
            L.t("Group apps together (e.g. Social, Games) and block the whole group at once.", "অ্যাপগুলো গ্রুপ করুন (যেমন সোশ্যাল, গেমস) আর একসাথে পুরো গ্রুপ ব্লক করুন।"),
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val groups = Extra.appGroups(this)
    val pm = packageManager
    if (groups.isEmpty()) {
        c.addView(
            tv(L.t("No groups yet. Add one below.", "এখনো কোনো গ্রুপ নেই। নিচে গ্রুপ যোগ করুন।"), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(6))
        )
    }
    for (g in groups) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(10), 0, dp(10))
        // up to 4 small app icons
        val icons = LinearLayout(this)
        icons.orientation = LinearLayout.HORIZONTAL
        var shown = 0
        for (pkg in g.pkgs) {
            if (shown >= 4) break
            try {
                val iv = ImageView(this)
                iv.setImageDrawable(pm.getApplicationIcon(pkg))
                icons.addView(iv, lp(dp(28), dp(28), r = dp(4)))
                shown++
            } catch (e: Exception) {
                // not installed: skip the icon, the package stays in the group
            }
        }
        if (shown > 0) r.addView(icons, lp(WRAP, WRAP, r = dp(12)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(g.name, 15f, C.text, 1))
        col.addView(tv(L.num(g.pkgs.size.toString()) + " " + L.t("apps", "টি অ্যাপ"), 12f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        val blockBtn = outlineBtn(L.t("Block all", "সব ব্লক"), C.primary)
        blockBtn.setOnClickListener {
            // full block wins: the apps leave the reels list, like the picker does
            Extra.setAppBlockFull(this, Extra.appBlockFull(this) + g.pkgs)
            Extra.setAppBlockReels(this, Extra.appBlockReels(this) - g.pkgs.toSet())
            toast(L.t("Blocked: ", "ব্লক করা হয়েছে: ") + g.name + " (" + L.num(g.pkgs.size.toString()) + ")")
            go(4)
        }
        r.addView(blockBtn, lp(WRAP, dp(40)))
        r.pressFeedback()
        r.setOnClickListener { groupEditorDialog(g) { go(4) } }
        c.addView(r, lp(MATCH, WRAP, t = dp(4)))
    }
    val add = outlineBtn(L.t("Add a group", "গ্রুপ যোগ করুন"), C.primary)
    add.setOnClickListener { groupEditorDialog(null) { go(4) } }
    c.addView(add, lp(MATCH, dp(48), t = dp(10)))
    return c
}

/** Create or edit a group: name + apps. Deleting a group never unblocks apps. */
private fun MainActivity.groupEditorDialog(existing: AppGroup?, done: () -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val nameEt = edit(L.t("Group name (e.g. Social)", "গ্রুপের নাম (যেমন সোশ্যাল)"), false)
    if (existing != null) nameEt.setText(existing.name)
    box.addView(nameEt)
    val chosen = HashSet<String>(existing?.pkgs ?: emptyList())
    val countText = tv("", 14f, C.muted)
    fun refreshCount() {
        countText.text = L.num(chosen.size.toString()) + " " + L.t("apps in this group", "টি অ্যাপ এই গ্রুপে")
    }
    refreshCount()
    box.addView(countText, lp(MATCH, WRAP, t = dp(14)))
    val pickBtn = outlineBtn(L.t("Choose apps", "অ্যাপ বাছুন"), C.primary)
    pickBtn.setOnClickListener {
        groupAppPicker(chosen) { newSet ->
            chosen.clear()
            chosen.addAll(newSet)
            refreshCount()
        }
    }
    box.addView(pickBtn, lp(MATCH, dp(48), t = dp(10)))
    box.addView(
        tv(L.t("Deleting a group does not unblock its apps.", "গ্রুপ মুছলে এর অ্যাপগুলোর ব্লক তোলা হয় না।"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(10))
    )
    val b = dlg()
        .setTitle(if (existing == null) L.t("New group", "নতুন গ্রুপ") else L.t("Edit group", "গ্রুপ সম্পাদনা"))
        .setView(box)
        .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
            val name = nameEt.text.toString().trim()
            if (name.isEmpty()) {
                toast(L.t("Give the group a name", "গ্রুপের একটা নাম দিন"))
                return@setPositiveButton
            }
            if (existing == null) Extra.addAppGroup(this, name, chosen.toList())
            else Extra.updateAppGroup(this, existing.id, name, chosen.toList())
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
    if (existing != null) {
        b.setNeutralButton(L.t("Delete", "মুছুন")) { _, _ ->
            Extra.deleteAppGroup(this, existing.id)
            toast(L.t("Group deleted: ", "গ্রুপ মোছা হয়েছে: ") + existing.name)
            done()
        }
    }
    b.show()
}

/** Multi-select app picker for a group. Reuses the cached launchable-app list. */
private fun MainActivity.groupAppPicker(initial: Set<String>, onSave: (Set<String>) -> Unit) {
    val cached = Extra.appListCache
    if (cached != null) {
        showGroupAppPicker(cached, initial, onSave)
        return
    }
    val loading = dlg()
        .setTitle(L.t("Loading apps…", "অ্যাপ লোড হচ্ছে…"))
        .setView(tv(L.t("Please wait…", "একটু অপেক্ষা করুন…"), 14f, C.muted).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
        })
        .setCancelable(false)
        .show()
    Thread {
        val apps = loadLaunchableApps()
        runOnUiThread {
            try { loading.dismiss() } catch (_: Exception) { }
            if (apps.isEmpty()) {
                toast(L.t("No apps found", "কোনো অ্যাপ পাওয়া যায়নি"))
                return@runOnUiThread
            }
            Extra.appListCache = apps
            showGroupAppPicker(apps, initial, onSave)
        }
    }.start()
}

private fun MainActivity.showGroupAppPicker(
    apps: List<Triple<String, String, Drawable>>, initial: Set<String>, onSave: (Set<String>) -> Unit
) {
    val chosen = HashSet<String>(initial)
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    val search = edit(L.t("Search apps", "অ্যাপ খুঁজুন"), false)
    body.addView(search, lp(MATCH, WRAP, l = dp(16), r = dp(16), t = dp(8), b = dp(4)))
    val sv = ScrollView(this)
    val list = LinearLayout(this)
    list.orientation = LinearLayout.VERTICAL
    list.setPadding(dp(20), dp(4), dp(20), dp(4))
    val rows = ArrayList<Pair<View, String>>() // (row, lowercase label)
    for (info in apps) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        val icon = ImageView(this)
        icon.setImageDrawable(info.third)
        r.addView(icon, lp(dp(40), dp(40), r = dp(14)))
        r.addView(tv(info.second, 15f, C.text), lp(0, WRAP, weight = 1f))
        val pkg = info.first
        val sw = toggle(chosen.contains(pkg)) { v ->
            if (v) chosen.add(pkg) else chosen.remove(pkg)
        }
        r.addView(sw)
        list.addView(r)
        rows.add(r to info.second.lowercase())
    }
    sv.addView(list)
    body.addView(sv, lp(MATCH, dp(380)))
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val q = (s?.toString() ?: "").trim().lowercase()
            for ((row, label) in rows) {
                row.visibility = if (q.isEmpty() || label.contains(q)) View.VISIBLE else View.GONE
            }
        }
    })
    dlg()
        .setTitle(L.t("Choose apps", "অ্যাপ বাছুন"))
        .setView(body)
        .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ -> onSave(chosen) }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

// =====================================================================
// UI: location lock (Settings)
// =====================================================================

fun MainActivity.requestLocationPerm() {
    if (!Perm.locationOn(this)) {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
    } else if (Build.VERSION.SDK_INT >= 29 && !Perm.backgroundLocationOn(this)) {
        requestPermissions(arrayOf("android.permission.ACCESS_BACKGROUND_LOCATION"), 12)
    }
}

/** Runs [block] only when this place is not locking right now; otherwise explains why. */
private fun MainActivity.ifPlaceNotLocking(pl: Place, block: () -> Unit) {
    if (!Store.isLocProtecting(this)) { block(); return }
    PlaceWatcher.isPlaceActiveNow(this, pl) { active ->
        when (active) {
            true -> toast(L.t("This place is locking right now — it turns off when you leave.", "এই জায়গায় এখন লক চলছে — জায়গা ছেড়ে গেলে নিজে নিজে বন্ধ হবে।"))
            false -> block()
            null -> toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
        }
    }
}

/** The full Location Lock card (switch, saved places, map/save-place buttons,
 * permission rows, battery note). Used by Settings and by Home's location
 * section; [onPlaceChanged] rebuilds the host screen after a place is added
 * or deleted — Settings by default, Home passes its own rebuild. */
fun MainActivity.locationCard(onPlaceChanged: () -> Unit = { go(3) }): View {
    val c = card()
    val st = Store.settings(this)
    // header first: title on the left, master on/off on the right
    lateinit var sw: PremiumSwitch
    sw = toggle(st.getBoolean("loc_on", false)) { v ->
        if (!v && Store.isLocProtecting(this)) {
            // locked right now: only leaving the place turns it off
            PlaceWatcher.isAnyPlaceActiveNow(this) { active ->
                if (active == true) {
                    toast(L.t("You are at a saved place — the lock turns off when you leave.", "সেভ করা জায়গায় আছেন — জায়গা ছেড়ে গেলে লক নিজে নিজে বন্ধ হবে।"))
                    sw.setCheckedImmediate(true)
                } else if (active == false) {
                    st.edit().putBoolean("loc_on", false).apply()
                    PlaceWatcher.sync(this)
                    PlaceWatcher.checkNow(this)
                } else {
                    toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
                    sw.setCheckedImmediate(true)
                }
            }
            return@toggle
        }
        st.edit().putBoolean("loc_on", v).apply()
        PlaceWatcher.sync(this)
        if (v) {
            if (!Perm.locationOn(this)) requestLocationPerm()
            PlaceWatcher.checkNow(this)
        }
    }
    val head = LinearLayout(this)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    head.addView(section(L.t("Location lock", "লোকেশন লক")), lp(0, WRAP, weight = 1f))
    head.addView(sw)
    c.addView(head)
    c.addView(
        tv(
            L.t(
                "Only this switch. While you are at a saved place the power menu is blocked; when you leave, it opens again. The Home lock does not need to be on.",
                "শুধু এই একটাই সুইচ। সেভ করা জায়গায় থাকলেই পাওয়ার মেনু ব্লক হবে; বাইরে গেলে আবার খুলে যাবে। হোমের লক অন করতে হবে না।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )

    val places = Extra.places(this)
    for (pl in places) {
        lateinit var rowSw: PremiumSwitch
        rowSw = toggle(pl.enabled) { v ->
            if (!v && Store.isLocProtecting(this)) {
                // this place may be locking right now: only leaving turns it off
                PlaceWatcher.isPlaceActiveNow(this, pl) { active ->
                    if (active == true) {
                        toast(L.t("This place is locking right now — it turns off when you leave.", "এই জায়গায় এখন লক চলছে — জায়গা ছেড়ে গেলে নিজে নিজে বন্ধ হবে।"))
                        rowSw.setCheckedImmediate(true)
                    } else if (active == false) {
                        Extra.savePlaces(this, Extra.places(this).map { if (it.id == pl.id) it.copy(enabled = false) else it })
                        PlaceWatcher.sync(this)
                        PlaceWatcher.checkNow(this)
                    } else {
                        toast(L.t("Location unavailable — turn on GPS and try again.", "অবস্থান পাওয়া যাচ্ছে না — GPS চালু করে আবার চেষ্টা করুন।"))
                        rowSw.setCheckedImmediate(true)
                    }
                }
                return@toggle
            }
            Extra.savePlaces(this, Extra.places(this).map { if (it.id == pl.id) it.copy(enabled = v) else it })
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
        }
        val modeTxt = if (pl.mode == PLACE_MODE_OUTSIDE) L.t("Lock when away", "বাইরে গেলে লক") else L.t("Lock when here", "এখানে থাকলে লক")
        val sub = L.num("${pl.radius} " + L.t("m radius", "মি. ব্যাসার্ধ")) + " · " + modeTxt
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(10), 0, dp(10))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(pl.name, 15f, C.text, 1))
        col.addView(tv(sub, 12f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        // visible delete button (long-press still works too)
        val del = tv("✕", 16f, C.danger, 1)
        del.gravity = Gravity.CENTER
        del.background = withRipple(rounded(C.card2, dp(18).toFloat()))
        del.pressFeedback()
        del.setOnClickListener { ifPlaceNotLocking(pl) { deletePlaceDialog(pl) { onPlaceChanged() } } }
        r.addView(del, lp(dp(36), dp(36), r = dp(10)))
        r.addView(rowSw)
        r.setOnLongClickListener {
            ifPlaceNotLocking(pl) { deletePlaceDialog(pl) { onPlaceChanged() } }
            true
        }
        c.addView(r)
    }

    val mapBtn = outlineBtn("🗺 " + L.t("Open map", "ম্যাপ খুলুন"), C.primary)
    mapBtn.setOnClickListener { startActivity(Intent(this, MapActivity::class.java)) }
    c.addView(mapBtn, lp(MATCH, dp(48), t = dp(12)))

    val add = outlineBtn("＋ " + L.t("Save my current place", "আমার এই জায়গা সেভ করুন"), C.primary)
    add.setOnClickListener { addPlaceFlow { onPlaceChanged() } }
    c.addView(add, lp(MATCH, dp(48), t = dp(12)))

    val locOk = Perm.locationOn(this)
    val bgOk = Perm.backgroundLocationOn(this)
    c.addView(
        row(
            L.t("Location permission", "লোকেশন পারমিশন"),
            if (locOk) L.t("Granted", "অনুমোদিত") else L.t("Tap to allow", "অনুমতি দিতে চাপুন"),
            tv(if (locOk) "✔" else "✘", 18f, if (locOk) C.primary else C.danger, 1)
        ) { if (!locOk) requestLocationPerm() },
        lp(MATCH, WRAP, t = dp(6))
    )
    c.addView(
        row(
            L.t("Allow all the time", "সবসময় অনুমতি"),
            if (bgOk) L.t("Granted", "অনুমোদিত") else L.t("Needed so it works with the screen off", "স্ক্রিন বন্ধ থাকলেও কাজ করার জন্য দরকার"),
            tv(if (bgOk) "✔" else "✘", 18f, if (bgOk) C.primary else C.danger, 1)
        ) { if (!bgOk && locOk) requestLocationPerm() }
    )
    c.addView(
        tv(
            L.t(
                "Uses a little extra battery. Indoors the position can be off by 10-50 m, so use a radius of 100 m or more.",
                "ব্যাটারি একটু বেশি খরচ হয়। ঘরের ভেতরে অবস্থান ১০-৫০ মিটার ভুল হতে পারে, তাই ব্যাসার্ধ ১০০ মিটার বা বেশি রাখুন।"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

/** Route (30 days, drawn on the map) + entry/exit history (30 days). */
fun MainActivity.historyCard(): View {
    val c = card()
    c.addView(section(L.t("Route & history (30 days)", "রুট ও হিস্ট্রি (৩০ দিন)")))
    // today's route: total distance + open the map
    val distM = Store.todayDistanceM(this)
    val pts = Store.todayRoute(this).size
    c.addView(
        row(
            L.t("Today's route", "আজকের রুট"),
            L.num(pts.toString()) + " " + L.t("points", "পয়েন্ট") + " · " + fmtDistM(distM),
            tv("🗺", 18f, C.primary, 1)
        ) { startActivity(Intent(this, MapActivity::class.java)) },
        lp(MATCH, WRAP, t = dp(4))
    )
    // this month's total distance (all stored days)
    c.addView(
        row(
            L.t("This month in total", "এই মাসে মোট"),
            fmtDistM(Store.monthDistanceM(this)),
            tv("🗓", 18f, C.primary, 1)
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    // entry / exit history
    c.addView(tv(L.t("Entry / exit history", "ঢোকা / বেরোনোর হিস্ট্রি"), 13f, C.muted), lp(MATCH, WRAP, t = dp(10)))
    val events = Store.placeEvents(this)
    if (events.isEmpty()) {
        c.addView(
            tv(
                L.t("No history yet — it appears when you enter or leave a saved place.", "এখনো কোনো হিস্ট্রি নেই — সেভ করা জায়গায় ঢুকলে বা বেরোলে এখানে দেখাবে।"),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = dp(4))
        )
    } else {
        for (e in events.take(30)) {
            val line1 = e.name + " · " + L.t("entered", "ঢুকেছে") + " " + fmtWhen(e.inTime)
            val line2 = if (e.outTime > 0) {
                L.t("left", "বেরিয়েছে") + " " + fmtWhen(e.outTime) + " · " +
                    L.t("inside for", "ভেতরে ছিল") + " " + fmtDur(e.outTime - e.inTime)
            } else {
                L.t("still inside", "এখনো ভেতরে") + " · " + fmtDur(System.currentTimeMillis() - e.inTime)
            }
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(tv(line1, 14f, C.text, 1))
            col.addView(tv(line2, 12f, C.muted))
            c.addView(col, lp(MATCH, WRAP, t = dp(8)))
        }
        val clear = tv(L.t("Clear history", "হিস্ট্রি মুছুন"), 13f, C.danger, 1)
        clear.setOnClickListener { Store.clearEvents(this); go(3) }
        c.addView(clear, lp(MATCH, WRAP, t = dp(10)))
    }
    return c
}

/** "3.4 km" / "250 m" for a distance in metres. */
fun fmtDistM(distM: Float): String {
    return if (distM >= 1000f) {
        val km10 = (distM / 100).toInt() // tenths of a km
        L.num("${km10 / 10}.${km10 % 10}") + " " + L.t("km", "কিমি")
    } else {
        L.num(distM.toInt().toString()) + " " + L.t("m", "মি")
    }
}

/** "10:30 AM" today, "19 Nov 10:30 AM" on older days. */
private fun fmtWhen(ms: Long): String {
    return if (isToday(ms)) fmtClock(ms) else fmtDayShort(ms) + " " + fmtClock(ms)
}

private fun isToday(ms: Long): Boolean {
    val a = Calendar.getInstance()
    val b = Calendar.getInstance()
    b.timeInMillis = ms
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** "19 Nov" (day + short month). */
private fun fmtDayShort(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return L.num(c.get(Calendar.DAY_OF_MONTH).toString()) + " " + L.month(c.get(Calendar.MONTH))
}

private fun MainActivity.deletePlaceDialog(pl: Place, done: () -> Unit) {
    dlg()
        .setTitle(L.t("Delete this place?", "এই জায়গা মুছবেন?"))
        .setMessage(pl.name)
        .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
            Extra.savePlaces(this, Extra.places(this).filter { it.id != pl.id })
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
            done()
        }
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
}

private fun MainActivity.addPlaceFlow(done: () -> Unit) {
    if (!Perm.locationOn(this)) {
        requestLocationPerm()
        return
    }
    toast(L.t("Finding your location…", "আপনার অবস্থান খোঁজা হচ্ছে…"))
    Extra.currentLocation(this) { loc ->
        if (loc == null) {
            toast(L.t("Could not get the location. Turn on GPS and try again outside.", "অবস্থান পাওয়া যায়নি। GPS চালু করে বাইরে আবার চেষ্টা করুন।"))
        } else {
            placeDialog(loc.latitude, loc.longitude, done)
        }
    }
}

private fun MainActivity.placeDialog(lat: Double, lng: Double, done: () -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(24), dp(8), dp(24), 0)
    val nameEt = edit(L.t("Place name (e.g. Office)", "জায়গার নাম (যেমন অফিস)"), false)
    box.addView(nameEt)

    var radius = 200
    val radii = intArrayOf(100, 200, 500, 1000, 5000, 10000)
    val chips = ArrayList<TextView>()
    val seek = SeekBar(this)
    seek.max = 995 // (10000 - 50) / 10: 50 m to 10000 m in 10 m steps
    // header: label on the left, live value on the right
    val rHead = LinearLayout(this)
    rHead.orientation = LinearLayout.HORIZONTAL
    rHead.gravity = Gravity.CENTER_VERTICAL
    rHead.addView(tv(L.t("Radius", "ব্যাসার্ধ"), 13f, C.muted), lp(0, WRAP, weight = 1f))
    val radiusVal = tv(L.num("$radius " + L.t("m", "মি.")), 16f, C.primary, 1)
    rHead.addView(radiusVal)
    box.addView(rHead, lp(MATCH, WRAP, t = dp(14)))
    // quick chips, two rows: 100 / 200 / 500, then 1000 / 5000 / 10000
    for (rowRadii in listOf(intArrayOf(100, 200, 500), intArrayOf(1000, 5000, 10000))) {
        val rr = LinearLayout(this)
        for ((i, rv) in rowRadii.withIndex()) {
            val ch = chip(L.num("$rv " + L.t("m", "মি.")), rv == radius)
            ch.setOnClickListener { seek.progress = (rv - 50) / 10 }
            chips.add(ch)
            rr.addView(ch, lp(0, WRAP, r = if (i < rowRadii.size - 1) dp(8) else 0, t = dp(8), weight = 1f))
        }
        box.addView(rr)
    }
    // smooth slider: drag to grow or shrink the area
    seek.progressTintList = ColorStateList.valueOf(C.primary)
    seek.progressBackgroundTintList = ColorStateList.valueOf(C.card2)
    seek.thumbTintList = ColorStateList.valueOf(C.mint)
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            radius = 50 + p * 10
            radiusVal.setTextAnimated(L.num("$radius " + L.t("m", "মি.")))
            for (j in chips.indices) styleChip(chips[j], radii[j] == radius)
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    seek.progress = (radius - 50) / 10
    box.addView(seek, lp(MATCH, WRAP, t = dp(10)))
    val range = LinearLayout(this)
    range.orientation = LinearLayout.HORIZONTAL
    range.addView(tv(L.num("50 " + L.t("m", "মি.")), 12f, C.muted), lp(0, WRAP, weight = 1f))
    range.addView(tv(L.num(L.t("10000 m (10 km)", "10000 মি. (10 কিমি)")), 12f, C.muted))
    box.addView(range)

    box.addView(tv(L.t("When to lock", "কখন লক হবে"), 13f, C.muted), lp(WRAP, WRAP, t = dp(14)))
    var mode = PLACE_MODE_INSIDE
    val modeChips = ArrayList<TextView>()
    val mr = LinearLayout(this)
    val modeLabels = arrayOf(L.t("While I'm here", "এখানে থাকলে"), L.t("While I'm away", "বাইরে গেলে"))
    for ((i, ml) in modeLabels.withIndex()) {
        val ch = chip(ml, i == mode)
        ch.setOnClickListener {
            mode = i
            for (j in modeChips.indices) styleChip(modeChips[j], j == mode)
        }
        modeChips.add(ch)
        mr.addView(ch, lp(0, WRAP, r = if (i < modeLabels.size - 1) dp(8) else 0, t = dp(8), weight = 1f))
    }
    box.addView(mr)

    val d = dlg()
        .setTitle(L.t("Save this place", "এই জায়গা সেভ করুন"))
        .setView(box)
        .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .create()
    d.show()
    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameEt.text.toString().trim()
        if (name.isEmpty()) {
            nameEt.error = L.t("Enter a name", "নাম লিখুন")
        } else {
            val id = System.currentTimeMillis()
            // no time: the lock follows presence only, so the place has no duration (min = 0)
            Extra.savePlaces(this, Extra.places(this) + Place(id, name, lat, lng, radius, 0, true, mode))
            PlaceWatcher.sync(this)
            PlaceWatcher.checkNow(this)
            d.dismiss()
            done()
        }
    }
}

// =====================================================================
// UI: blocked attempts (Statistics)
// =====================================================================

fun MainActivity.attemptsCard(): View {
    val c = card()
    c.addView(section(L.t("Blocked attempts", "আটকানো চেষ্টা")))
    val list = Extra.attempts(this)
    val now = System.currentTimeMillis()
    val today = Store.dayStart(now, 0)
    val week = Store.dayStart(now, -6)
    var nToday = 0
    var nWeek = 0
    var nPower = 0
    var nSettings = 0
    var nFocus = 0
    var nAppBlock = 0
    var nReels = 0
    var nMsgSearch = 0
    var nLimit = 0
    var nSite = 0
    var nUninstall = 0
    for ((t, k) in list) {
        if (t >= today) nToday++
        if (t >= week) nWeek++
        when (k) {
            "power" -> nPower++
            "settings" -> nSettings++
            "appblock" -> nAppBlock++
            "reels" -> nReels++
            "msgsearch" -> nMsgSearch++
            "limit" -> nLimit++
            "uninstall" -> nUninstall++
            "site", "url", "kw_url", "kw_typed", "kw_visible" -> nSite++
            else -> nFocus++
        }
    }
    val nums = LinearLayout(this)
    fun stat(value: Int, label: String, last: Boolean) {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        b.addView(tv(L.num(value.toString()), 26f, C.mint, 1))
        b.addView(tv(label, 12f, C.muted))
        nums.addView(b, lp(0, WRAP, r = if (last) 0 else dp(8), weight = 1f))
    }
    stat(nToday, L.t("Today", "আজ"), false)
    stat(nWeek, L.t("Last 7 days", "গত ৭ দিন"), false)
    stat(list.size, L.t("Total", "মোট"), true)
    c.addView(nums, lp(MATCH, WRAP, t = dp(12)))

    val chart = BarChartView(this)
    val vals = ArrayList<Long>()
    val labs = ArrayList<String>()
    for (off in -6..0) {
        val from = Store.dayStart(now, off)
        val to = Store.dayStart(now, off + 1)
        var n = 0L
        for ((t, _) in list) if (t >= from && t < to) n++
        vals.add(n)
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        labs.add(L.dayLong(dayIdx(cal)))
    }
    chart.values = vals
    chart.labels = labs
    c.addView(chart, lp(MATCH, dp(130), t = dp(14)))

    c.addView(
        tv(
            L.t(
                "Power menu: $nPower · App settings: $nSettings · Focus apps: $nFocus · App block: $nAppBlock · Reels block: $nReels · Search block: $nMsgSearch · Daily limit: $nLimit · Site block: $nSite · Uninstall block: $nUninstall",
                "পাওয়ার মেনু: $nPower · অ্যাপ সেটিংস: $nSettings · ফোকাস অ্যাপ: $nFocus · অ্যাপ ব্লক: $nAppBlock · রিলস ব্লক: $nReels · সার্চ ব্লক: $nMsgSearch · দৈনিক লিমিট: $nLimit · সাইট ব্লক: $nSite · আনইনস্টল ব্লক: $nUninstall"
            ),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(10))
    )
    return c
}

// =====================================================================
// UI: diagnostics log (which block fired, and what matched)
// =====================================================================

/** Bengali/English label for a log kind (see Extra.logAttempt). */
private fun diagReasonLabel(kind: String): String = when (kind) {
    "power" -> L.t("Power menu block", "পাওয়ার মেনু ব্লক")
    "settings" -> L.t("Settings block", "সেটিংস ব্লক")
    "focus" -> L.t("Focus app block", "ফোকাস অ্যাপ ব্লক")
    "appblock" -> L.t("App block", "অ্যাপ ব্লক")
    "limit" -> L.t("Daily limit", "দৈনিক লিমিট")
    "uninstall" -> L.t("Uninstall block", "আনইনস্টল ব্লক")
    "dns" -> L.t("DNS change block", "DNS পরিবর্তন ব্লক")
    "user" -> L.t("Guest user block", "Guest ইউজার ব্লক")
    "whitelist" -> L.t("Whitelist block", "হোয়াইটলিস্ট ব্লক")
    "install" -> L.t("Install block", "ইনস্টল ব্লক")
    "crash" -> L.t("Service crash", "সার্ভিস ক্র্যাশ")
    "reels" -> L.t("Reels block", "রিলস ব্লক")
    "msgsearch" -> L.t("Search block", "সার্চ ব্লক")
    "site" -> L.t("Site block (DNS)", "সাইট ব্লক (DNS)")
    "url" -> L.t("Site block (URL)", "সাইট ব্লক (URL)")
    "kw_url" -> L.t("Keyword in URL", "কিওয়ার্ড (URL-এ)")
    "kw_typed" -> L.t("Keyword typed", "কিওয়ার্ড (টাইপ করা)")
    "kw_visible" -> L.t("Keyword on screen", "কিওয়ার্ড (স্ক্রিনে)")
    else -> L.t("Block", "ব্লক")
}

private fun MainActivity.appLabelFor(pkg: String): String {
    if (pkg.isBlank()) return L.t("Unknown app", "অজানা অ্যাপ")
    return try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}

/** "17:22" today, "23 Nov, 17:22" on earlier days (digits localized). */
private fun formatDiagTime(t: Long): String {
    val todayStart = Store.dayStart(System.currentTimeMillis(), 0)
    val sdf = if (t >= todayStart) SimpleDateFormat("HH:mm", Locale.getDefault())
    else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    return L.num(sdf.format(Date(t)))
}

private fun MainActivity.diagRow(ev: Extra.DiagEvent): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.VERTICAL
    val top = LinearLayout(this)
    top.orientation = LinearLayout.HORIZONTAL
    top.gravity = Gravity.CENTER_VERTICAL
    top.addView(tv(appLabelFor(ev.p), 15f, C.text, 1), lp(0, WRAP, weight = 1f))
    top.addView(tv(formatDiagTime(ev.t), 12f, C.muted))
    row.addView(top, lp(MATCH, WRAP))
    // Build 118: also show the package name, so a blocked entry can be
    // identified (the label alone does not say which app it is).
    row.addView(tv(ev.p, 12f, C.muted), lp(MATCH, WRAP, t = dp(2)))
    row.addView(tv(diagReasonLabel(ev.k), 13f, C.mint, 1), lp(MATCH, WRAP, t = dp(2)))
    if (ev.d.isNotBlank()) {
        row.addView(tv(L.t("Matched: ", "যা ধরা পড়লো: ") + ev.d, 13f, C.muted), lp(MATCH, WRAP, t = dp(2)))
    }
    return row
}

/** Diagnostics log card (Statistics tab): the last 30 blocks, newest first. Tap it for the full log in a separate window. */
fun MainActivity.diagCard(): View {
    val c = card()
    c.addView(section(L.t("Diagnostics", "ডায়াগনস্টিক")))
    c.addView(
        tv(L.t("Why each block fired — tap for the full log.", "প্রতিটি ব্লক কেন হলো — পুরো লগ দেখতে ট্যাপ করুন।"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(4))
    )
    val list = Extra.attemptsFull(this).takeLast(30).reversed()
    if (list.isEmpty()) {
        c.addView(tv(L.t("No blocks yet.", "এখনো কোনো ব্লক হয়নি।"), 14f, C.muted), lp(MATCH, WRAP, t = dp(8)))
    } else {
        for (ev in list) {
            c.addView(diagRow(ev), lp(MATCH, WRAP, t = dp(10)))
        }
    }
    val clear = outlineBtn(L.t("Clear log", "লগ মুছুন"), C.muted)
    clear.setOnClickListener {
        Extra.clearAttempts(this)
        go(2)
    }
    c.addView(clear, lp(MATCH, WRAP, t = dp(12)))
    c.setOnClickListener { showDiagDialog() }
    return c
}

/** Full diagnostics log in a separate window (dialog): the last 100 blocks, newest first. */
fun MainActivity.showDiagDialog() {
    val list = Extra.attemptsFull(this).takeLast(100).reversed()
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    val sv = ScrollView(this)
    val inner = LinearLayout(this)
    inner.orientation = LinearLayout.VERTICAL
    inner.setPadding(dp(20), dp(4), dp(20), dp(4))
    if (list.isEmpty()) {
        inner.addView(tv(L.t("No blocks yet.", "এখনো কোনো ব্লক হয়নি।"), 14f, C.muted))
    } else {
        for (ev in list) {
            inner.addView(diagRow(ev), lp(MATCH, WRAP, t = dp(10)))
        }
    }
    sv.addView(inner)
    body.addView(sv, lp(MATCH, dp(420)))
    dlg()
        .setTitle(L.t("Diagnostics", "ডায়াগনস্টিক"))
        .setView(body)
        .setPositiveButton(L.t("Clear log", "লগ মুছুন")) { _, _ ->
            Extra.clearAttempts(this)
            go(2)
        }
        .setNegativeButton(L.t("Close", "বন্ধ করুন"), null)
        .show()
}

/** পরিসংখ্যানের লম্বা সেকশনগুলোর জন্য আলাদা উইন্ডো: ট্যাপ করলে খোলে। */
fun MainActivity.showSectionDialog(title: String, content: View) {
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    val sv = ScrollView(this)
    val inner = LinearLayout(this)
    inner.orientation = LinearLayout.VERTICAL
    inner.setPadding(dp(20), dp(4), dp(20), dp(4))
    inner.addView(content, lp(MATCH, WRAP))
    sv.addView(inner)
    body.addView(sv, lp(MATCH, dp(520)))
    dlg()
        .setTitle(title)
        .setView(body)
        .setNegativeButton(L.t("Close", "বন্ধ করুন"), null)
        .show()
}

// =====================================================================
// SHARING & CONTENT (build 55): focus notes, fun facts, time capsule
// =====================================================================

/** Focus notes: write short notes about why you're focusing, newest first. */
fun MainActivity.focusNotesCard(): View {
    val c = card()
    c.addView(section(L.t("Focus notes", "ফোকাস নোট")))
    c.addView(
        tv(L.t("Write why you're focusing today — a line to come back to.", "আজ কেন ফোকাস করছো তা লিখে রাখো — পরে ফিরে দেখার জন্য।"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(4))
    )
    val et = edit(L.t("Why am I focusing today?", "আজ আমি কেন ফোকাস করছি?"), false)
    et.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    val add = primaryBtn(L.t("Add", "যোগ করুন"))
    val inputRow = LinearLayout(this)
    inputRow.gravity = Gravity.CENTER_VERTICAL
    inputRow.addView(et, lp(0, WRAP, r = dp(10), weight = 1f))
    inputRow.addView(add, lp(dp(84), dp(48)))
    c.addView(inputRow, lp(MATCH, WRAP, t = dp(12)))
    add.setOnClickListener {
        if (FocusNotes.add(this, et.text.toString())) {
            go(0)
        } else {
            toast(L.t("Write something first", "আগে কিছু লিখুন"))
        }
    }
    val notes = FocusNotes.list(this)
    if (notes.isEmpty()) {
        c.addView(
            tv(L.t("No notes yet.", "এখনো কোনো নোট নেই।"), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(12))
        )
    } else {
        for (n in notes.take(20)) {
            val r = LinearLayout(this)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(10), 0, dp(10))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(tv(n.text, 14f, C.text))
            col.addView(tv(fmtDate(n.time) + " · " + fmtClock(n.time), 12f, C.muted))
            r.addView(col, lp(0, WRAP, weight = 1f))
            val del = tv("✕", 16f, C.muted)
            del.setPadding(dp(10), dp(10), dp(10), dp(10))
            del.pressFeedback()
            del.setOnClickListener {
                FocusNotes.delete(this, n.id)
                go(0)
            }
            r.addView(del)
            c.addView(r)
        }
        if (notes.size > 20) {
            c.addView(
                tv(L.num(L.t("and ${notes.size - 20} older notes", "আরও ${notes.size - 20}টি পুরোনো নোট")), 12f, C.muted),
                lp(MATCH, WRAP, t = dp(4))
            )
        }
    }
    return c
}

/** Fun facts: one fresh fact per day; tap for the next one. */
fun MainActivity.funFactsCard(): View {
    val c = card()
    c.addView(section(L.t("Did you know?", "জানতেন কি?")))
    val factText = tv(FunFacts.current(this), 15f, C.textSoft)
    c.addView(factText, lp(MATCH, WRAP, t = dp(10)))
    val next = outlineBtn(L.t("Next fact", "পরের তথ্য"), C.primary)
    next.setOnClickListener { factText.text = FunFacts.next(this) }
    c.addView(next, lp(MATCH, dp(48), t = dp(12)))
    return c
}

/** Time capsule: sealed messages to your future self. */
fun MainActivity.timeCapsuleCard(): View {
    val c = card()
    c.addView(section(L.t("Time capsule", "টাইম ক্যাপসুল")))
    c.addView(
        tv(L.t("Write a message to your future self. It stays sealed until its open date.", "ভবিষ্যতের নিজেকে একটা মেসেজ লিখুন। খোলার তারিখ না আসা পর্যন্ত সিল করা থাকবে।"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(4))
    )
    val now = System.currentTimeMillis()
    val list = TimeCapsules.list(this)
    for (cap in list) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(10), 0, dp(10))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (cap.opened) {
            col.addView(tv(cap.text, 14f, C.text))
            col.addView(tv(L.t("Opened ", "খোলা হয়েছে ") + fmtDate(cap.openAt), 12f, C.muted))
        } else if (cap.openAt <= now) {
            col.addView(tv("✉️ " + L.t("Ready to open", "খোলার জন্য প্রস্তুত"), 14f, C.mint, 1))
            col.addView(tv(L.t("Open date: ", "খোলার তারিখ: ") + fmtDate(cap.openAt), 12f, C.muted))
        } else {
            val daysLeft = ((cap.openAt - now + 86_400_000L - 1) / 86_400_000L).toInt().coerceAtLeast(1)
            col.addView(tv("🔒 " + L.t("Sealed", "সিল করা"), 14f, C.text, 1))
            col.addView(
                tv(
                    L.t("Opens ", "খুলবে ") + fmtDate(cap.openAt) + " · " +
                        L.num(daysLeft.toString()) + L.t(" days left", " দিন বাকি"),
                    12f, C.muted
                )
            )
        }
        r.addView(col, lp(0, WRAP, weight = 1f))
        if (!cap.opened && cap.openAt <= now) {
            val openBtn = outlineBtn(L.t("Open", "খুলুন"), C.primary)
            openBtn.setOnClickListener { openCapsuleDialog(cap) }
            r.addView(openBtn, lp(dp(84), dp(44)))
        }
        val del = tv("✕", 16f, C.muted)
        del.setPadding(dp(10), dp(10), dp(4), dp(10))
        del.pressFeedback()
        del.setOnClickListener {
            TimeCapsules.delete(this, cap.id)
            go(0)
        }
        r.addView(del)
        c.addView(r)
    }
    if (list.isEmpty()) {
        c.addView(tv(L.t("No capsules yet.", "এখনো কোনো ক্যাপসুল নেই।"), 13f, C.muted), lp(MATCH, WRAP, t = dp(10)))
    }
    val add = outlineBtn(L.t("New capsule", "নতুন ক্যাপসুল"), C.primary)
    add.setOnClickListener { newCapsuleDialog() }
    c.addView(add, lp(MATCH, dp(48), t = dp(10)))
    return c
}

private fun MainActivity.openCapsuleDialog(cap: Capsule) {
    dlg()
        .setTitle(L.t("Time capsule", "টাইম ক্যাপসুল"))
        .setMessage(cap.text)
        .setPositiveButton(L.t("Done", "ঠিক আছে")) { _, _ ->
            TimeCapsules.markOpened(this, cap.id)
            go(0)
        }
        .show()
}

private fun MainActivity.newCapsuleDialog() {
    val et = edit(L.t("Message to your future self…", "ভবিষ্যতের নিজেকে মেসেজ…"), false)
    et.gravity = Gravity.TOP or Gravity.START
    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    et.minLines = 3
    // default open date: 30 days from now
    val chosen = Calendar.getInstance()
    chosen.add(Calendar.DAY_OF_YEAR, 30)
    fun openDayStart(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    val dateText = tv("", 14f, C.text, 1)
    fun refreshDate() {
        dateText.text = L.t("Open date: ", "খোলার তারিখ: ") + fmtDate(openDayStart(chosen))
    }
    refreshDate()
    val pickDate = outlineBtn(L.t("Pick a date", "তারিখ বাছুন"), C.primary)
    pickDate.setOnClickListener {
        val min = Calendar.getInstance()
        min.add(Calendar.DAY_OF_YEAR, 1)
        val max = Calendar.getInstance()
        max.add(Calendar.YEAR, 1)
        val dpd = DatePickerDialog(
            this, android.R.style.Theme_DeviceDefault_Dialog,
            { _, y, m, d ->
                chosen.set(y, m, d)
                refreshDate()
            },
            chosen.get(Calendar.YEAR), chosen.get(Calendar.MONTH), chosen.get(Calendar.DAY_OF_MONTH)
        )
        dpd.datePicker.minDate = openDayStart(min)
        dpd.datePicker.maxDate = openDayStart(max)
        dpd.show()
    }
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(et, lp(MATCH, WRAP))
    body.addView(dateText, lp(MATCH, WRAP, t = dp(14)))
    body.addView(pickDate, lp(MATCH, dp(48), t = dp(10)))
    body.addView(
        tv(L.t("The message stays sealed until the open date. You'll get a notification that day.", "খোলার তারিখ না আসা পর্যন্ত মেসেজ সিল করা থাকবে। সেদিন নোটিফিকেশন পাবেন।"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(10))
    )
    // The positive button is wired after show() so a failed validation keeps
    // the dialog (and the typed message) instead of dismissing it.
    val dialog = dlg()
        .setTitle(L.t("New time capsule", "নতুন টাইম ক্যাপসুল"))
        .setView(body)
        .setPositiveButton(L.t("Seal it", "সিল করুন"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val text = et.text.toString()
        if (text.isBlank()) {
            toast(L.t("Write a message first", "আগে মেসেজ লিখুন"))
            return@setOnClickListener
        }
        val openAt = openDayStart(chosen)
        if (openAt <= System.currentTimeMillis()) {
            toast(L.t("Pick a future date", "ভবিষ্যতের তারিখ বাছুন"))
            return@setOnClickListener
        }
        val added = TimeCapsules.add(this, text, openAt)
        if (added == null) {
            toast(L.t("Too many capsules — delete an opened one first", "অনেক ক্যাপসুল হয়ে গেছে — আগে খোলা একটা মুছুন"))
            return@setOnClickListener
        }
        toast(L.t("Capsule sealed", "ক্যাপসুল সিল করা হয়েছে"))
        dialog.dismiss()
        go(0)
    }
}

// =====================================================================
// COMPANIONS (build 56): focus tree + garden + animals, pet Mimi, story
// =====================================================================

/** Focus tree (grows with total focus minutes) + garden + animals. */
fun MainActivity.treeGardenCard(): View {
    val c = card()
    c.addView(section(L.t("Focus tree & garden", "ফোকাস গাছ ও বাগান")))
    val fs = Store.focusStats(this)
    val stage = FocusTree.stageFor(fs.totalMin)
    val next = FocusTree.nextAfter(stage)

    val tree = TreeView(this, stage.index)
    c.addView(tree, lp(MATCH, dp(200), t = dp(8)))

    c.addView(
        tv(L.t(stage.nameEn, stage.nameBn), 18f, C.text, 1),
        lp(MATCH, WRAP, t = dp(4))
    )
    c.addView(
        tv(L.t(stage.descEn, stage.descBn), 13f, C.muted),
        lp(MATCH, WRAP, t = dp(2))
    )
    // progress toward the next stage
    val progRow = LinearLayout(this)
    progRow.orientation = LinearLayout.HORIZONTAL
    progRow.background = rounded(C.card2, dp(6).toFloat())
    val fill = View(this)
    fill.background = rounded(C.primary, dp(6).toFloat())
    val frac = FocusTree.progress(fs.totalMin)
    progRow.addView(fill, lp(0, dp(10), weight = frac.coerceAtLeast(0.02f)))
    progRow.addView(View(this), lp(0, dp(10), weight = 1f - frac.coerceAtLeast(0.02f)))
    c.addView(progRow, lp(MATCH, WRAP, t = dp(10)))
    val progText = if (next == null) {
        L.t("Fully grown — an ancient tree!", "পুরোপুরি বড় হয়েছে — প্রাচীন গাছ!")
    } else {
        L.num(fs.totalMin.toString()) + " / " + L.num(next.minMinutes.toString()) +
            L.t(" min to ", " মিনিট, পরের ধাপ: ") + L.t(next.nameEn, next.nameBn)
    }
    c.addView(tv(progText, 12f, C.muted), lp(MATCH, WRAP, t = dp(6)))

    // garden: one plant per completed lock (newest 24 shown)
    val plants = Garden.sync(this)
    c.addView(
        tv(
            L.t("Garden", "বাগান") + " · " + L.num(plants.size.toString()) +
                L.t(" plants", "টি গাছ"),
            15f, C.text, 1
        ),
        lp(MATCH, WRAP, t = dp(16))
    )
    if (plants.isEmpty()) {
        c.addView(
            tv(
                L.t("Complete a lock to plant your first flower.", "প্রথম ফুল লাগাতে একটা লক শেষ করুন।"),
                13f, C.muted
            ),
            lp(MATCH, WRAP, t = dp(6))
        )
    } else {
        val shown = plants.takeLast(24)
        for (rowIdx in shown.indices step 6) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until 6) {
                val p = shown.getOrNull(rowIdx + j)
                val cell = if (p != null) PlantView(this, p.kind, p.variant) else View(this)
                row.addView(cell, lp(0, dp(44), weight = 1f))
            }
            c.addView(row, lp(MATCH, WRAP, t = dp(4)))
        }
        if (plants.size > 24) {
            c.addView(
                tv(
                    L.t("and ", "আরও ") + L.num((plants.size - 24).toString()) +
                        L.t(" more plants", "টি গাছ আছে"),
                    12f, C.muted
                ),
                lp(MATCH, WRAP, t = dp(4))
            )
        }
        c.addView(
            tv(
                L.t("Every lock plants a flower, every 5th a bush, every 10th a tree.", "প্রতি লকে একটা ফুল, প্রতি ৫ম লকে ঝোপ, প্রতি ১০ম লকে গাছ লাগে।"),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = dp(6))
        )
    }

    // garden animals: unlock at garden-size milestones
    c.addView(tv(L.t("Garden animals", "বাগানের প্রাণী"), 15f, C.text, 1), lp(MATCH, WRAP, t = dp(16)))
    for (a in GardenAnimals.ALL) {
        val got = plants.size >= a.need
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        r.alpha = if (got) 1f else 0.45f
        r.addView(AnimalView(this, a.id), lp(dp(40), dp(40), r = dp(12)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(L.t(a.nameEn, a.nameBn), 14f, C.text, 1))
        col.addView(
            tv(
                if (got) L.t(a.descEn, a.descBn)
                else L.t("Unlocks at ", "খুলবে ") + L.num(a.need.toString()) +
                    L.t(" plants", "টি গাছ হলে"),
                12f, C.muted
            )
        )
        r.addView(col, lp(0, WRAP, weight = 1f))
        if (got) r.addView(tv("✓", 16f, C.mint, 1))
        c.addView(r)
    }
    return c
}

/** Mimi the focus pet: level + mood from focus activity, feed/pet buttons. */
fun MainActivity.petCard(): View {
    val c = card()
    c.addView(section(L.t("Mimi, your focus pet", "মিমি, তোমার ফোকাস পোষা প্রাণী")))
    val fs = Store.focusStats(this)
    val mood = Pet.mood(this)
    val pet = PetView(this, mood)
    c.addView(pet, lp(MATCH, dp(180), t = dp(8)))
    val (moodEn, moodBn) = Pet.moodText(mood)
    c.addView(
        tv(
            L.t("Level ", "লেভেল ") + L.num(Pet.level(fs.completed).toString()) +
                " · " + L.t(moodEn, moodBn),
            16f, C.text, 1
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    val st = Pet.state(this)
    c.addView(
        tv(
            L.t("Fed ", "খাওয়ানো হয়েছে ") + L.num(st.fed.toString()) +
                L.t(" times · petted ", " বার · আদর করা হয়েছে ") +
                L.num(st.petted.toString()) + L.t(" times", " বার"),
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    if (st.lastFed > 0L) {
        c.addView(
            tv(L.t("Last fed: ", "শেষ খাওয়ানো: ") + fmtDate(st.lastFed) + " · " + fmtClock(st.lastFed), 12f, C.muted),
            lp(MATCH, WRAP, t = dp(2))
        )
    }
    c.addView(
        tv(
            L.t("Mimi grows with your focus — one level per 5 completed locks.", "মিমি তোমার ফোকাসের সাথে বড় হয় — প্রতি ৫টা লকে এক লেভেল।"),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val btnRow = LinearLayout(this)
    btnRow.orientation = LinearLayout.HORIZONTAL
    val feedBtn = outlineBtn(L.t("Feed", "খাওয়ান"), C.primary)
    feedBtn.setOnClickListener {
        if (Pet.feed(this)) {
            toast(L.t("Mimi enjoyed the meal!", "মিমি খেয়ে খুশি!"))
            go(2)
        } else {
            toast(L.t("Mimi just ate — try again in a bit", "মিমি এইমাত্র খেয়েছে — একটু পরে আবার চেষ্টা করুন"))
        }
    }
    val petBtn = outlineBtn(L.t("Pet", "আদর করুন"), C.primary)
    petBtn.setOnClickListener {
        Pet.pet(this)
        toast(L.t("Mimi purrs happily", "মিমি খুশিতে গরগর করছে"))
        go(2)
    }
    btnRow.addView(feedBtn, lp(0, dp(48), r = dp(10), weight = 1f))
    btnRow.addView(petBtn, lp(0, dp(48), weight = 1f))
    c.addView(btnRow, lp(MATCH, WRAP, t = dp(12)))
    return c
}

/** Story: 10 chapters, one unlocked per completed lock. Tap to read. */
fun MainActivity.storyCard(): View {
    val c = card()
    c.addView(section(L.t("Story — The Quiet Garden", "গল্প — শান্ত বাগান")))
    val unlocked = Story.unlockedCount(this)
    val read = Story.readIds(this)
    c.addView(
        tv(
            L.num(unlocked.toString()) + " / " + L.num(Story.TOTAL.toString()) +
                L.t(" chapters unlocked · ", " অধ্যায় খোলা · ") +
                L.num(read.size.toString()) + L.t(" read", "টি পড়া"),
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(4))
    )
    c.addView(
        tv(
            L.t("Complete a lock to unlock the next chapter.", "পরের অধ্যায় খুলতে একটা লক শেষ করুন।"),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(2))
    )
    for (ch in Story.ALL) {
        val open = unlocked >= ch.id
        val wasRead = read.contains(ch.id)
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(10), 0, dp(10))
        r.alpha = if (open) 1f else 0.45f
        val num = tv(L.num(ch.id.toString()), 15f, if (open) C.mint else C.muted, 1)
        num.gravity = Gravity.CENTER
        num.background = rounded(C.card2, dp(16).toFloat())
        r.addView(num, lp(dp(32), dp(32), r = dp(12)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(L.t(ch.titleEn, ch.titleBn), 14f, C.text, 1))
        col.addView(
            tv(
                when {
                    wasRead -> L.t("Read", "পড়া হয়েছে")
                    open -> L.t("Tap to read", "পড়তে ট্যাপ করুন")
                    else -> L.t("Unlocks after ", "খুলবে ") + L.num(ch.id.toString()) +
                        L.t(" completed locks", "টি লক শেষ হলে")
                },
                12f, if (wasRead) C.primary else C.muted
            )
        )
        r.addView(col, lp(0, WRAP, weight = 1f))
        if (open) {
            r.addView(tv(if (wasRead) "✓" else "›", 18f, C.mint, 1))
            r.pressFeedback()
            r.setOnClickListener { openChapterDialog(ch) }
        } else {
            r.addView(tv("🔒", 15f, C.muted))
        }
        c.addView(r)
    }
    return c
}

/** Chapter reader: scrollable dialog; marks the chapter read on close. */
private fun MainActivity.openChapterDialog(ch: StoryChapter) {
    val sv = ScrollView(this)
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(12))
    body.addView(
        tv(L.t("Chapter ", "অধ্যায় ") + L.num(ch.id.toString()), 13f, C.muted, 1),
        lp(MATCH, WRAP)
    )
    body.addView(tv(L.t(ch.titleEn, ch.titleBn), 20f, C.text, 1), lp(MATCH, WRAP, t = dp(4)))
    body.addView(
        tv(L.t(ch.bodyEn, ch.bodyBn), 15f, C.textSoft),
        lp(MATCH, WRAP, t = dp(12))
    )
    sv.addView(body)
    dlg()
        .setTitle(L.t("The Quiet Garden", "শান্ত বাগান"))
        .setView(sv)
        .setPositiveButton(L.t("Done", "ঠিক আছে")) { _, _ ->
            Story.markRead(this, ch.id)
            go(2)
        }
        .show()
}

// =====================================================================
// GOALS & PLAY (build 57): daily routine, lucky wheel, focus journey,
// mini-games
// =====================================================================

/**
 * Daily routine: a standing task checklist on Home. When the switch is on
 * and the day ends with tasks undone, a strict 30-minute consequence lock
 * starts the next morning (see Routine.checkDayEnd — it fires at most
 * once per day, and only with a snapshot proving the tasks existed).
 */
fun MainActivity.routineCard(): View {
    val c = card()
    c.addView(section(L.t("Daily routine", "দৈনিক রুটিন")))
    val on = Routine.isOn(this)
    // enable toggle + what it means, up front
    val toggleRow = LinearLayout(this)
    toggleRow.gravity = Gravity.CENTER_VERTICAL
    toggleRow.setPadding(0, dp(10), 0, dp(6))
    val tcol = LinearLayout(this)
    tcol.orientation = LinearLayout.VERTICAL
    tcol.addView(tv(L.t("Routine + consequence lock", "রুটিন + শাস্তি লক"), 15f, C.text, 1))
    tcol.addView(
        tv(
            L.t("Miss a task and a strict 30-minute lock starts tomorrow morning.", "কোনো কাজ বাকি থাকলে কাল সকালে কঠোর ৩০ মিনিটের লক শুরু হবে।"),
            12f, C.muted
        )
    )
    toggleRow.addView(tcol, lp(0, WRAP, weight = 1f))
    toggleRow.addView(toggle(on) { checked ->
        Routine.setOn(this, checked)
        go(0)
    })
    c.addView(toggleRow)
    if (!on) {
        c.addView(
            tv(L.t("Turn it on to plan your day — and mean it.", "দিনের পরিকল্পনা করতে চালু করুন — আর সত্যিই মেনে চলুন।"), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(4))
        )
        return c
    }
    Routine.snapshotToday(this)
    val tasks = Routine.list(this)
    val done = Routine.doneToday(this)
    val doneCount = tasks.count { it.id in done }
    // progress line (+ a small celebration when the whole day is done)
    if (tasks.isNotEmpty()) {
        val allDone = doneCount == tasks.size
        c.addView(
            tv(
                if (allDone) L.t("All tasks done today — well done!", "আজকের সব কাজ শেষ — দারুণ!")
                else L.num(doneCount.toString()) + " / " + L.num(tasks.size.toString()) +
                    L.t(" tasks done", "টি কাজ শেষ"),
                14f, if (allDone) C.mint else C.text, 1
            ),
            lp(MATCH, WRAP, t = dp(8))
        )
        val full7 = Routine.fullDaysLast7(this)
        if (full7 > 0) {
            c.addView(
                tv(
                    L.t("Full routine days this week: ", "এই সপ্তাহে সম্পূর্ণ রুটিন দিন: ") +
                        L.num(full7.toString()),
                    12f, C.muted
                ),
                lp(MATCH, WRAP, t = dp(2))
            )
        }
    }
    // task rows: tap the row to tick it off, ✎ to edit, ✕ to delete
    for (t in tasks) {
        val isDone = t.id in done
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))
        val box = tv(if (isDone) "✓" else "○", 18f, if (isDone) C.mint else C.muted, 1)
        box.gravity = Gravity.CENTER
        box.background = rounded(if (isDone) C.selFill else C.card2, dp(10).toFloat())
        r.addView(box, lp(dp(32), dp(32), r = dp(12)))
        r.addView(tv(t.text, 15f, if (isDone) C.muted else C.text), lp(0, WRAP, weight = 1f))
        val editBtn = tv("✎", 16f, C.muted)
        editBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        editBtn.pressFeedback()
        editBtn.setOnClickListener { editTaskDialog(t) }
        r.addView(editBtn)
        val del = tv("✕", 16f, C.muted)
        del.setPadding(dp(8), dp(8), dp(4), dp(8))
        del.pressFeedback()
        del.setOnClickListener {
            Routine.delete(this, t.id)
            go(0)
        }
        r.addView(del)
        r.pressFeedback()
        r.setOnClickListener {
            Routine.toggleDone(this, t.id)
            go(0)
        }
        c.addView(r)
    }
    if (tasks.isEmpty()) {
        c.addView(
            tv(L.t("No tasks yet — add your first one below.", "এখনো কোনো কাজ নেই — নিচে প্রথমটা যোগ করুন।"), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(8))
        )
    }
    // add row
    val et = edit(L.t("New task…", "নতুন কাজ…"), false)
    et.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    val addBtn = primaryBtn(L.t("Add", "যোগ করুন"))
    val inputRow = LinearLayout(this)
    inputRow.gravity = Gravity.CENTER_VERTICAL
    inputRow.addView(et, lp(0, WRAP, r = dp(10), weight = 1f))
    inputRow.addView(addBtn, lp(dp(84), dp(48)))
    c.addView(inputRow, lp(MATCH, WRAP, t = dp(12)))
    addBtn.setOnClickListener {
        if (Routine.add(this, et.text.toString())) {
            go(0)
        } else {
            toast(L.t("Write a task first (max 20)", "আগে কাজ লিখুন (সর্বোচ্চ ২০টি)"))
        }
    }
    return c
}

/** Edit one routine task's text (the done state for today is kept). */
private fun MainActivity.editTaskDialog(t: RoutineTask) {
    val et = edit(L.t("Task", "কাজ"), false)
    et.setText(t.text)
    et.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(et, lp(MATCH, WRAP))
    // The positive button is wired after show() so a failed validation keeps
    // the dialog (and the typed text) instead of dismissing it.
    val dialog = dlg()
        .setTitle(L.t("Edit task", "কাজ সম্পাদনা"))
        .setView(body)
        .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
        .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        .show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (Routine.update(this, t.id, et.text.toString())) {
            dialog.dismiss()
            go(0)
        } else {
            toast(L.t("Write something first", "আগে কিছু লিখুন"))
        }
    }
}

/**
 * Lucky wheel: 1 spin per completed lock (max 5 banked). The spin is spent
 * only when the wheel actually lands; rewards are cosmetic only — never
 * break minutes, never lock time.
 */
fun MainActivity.wheelCard(): View {
    val c = card()
    c.addView(section(L.t("Lucky wheel", "ভাগ্যের চাকা")))
    val spins = LuckyWheel.spinsAvailable(this)
    c.addView(
        tv(
            L.t("Spins: ", "স্পিন: ") + L.num(spins.toString()) +
                L.t("  ·  1 spin per completed lock (max 5)", "  ·  প্রতি লকে ১টি স্পিন (সর্বোচ্চ ৫টি)"),
            13f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(6))
    )
    val wheel = WheelView(this, LuckyWheel.REWARDS)
    c.addView(wheel, lp(MATCH, dp(300), t = dp(10)))
    val spinBtn = primaryBtn(L.t("Spin the wheel", "চাকা ঘুরান"))
    spinBtn.alpha = if (spins > 0) 1f else 0.45f
    spinBtn.setOnClickListener {
        if (wheel.isSpinning) return@setOnClickListener
        if (LuckyWheel.spinsAvailable(this) <= 0) {
            toast(L.t("No spins left — complete a lock to earn one", "স্পিন নেই — স্পিন পেতে একটা লক শেষ করুন"))
            return@setOnClickListener
        }
        val target = (0 until LuckyWheel.REWARDS.size).random()
        spinBtn.alpha = 0.45f
        wheel.spinTo(target) {
            if (!LuckyWheel.useSpin(this)) return@spinTo
            val reward = LuckyWheel.REWARDS[target]
            val line = LuckyWheel.applyReward(this, reward)
            dlg()
                .setTitle(L.t("You won: ", "আপনি জিতলেন: ") + L.t(reward.labelEn, reward.labelBn))
                .setMessage(line)
                .setPositiveButton(L.t("Nice!", "দারুণ!")) { _, _ -> go(2) }
                .setOnCancelListener { go(2) }
                .show()
        }
    }
    c.addView(spinBtn, lp(MATCH, dp(52), t = dp(12)))
    val stars = LuckyWheel.stars(this)
    if (stars > 0) {
        c.addView(
            tv(L.t("Lucky stars: ", "ভাগ্যের তারা: ") + L.num(stars.toString()), 13f, C.muted),
            lp(MATCH, WRAP, t = dp(8))
        )
    }
    val last = LuckyWheel.lastReward(this)
    if (last != null) {
        val def = LuckyWheel.REWARDS.firstOrNull { it.id == last.first }
        if (def != null) {
            c.addView(
                tv(
                    L.t("Last win: ", "শেষ জয়: ") + L.t(def.labelEn, def.labelBn) +
                        " · " + fmtDate(last.second),
                    12f, C.muted
                ),
                lp(MATCH, WRAP, t = dp(4))
            )
        }
    }
    c.addView(
        tv(
            L.t("Rewards: garden plants, a pet treat, a lucky quote, a fun fact, or a lucky star. Never break minutes.", "পুরস্কার: বাগানের গাছ, পোষা প্রাণীর ট্রিট, ভাগ্যের উক্তি, মজার তথ্য বা ভাগ্যের তারা। কখনো বিরতির মিনিট নয়।"),
            12f, C.muted
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

/** Focus journey: Dhaka to Cox's Bazar, 400 km, driven by travelled distance. */
fun MainActivity.journeyCard(): View {
    val c = card()
    c.addView(section(L.t("Focus journey — Dhaka to Cox's Bazar", "ফোকাস যাত্রা — ঢাকা থেকে কক্সবাজার")))
    val km = Journey.totalKm(this)
    val arrived = km >= Journey.TOTAL_KM
    c.addView(JourneyTrackView(this, km), lp(MATCH, dp(56), t = dp(12)))
    c.addView(
        tv(
            L.num(String.format(Locale.US, "%.1f", km)) + " / " +
                L.num(Journey.TOTAL_KM.toString()) + L.t(" km", " কিমি"),
            20f, C.text, 1
        ),
        lp(MATCH, WRAP, t = dp(8))
    )
    val status = if (arrived) {
        L.t("You arrived in Cox's Bazar! The sea is yours.", "তুমি কক্সবাজার পৌঁছে গেছ! সমুদ্র এখন তোমার।")
    } else {
        val next = Journey.nextStop(km)
        val prev = Journey.reached(km).lastOrNull()
        val prevName = if (prev != null) L.t(prev.nameEn, prev.nameBn) else L.t("Dhaka", "ঢাকা")
        val nextName = if (next != null) L.t(next.nameEn, next.nameBn) else ""
        val left = if (next != null) next.km - km else 0.0
        L.t("Past ", "পার হয়েছ ") + prevName +
            L.t(" · next: ", " · পরের গন্তব্য: ") + nextName +
            " · " + L.num(String.format(Locale.US, "%.1f", left)) +
            L.t(" km to go", " কিমি বাকি")
    }
    c.addView(tv(status, 13f, if (arrived) C.mint else C.muted), lp(MATCH, WRAP, t = dp(4)))
    // progress bar toward the destination
    val progRow = LinearLayout(this)
    progRow.orientation = LinearLayout.HORIZONTAL
    progRow.background = rounded(C.card2, dp(6).toFloat())
    val fill = View(this)
    fill.background = rounded(C.primary, dp(6).toFloat())
    val frac = (km / Journey.TOTAL_KM).toFloat().coerceIn(0f, 1f)
    progRow.addView(fill, lp(0, dp(10), weight = frac.coerceAtLeast(0.02f)))
    progRow.addView(View(this), lp(0, dp(10), weight = 1f - frac.coerceAtLeast(0.02f)))
    c.addView(progRow, lp(MATCH, WRAP, t = dp(10)))
    // milestone stops
    for (s in Journey.STOPS) {
        val got = km >= s.km
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(6), 0, dp(6))
        r.alpha = if (got) 1f else 0.5f
        r.addView(tv(if (got) "●" else "○", 14f, if (got) C.mint else C.muted, 1), lp(dp(24), WRAP))
        r.addView(tv(L.t(s.nameEn, s.nameBn), 14f, C.text, 1), lp(0, WRAP, weight = 1f))
        r.addView(tv(L.num(s.km.toString()) + L.t(" km", " কিমি"), 13f, C.muted))
        c.addView(r)
    }
    c.addView(
        tv(L.t("Distance counts while the location lock records your route.", "লোকেশন লক তোমার রুট রেকর্ড করার সময় দূরত্ব গোনা হয়।"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(8))
    )
    return c
}

/** Mini-games: tiny breathers. Best scores are kept per game. */
fun MainActivity.gamesCard(): View {
    val c = card()
    c.addView(section(L.t("Mini-games", "মিনি গেম")))
    c.addView(
        tv(L.t("Tiny breathers — nothing here can break a lock.", "ছোট্ট বিরতি — এখানে কিছুই লক ভাঙতে পারে না।"), 12f, C.muted),
        lp(MATCH, WRAP, t = dp(4))
    )
    fun gameRow(name: String, desc: String, best: String, play: () -> Unit) {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(10), 0, dp(10))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(name, 15f, C.text, 1))
        col.addView(tv(desc, 12f, C.muted))
        if (best.isNotEmpty()) col.addView(tv(best, 12f, C.primary, 1))
        r.addView(col, lp(0, WRAP, weight = 1f))
        val playBtn = outlineBtn(L.t("Play", "খেলুন"), C.primary)
        playBtn.setOnClickListener { play() }
        r.addView(playBtn, lp(dp(88), dp(44)))
        c.addView(r)
    }
    val memBest = MiniGames.memoryBest(this)
    gameRow(
        L.t("Memory match", "মেমরি ম্যাচ"),
        L.t("Find all 6 pairs in as few moves as you can.", "কম চালে ৬ জোড়া কার্ড মিলাও।"),
        if (memBest > 0) L.t("Best: ", "সেরা: ") + L.num(memBest.toString()) + L.t(" moves", " চালে") else ""
    ) { openMemoryGame() }
    val reactBest = MiniGames.reactionBest(this)
    gameRow(
        L.t("Reaction tap", "রিঅ্যাকশন ট্যাপ"),
        L.t("Tap the moment it turns green.", "সবুজ হলেই ট্যাপ করো।"),
        if (reactBest > 0) L.t("Best: ", "সেরা: ") + L.num(reactBest.toString()) + L.t(" ms", " মি.সে.") else ""
    ) { openReactionGame() }
    val guessBest = MiniGames.guessBest(this)
    gameRow(
        L.t("Number guess", "সংখ্যা অনুমান"),
        L.t("Guess the number from 1 to 50.", "১ থেকে ৫০-এর মধ্যে সংখ্যাটা অনুমান করো।"),
        if (guessBest > 0) L.t("Best: ", "সেরা: ") + L.num(guessBest.toString()) + L.t(" guesses", " বার অনুমানে") else ""
    ) { openGuessGame() }
    return c
}

/** Memory match: 12 cards, 6 pairs. Fewest moves wins. */
private fun MainActivity.openMemoryGame() {
    val emojis = listOf("🌸", "🌿", "⭐", "🐱", "🦋", "🐝")
    val deck = (emojis + emojis).shuffled()
    // alive[] guards the delayed flip-back: the dialog may be dismissed
    // while a mismatch is still waiting to flip back.
    val alive = booleanArrayOf(true)
    val handler = Handler(Looper.getMainLooper())
    var first: Int? = null
    var lockBoard = false
    var moves = 0
    var matched = 0
    val tiles = ArrayList<TextView>()
    val movesText = tv(L.t("Moves: ", "চাল: ") + L.num("0"), 14f, C.muted, 1)
    val grid = LinearLayout(this)
    grid.orientation = LinearLayout.VERTICAL
    for (row in 0 until 3) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (colI in 0 until 4) {
            val idx = row * 4 + colI
            val tile = tv("?", 22f, C.text, 1)
            tile.gravity = Gravity.CENTER
            tile.background = rounded(C.card2, dp(10).toFloat())
            tile.pressFeedback()
            tile.setOnClickListener {
                // Matched tiles and the already-flipped first tile can't be
                // picked again (tapping the first tile twice must not count
                // as a match with itself).
                if (lockBoard || tile.tag == true || first == idx) return@setOnClickListener
                tile.text = deck[idx]
                tile.background = rounded(C.selFill, dp(10).toFloat())
                val f = first
                if (f == null) {
                    first = idx
                } else {
                    moves++
                    movesText.text = L.t("Moves: ", "চাল: ") + L.num(moves.toString())
                    if (deck[f] == deck[idx]) {
                        tiles[f].tag = true
                        tile.tag = true
                        tiles[f].background = rounded(C.primary, dp(10).toFloat())
                        tile.background = rounded(C.primary, dp(10).toFloat())
                        matched++
                        first = null
                        if (matched == 6) {
                            MiniGames.recordMemory(this, moves)
                            movesText.text = L.t("You did it in ", "তুমি পেরেছ ") +
                                L.num(moves.toString()) + L.t(" moves!", " চালে!")
                        }
                    } else {
                        lockBoard = true
                        val fTile = tiles[f]
                        handler.postDelayed({
                            if (!alive[0]) return@postDelayed
                            fTile.text = "?"
                            fTile.background = rounded(C.card2, dp(10).toFloat())
                            tile.text = "?"
                            tile.background = rounded(C.card2, dp(10).toFloat())
                            first = null
                            lockBoard = false
                        }, 700)
                        first = null
                    }
                }
            }
            tiles.add(tile)
            r.addView(tile, lp(0, dp(64), r = if (colI < 3) dp(8) else 0, t = if (row > 0) dp(8) else 0, weight = 1f))
        }
        grid.addView(r)
    }
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(movesText, lp(MATCH, WRAP, b = dp(12)))
    body.addView(grid)
    val dialog = dlg()
        .setTitle(L.t("Memory match", "মেমরি ম্যাচ"))
        .setView(body)
        .setPositiveButton(L.t("Done", "ঠিক আছে"), null)
        .show()
    dialog.setOnDismissListener { alive[0] = false }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        dialog.dismiss()
        go(2)
    }
}

/** Reaction tap: wait for green, then tap as fast as you can. */
private fun MainActivity.openReactionGame() {
    val alive = booleanArrayOf(true)
    val handler = Handler(Looper.getMainLooper())
    var state = 0 // 0 idle, 1 waiting for green, 2 ready to tap
    var readyAt = 0L
    val statusText = tv(L.t("Tap Start, wait for green, then tap fast!", "স্টার্ট ট্যাপ করো, সবুজের অপেক্ষা করো, তারপর দ্রুত ট্যাপ করো!"), 14f, C.muted)
    val pad = tv(L.t("Tap to start", "শুরু করতে ট্যাপ করো"), 18f, C.text, 1)
    pad.gravity = Gravity.CENTER
    pad.background = rounded(C.card2, dp(16).toFloat())
    pad.pressFeedback()
    val startBtn = primaryBtn(L.t("Start", "শুরু"))
    fun resetPad() {
        state = 0
        pad.text = L.t("Tap to start", "শুরু করতে ট্যাপ করো")
        pad.background = rounded(C.card2, dp(16).toFloat())
        startBtn.alpha = 1f
    }
    startBtn.setOnClickListener {
        if (state != 0) return@setOnClickListener
        state = 1
        startBtn.alpha = 0.45f
        pad.text = L.t("Wait for green…", "সবুজের অপেক্ষা…")
        pad.background = rounded(C.danger, dp(16).toFloat())
        statusText.text = L.t("Don't tap yet!", "এখনো ট্যাপ কোরো না!")
        val delay = (1200L..3200L).random()
        handler.postDelayed({
            if (!alive[0] || state != 1) return@postDelayed
            state = 2
            readyAt = System.currentTimeMillis()
            pad.text = L.t("TAP NOW!", "এখন ট্যাপ করো!")
            pad.background = rounded(C.primary, dp(16).toFloat())
        }, delay)
    }
    pad.setOnClickListener {
        when (state) {
            1 -> {
                // Too soon: the pending green is ignored (state is no longer 1).
                state = 0
                statusText.text = L.t("Too soon! Tap Start and try again.", "খুব তাড়াতাড়ি! স্টার্ট ট্যাপ করে আবার চেষ্টা করো।")
                resetPad()
            }
            2 -> {
                val ms = System.currentTimeMillis() - readyAt
                MiniGames.recordReaction(this, ms)
                val best = MiniGames.reactionBest(this)
                statusText.text = L.t("Your time: ", "তোমার সময়: ") + L.num(ms.toString()) +
                    L.t(" ms", " মি.সে.") +
                    if (best == ms) L.t(" — new best!", " — নতুন সেরা!") else ""
                resetPad()
            }
            else -> { /* idle: use the Start button */ }
        }
    }
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(statusText, lp(MATCH, WRAP))
    body.addView(pad, lp(MATCH, dp(160), t = dp(14)))
    body.addView(startBtn, lp(MATCH, dp(52), t = dp(14)))
    val dialog = dlg()
        .setTitle(L.t("Reaction tap", "রিঅ্যাকশন ট্যাপ"))
        .setView(body)
        .setPositiveButton(L.t("Done", "ঠিক আছে"), null)
        .show()
    dialog.setOnDismissListener { alive[0] = false }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        dialog.dismiss()
        go(2)
    }
}

/** Number guess: 1–50, higher/lower hints, fewest guesses wins. */
private fun MainActivity.openGuessGame() {
    var target = (1..50).random()
    var tries = 0
    var won = false
    val hintText = tv(L.t("I'm thinking of a number from 1 to 50.", "আমি ১ থেকে ৫০-এর মধ্যে একটা সংখ্যা ভাবছি।"), 14f, C.muted)
    val triesText = tv(L.t("Guesses: ", "অনুমান: ") + L.num("0"), 13f, C.muted, 1)
    val et = edit(L.t("Your guess", "তোমার অনুমান"), true)
    et.gravity = Gravity.CENTER
    val guessBtn = primaryBtn(L.t("Guess", "অনুমান করো"))
    val againBtn = outlineBtn(L.t("Play again", "আবার খেলুন"), C.primary)
    againBtn.visibility = View.GONE
    guessBtn.setOnClickListener {
        if (won) return@setOnClickListener
        val g = et.text.toString().trim().toIntOrNull()
        if (g == null || g !in 1..50) {
            toast(L.t("Enter a number from 1 to 50", "১ থেকে ৫০-এর মধ্যে সংখ্যা লিখুন"))
            return@setOnClickListener
        }
        tries++
        triesText.text = L.t("Guesses: ", "অনুমান: ") + L.num(tries.toString())
        et.text.clear()
        when {
            g < target -> hintText.text = L.t("Higher!", "আরও বড়!")
            g > target -> hintText.text = L.t("Lower!", "আরও ছোট!")
            else -> {
                won = true
                MiniGames.recordGuess(this, tries)
                hintText.text = L.t("Correct! The number was ", "ঠিক! সংখ্যাটা ছিল ") +
                    L.num(target.toString()) + L.t(" — ", " — ") +
                    L.num(tries.toString()) + L.t(" guesses.", " বার অনুমানে।")
                guessBtn.alpha = 0.45f
                againBtn.visibility = View.VISIBLE
            }
        }
    }
    againBtn.setOnClickListener {
        target = (1..50).random()
        tries = 0
        won = false
        triesText.text = L.t("Guesses: ", "অনুমান: ") + L.num("0")
        hintText.text = L.t("I'm thinking of a new number from 1 to 50.", "আমি ১ থেকে ৫০-এর মধ্যে নতুন সংখ্যা ভাবছি।")
        guessBtn.alpha = 1f
        againBtn.visibility = View.GONE
    }
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(dp(20), dp(12), dp(20), dp(4))
    body.addView(hintText, lp(MATCH, WRAP))
    body.addView(triesText, lp(MATCH, WRAP, t = dp(6)))
    body.addView(et, lp(MATCH, WRAP, t = dp(14)))
    body.addView(guessBtn, lp(MATCH, dp(52), t = dp(12)))
    body.addView(againBtn, lp(MATCH, dp(48), t = dp(10)))
    val dialog = dlg()
        .setTitle(L.t("Number guess", "সংখ্যা অনুমান"))
        .setView(body)
        .setPositiveButton(L.t("Done", "ঠিক আছে"), null)
        .show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        dialog.dismiss()
        go(2)
    }
}
