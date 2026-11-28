package com.powerlock.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

const val REQ_WALLPAPER = 1002

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var navHolder: FrameLayout
    private val navItems = ArrayList<LinearLayout>()
    private val navLabels = ArrayList<TextView>()
    private val navIcons = ArrayList<IconView>()
    private var tab = 0
    private var lastNavTab = -1
    /** Saved vertical scroll position per tab, so a screen rebuild keeps the user's place instead of jumping to the top. */
    private val tabScroll = IntArray(5)
    private var home: HomeScreen? = null
    private var stats: StatsScreen? = null

    /** Selected quick-timer duration in minutes (persisted). */
    var selectedMin: Int
        get() = Store.settings(this).getInt("sel_min", 30)
        set(v) {
            Store.settings(this).edit().putInt("sel_min", v).apply()
        }

    private val ticker = object : Runnable {
        override fun run() {
            try {
                Store.finalizeIfExpired(this@MainActivity)
                home?.tick()
                stats?.tick() // StatsScreen throttles itself to every 5 s
                // widget countdown: every 30 s is enough (the service heartbeat
                // updates it every 60 s while the app is closed)
                if (System.currentTimeMillis() - lastWidgetRefresh >= 30_000) {
                    lastWidgetRefresh = System.currentTimeMillis()
                    LockWidget.refresh(this@MainActivity)
                }
            } catch (e: Exception) {
                Log.e(TAG, "tick failed", e)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private var lastWidgetRefresh = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)
        C.load(this)
        tab = savedInstanceState?.getInt("tab") ?: 0

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        frame = FrameLayout(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        content = FrameLayout(this)
        navHolder = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, weight = 1f))
        root.addView(navHolder, lp(MATCH, WRAP))
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)

        applyChrome()
        navHolder.addView(buildNav())
        Scheduler.onAlarm(this)
        Routine.checkDayEnd(this) // daily routine: settle yesterday on app open
        go(tab, false)
        Store.kickService(this) // restart the foreground service if it was swiped away
        SiteBlockService.sync(this) // restart the site-block VPN if a lock is running
        Watchdog.enqueue(this) // safety net: restart the service every 15 min if it dies
        CapsuleAlarms.rescheduleAll(this) // re-arm time-capsule alarms (force-stop cancels them)
        PlaceWatcher.checkNow(this) // already at a saved place? lock right away
        PlaceWatcher.checkUnlockNow(this) // location-unlock session? check arrival right away
    }

    /** Result of the system VPN-consent dialog (site block). */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_CONSENT) {
            if (resultCode == RESULT_OK) {
                SiteBlockService.sync(this)
                toast(L.t("Site block is ready — it starts with the next lock", "সাইট ব্লক প্রস্তুত — পরের লক থেকে চালু হবে"))
            } else {
                // consent denied: turn the switch back off so the UI stays honest
                Extra.setSiteOn(this, false)
                toast(L.t("Site block needs VPN permission", "সাইট ব্লকের জন্য VPN অনুমতি দরকার"))
            }
            if (tab == 3) go(3, false)
        }
        if (requestCode == REQ_WALLPAPER) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        Wallpaper.setUri(this, uri)
                        toast(L.t("Wallpaper set", "ওয়ালপেপার দেওয়া হয়েছে"))
                    } catch (e: Exception) {
                        Log.e(TAG, "wallpaper permission failed", e)
                        toast(L.t("Could not use that photo", "এই ছবিটা ব্যবহার করা যায়নি"))
                    }
                }
            }
            if (tab == 3) go(3, false)
        }
    }

    /** Opens the system document picker for a Home wallpaper photo. */
    fun pickWallpaper() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_WALLPAPER)
        } catch (e: Exception) {
            Log.e(TAG, "wallpaper picker failed", e)
            toast(L.t("Could not open the gallery", "গ্যালারি খোলা যায়নি"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        Store.syncLauncherIcon(this) // build 133: enforce the icon state whenever the app opens (a lock already running at install time never passed through startSession, so the icon was never hidden)
        handler.post(ticker)
        if (tab == 3) go(3, false) // refresh permission status after returning from system settings
    }

    override fun onPause() {
        VoiceQuotes.stop() // leaving the app stops any spoken quote
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        VoiceQuotes.shutdown()
        super.onDestroy()
    }

    fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun applyChrome() {
        root.setBackgroundColor(C.bg)
        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg
    }

    /**
     * Applies a language / theme change without recreating the activity:
     * a snapshot of the current screen stays on top and fades out while the new screen is built underneath.
     */
    fun crossfade(change: () -> Unit) {
        var bmp: Bitmap? = null
        var overlay: ImageView? = null
        try {
            val w = root.width
            val h = root.height
            if (w > 0 && h > 0) {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(b))
                val iv = ImageView(this)
                iv.setImageBitmap(b)
                iv.isClickable = true
                frame.addView(iv, FrameLayout.LayoutParams(w, h))
                bmp = b
                overlay = iv
            }
        } catch (t: Throwable) {
            Log.e(TAG, "snapshot failed", t)
        }
        change()
        rebuildAll()
        val iv = overlay
        val b2 = bmp
        if (iv != null) {
            iv.animate()
                .alpha(0f)
                .setDuration(Motion.SLOW)
                .setInterpolator(Motion.EASE)
                .withEndAction {
                    frame.removeView(iv)
                    b2?.recycle()
                }
                .start()
        }
    }

    private fun rebuildAll() {
        applyChrome()
        navItems.clear()
        navLabels.clear()
        navIcons.clear()
        navHolder.removeAllViews()
        navHolder.addView(buildNav())
        go(tab, false)
    }

    /**
     * Finds the ScrollView inside a tab root: the view itself, or its first
     * ScrollView child (the Home page may be wrapped in a FrameLayout for the wallpaper).
     */
    private fun findScroll(v: View?): ScrollView? = when (v) {
        is ScrollView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { v.getChildAt(it) as? ScrollView }
        else -> null
    }

    fun go(i: Int, animate: Boolean = true) {
        VoiceQuotes.stop() // leaving a screen stops any spoken quote
        // remember where the current tab was scrolled, before the rebuild below throws the view away
        findScroll(if (content.childCount > 0) content.getChildAt(0) else null)?.let { tabScroll[tab] = it.scrollY }
        tab = i
        content.removeAllViews()
        home = null
        stats = null
        val v: View = when (i) {
            0 -> {
                val h = HomeScreen(this)
                home = h
                h.build()
            }
            1 -> ScheduleScreen(this).build()
            2 -> {
                val s = StatsScreen(this)
                stats = s
                s.build()
            }
            3 -> SettingsScreen(this).build()
            else -> ProtectionScreen(this).build()
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        // restore this tab's scroll position once the new layout is ready (posted so it
        // runs after measure/layout; scrollTo clamps if the new content is shorter)
        val y = tabScroll[i]
        if (y > 0) {
            val sv = findScroll(v)
            sv?.post { if (sv.isAttachedToWindow) sv.scrollTo(0, y) }
        }
        refreshNav(animate)
        if (animate) enter(v)
    }

    /** Page transition: the page fades in and its cards rise in one after another. */
    private fun enter(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.EASE).start()
        val sv = findScroll(v)
        val col = sv?.getChildAt(0) as? ViewGroup
        if (col != null) staggerIn(col)
    }

    private fun buildNav(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(C.bg)
        val line = View(this)
        line.setBackgroundColor(C.card)
        wrap.addView(line, lp(MATCH, dp(1)))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(8), dp(10), dp(8), dp(10))
        val labels = listOf(
            L.t("Home", "হোম"),
            L.t("Schedule", "সময়সূচি"),
            L.t("Statistics", "পরিসংখ্যান"),
            L.t("Settings", "সেটিংস"),
            L.t("Protection", "সুরক্ষা")
        )
        val kinds = listOf(Ic.HOME, Ic.CAL, Ic.BARS, Ic.GEAR, Ic.SHIELD)
        for (i in 0..4) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setPadding(0, dp(4), 0, dp(4))
            val ic = IconView(this, kinds[i], C.muted)
            item.addView(ic, lp(dp(26), dp(26)))
            val lb = tv(labels[i], 12f, C.muted, 1)
            lb.gravity = Gravity.CENTER
            item.addView(lb, lp(WRAP, WRAP, t = dp(4)))
            item.setOnClickListener { if (tab != i) go(i) }
            item.pressFeedback()
            navItems.add(item)
            navLabels.add(lb)
            navIcons.add(ic)
            bar.addView(item, lp(0, WRAP, weight = 1f))
        }
        wrap.addView(bar, lp(MATCH, WRAP))
        return wrap
    }

    private fun refreshNav(animate: Boolean) {
        for (i in navItems.indices) {
            val sel = i == tab
            navLabels[i].setTextColor(if (sel) C.primary else C.muted)
            navIcons[i].color = if (sel) C.primary else C.muted
            if (animate && sel && lastNavTab != tab) {
                navIcons[i].scaleX = 0.8f
                navIcons[i].scaleY = 0.8f
                navIcons[i].animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(Motion.MED)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
        }
        lastNavTab = tab
    }
}
