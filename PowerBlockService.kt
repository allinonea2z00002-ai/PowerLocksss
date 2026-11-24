package com.powerlock.app

import android.view.accessibility.AccessibilityWindowInfo
import android.os.Build
import android.content.IntentFilter
import android.content.Context
import android.content.BroadcastReceiver
import android.widget.TextView
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Power menu protection engine (event driven, no polling).
 *
 * While a session is active:
 *  1. the power menu is recognised from the window event itself (class / text), with no extra IPC,
 *  2. if needed, the window content is scanned (throttled, only for system UI),
 *  3. Back is pressed immediately, then Back again and Home shortly after, so the user ends on the Home screen,
 *  4. a short "PowerLock active" shield blocks touches on the slider while the menu is closing.
 * The protection state is cached from SharedPreferences, so events do not read storage.
 * Settings / uninstall pages that show "PowerLock" are sent Home when "protect app settings" is on.
 * App block (Protection section) also runs here while a session is active: fully
 * blocked apps are covered instantly by a protection screen (with a Home button),
 * Reels-blocked apps get Back (repeated while the feed is still showing)
 * when a Reels / Shorts screen is detected, and apps with search block get
 * Back (with the typed query cleared) as soon as the user starts typing in
 * search — the keyboard still appears, and searching inside a chat or
 * channel is not blocked.
 * Home is never used for Reels: it sends YouTube Shorts into picture-in-picture
 * instead of closing it.
 * Site block also runs here: when the DNS VPN sees a query for a blocked
 * domain it broadcasts, and this service covers the screen with the block
 * screen; and when URL detection is on, the browser address bar is watched
 * directly, so a blocked site is covered even when the DNS option is off.
 * The address bar is found by view ID, with a fallback scan for URL-like
 * text in the window tree, and the check runs on text, window and content
 * changes plus once when a lock starts — so a page already open, or a
 * navigation inside an open tab, is still caught. Keyword block rides the
 * same watching: any keyword anywhere in the full URL covers the screen
 * too, and a keyword typed in ANY text field, in ANY app (browsers,
 * search, WhatsApp/Messenger/Telegram — the user's own choice: keywords
 * block wherever they are typed on the phone) does the same. Password
 * fields are never read.
 */
class PowerBlockService : AccessibilityService() {

    companion object {
        /** Diagnostic log: what the service sees on reels screens. Read by the
         * Protection screen's Diagnostic card. Capped at 120 lines. */
        val debugLog = mutableListOf<String>()

        /** Plain-language summary of the same scans (2 lines per scan), shown
         * in the Diagnostic card so it reads simply. Capped at 60 lines. */
        val debugSummary = mutableListOf<String>()

    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastAction = 0L
    private var lastMenuScan = 0L
    private var lastGuardScan = 0L

    @Volatile
    private var provisionalCheckRunning = false // a location check is running for the power menu

    private var overlay: View? = null
    private var overlaySub: TextView? = null
    private var overlayAttached = false

    @Volatile
    private var active = false

    @Volatile
    private var endAt = 0L

    @Volatile
    private var locProtecting = false // location lock: blocked while at a saved place, no session

    @Volatile
    private var guardOn = true

    @Volatile
    private var focusOn = false

    @Volatile
    private var focusApps: Set<String> = emptySet()

    private var lastFocusAction = 0L

    // App block (Protection section): full block + reels block + search block.
    @Volatile
    private var abOn = true

    @Volatile
    private var abFull: Set<String> = emptySet()

    @Volatile
    private var abReels: Set<String> = emptySet()

    @Volatile
    private var abMsg: Set<String> = emptySet()

    // Daily limits: foreground time per app, counted without any lock running.
    @Volatile
    private var limitMap: Map<String, Int> = emptyMap()
    private var usagePkg: String? = null
    private var usageSince = 0L
    private var usageDay = ""
    private val usageToday = HashMap<String, Long>()
    private var imePkg: String? = null
    private var imeCheckedAt = 0L
    private var limitBlockActive = false // the overlay is up for a daily limit (no lock needed)
    private var blockMsg = "" // the user's own message on the block screen


    private var lastAppBlockAction = 0L
    private var lastReelsScan = 0L
    private var lastDebugScan = 0L
    private var lastReelsBack = 0L // cooldown after a reels Back (the close animation)
    private var lastMsgScan = 0L
    private var lastMsgAction = 0L

    // Full-block protection screen: covers a blocked app the instant it opens.
    private var blockOverlay: View? = null
    private var blockOverlayTitle: TextView? = null
    private var blockOverlaySub: TextView? = null
    private var blockOverlayMsg: TextView? = null
    private var blockOverlayIcon: IconView? = null
    private var blockOverlayAttached = false
    private var blockedPkg: String? = null
    private var lastBlockPkg: String? = null
    private var lastBlockAt = 0L
    private var lastHomeFallbackAt = 0L // throttles the HOME fallback on add failure
    private val appLabelCache = HashMap<String, String>()

    // Site block (URL detection + DNS-block overlay): the accessibility side
    // of the site block. Works during a lock even when the DNS/VPN option is
    // off — it reads the browser address bar, not the network.
    @Volatile
    private var siteUrlOn = false
    // Keyword block (third site-block option): any keyword anywhere in the
    // address-bar URL blocks the site. Independent of the URL-detection
    // switch — it rides the same address-bar watching.
    @Volatile
    private var siteKwOn = false
    private var lastCheckedKwUrl = ""
    private var lastForegroundPkg: String? = null
    private var lastBrowserScanAt = 0L
    private var lastTypedScanAt = 0L // throttles the non-browser typed-field scan (once per 2 s)
    private var lastVisibleTextScan = 0L // throttles the full visible-text keyword scan (once per 2 s)
    private var lastUrlCheckAt = 0L
    private var lastCheckedUrl = ""
    private var lastSiteOverlayAt = 0L
    private var lastSiteOverlayDomain = ""
    // Uninstall protection: apps whose uninstall dialog is blocked instantly,
    // lock or no lock. Labels are cached and rebuilt only when the list changes.
    private var uninstallProtectPkgs: Set<String> = emptySet()
    private var uninstallProtectLabels: Map<String, String> = emptyMap()
    private var lastUninstallScan = 0L
    private var lastUninstallBlock = 0L
    @Volatile
    private var urlCheckRunning = false
    private var siteReceiverOn = false
    // Lock-start detection: readState() runs on every lock-pref write, so
    // these track the false→true transition — only a new lock start scans
    // the foreground browser at once (a page already open before the lock
    // must still be caught; no navigation event fires for it).
    private var lastLockActive = false
    private var lastLockStart = 0L
    // Keyword block on typing: typed text is checked INSTANTLY on
    // every keystroke — no debounce, no throttle. The match is an
    // Aho-Corasick scan over the built-in + custom keywords (linear in
    // the text, microseconds), so checking per keystroke is cheap and
    // the block fires the moment the typed text first contains a
    // keyword. Only the last checked text is kept, so the same text is
    // not re-checked while the block screen is up; the key resets when
    // the screen hides, so returning to the same text re-checks and
    // re-blocks. The typed text itself is never logged or stored.
    private var lastCheckedTypedKw = ""

    /** Fallback browser packages: the well-known browsers, kept so URL
     * detection still works on a device where the dynamic query below
     * returns nothing (some firmwares filter the query). The real
     * watched set is discovered at runtime — see [discoveredBrowsers]
     * — so ANY browser installed on the phone is covered, not just the
     * names listed here. */
    private val knownBrowsers = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "com.chrome.canary", "org.chromium.chrome",
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser", "com.microsoft.emmx",
        "com.brave.browser", "com.opera.browser", "com.opera.mini.native",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser",
        "com.kiwibrowser.browser", "com.UCMobile.intl",
        "mark.via.gp", "mark.via"
    )

    /** Browsers discovered on THIS phone: every installed app that can
     * open a web link (ACTION_VIEW for http/https), found with
     * PackageManager. Cached — the query is IPC, so it runs only on
     * service start and when a lock starts, never on an accessibility
     * event. The manifest's <queries> entry for VIEW http/https is what
     * lets the query see user-installed browsers on Android 11+;
     * without it the system would filter the results. */
    @Volatile
    private var discoveredBrowsers: Set<String> = emptySet()

    /** True when [pkg] is a browser on this phone: found by the runtime
     * query, or a known browser as fallback. */
    private fun isBrowser(pkg: String?): Boolean {
        if (pkg == null) return false
        return discoveredBrowsers.contains(pkg) || knownBrowsers.contains(pkg)
    }

    /** (Re)discover the browsers installed on this phone. Cheap enough
     * to run on service start and on lock start; never called from an
     * accessibility event. The set is only replaced when the query
     * returns something, so a failed query can never turn detection
     * off — the previous set (and the known-browser fallback) stay. */
    private fun refreshBrowsers() {
        try {
            val pm = packageManager
            val found = HashSet<String>()
            for (scheme in listOf("http", "https")) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://www.example.com"))
                try {
                    for (ri in pm.queryIntentActivities(intent, 0)) {
                        val p = ri.activityInfo?.packageName ?: continue
                        if (p != packageName) found.add(p)
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "browser query ($scheme) failed", ex)
                }
            }
            if (found.isNotEmpty()) discoveredBrowsers = found
        } catch (ex: Exception) {
            Log.e(TAG, "browser discovery failed", ex)
        }
    }

    /** Address-bar view IDs (full "package:id/name" form). Chromium-based
     * browsers share the "url_bar" ID. Browser updates can rename these —
     * the fallback tree scan below covers a missed ID, and a missed ID
     * only means no URL detection, never a crash. */
    private val addressBarIds = setOf(
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box",
        "com.chrome.beta:id/url_bar", "com.chrome.dev:id/url_bar",
        "com.chrome.canary:id/url_bar", "org.chromium.chrome:id/url_bar",
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.firefox:id/url_bar_title",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.sec.android.app.sbrowser:id/url_bar",
        "com.microsoft.emmx:id/url_bar",
        "com.microsoft.emmx:id/search_box",
        "com.brave.browser:id/url_bar",
        "com.vivaldi.browser:id/url_bar",
        "com.kiwibrowser.browser:id/url_bar",
        "com.duckduckgo.mobile.android:id/omnibarTextInput"
    )

    /** The VPN saw a DNS query for a blocked domain: cover the screen. */
    private val siteBlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                val domain = i?.getStringExtra(SiteBlockService.EXTRA_BLOCKED_DOMAIN) ?: return
                onSiteBlocked(domain, lastForegroundPkg)
            } catch (ex: Exception) {
                Log.e(TAG, "site-blocked receiver failed", ex)
            }
        }
    }


    // Tab labels that mean a Reels / Shorts / Status feed is showing when selected.
    private val reelsTabMarkers = listOf(
        "Reels", "Shorts", "রিলস", "শর্টস", "Spotlight", "For You", "Following", "Updates"
    )
    private val reelsMarkersLower = listOf(
        "reels", "shorts", "রিলস", "শর্টস", "spotlight", "for you", "following", "updates"
    )

    private var lastKeepAliveCheck = 0L // throttles the revive check (once per 30s)

    // English + Bengali: the phone's system language decides the menu text.
    private val triggers = listOf(
        "Swipe to power off", "Swipe to restart", "Swipe for SOS",
        "Power off", "Restart",
        "পাওয়ার বন্ধ", "পাওয়ার অফ", "রিস্টার্ট", "বন্ধ করতে সোয়াইপ"
    )
    private val triggersLower = triggers.map { it.lowercase() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> readState() }
    private val hideTask = Runnable { hideOverlay() }
    private val backAgain = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private val backAgain2 = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private var lastWinScan = 0L
    private var receiverOn = false


    // The system announces "globalactions" the instant the power button is long-pressed,
    // before the menu is even drawn - the earliest possible trigger.
    private val closeDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                if (i?.getStringExtra("reason") == "globalactions" && protecting()) block()
            } catch (ex: Exception) {
                Log.e(TAG, "close-dialogs receiver failed", ex)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            L.load(this)
            readState()
            refreshBrowsers() // discover this phone's browsers before events flow
            loadUsage()
            Store.lock(this).registerOnSharedPreferenceChangeListener(prefsListener)
            Store.settings(this).registerOnSharedPreferenceChangeListener(prefsListener)
            ensureOverlay() // pre-build the shield so it can appear instantly
            ensureBlockOverlay() // pre-build the block screen too: it must appear instantly
            registerCloseDialogs()
            registerSiteBlocked()
            Store.kickService(this)
            Scheduler.onAlarm(this)
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }


    @Suppress("DEPRECATION")
    private fun registerCloseDialogs() {
        try {
            val f = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(closeDialogsReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(closeDialogsReceiver, f)
            }
            receiverOn = true
        } catch (ex: Exception) {
            Log.e(TAG, "register close-dialogs failed", ex)
        }
    }

    /** The VPN's blocked-site broadcast is ours only (setPackage), so it is not exported. */
    private fun registerSiteBlocked() {
        try {
            val f = IntentFilter(SiteBlockService.ACTION_SITE_BLOCKED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(siteBlockedReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(siteBlockedReceiver, f)
            }
            siteReceiverOn = true
        } catch (ex: Exception) {
            Log.e(TAG, "register site-blocked failed", ex)
        }
    }

    private fun readState() {
        val p = Store.lock(this)
        active = p.getBoolean("active", false)
        endAt = p.getLong("end", 0)
        locProtecting = p.getBoolean("loc_protecting", false)
        // the guard follows the session while one runs (locked at its start); otherwise it
        // follows the live "App settings protection" switch in Settings
        val sessionOn = active && System.currentTimeMillis() < endAt
        guardOn = if (sessionOn) p.getBoolean("s_guard", true)
        else Store.settings(this).getBoolean("guard", true)
        val st = Store.settings(this)
        focusOn = st.getBoolean("focus_on", false)
        focusApps = (st.getString("focus_apps", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        abOn = st.getBoolean("ab_on", true)
        abFull = Extra.appBlockFull(this)
        abReels = Extra.appBlockReels(this)
        abMsg = Extra.appBlockMsg(this)
        limitMap = Extra.dailyLimits(this)
        blockMsg = Extra.blockMessage(this)
        siteUrlOn = st.getBoolean("site_url_on", false)
        if (siteUrlOn) SiteBlockService.preloadBlocklist(this)
        siteKwOn = st.getBoolean("site_kw_on", true) // default ON: hard fallback
        if (siteKwOn) KeywordBlock.preload(this) // built-in keywords load in the background
        // lock just started? scan the foreground window at once: a page
        // already open before the lock (or a keyword already sitting in
        // a text field) must still be caught — no event fires for it.
        // readState runs on every lock-pref write
        // (heartbeats included), so only the false→true transition — or a
        // new start time — triggers a scan. Posted to the main thread: the
        // prefs listener can fire off-thread, and the scan needs the
        // service's window access.
        val start = p.getLong("start", 0)
        val lockStarted = active && (!lastLockActive || start != lastLockStart)
        lastLockActive = active
        lastLockStart = start
        if (lockStarted) {
            // a new lock: re-discover the browsers on this phone first
            // (a browser installed since the service started must be
            // covered too), then scan the foreground window at once
            refreshBrowsers()
            if (siteUrlOn || siteKwOn) {
                handler.post { scanBrowserNow() }
            }
        }
    }

    private fun protecting(): Boolean = (active && System.currentTimeMillis() < endAt) || locProtecting

    /**
     * The system re-binds this service after the process dies, so it is the one
     * watchdog the system itself brings back: if the keep-alive service has not
     * written a heartbeat for 90s while protection should be on, bring it back.
     */
    private fun maybeReviveKeepAlive() {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastKeepAliveCheck < 30_000) return
            lastKeepAliveCheck = now
            if (!protecting()) return
            val hb = Store.lock(this).getLong("kas_heartbeat", 0)
            if (System.currentTimeMillis() - hb < 90_000) return
            Store.kickService(this)
            KeepAliveService.restartSoon(this, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "revive keep-alive failed", e)
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        try {
            maybeReviveKeepAlive() // the system re-binds this service, so it revives the keep-alive service
            // daily limits work without a lock: track foreground time on every app switch
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) trackUsage(pkg)
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) {
                lastForegroundPkg = pkg
            }
            // Uninstall protection: runs before the lock-gated routing below —
            // a protected app's uninstall must be blocked instantly even with
            // no lock running.
            onUninstallAttempt(e)
            // URL detection (site block, second option) and keyword block
            // (third option): the browser address bar changed, a browser
            // window just opened, or a page loaded in an already-open tab.
            // In-tab navigation fires content-changed, NOT state-changed —
            // without that trigger a page load in an open tab is never
            // scanned, which is how a blocked site slipped through.
            // Either switch enables the watching.
            if ((siteUrlOn || siteKwOn) && protecting() && isBrowser(pkg)) {
                when (e.eventType) {
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onBrowserTextChanged(e)
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onBrowserWindowChanged(pkg)
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onBrowserWindowChanged(pkg)
                }
            }
            // Keyword block on typing: text typed in ANY editable field,
            // in ANY app — browsers, search, WhatsApp/Messenger/Telegram —
            // is keyword-checked as it is typed. The user asked for
            // keywords to block wherever they are typed on the phone, so
            // there is no app exclusion here. Password fields are never
            // read (see onTypedTextChanged), and our own app is excluded:
            // typing a keyword in Settings (to add it) must not block.
            if (siteKwOn && protecting() && pkg != packageName &&
                e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            ) {
                onTypedTextChanged(e)
            }
            // Keyword block on return: coming back to an app (a window
            // change) fires no typing event, so a keyword still sitting
            // in a text field would otherwise never be re-checked —
            // scan the foreground window's text fields once. Browsers
            // are covered by onBrowserWindowChanged above; this branch
            // is for every other app. State changes only, not content
            // changes — those fire constantly while scrolling a chat.
            if (siteKwOn && protecting() && pkg != packageName && !isBrowser(pkg) &&
                e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                scanForegroundTypedFields(pkg)
            }
            // ScreenGuard-style full-text block: keywords anywhere in the
            // VISIBLE text — posts, captions, titles, messages — not just
            // typed text or URLs. Same KeywordBlock matcher (built-in +
            // custom) and the same shared block screen. Lock-gated like
            // the other keyword checks; throttled, and content changes
            // are included so a feed that loads while scrolling is caught.
            if (siteKwOn && protecting() && pkg != packageName && !pkg.contains("systemui") &&
                (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            ) {
                scanVisibleTextForKeywords(pkg)
            }
            // the block screen follows the foreground app: hide it once protection ends,
            // or once a real window change shows another app in front. Our own package
            // is excluded: taps on the overlay itself must not dismiss it. A daily-limit
            // block stays up without a lock — only leaving the app dismisses it.
            if (isBlockOverlayAttached() && !protecting() && !limitBlockActive) {
                hideBlockOverlay()
            } else if (isBlockOverlayAttached() && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.contains("systemui") && pkg != packageName && pkg != blockedPkg
            ) {
                hideBlockOverlay()
            }
            if (pkg.contains("systemui")) {
                // the power menu: even when the flag is not set yet, the location lock may apply
                onSystemUi(e)
            } else if (limitReached(pkg)) {
                onLimitBlockedApp(e)
            } else if (!protecting()) {
                return
            } else if (abOn && abFull.contains(pkg)) {
                onFullBlockedApp(e)
            } else if (abOn && (abReels.contains(pkg) || abMsg.contains(pkg))) {
                // reels and search blocks are independent: an app can have both
                if (abReels.contains(pkg)) onReelsApp(e)
                if (abMsg.contains(pkg)) onMsgSearchApp(e)
            } else if (focusOn && focusApps.contains(pkg)) {
                onFocusApp(e)
            } else if (guardOn && isGuarded(pkg)) {
                onGuarded(e)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "onAccessibilityEvent failed", ex)
        }
    }


    // ---------- detection ----------

    private fun onSystemUi(e: AccessibilityEvent) {
        // 1) instant check from the event itself (no IPC at all)
        val menu = looksLikePowerMenu(e) || menuFromNode(e)
        if (!menu) return
        if (protecting()) {
            block()
            return
        }
        // location lock on but the flag has not caught up yet (updates are slow): shield now,
        // verify presence, then keep blocking or release the menu
        if (!Store.settings(this).getBoolean("loc_on", false)) return
        if (!Perm.locationOn(this)) return
        if (provisionalCheckRunning) {
            showOverlay() // keep the shield up while the check runs
            return
        }
        showOverlay()
        provisionalCheckRunning = true
        PlaceWatcher.isAnyPlaceActiveNow(this) { active ->
            provisionalCheckRunning = false
            if (active == true) {
                Store.setLocProtecting(this, true)
                block()
            } else {
                hideOverlay()
            }
        }
    }

    /** Node / window scan for the power menu (throttled like before). */
    private fun menuFromNode(e: AccessibilityEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastMenuScan < 60) return false
            lastMenuScan = now
            if (hasPowerMenu(e.source)) return true
            if (now - lastWinScan < 150) return false
            lastWinScan = now
            return scanWindows(false)
        }
        return hasPowerMenu(e.source) || scanWindows(true)
    }

    private fun onGuarded(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastGuardScan < 120) return
            lastGuardScan = now
        }
        val root = rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("PowerLock").isNotEmpty() && now - lastAction >= 300) {
            lastAction = now
            Extra.logAttempt(this, "settings", e.packageName?.toString() ?: "")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /** Protected apps' labels, cached and rebuilt only when the list changes. */
    private fun uninstallProtectLabels(): Map<String, String> {
        val pkgs = Extra.uninstallProtectApps(this)
        if (pkgs == uninstallProtectPkgs) return uninstallProtectLabels
        uninstallProtectPkgs = pkgs
        val pm = packageManager
        val map = HashMap<String, String>()
        for (pkg in pkgs) {
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                ""
            }
            if (label.isNotBlank()) map[pkg] = label
        }
        uninstallProtectLabels = map
        return map
    }

    /**
     * Finds the protected app whose uninstall dialog is showing, or null.
     * Two gates in one pass over the nodes that mention "Uninstall":
     *  - a real Uninstall button: a Button, a clickable node, or a TextView
     *    inside a clickable row/container (how Vivo draws its dialog buttons
     *    and long-press menu items). The node's text must be exactly
     *    "Uninstall" — a chat message that merely mentions the word has a
     *    much longer text and never matches, so plain words on the screen
     *    can no longer trigger the block (that used to kick the user out of
     *    the Muse app itself). The one-level parent walk is safe because of
     *    that exact-text gate: a message bubble may be clickable, but its
     *    text is never exactly "Uninstall".
     *  - the dialog title naming the app: a node whose text starts with
     *    "Uninstall"/"আনইনস্টল" and contains a protected label, e.g.
     *    Uninstall "BlockP"? This is the gate that stops the false fires:
     *    a home-screen icon label, a chat message or an app list can show
     *    the same name anywhere on the screen, but only the uninstall
     *    dialog titles it "Uninstall <name>?". So an app NOT in the list is
     *    never blocked — its dialog title names it, not the protected app.
     * The label is read from the title node directly, so no full-window
     * text collection is needed here (that was the slow part).
     */
    private fun findUninstallTarget(root: AccessibilityNodeInfo, labels: Map<String, String>): String? {
        var buttonFound = false
        var hitPkg: String? = null
        for (word in listOf("Uninstall", "আনইনস্টল")) {
            val nodes = try { root.findAccessibilityNodeInfosByText(word) } catch (_: Exception) { continue }
            for (n in nodes) {
                val text = n.text?.toString()?.trim() ?: ""
                // Dialog title gate.
                if (hitPkg == null) {
                    val starts = text.startsWith("Uninstall", ignoreCase = true) || text.startsWith("আনইনস্টল")
                    if (starts) {
                        val m = labels.entries.firstOrNull { (_, label) -> text.contains(label) }
                        if (m != null) hitPkg = m.key
                    }
                }
                // Button gate.
                if (!buttonFound) {
                    val exact = text.equals("Uninstall", ignoreCase = true) ||
                        text == "আনইনস্টল" || text == "আনইনস্টল করুন" || text == "আনইনস্টল করো"
                    if (exact) {
                        if (n.isClickable) buttonFound = true
                        val cn = n.className?.toString()
                        if (cn == "android.widget.Button" || cn == "android.widget.ImageButton") buttonFound = true
                        // Vivo's dialog buttons and menu items are often a
                        // TextView inside a clickable row — the parent counts.
                        if (!buttonFound) {
                            val p = n.parent
                            if (p != null) {
                                if (p.isClickable) buttonFound = true
                                try { p.recycle() } catch (_: Exception) { }
                            }
                        }
                    }
                }
                try { n.recycle() } catch (_: Exception) { }
            }
            if (buttonFound && hitPkg != null) return hitPkg
        }
        return if (buttonFound && hitPkg != null) hitPkg else null
    }

    /**
     * Uninstall protection (instant, no lock needed): the installer — or a
     * system screen such as App info — is showing an uninstall confirmation
     * for a protected app: a real clickable "Uninstall" button plus the
     * dialog title naming the app (Uninstall "BlockP"?). The block fires the
     * moment the dialog appears: no 300 ms wait like [onGuarded], only a
     * 1 s dedup against repeats. The user asked for this to be instant
     * because uninstall is the sensitive one. The title gate means an app
     * NOT in the list is never blocked — its dialog names it, not the
     * protected app. Runs for ANY foreground package: on Vivo the long-press
     * uninstall sheet comes from the launcher itself, not the installer, so a
     * guarded-package check never sees it.
     */
    private fun onUninstallAttempt(e: AccessibilityEvent) {
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // Never scan our own screens: an uninstall is confirmed in the launcher
        // or the installer, never inside PowerLocksss. Scanning our own huge
        // Settings tree on every scroll event is what made scrolling stutter
        // after the any-package change — so our own package is skipped here.
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        val now = SystemClock.uptimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastUninstallScan < 50) return
        lastUninstallScan = now
        val labels = uninstallProtectLabels()
        if (labels.isEmpty()) return
        val root = rootInActiveWindow ?: return
        // Button + dialog-title gates, one pass (see findUninstallTarget).
        val hitPkg = findUninstallTarget(root, labels) ?: return
        if (now - lastUninstallBlock < 1000) return
        lastUninstallBlock = now
        Extra.logAttempt(this, "uninstall", hitPkg, labels[hitPkg] ?: hitPkg)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun onFocusApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFocusAction < 300) return
        lastFocusAction = now
        Extra.logAttempt(this, "focus", e.packageName?.toString() ?: "")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Full app block: a protection screen covers the app the instant it opens.
     * No throttle on first sight of an app — the screen must appear before the
     * user can see or touch anything. The burst of events that follows a single
     * open is ignored while the screen is already up for that app.
     */
    private fun onFullBlockedApp(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastBlockPkg && now - lastBlockAt < 1000 && blockOverlayAttached) return
        lastBlockPkg = pkg
        lastBlockAt = now
        limitBlockActive = false // a lock block, not a limit block
        showBlockOverlay(pkg) // screen first: it must cover the app before anything else runs
        Extra.logAttempt(this, "appblock", pkg)
    }

    // ---------- daily limits ----------

    /** Today's usage, loaded once at start; trackUsage keeps it fresh in memory. */
    private fun loadUsage() {
        try {
            usageDay = Store.dayKey(0)
            usageToday.clear()
            usageToday.putAll(Extra.usageTodayAll(this))
        } catch (e: Exception) {
            Log.e(TAG, "loadUsage failed", e)
        }
    }

    /** The current keyboard package (cached 60 s): typing must not count as leaving the app. */
    private fun currentImePackage(): String? {
        val now = SystemClock.uptimeMillis()
        if (now - imeCheckedAt < 60_000) return imePkg
        imeCheckedAt = now
        imePkg = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore("/")
        } catch (e: Exception) {
            null
        }
        return imePkg
    }

    /**
     * Called on every window-state change: closes the previous app's time slice
     * (persisted for every app, so the Stats screen shows full screen time)
     * and opens one for the new app. System UI, the permission screen, our own
     * package and the keyboard never switch the slice. At midnight the counters
     * reset. Daily limits still read from the same counters — only limited apps
     * are ever blocked, but every app's time is now recorded.
     */
    private fun trackUsage(pkg: String) {
        try {
            if (pkg.contains("systemui") || pkg == packageName || pkg.contains("permissioncontroller")) return
            if (pkg == currentImePackage()) return
            val now = SystemClock.uptimeMillis()
            val day = Store.dayKey(0)
            if (day != usageDay) {
                usageDay = day
                usageToday.clear()
                usageToday.putAll(Extra.usageTodayAll(this))
            }
            val prev = usagePkg
            if (prev != null && prev != pkg) {
                val secs = (now - usageSince) / 1000
                if (secs > 0) {
                    Extra.addUsage(this, prev, secs)
                    usageToday[prev] = (usageToday[prev] ?: 0L) + secs
                }
            }
            if (prev != pkg) {
                usagePkg = pkg
                usageSince = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "trackUsage failed", e)
        }
    }

    /** True when the app has a daily limit and today's time (including the live slice) is up. */
    private fun limitReached(pkg: String): Boolean {
        val lim = limitMap[pkg] ?: return false
        val used = usageToday[pkg] ?: 0L
        val live = if (pkg == usagePkg) (SystemClock.uptimeMillis() - usageSince) / 1000 else 0L
        return used + live >= lim * 60L
    }

    /** Daily-limit block: the same protection screen as a full block, until midnight. */
    private fun onLimitBlockedApp(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastBlockPkg && now - lastBlockAt < 1000 && blockOverlayAttached) return
        lastBlockPkg = pkg
        lastBlockAt = now
        limitBlockActive = true
        showBlockOverlay(pkg, L.t("Daily limit reached — opens again tomorrow", "দৈনিক লিমিট শেষ — আগামীকাল আবার খুলবে"))
        Extra.logAttempt(this, "limit", pkg)
    }


    private fun appLabel(pkg: String): String {
        appLabelCache[pkg]?.let { return it }
        val label = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        appLabelCache[pkg] = label
        return label
    }

    private fun ensureBlockOverlay(): View {
        val existing = blockOverlay
        if (existing != null) return existing
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(250, 8, 16, 12))
        root.isClickable = true // touches must not reach the app underneath
        root.setPadding(dp(32), dp(32), dp(32), dp(32))
        val icon = IconView(this, Ic.SHIELD, C.primary)
        root.addView(icon, lp(dp(84), dp(84)))
        blockOverlayIcon = icon
        val title = tv("", 22f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv(L.t("This app is blocked while the lock runs", "লক চলাকালীন এই অ্যাপ ব্লক করা আছে"), 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        blockOverlaySub = sub
        val msg = tv("", 15f, C.mint, 1)
        msg.gravity = Gravity.CENTER
        root.addView(msg, lp(WRAP, WRAP, t = dp(10)))
        blockOverlayMsg = msg
        val btn = primaryBtn(L.t("Back to Home", "হোমে ফিরুন"))
        btn.setOnClickListener {
            hideBlockOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        root.addView(btn, lp(MATCH, dp(52), t = dp(12)))
        blockOverlay = root
        blockOverlayTitle = title
        return root
    }

    /**
     * The block screen is attached when the flag says so OR the view itself
     * reports attached. The flag alone can lie: if a removal fails, the flag
     * would say "detached" while a stale view still covers the screen, and
     * re-adding that view throws — the old code then pressed HOME on every
     * event (kick-out) while the stale view stayed up (black screen).
     */
    private fun isBlockOverlayAttached(): Boolean =
        blockOverlayAttached || (blockOverlay?.isAttachedToWindow == true)

    /** Same reconciliation for the power-menu shield. */
    private fun isShieldAttached(): Boolean =
        overlayAttached || (overlay?.isAttachedToWindow == true)

    private fun showBlockOverlay(pkg: String, sub: String? = null, titleOverride: String? = null) {
        hideOverlay() // never stack the power-menu shield under the block screen
        blockedPkg = pkg
        val subText = sub ?: L.t("This app is blocked while the lock runs", "লক চলাকালীন এই অ্যাপ ব্লক করা আছে")
        val titleText = titleOverride ?: appLabel(pkg)
        if (isBlockOverlayAttached()) {
            // already up: refresh the text instead of re-adding (re-adding an
            // attached view throws IllegalStateException)
            blockOverlayTitle?.text = titleText
            blockOverlaySub?.text = subText
            blockOverlayMsg?.text = blockMsg
            blockOverlayMsg?.visibility = if (blockMsg.isBlank()) View.GONE else View.VISIBLE
            return
        }
        try {
            val v = ensureBlockOverlay()
            // attach first (label + animation after): the cover must be instant
            v.alpha = 0f
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, params)
            blockOverlayAttached = true
            blockOverlayTitle?.text = titleText
            blockOverlaySub?.text = subText
            blockOverlayMsg?.text = blockMsg
            blockOverlayMsg?.visibility = if (blockMsg.isBlank()) View.GONE else View.VISIBLE
            // entrance: the screen fades in and the shield pops
            v.animate().alpha(1f).setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
            val ic = blockOverlayIcon
            if (ic != null) {
                ic.scaleX = 0.6f
                ic.scaleY = 0.6f
                ic.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.8f)).start()
            }
        } catch (ex: Exception) {
            Log.e(TAG, "showBlockOverlay failed", ex)
            blockOverlayAttached = false
            // fall back to the old behaviour — leave the app at once — but at
            // most once per 3 s, or a stuck overlay failure kicks the user
            // out on every single accessibility event
            val now = SystemClock.uptimeMillis()
            if (now - lastHomeFallbackAt >= 3000) {
                lastHomeFallbackAt = now
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun hideBlockOverlay() {
        val v = blockOverlay
        if (v == null || !isBlockOverlayAttached()) {
            // nothing is really up: clear the state without touching WindowManager
            blockOverlayAttached = false
            blockedPkg = null
            limitBlockActive = false
            resetSiteCheckDedup()
            return
        }
        try {
            // stop the entrance animation and reset the view so the next show
            // starts from a clean state
            v.animate().cancel()
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            blockOverlayIcon?.animate()?.cancel()
            blockOverlayIcon?.let {
                it.scaleX = 1f
                it.scaleY = 1f
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
            // only now is the screen really gone: clear the state
            blockOverlayAttached = false
            blockedPkg = null
            limitBlockActive = false
            resetSiteCheckDedup()
        } catch (ex: Exception) {
            Log.e(TAG, "hideBlockOverlay failed", ex)
            // removal failed: the view may still be up, so keep the block
            // state — isBlockOverlayAttached() reconciles the flag with the
            // view on the next check instead of trusting a stale flag
        }
    }

    /**
     * The block screen is gone: forget which URLs and typed texts were
     * already checked, so returning to the same page re-checks and
     * re-blocks. Without this the dedup keys (set when the block first
     * fired) would skip the check forever — the user could walk
     * straight back to the blocked page and it would never block again.
     * Called on every hide, including the nothing-attached path: with
     * no overlay up there is no in-progress block to protect. NOT
     * called when removal fails — the screen may still be up then, so
     * the block state stays.
     */
    private fun resetSiteCheckDedup() {
        lastCheckedKwUrl = ""
        lastCheckedUrl = ""
        lastCheckedTypedKw = ""
    }

    // ---------- site block: shared "block this site" path ----------

    /**
     * One shared path for all site-block triggers: a DNS query seen by the
     * VPN, an address-bar URL seen here, a keyword in a URL, or a keyword
     * typed in any text field. Shows the block screen with the
     * domain/keyword as its title and logs the attempt (kind "site"). The
     * overlay only appears while a lock is running and the right app is in
     * front — a background prefetch must not cover whatever the user is
     * doing. For URL/DNS triggers that means a browser; for typed-text
     * triggers ([allowAnyApp]) any app counts — keywords block wherever
     * they are typed. Throttled: the same domain at most once per 2 s; a
     * new domain always refreshes the screen at once.
     *
     * [reason] is the diagnostics kind ("site" for DNS, "url" for an
     * address-bar URL, "kw_url" for a keyword in a URL, "kw_typed" for a
     * keyword typed in a text field, "kw_visible" for a keyword in visible
     * screen text); [detail] is what matched (the domain or the keyword),
     * defaulting to the displayed title.
     */
    private fun onSiteBlocked(domain: String, pkg: String?, allowAnyApp: Boolean = false, reason: String = "site", detail: String? = null) {
        try {
            if (!protecting()) return
            val fg = pkg ?: lastForegroundPkg
            if (fg == null) return
            val allowed = if (allowAnyApp) true else isBrowser(fg)
            if (!allowed) return
            val now = SystemClock.uptimeMillis()
            if (domain == lastSiteOverlayDomain && now - lastSiteOverlayAt < 2000) return
            lastSiteOverlayAt = now
            lastSiteOverlayDomain = domain
            limitBlockActive = false // a lock block, not a limit block
            showBlockOverlay(
                fg,
                L.t("This site is blocked while the lock runs", "লক চলাকালীন এই সাইট ব্লক করা আছে"),
                titleOverride = domain
            )
            // navigate away from the blocked page: Android has no API to
            // close another app's browser tab, so Back is the closest
            // reliable move — with history it leaves the blocked page, on
            // a fresh tab it exits the browser (returning re-blocks: the
            // dedup resets when the screen hides). One press per block;
            // the 2 s throttle above bounds repeats. The overlay is
            // NOT_FOCUSABLE, so Back reaches the app underneath,
            // not the overlay.
            try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) { }
            Extra.logAttempt(this, reason, fg, detail ?: domain)
        } catch (ex: Exception) {
            Log.e(TAG, "site block overlay failed", ex)
        }
    }

    // ---------- site block: URL detection (accessibility) ----------

    /**
     * The address-bar text changed in a browser: the event carries the new
     * text, so no tree scan is needed. Only the known address-bar view IDs
     * are trusted — typing anywhere else in the browser is ignored.
     */
    private fun onBrowserTextChanged(e: AccessibilityEvent) {
        try {
            val src = e.source ?: return
            val vid = try { src.viewIdResourceName } catch (_: Exception) { null } ?: ""
            if (!addressBarIds.contains(vid)) return
            val text = e.text?.joinToString("") ?: return
            maybeCheckUrl(e.packageName?.toString() ?: return, text)
        } catch (ex: Exception) {
            Log.e(TAG, "browser text check failed", ex)
        }
    }

    /**
     * A browser window just opened or a page loaded (new tab, app switch,
     * in-tab navigation, or RETURNING to the browser after a block): scan
     * the tree once for the address bar and check its text, and also
     * check any typed-text field's text — returning fires no typing
     * event, so a keyword still sitting in a text field would otherwise
     * never be re-checked. Throttled to once per 2 s — content changes fire
     * often while a page loads. [force] bypasses the throttle for the
     * lock-start scan, which fires at most once per lock. If no address
     * bar is found by view ID, the fallback scan below looks for
     * URL-like text instead — browser updates rename IDs, and a missed
     * ID must not mean a missed block.
     */
    private fun onBrowserWindowChanged(pkg: String, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastBrowserScanAt < 2000) return
        lastBrowserScanAt = now
        try {
            val root = rootInActiveWindow ?: return
            try {
                // only scan when a watched browser is actually in front —
                // the event package can be stale (e.g. the lock was just
                // started from this app while the browser was behind it)
                if (!isBrowser(root.packageName?.toString())) return
                var foundAddressBar = false
                for (id in addressBarIds) {
                    val nodes = try {
                        root.findAccessibilityNodeInfosByViewId(id)
                    } catch (ex: Exception) {
                        continue
                    }
                    try {
                        for (n in nodes) {
                            val text = n.text?.toString() ?: ""
                            if (text.isNotEmpty()) {
                                maybeCheckUrl(pkg, text)
                                foundAddressBar = true
                                break
                            }
                        }
                    } finally {
                        for (n in nodes) {
                            try { n.recycle() } catch (_: Exception) { }
                        }
                    }
                    if (foundAddressBar) break
                }
                // no address bar by ID — fall back to URL-like text in the tree
                if (!foundAddressBar) scanForUrlLikeText(root, pkg)
                // also check typed-text fields: the address bar alone does
                // not catch a keyword sitting in a text field, and
                // returning to the browser fires no typing event at all
                if (siteKwOn) scanTypedFields(root, pkg)
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "browser window scan failed", ex)
        }
    }

    /** A lock just started (or the service re-bound mid-lock): scan the
     * foreground window at once — a page already open before the lock, or
     * a keyword already sitting in a text field, must still be caught.
     * Bypasses the scan throttle: this fires at most once per lock start. */
    private fun scanBrowserNow() {
        try {
            if (!protecting() || !(siteUrlOn || siteKwOn)) return
            val pkg = lastForegroundPkg ?: return
            if (pkg == packageName) return
            if (isBrowser(pkg)) {
                onBrowserWindowChanged(pkg, force = true)
            } else if (siteKwOn) {
                scanForegroundTypedFields(pkg, force = true)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "lock-start browser scan failed", ex)
        }
    }

    /**
     * Fallback URL discovery: the address bar was not found by view ID
     * (browser updates rename IDs). Bounded breadth-first walk over the
     * window tree looking for URL-like text — any node whose text has a
     * scheme, starts with "www.", or looks like a domain with a path.
     * EditText nodes (address bars are edit fields) and nodes near the top
     * or bottom edge of the screen are checked; page copy in the middle is
     * skipped, so a link printed on a page does not trigger a block. At
     * most 3 candidates are checked and the walk is capped at 600 nodes,
     * so a big page tree cannot stall the service. Nodes are recycled as
     * the walk goes, following the same pattern as the reels scan.
     */
    private fun scanForUrlLikeText(root: AccessibilityNodeInfo, pkg: String) {
        val screenH = try { resources.displayMetrics.heightPixels } catch (_: Exception) { 0 }
        val seen = HashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var used = 0
        var checked = 0
        try {
            while (stack.isNotEmpty() && used < 600 && checked < 3) {
                val n = stack.removeFirst()
                used++
                try {
                    val text = n.text?.toString()?.trim() ?: ""
                    if (text.isNotEmpty() && looksLikeUrl(text) && seen.add(text)) {
                        val cls = n.className?.toString() ?: ""
                        val isEdit = cls.contains("EditText")
                        val r = Rect()
                        try { n.getBoundsInScreen(r) } catch (_: Exception) { }
                        // address bars sit at the top (or bottom) edge of
                        // the screen; page links sit in the middle
                        val nearEdge = screenH <= 0 || r.top < screenH / 5 || r.top > screenH * 4 / 5
                        if (isEdit || nearEdge) {
                            checked++
                            maybeCheckUrl(pkg, text)
                        }
                    }
                    for (i in 0 until n.childCount) {
                        try { n.getChild(i)?.let { stack.add(it) } } catch (_: Exception) { }
                    }
                } catch (_: Exception) {
                } finally {
                    if (n !== root) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "url-like text scan failed", ex)
        } finally {
            while (stack.isNotEmpty()) {
                val x = stack.removeFirst()
                if (x !== root) {
                    try { x.recycle() } catch (_: Exception) { }
                }
            }
        }
    }

    /** True when [text] looks like a URL rather than page copy: it has a
     * scheme, starts with www., or is a bare domain (optionally with a
     * path/query). Spaces or newlines disqualify it. */
    private fun looksLikeUrl(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty() || s.length > 2048) return false
        if (s.contains(' ') || s.contains('\n') || s.contains('\t')) return false
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (lower.startsWith("www.")) return true
        if (!lower.contains('.')) return false
        val hostPart = lower.substringBefore('/').substringBefore('?').substringBefore('#')
        val tld = hostPart.substringAfterLast('.', "")
        if (tld.length < 2 || !tld.all { it.isLetter() }) return false
        if (!hostPart.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return false
        return hostPart.split('.').all { it.isNotEmpty() && it.length <= 63 }
    }

    /** Lowercase, trimmed, scheme stripped — for full-URL matching. */
    private fun normalizeUrlText(raw: String): String {
        var s = raw.trim().lowercase()
        if (s.startsWith("http://")) s = s.substring(7)
        else if (s.startsWith("https://")) s = s.substring(8)
        return s
    }

    /**
     * Keyword block: the FULL address-bar text (host + path + query) is
     * checked for any keyword — a case-insensitive match against the
     * built-in + custom lists (Aho-Corasick, linear in the text; short
     * keywords need a word boundary — see KeywordBlock). Runs
     * synchronously and INSTANTLY on every text change: the match is
     * microseconds, so no throttle may delay it — the block must fire
     * the moment the typed text first contains a keyword. Deduped on the
     * full text (not just the host), so navigating to a new path on the
     * same host re-checks. The dedup resets when the block screen hides,
     * so returning to the same page re-checks and re-blocks. On a match
     * the shared onSiteBlocked path covers the screen, titled with the keyword.
     */
    private fun maybeCheckKeywords(pkg: String, rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val key = "$pkg|${text.lowercase()}"
        if (key == lastCheckedKwUrl) return
        lastCheckedKwUrl = key
        val kw = try {
            KeywordBlock.findMatch(applicationContext, text, Extra.siteKeywords(applicationContext))
        } catch (ex: Exception) {
            Log.e(TAG, "keyword check failed", ex)
            return
        } ?: return
        if (protecting()) onSiteBlocked(L.t("Keyword: $kw", "কিওয়ার্ড: $kw"), pkg, reason = "kw_url", detail = kw)
    }

    /**
     * The URL in a browser address bar may have changed: match it against
     * the user's own list (host suffix OR the domain appearing anywhere in
     * the full URL text — so www.xnxx.tv/search/top matches a user entry
     * of xnxx.tv even if the host parse ever fails) and then the 100k
     * blocklist. The blocklist check runs on a worker thread (the list
     * load may block on first use). Dedup is on the FULL normalized URL:
     * the same host with a new path must re-check, because the substring
     * match depends on the whole text — and the dedup resets when the
     * block screen hides, so returning to the same page re-checks.
     * At most one check per 2 s, and only one running at a time — the next URL change retries anything skipped.
     */
    private fun maybeCheckUrl(pkg: String, rawText: String) {
        // keyword block first: it needs the full text, not just the host,
        // and it is independent of the URL-detection switch
        if (siteKwOn) maybeCheckKeywords(pkg, rawText)
        if (!siteUrlOn) return
        val normalized = normalizeUrlText(rawText)
        val host = hostFromUrl(rawText) ?: return
        val key = "$pkg|$normalized"
        if (key == lastCheckedUrl) return
        val now = SystemClock.uptimeMillis()
        if (now - lastUrlCheckAt < 2000) return
        // do not mark the URL checked until a check actually starts: if a
        // check is already running, the next event must retry this URL
        if (urlCheckRunning) return
        lastUrlCheckAt = now
        lastCheckedUrl = key
        urlCheckRunning = true
        val appCtx = applicationContext
        val t = Thread({
            try {
                // the user's own list first (fast): host suffix match, or
                // the domain appearing anywhere in the full URL text;
                // then the 100k blocklist (host suffix match)
                var blocked = false
                for (d in Extra.siteDomains(appCtx)) {
                    if (host == d || host.endsWith(".$d") || normalized.contains(d)) { blocked = true; break }
                }
                if (!blocked) blocked = SiteBlockService.isBlockedByBuiltInList(appCtx, host)
                if (blocked) {
                    handler.post {
                        try {
                            if (protecting()) onSiteBlocked(host, pkg, reason = "url")
                        } catch (ex: Exception) {
                            Log.e(TAG, "site overlay post failed", ex)
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "url check failed", ex)
            } finally {
                urlCheckRunning = false
            }
        }, "siteblock-url-check")
        t.isDaemon = true
        t.start()
    }

    /**
     * Is this node a text field the keyword block may read? ANY editable
     * text field — the user asked for keywords to block wherever they are
     * typed on the phone, so there is no search-field restriction and no
     * app restriction. The guards that stay: never a password field, and
     * never the address bar (handled by the URL path). Shared by the
     * typing check and the window-change scans so all paths recognise
     * the same fields.
     */
    private fun isKeywordFieldNode(n: AccessibilityNodeInfo): Boolean {
        try {
            val cls = n.className?.toString() ?: ""
            if (!cls.contains("EditText")) return false
            if (n.isPassword) return false
            val vid = try { n.viewIdResourceName } catch (_: Exception) { null } ?: ""
            if (addressBarIds.contains(vid)) return false
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Keyword block on typing: text typed in ANY editable field, in ANY
     * app, is keyword-checked INSTANTLY on every keystroke — no
     * debounce, so the block fires the moment the typed text first
     * contains a keyword. Any editable field is read (see
     * [isKeywordFieldNode]) — password fields never are, and the address
     * bar is skipped (onBrowserTextChanged already handles it). The typed
     * text itself is never logged or stored; only the matched keyword
     * (the user's own word) is used, as the overlay title.
     */
    private fun onTypedTextChanged(e: AccessibilityEvent) {
        try {
            val src = e.source ?: return
            if (!isKeywordFieldNode(src)) return
            val text = e.text?.joinToString("") ?: return
            if (text.isBlank()) return
            // instant: check the typed text right now, on this keystroke —
            // no debounce, no throttle (the match itself is microseconds)
            maybeCheckTypedKeywords(e.packageName?.toString() ?: return, text)
        } catch (ex: Exception) {
            Log.e(TAG, "typed keyword check failed", ex)
        }
    }

    /**
     * Window-change typed-text scan for NON-browser apps: find editable
     * text fields in the foreground window and keyword-check their text.
     * This is what re-blocks a messaging app the user returns to —
     * returning fires no typing event, so a keyword still sitting in the
     * message box would otherwise never be seen again (it could even be
     * sent without another keystroke). Same field rules as the typing
     * check (see [isKeywordFieldNode]). Bounded: throttled to once per
     * 2 s (window state changes are infrequent), at most 600 nodes
     * walked, at most 3 fields checked. [force] bypasses the throttle
     * for the lock-start scan, which fires at most once per lock.
     */
    private fun scanForegroundTypedFields(pkg: String, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastTypedScanAt < 2000) return
        lastTypedScanAt = now
        try {
            val root = rootInActiveWindow ?: return
            try {
                // only scan when a real app is in front — never our own
                // package (typing a keyword in Settings must not block)
                val rootPkg = root.packageName?.toString() ?: return
                if (rootPkg == packageName) return
                scanTypedFields(root, pkg)
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "foreground typed-field scan failed", ex)
        }
    }

    /**
     * Window-change typed-text scan: find editable text fields in the
     * given window and keyword-check their text. This is what re-blocks
     * a page or chat the user returns to — returning fires no typing
     * event, so the typing check never runs, and without this scan a
     * keyword still sitting in a text field would never be seen again.
     * Same field rules as the typing check (see [isKeywordFieldNode]):
     * any editable field, never password fields or the address bar.
     * Bounded like the URL fallback scan: at most 600 nodes walked, at
     * most 3 fields checked, nodes recycled as the walk goes.
     */
    private fun scanTypedFields(root: AccessibilityNodeInfo, pkg: String) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var used = 0
        var checked = 0
        try {
            while (stack.isNotEmpty() && used < 600 && checked < 3) {
                val n = stack.removeFirst()
                used++
                try {
                    if (isKeywordFieldNode(n)) {
                        val text = n.text?.toString()?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            checked++
                            maybeCheckTypedKeywords(pkg, text)
                        }
                    }
                    for (i in 0 until n.childCount) {
                        try { n.getChild(i)?.let { stack.add(it) } } catch (_: Exception) { }
                    }
                } catch (_: Exception) {
                } finally {
                    if (n !== root) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "typed field scan failed", ex)
        }
    }

    /** Typed text may contain a keyword: same shared overlay + logging
     * path as URL detection, titled with the keyword. The same text is
     * checked once while the block screen is up (typing the same text
     * again does not re-fire); the dedup resets when the screen hides, so
     * returning to the same text re-checks and re-blocks. */
    private fun maybeCheckTypedKeywords(pkg: String, rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val key = "$pkg|${text.lowercase()}"
        if (key == lastCheckedTypedKw) return
        lastCheckedTypedKw = key
        val kw = try {
            KeywordBlock.findMatch(applicationContext, text, Extra.siteKeywords(applicationContext))
        } catch (ex: Exception) {
            Log.e(TAG, "typed keyword match failed", ex)
            return
        } ?: return
        if (protecting()) onSiteBlocked(L.t("Keyword: $kw", "কিওয়ার্ড: $kw"), pkg, allowAnyApp = true, reason = "kw_typed", detail = kw)
    }

    /**
     * Full visible-text keyword scan (ScreenGuard-style): walks the whole
     * foreground window and keyword-checks ALL visible text — posts,
     * captions, titles, messages — not just editable fields or the
     * address bar. A keyword showing on screen (not typed) is caught
     * here; typed text and URLs are already covered by the checks above.
     * Same matcher, same block screen, same logging as a typed keyword.
     * Bounded like the other scans: throttled to once per 2 s, at most
     * 600 nodes walked, at most 20,000 characters collected. Password
     * fields are never read, and the scan never runs while the block
     * screen is already up (its own title holds the keyword).
     */
    private fun scanVisibleTextForKeywords(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastVisibleTextScan < 2000) return
        lastVisibleTextScan = now
        if (isBlockOverlayAttached()) return
        try {
            val root = rootInActiveWindow ?: return
            try {
                val rootPkg = root.packageName?.toString() ?: return
                if (rootPkg == packageName) return
                val sb = StringBuilder()
                collectVisibleText(root, sb)
                if (sb.isEmpty()) return
                val kw = try {
                    KeywordBlock.findMatch(applicationContext, sb.toString(), Extra.siteKeywords(applicationContext))
                } catch (ex: Exception) {
                    Log.e(TAG, "visible text keyword match failed", ex)
                    return
                } ?: return
                if (protecting()) onSiteBlocked(L.t("Keyword: $kw", "কিওয়ার্ড: $kw"), pkg, allowAnyApp = true, reason = "kw_visible", detail = kw)
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "visible text keyword scan failed", ex)
        }
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo, sb: StringBuilder) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var used = 0
        try {
            while (stack.isNotEmpty() && used < 600 && sb.length < 20000) {
                val n = stack.removeFirst()
                used++
                try {
                    if (!n.isPassword) {
                        n.text?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
                        n.contentDescription?.let { if (it.isNotEmpty()) sb.append(it).append(' ') }
                    }
                    for (i in 0 until n.childCount) {
                        try { n.getChild(i)?.let { stack.add(it) } } catch (_: Exception) { }
                    }
                } catch (_: Exception) {
                } finally {
                    if (n !== root) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "visible text collect failed", ex)
        }
    }

    /**
     * Pulls a host out of address-bar text ("https://www.example.com/x" or
     * just "example.com"). Returns null for search queries and anything
     * that is not a plain domain name.
     */
    private fun hostFromUrl(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty() || s.contains(' ') || s.contains('\n')) return null
        if (s.startsWith("http://")) s = s.substring(7)
        else if (s.startsWith("https://")) s = s.substring(8)
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        val colon = s.indexOf(':')
        if (colon >= 0) s = s.substring(0, colon)
        if (s.startsWith("www.")) s = s.substring(4)
        s = s.trimEnd('.')
        if (s.isEmpty() || s.length > 253 || !s.contains('.')) return null
        for (part in s.split('.')) {
            if (part.isEmpty() || part.length > 63) return null
            if (!part.all { it.isLetterOrDigit() || it == '-' }) return null
            if (part.startsWith('-') || part.endsWith('-')) return null
        }
        return s
    }

    /**
     * Reels block: the app stays usable, only the Reels / Shorts feed is closed.
     * A detection presses Back (out of the feed/player). Home is never used:
     * pressing Home while a Short plays sends YouTube into picture-in-picture,
     * where the video keeps playing in a small floating window. After a Back
     * there is a short cooldown: the close animation keeps the reel in the
     * tree for a moment, and Backing again would leave the app instead of
     * just the reel. Only the reel is closed — never the app itself.
     */
    private fun onReelsApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        // window state changes always scan (a new screen may be the feed);
        // other events are throttled so scrolling does not scan constantly
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (now - lastReelsScan < 400) return
            lastReelsScan = now
        }
        val detected = looksLikeReels(e)
        // diagnostic: every 3s, record what the service actually sees on this
        // screen, so a missed block can be traced instead of guessed
        if (now - lastDebugScan >= 3000) {
            lastDebugScan = now
            debugScanReels(e.packageName?.toString() ?: "?", e, detected)
        }
        if (!detected) return
        if (now - lastAppBlockAction < 500) return
        if (now - lastReelsBack < 1500) return
        lastAppBlockAction = now
        lastReelsBack = now
        Extra.logAttempt(this, "reels", e.packageName?.toString() ?: "")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Diagnostic: records what the service actually sees on a reels app's
     * screen — which signals are present (marker text, counts, action labels,
     * viewer view IDs) and how many nodes were scanned. Written to
     * [debugLog], shown in the Protection screen's Diagnostic card. The log
     * is capped so it never grows without bound.
     */
    private fun debugScanReels(pkg: String, e: AccessibilityEvent, detected: Boolean) {
        val lines = mutableListOf<String>()
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.add("[$time] $pkg detected=$detected event=${e.eventType} cls=${e.className}")
        val countRe = Regex("^[0-9,]+(\\.[0-9]+)?[km]?$")
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val wins = windows ?: emptyList()
            for (w in wins) {
                try { w.root?.let { roots.add(it) } } catch (_: Exception) { }
            }
            lines.add("  windows=${roots.size}")
            var total = 0
            val markers = mutableListOf<String>()
            val counts = mutableListOf<String>()
            val actions = mutableListOf<String>()
            val viewIds = mutableListOf<String>()
            for (root in roots.asReversed()) {
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                stack.add(root)
                var used = 0
                while (stack.isNotEmpty() && used < 800) {
                    val n = stack.removeFirst()
                    used++
                    total++
                    try {
                        val lt = (n.text?.toString() ?: "").lowercase()
                        val lc = (n.contentDescription?.toString() ?: "").lowercase()
                        val cls = n.className?.toString() ?: ""
                        if (reelsMarkersLower.any { lt.contains(it) || lc.contains(it) }) {
                            if (markers.size < 5) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                markers.add("marker cls=$cls t='$lt' cd='$lc' sel=${n.isSelected} chk=${n.isChecked} b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        if (countRe.matches(lt)) {
                            if (counts.size < 8) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                counts.add("count '$lt' b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        val label = lt.trim().ifEmpty { lc.trim() }
                        if (label.startsWith("like") || label.startsWith("comment") || label.startsWith("share")) {
                            if (actions.size < 8) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                actions.add("action '$label' b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        val vid = n.viewIdResourceName ?: ""
                        if (vid.isNotEmpty() && (vid.contains("viewer") || vid.contains("player")) && reelsMarkersLower.any { vid.contains(it) }) {
                            if (viewIds.size < 5) viewIds.add("viewId $vid")
                        }
                        for (i in 0 until n.childCount) {
                            try { n.getChild(i)?.let { stack.add(it) } } catch (_: Exception) { }
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (n !== root) {
                            try { n.recycle() } catch (_: Exception) { }
                        }
                    }
                }
                while (stack.isNotEmpty()) {
                    val x = stack.removeFirst()
                    if (x !== root) {
                        try { x.recycle() } catch (_: Exception) { }
                    }
                }
            }
            lines.add("  nodes=$total markers=${markers.size} counts=${counts.size} actions=${actions.size} viewIds=${viewIds.size}")
            markers.forEach { lines.add("  $it") }
            counts.forEach { lines.add("  $it") }
            actions.forEach { lines.add("  $it") }
            viewIds.forEach { lines.add("  $it") }
            // plain-language summary (2 lines per scan) for the Diagnostic card
            val label = try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }
            val status = if (detected) L.t("Reels detected ✓", "রিলস ধরা পড়েছে ✓") else L.t("Reels not detected ✗", "রিলস ধরা পড়েনি ✗")
            debugSummary.add("[${time.take(5)}] $label — $status")
            debugSummary.add(
                "  " + L.t("Reels text", "Reels লেখা") + ": ${markers.size} • " +
                    L.t("counts", "সংখ্যা") + ": ${counts.size} • " +
                    L.t("buttons", "বোতাম") + ": ${actions.size} • " +
                    L.t("viewer", "ভিউয়ার") + ": " + if (viewIds.isNotEmpty()) L.t("yes", "আছে") else L.t("no", "নেই")
            )
            while (debugSummary.size > 60) debugSummary.removeAt(0)
        } catch (ex: Exception) {
            lines.add("  debug error: ${ex.message}")
        } finally {
            for (r in roots) {
                try { r.recycle() } catch (_: Exception) { }
            }
        }
        debugLog.addAll(lines)
        while (debugLog.size > 120) debugLog.removeAt(0)
    }

    /**
     * Search block: the app stays usable and search opens normally (the
     * keyboard appears), but as soon as the user starts typing, the query
     * is cleared and Back closes search — the user lands back in the app,
     * never Home, and the next search starts empty. Searching inside a
     * chat or channel (Telegram, WhatsApp…) is NOT blocked: it shows a
     * "1 of 5" match counter, and only the main search bar is blocked.
     */
    private fun onMsgSearchApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (now - lastMsgScan < 400) return
            lastMsgScan = now
        }
        if (!looksLikeSearch(e)) return
        // the keyboard must appear first: only act once the user types
        if (!focusedSearchFieldHasText()) return
        // in-chat / in-channel search shows a "1 of 5" match counter — allowed
        if (isInChatSearch()) return
        if (now - lastMsgAction < 500) return
        lastMsgAction = now
        clearFocusedSearchField() // erase the query so the next search starts empty
        Extra.logAttempt(this, "msgsearch", e.packageName?.toString() ?: "")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Runs [block] with the focused search field (if any); the node is recycled after. */
    private fun withFocusedSearchField(block: (AccessibilityNodeInfo) -> Unit) {
        try {
            val root = rootInActiveWindow ?: return
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                try {
                    val isEdit = (focused.className?.toString() ?: "").contains("EditText")
                    val hint = focused.hintText?.toString() ?: ""
                    if (!isEdit || !hint.lowercase().contains("search")) return
                    block(focused)
                } finally {
                    try { focused.recycle() } catch (_: Exception) { }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "focused search field failed", ex)
        }
    }

    /** True if the focused search field already has typed text in it. */
    private fun focusedSearchFieldHasText(): Boolean {
        var hasText = false
        withFocusedSearchField { hasText = !it.text.isNullOrEmpty() }
        return hasText
    }

    /** Clears the focused search field (if any) via ACTION_SET_TEXT with "". */
    private fun clearFocusedSearchField() {
        withFocusedSearchField {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    /** True if the current search is inside a chat/channel: it shows a "1 of 5" match counter. */
    private fun isInChatSearch(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            try {
                val nodes = root.findAccessibilityNodeInfosByText(" of ")
                try {
                    val re = Regex("^\\d+ of \\d+$")
                    for (n in nodes) {
                        val t = n.text?.toString()?.trim() ?: ""
                        if (re.matches(t)) return true
                    }
                    false
                } finally {
                    for (n in nodes) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Is a search screen open right now? Two precise signals only:
     * 1) a new window whose class contains "search" (a dedicated search screen),
     * 2) the focused input is a search field (its hint contains "search").
     * The chat list's own search bar is never focused, and a message input's
     * hint is "Message", so neither false-positives. No plain text check:
     * "Search" text sits on the chat list too.
     */
    private fun looksLikeSearch(e: AccessibilityEvent): Boolean {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = (e.className?.toString() ?: "").lowercase()
            if (cls.contains("search")) return true
        }
        return try {
            val root = rootInActiveWindow ?: return false
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
                try {
                    val isEdit = (focused.className?.toString() ?: "").contains("EditText")
                    val hint = focused.hintText?.toString() ?: ""
                    isEdit && hint.lowercase().contains("search")
                } finally {
                    try { focused.recycle() } catch (_: Exception) { }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * A chat/messages screen (a "Messages" or "Chats" header at the top) is
     * never a Reels feed. This guards the Reels block against false-positives
     * inside messaging tabs (e.g. Facebook Lite's Messages tab with its notes
     * row), where a Back press would kick the user out of their chats.
     */
    private fun isChatScreen(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val screenH = resources.displayMetrics.heightPixels
            var found = false
            try {
                for (marker in listOf("Messages", "Chats")) {
                    val nodes = root.findAccessibilityNodeInfosByText(marker)
                    for (n in nodes) {
                        try {
                            val t = n.text?.toString()?.trim() ?: ""
                            if (t.equals(marker, ignoreCase = true)) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                if (r.top >= 0 && r.top < screenH * 0.20) {
                                    found = true
                                    break
                                }
                            }
                        } finally {
                            try { n.recycle() } catch (_: Exception) { }
                        }
                    }
                    if (found) break
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
            found
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Signals, cheapest first:
     * 1) the window class on a window-state change (reel / shorts / story
     *    viewer) — on a content change the class is the changed node's class,
     *    and a story cell in the feed would false-positive,
     * 2) the app's navigation: a Reels / Shorts / Status tab that is selected
     *    or checked (bottom-nav items are Checkable, so isChecked is the state
     *    that actually changes — checking only isSelected missed them),
     * 3) a bounded tree scan: icon-only tabs (label in contentDescription),
     *    the story/status reply field, the YouTube Shorts "Remix" button, and
     *    the viewer's action/count rail at the right edge (no header check:
     *    the feed's Reels carousel has the same header).
     * A plain text search would false-positive (the "Reels" tab label is in
     * the tree even on the home feed, and the feed's Reels carousel carries
     * the same text), so selection/checked state — or a viewer-only
     * fingerprint — is what counts. Chat/messages screens are excluded up
     * front: they are never a Reels feed.
     */
    private fun looksLikeReels(e: AccessibilityEvent): Boolean {
        if (isChatScreen()) return false
        val pkg = e.packageName?.toString() ?: ""
        val cls = (e.className?.toString() ?: "").lowercase()
        // a messaging screen is never a Reels feed (the event's own class —
        // a message/chat/conversation screen must not be Backed out of)
        if (cls.contains("message") || cls.contains("chat") || cls.contains("conversation")) return false
        // the window class only identifies a screen on a window-state change:
        // on a content change it is the changed node's class (a story cell
        // in the feed would false-positive and Back the user out of the app)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (cls.contains("reel") || cls.contains("shorts") || cls.contains("clips") ||
                cls.contains("spotlight") || cls.contains("story")
            ) return true
            if ((pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") && cls.contains("status")) return true
        }
        if (reelsTabSelected()) return true
        return reelsUiScan(pkg)
    }

    /** A marker tab is selected or checked (node, parent or grandparent). */
    private fun reelsTabSelected(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            for (marker in reelsTabMarkers) {
                val nodes = root.findAccessibilityNodeInfosByText(marker)
                for (n in nodes) {
                    // the label itself may not carry the state — its tab container does
                    var found = n.isSelected || n.isChecked
                    var p1: AccessibilityNodeInfo? = null
                    var p2: AccessibilityNodeInfo? = null
                    if (!found) {
                        p1 = n.parent
                        found = p1?.let { it.isSelected || it.isChecked } == true
                    }
                    if (!found && p1 != null) {
                        p2 = p1.parent
                        found = p2?.let { it.isSelected || it.isChecked } == true
                    }
                    try { p2?.recycle() } catch (_: Exception) { }
                    try { p1?.recycle() } catch (_: Exception) { }
                    try { n.recycle() } catch (_: Exception) { }
                    if (found) return true
                }
            }
            false
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Thorough tree scan (bounded: 500 nodes over all of the app's windows),
     * run only when the cheap signals miss. Catches what the text search cannot:
     * - icon-only tabs whose label is a contentDescription,
     * - the story/status viewer: its reply field's hint starts with "Reply"
     *   (Instagram / Facebook / WhatsApp / Telegram story viewers), or the
     *   "You can't reply to this story" notice at the bottom of the screen
     *   (Telegram stories with replies off have no reply field at all),
     * - the YouTube Shorts player: the "Remix" button exists only there
     *   (the bottom nav is hidden in the player, so the tab check can't see it),
     * - (no header check: the feed's Reels carousel carries the same "Reels"
     *   header at the top when scrolled, and blocking on it would Back the
     *   user out of the feed — the rail is the viewer-only fingerprint),
     * - viewer/player view IDs containing reel / short / story,
     * - the viewer's action rail: Like / Comment / Share stacked vertically
     *   on the right edge (the feed shows them in a horizontal row instead),
     * - the viewer's count rail: bare count numbers (14K, 562, 58) stacked
     *   at the right edge below the icons (their text is what FB Lite exposes).
     * All of the app's windows are scanned (topmost first, each with its own
     * budget): story viewers can live in their own window, not the active one.
     */
    private fun reelsUiScan(pkg: String): Boolean {
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            try {
                for (w in windows) {
                    try {
                        val r = w.root ?: continue
                        if (r.packageName?.toString() == pkg) roots.add(r)
                        else try { r.recycle() } catch (_: Exception) { }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            if (roots.isEmpty()) {
                try {
                    rootInActiveWindow?.let { roots.add(it) }
                } catch (_: Exception) { }
            }
            // topmost window first: the viewer may sit in its own window above
            // the feed, and each window gets its own budget so a big feed tree
            // can no longer starve the viewer window
            for (r in roots.asReversed()) {
                val used = intArrayOf(0)
                if (scanReelsTree(r, pkg, screenH, screenW, 500, used)) return true
            }
            return false
        } finally {
            for (r in roots) {
                try { r.recycle() } catch (_: Exception) { }
            }
        }
    }

    /** Bounded DFS of one window's tree. Does not recycle [root]. */
    private fun scanReelsTree(
        root: AccessibilityNodeInfo, pkg: String, screenH: Int, screenW: Int, budget: Int, used: IntArray
    ): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        // right-edge action rail: kinds + y positions of Like / Comment / Share buttons
        val railKinds = HashSet<String>()
        val railTops = ArrayList<Int>()
        // viewer count rail: y positions of bare count numbers at the right edge
        val railCounts = ArrayList<Int>()
        // a bare count: 562, 58, 14K, 1.2M (lowercase text, so [km] not [KM])
        val countRe = Regex("^[0-9,]+(\\.[0-9]+)?[km]?$")
        try {
            while (stack.isNotEmpty() && used[0] < budget) {
                val n = stack.removeFirst()
                used[0]++
                try {
                    val text = n.text?.toString() ?: ""
                    val cd = n.contentDescription?.toString() ?: ""
                    val lt = text.lowercase()
                    val lc = cd.lowercase()
                    // viewer action rail: Like / Comment / Share stacked vertically
                    // on the right edge. At least two DIFFERENT kinds must be there:
                    // a feed post has only its Share button at the right edge, and
                    // two posts on screen would otherwise false-positive.
                    val label = lt.trim().ifEmpty { lc.trim() }
                    val kind = when {
                        label.startsWith("like") -> "like"
                        label.startsWith("comment") -> "comment"
                        label.startsWith("share") -> "share"
                        else -> null
                    }
                    if (kind != null) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        // centerX, not left: the icon may sit in a wider touch target
                        if (r.centerX() > screenW * 0.80 && r.top >= 0) {
                            railKinds.add(kind)
                            railTops.add(r.top)
                            val top = railTops.maxOrNull() ?: 0
                            val bottom = railTops.minOrNull() ?: 0
                            if (railKinds.size >= 2 && railTops.size >= 2 && top - bottom > screenH * 0.10) return true
                        }
                    }
                    // viewer / player view IDs (e.g. reels_viewer, story_viewer)
                    val vid = n.viewIdResourceName?.lowercase() ?: ""
                    if ((vid.contains("viewer") || vid.contains("player")) &&
                        (vid.contains("reel") || vid.contains("short") || vid.contains("story"))
                    ) return true
                    // YouTube Shorts player: Remix exists only in the Shorts player
                    if (pkg == "com.google.android.youtube" &&
                        (lt.contains("remix") || lc.contains("remix"))
                    ) return true
                    // Story / Status viewer: the reply field's hint starts with "Reply"
                    val clsN = n.className?.toString() ?: ""
                    if (clsN.contains("EditText")) {
                        val hint = n.hintText?.toString() ?: ""
                        if (hint.lowercase().startsWith("reply")) return true
                    }
                    // Viewer count rail: the Like / Comment / Share icons carry no
                    // text in FB Lite, but their counts do (14K, 562, 58) — short
                    // bare numbers stacked vertically at the right edge, just below
                    // each icon. The feed has no bare numbers at the right edge (its
                    // "10 comments" text is not a bare number), and the top-bar
                    // notification badge is excluded by the y > 15% rule.
                    if (countRe.matches(lt)) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        // right edge, not left: the count TextView may be wide with
                        // right-aligned text (its left edge would be far from the rail)
                        if (r.right > screenW * 0.80 && r.top > screenH * 0.15 && r.top >= 0) {
                            railCounts.add(r.top)
                            val top = railCounts.maxOrNull() ?: 0
                            val bottom = railCounts.minOrNull() ?: 0
                            if (railCounts.size >= 2 && top - bottom > screenH * 0.10) return true
                        }
                    }
                    // Story viewer with replies off: "You can't reply to this story"
                    // sits at the bottom of the screen (Telegram)
                    if (lt.contains("this story")) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.top > screenH * 0.75) return true
                    }
                    if (reelsMarkersLower.any { lt.contains(it) || lc.contains(it) }) {
                        // marker tab, selected or checked (itself or an ancestor)
                        if (n.isSelected || n.isChecked) return true
                        var p1: AccessibilityNodeInfo? = null
                        var p2: AccessibilityNodeInfo? = null
                        var found = false
                        try {
                            p1 = n.parent
                            found = p1?.let { it.isSelected || it.isChecked } == true
                            if (!found && p1 != null) {
                                p2 = p1.parent
                                found = p2?.let { it.isSelected || it.isChecked } == true
                            }
                        } catch (_: Exception) {
                        }
                        try { p2?.recycle() } catch (_: Exception) { }
                        try { p1?.recycle() } catch (_: Exception) { }
                        if (found) return true
                        // NOTE: no header check here on purpose — the feed's Reels
                        // carousel has the same "Reels" header at the top when
                        // scrolled, and blocking on it would Back the user out of
                        // the feed. The viewer is caught by its rail instead.
                    }
                    for (i in 0 until n.childCount) {
                        try {
                            n.getChild(i)?.let { stack.add(it) }
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    if (n !== root) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            }
            return false
        } catch (ex: Exception) {
            return false
        } finally {
            while (stack.isNotEmpty()) {
                val x = stack.removeFirst()
                if (x !== root) {
                    try { x.recycle() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun looksLikePowerMenu(e: AccessibilityEvent): Boolean {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = (e.className?.toString() ?: "").lowercase()
            if (cls.contains("globalaction") || cls.contains("powermenu") || cls.contains("poweroff") || cls.contains("shutdown")) {
                return true
            }
        }
        val sb = StringBuilder()
        for (t in e.text) sb.append(t).append(' ')
        val cd = e.contentDescription
        if (cd != null) sb.append(cd)
        if (sb.isEmpty()) return false
        val s = sb.toString().lowercase()
        for (t in triggersLower) {
            if (s.contains(t)) return true
        }
        return false
    }

    /** The power menu always has the "power off" slider (English or Bengali). */
    private fun hasPowerMenu(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        return n.findAccessibilityNodeInfosByText("to power off").isNotEmpty() ||
                n.findAccessibilityNodeInfosByText("পাওয়ার বন্ধ").isNotEmpty() ||
                n.findAccessibilityNodeInfosByText("রিস্টার্ট").isNotEmpty()
    }

    /** System windows first (where the power menu lives); other windows only when [all] is true. */
    private fun scanWindows(all: Boolean): Boolean {
        return try {
            val list = windows
            var found = false
            for (w in list) {
                if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && hasPowerMenu(w.root)) {
                    found = true
                    break
                }
            }
            if (!found && all) {
                for (w in list) {
                    if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM && hasPowerMenu(w.root)) {
                        found = true
                        break
                    }
                }
            }
            found
        } catch (ex: Exception) {
            false
        }
    }

    private fun isGuarded(p: String): Boolean =
        p.contains("settings") || p.contains("packageinstaller") ||
                p.contains("securitycenter") || p.contains("permissioncontroller")

    // ---------- blocking ----------

    private fun block() {
        Extra.logAttempt(this, "power", lastForegroundPkg ?: "")
        showOverlay() // shield first: from this moment the slider cannot be touched
        val now = SystemClock.uptimeMillis()
        if (now - lastAction >= 100) {
            lastAction = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.removeCallbacks(backAgain)
            handler.removeCallbacks(backAgain2)
            handler.postDelayed(backAgain, 60)
            handler.postDelayed(backAgain2, 150)
            // no HOME: the app the user is in must stay open
        }
    }

    private fun ensureOverlay(): View {
        val existing = overlay
        if (existing != null) return existing
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(245, 8, 16, 12))
        root.isClickable = true
        root.addView(IconView(this, Ic.SHIELD, C.primary), lp(dp(84), dp(84)))
        val title = tv(L.t("PowerLock 2 is active", "PowerLock 2 সক্রিয়"), 24f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv("", 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        overlay = root
        overlaySub = sub
        return root
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 1400)
        if (isShieldAttached()) return // never more than one shield
        try {
            val v = ensureOverlay()
            val sessionOn = active && System.currentTimeMillis() < endAt
            overlaySub?.text = if (locProtecting && !sessionOn) {
                L.t("Location lock is active", "লোকেশন লক সক্রিয়")
            } else {
                val at = fmtClock(endAt)
                L.t("Power menu is blocked until $at", "$at পর্যন্ত পাওয়ার মেনু ব্লক করা আছে")
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, params)
            overlayAttached = true
        } catch (ex: Exception) {
            Log.e(TAG, "showOverlay failed", ex)
            overlayAttached = false
        }
    }

    private fun hideOverlay() {
        val v = overlay
        if (v == null || !isShieldAttached()) {
            // nothing is really up: clear the flag without touching WindowManager
            overlayAttached = false
            return
        }
        try {
            v.animate().cancel()
            v.alpha = 1f
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
            // only now is the shield really gone
            overlayAttached = false
        } catch (ex: Exception) {
            Log.e(TAG, "hideOverlay failed", ex)
            // removal failed: the view may still be up — isShieldAttached()
            // reconciles the flag with the view on the next check
        }
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        hideBlockOverlay()
        if (receiverOn) {
            receiverOn = false
            try {
                unregisterReceiver(closeDialogsReceiver)
            } catch (ex: Exception) {
                Log.e(TAG, "unregister close-dialogs failed", ex)
            }
        }
        if (siteReceiverOn) {
            siteReceiverOn = false
            try {
                unregisterReceiver(siteBlockedReceiver)
            } catch (ex: Exception) {
                Log.e(TAG, "unregister site-blocked failed", ex)
            }
        }
        try {
            Store.lock(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister failed", ex)
        }
        try {
            Store.settings(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister settings failed", ex)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
