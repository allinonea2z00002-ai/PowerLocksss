package com.powerlock.app

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

// =====================================================================
// HOME
// =====================================================================
class HomeScreen(private val a: MainActivity) {
    private lateinit var ring: RingView
    private lateinit var pill: TextView
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var previewText: TextView
    private lateinit var schedPill: TextView
    private lateinit var lastSummaryText: TextView
    private var summaryShown = false
    private var lastSummarySig = ""
    private var badgeShown = false
    private lateinit var startBtn: LinearLayout
    private lateinit var stopBtn: LinearLayout
    private lateinit var extendBtn: LinearLayout
    private lateinit var warnBox: LinearLayout
    private val chips = ArrayList<TextView>()
    private val quick = listOf(5, 10, 15, 30, 60)
    private val modeChips = ArrayList<TextView>()
    private val modes = listOf(UNLOCK_TIME, UNLOCK_LOC, UNLOCK_EITHER)
    private lateinit var homeLocCard: View
    private lateinit var focusScoreText: TextView
    private lateinit var focusScoreDetail: TextView
    private var lastScoreAt = 0L
    private var warnSig = ""
    private var lastStatus: PStatus? = null
    private var lastActive: Boolean? = null
    private var lastSched: Boolean? = null

    private fun quickLabel(m: Int): String = L.num(L.t("$m min", "$m মিনিট"))

    fun build(): View {
        val (sv, col) = a.page()

        // ---- header ----
        val header = LinearLayout(a)
        header.gravity = Gravity.CENTER_VERTICAL
        val tile = LinearLayout(a)
        tile.gravity = Gravity.CENTER
        tile.background = rounded(Color.parseColor("#14392A"), a.dp(16).toFloat())
        tile.addView(IconView(a, Ic.SHIELD, C.primary), lp(a.dp(30), a.dp(30)))
        header.addView(tile, lp(a.dp(52), a.dp(52)))
        val names = LinearLayout(a)
        names.orientation = LinearLayout.VERTICAL
        names.addView(a.tv("PowerLock 2", 24f, C.text, 1))
        val sub = a.tv(L.t("PREMIUM EDITION", "প্রিমিয়াম এডিশন"), 12f, C.muted, 1)
        sub.letterSpacing = 0.1f
        names.addView(sub)
        header.addView(names, lp(0, WRAP, l = a.dp(14), weight = 1f))
        pill = a.tv("", 13f, C.primary, 1)
        pill.letterSpacing = 0.05f
        pill.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        header.addView(pill)
        col.addView(header)

        warnBox = LinearLayout(a)
        warnBox.orientation = LinearLayout.VERTICAL
        col.addView(warnBox, lp(MATCH, WRAP, t = a.dp(14)))

        // ---- status card ----
        val sc = a.card(20)
        sc.gravity = Gravity.CENTER_HORIZONTAL
        sc.background = rounded(C.card, a.dp(28).toFloat())
        val lbl = a.tv(L.t("POWER LOCK STATUS", "পাওয়ার লক স্ট্যাটাস"), 12f, C.muted, 1)
        lbl.letterSpacing = 0.12f
        lbl.gravity = Gravity.CENTER
        sc.addView(lbl)
        ring = RingView(a)
        sc.addView(ring, lp(a.dp(170), a.dp(170), t = a.dp(16)))
        titleText = a.tv("", 26f, C.text, 1)
        titleText.gravity = Gravity.CENTER
        sc.addView(titleText, lp(MATCH, WRAP, t = a.dp(16)))
        subText = a.tv("", 15f, C.muted)
        subText.gravity = Gravity.CENTER
        sc.addView(subText, lp(MATCH, WRAP, t = a.dp(6)))
        startBtn = a.bigButton(Ic.LOCK, L.t("Start Power Lock", "পাওয়ার লক শুরু"), C.primary, C.onPrimary)
        startBtn.setOnClickListener { onStart() }
        sc.addView(startBtn, lp(MATCH, a.dp(60), t = a.dp(20)))
        stopBtn = a.bigButton(Ic.LOCK, L.t("Stop Power Lock", "পাওয়ার লক বন্ধ"), Color.TRANSPARENT, C.danger, C.danger)
        stopBtn.setOnClickListener { onStop() }
        sc.addView(stopBtn, lp(MATCH, a.dp(60), t = a.dp(20)))
        extendBtn = a.bigButton(Ic.CLOCK, L.t("Add time", "সময় বাড়ান"), C.card2, C.primary, C.primary)
        extendBtn.setOnClickListener { a.extendFlow { tick() } }
        sc.addView(extendBtn, lp(MATCH, a.dp(60), t = a.dp(12)))
        col.addView(sc, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- focus score today (0–100) ----
        val fsCard = a.card()
        val fsRow = LinearLayout(a)
        fsRow.gravity = Gravity.CENTER_VERTICAL
        fsRow.addView(IconView(a, Ic.BARS, C.primary), lp(a.dp(26), a.dp(26), r = a.dp(12)))
        val fsCol = LinearLayout(a)
        fsCol.orientation = LinearLayout.VERTICAL
        fsCol.addView(a.tv(L.t("Focus score today", "আজকের ফোকাস স্কোর"), 13f, C.muted, 1))
        focusScoreText = a.tv("", 22f, C.mint, 1)
        fsCol.addView(focusScoreText)
        fsRow.addView(fsCol, lp(0, WRAP, weight = 1f))
        fsCard.addView(fsRow)
        focusScoreDetail = a.tv("", 12f, C.muted)
        fsCard.addView(focusScoreDetail, lp(MATCH, WRAP, t = a.dp(6)))
        col.addView(fsCard, lp(MATCH, WRAP, t = a.dp(14)))
        refreshFocusScore()

        // ---- duration ----
        col.addView(a.heading(L.t("Select Lock Duration", "লক সময় বাছুন")), lp(MATCH, WRAP, t = a.dp(26), b = a.dp(4)))
        val row1 = LinearLayout(a)
        val row2 = LinearLayout(a)
        for ((i, m) in quick.withIndex()) {
            val c = a.chip(quickLabel(m), false)
            c.setOnClickListener { pick(m) }
            chips.add(c)
            val rm = if (i == 2) 0 else a.dp(10)
            val target = if (i < 3) row1 else row2
            target.addView(c, lp(0, a.dp(56), r = rm, t = a.dp(10), weight = 1f))
        }
        val custom = a.chip(L.t("Custom", "কাস্টম"), false)
        custom.setOnClickListener { customDialog() }
        chips.add(custom)
        row2.addView(custom, lp(0, a.dp(56), t = a.dp(10), weight = 1f))
        col.addView(row1)
        col.addView(row2)
        previewText = a.tv("", 13f, C.muted)
        col.addView(previewText, lp(MATCH, WRAP, t = a.dp(12)))
        lastSummaryText = a.tv("", 13f, C.mint)
        col.addView(lastSummaryText, lp(MATCH, WRAP, t = a.dp(6)))

        // ---- unlock condition: time only, location only, or either one ----
        col.addView(a.heading(L.t("When does the lock open?", "লক খুলবে কখন?")), lp(MATCH, WRAP, t = a.dp(26), b = a.dp(4)))
        val modeRow = LinearLayout(a)
        val modeLabels = listOf(
            L.t("Time only", "শুধু সময়"),
            L.t("Location only", "শুধু লোকেশন"),
            L.t("Either one", "যেকোনো একটা")
        )
        for ((i, m) in modes.withIndex()) {
            val ch = a.chip(modeLabels[i], false)
            ch.setOnClickListener { pickMode(m) }
            modeChips.add(ch)
            modeRow.addView(ch, lp(0, a.dp(56), r = if (i < 2) a.dp(10) else 0, t = a.dp(10), weight = 1f))
        }
        col.addView(modeRow)

        // ---- location lock card (the same full card as Settings) ----
        // It replaces the old destination row: the map (from this card) is
        // where the unlock destination is picked.
        homeLocCard = a.locationCard { a.go(0) }
        col.addView(homeLocCard, lp(MATCH, WRAP, t = a.dp(10)))

        // ---- schedule card ----
        val scd = a.card(20)
        scd.addView(a.tv(L.t("Schedule", "সময়সূচি"), 22f, C.text, 1))
        val r1 = LinearLayout(a)
        r1.gravity = Gravity.CENTER_VERTICAL
        r1.setPadding(0, a.dp(18), 0, a.dp(18))
        r1.addView(IconView(a, Ic.CAL, C.primary), lp(a.dp(28), a.dp(28), r = a.dp(14)))
        r1.addView(a.tv(L.t("Weekly Schedule", "সাপ্তাহিক সময়সূচি"), 17f, C.text), lp(0, WRAP, weight = 1f))
        schedPill = a.tv("", 14f, C.primary, 1)
        schedPill.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        r1.addView(schedPill)
        r1.setOnClickListener { a.go(1) }
        scd.addView(r1)
        val dv = View(a)
        dv.setBackgroundColor(C.border)
        scd.addView(dv, lp(MATCH, a.dp(1)))
        val r2 = LinearLayout(a)
        r2.gravity = Gravity.CENTER_VERTICAL
        r2.setPadding(0, a.dp(18), 0, a.dp(2))
        r2.addView(a.tv(L.t("Statistics", "পরিসংখ্যান"), 17f, C.muted), lp(0, WRAP, weight = 1f))
        r2.addView(a.tv(L.t("View details →", "বিস্তারিত →"), 16f, C.primary, 1))
        r2.setOnClickListener { a.go(2) }
        scd.addView(r2)
        col.addView(scd, lp(MATCH, WRAP, t = a.dp(22)))

        // content: focus notes, fun facts, time capsule
        col.addView(a.focusNotesCard(), lp(MATCH, WRAP, t = a.dp(14)))
        col.addView(a.funFactsCard(), lp(MATCH, WRAP, t = a.dp(14)))
        col.addView(a.timeCapsuleCard(), lp(MATCH, WRAP, t = a.dp(14)))

        // daily routine: task checklist + consequence lock
        col.addView(a.routineCard(), lp(MATCH, WRAP, t = a.dp(14)))

        refreshChips()
        refreshModeChips()
        tick()
        return Wallpaper.wrap(a, sv)
    }

    private fun refreshChips(animate: Boolean = false) {
        val active = Store.isActive(a)
        // location-only mode has no duration: the chips stay visible but dimmed
        val locOnly = unlockMode() == UNLOCK_LOC
        for ((i, c) in chips.withIndex()) {
            val sel = if (i < quick.size) quick[i] == a.selectedMin else !quick.contains(a.selectedMin)
            if (animate) a.animateChip(c, sel) else a.styleChip(c, sel)
            c.alpha = if (active || locOnly) 0.4f else 1f
        }
    }

    private fun pick(m: Int) {
        if (Store.isActive(a)) return
        if (unlockMode() == UNLOCK_LOC) return
        a.selectedMin = m
        refreshChips(true)
        tick()
    }

    // ---------- unlock condition (time / location / either) ----------

    private fun unlockMode(): String = Store.settings(a).getString("unlock_mode", UNLOCK_TIME) ?: UNLOCK_TIME

    private fun setUnlockMode(m: String) {
        Store.settings(a).edit().putString("unlock_mode", m).apply()
    }

    /** The chosen unlock destination (settings), or null when none was picked yet. */
    private fun unlockDest(): UnlockTarget? {
        val raw = Store.settings(a).getString("unlock_dest", null) ?: return null
        return try {
            val o = JSONObject(raw)
            UnlockTarget(o.getDouble("la"), o.getDouble("lo"), o.getInt("r"), o.getString("n"))
        } catch (e: Exception) {
            Log.e(TAG, "unlock dest read failed", e)
            null
        }
    }

    private fun refreshModeChips(animate: Boolean = false) {
        val active = Store.isActive(a)
        val mode = unlockMode()
        for ((i, c) in modeChips.withIndex()) {
            val sel = modes[i] == mode
            if (animate) a.animateChip(c, sel) else a.styleChip(c, sel)
            c.alpha = if (active) 0.4f else 1f
        }
        refreshLocCard()
    }

    /** The Home location card is shown only for the location unlock modes. */
    private fun refreshLocCard() {
        homeLocCard.visibility = if (unlockMode() != UNLOCK_TIME) View.VISIBLE else View.GONE
    }

    private fun pickMode(m: String) {
        if (Store.isActive(a)) return
        setUnlockMode(m)
        refreshModeChips(true)
        refreshChips()
        tick()
    }

    private fun nextText(now: Long): String {
        val n = Scheduler.nextStart(a, now) ?: return ""
        val c = Calendar.getInstance()
        c.timeInMillis = n.second
        return L.t("Next: ", "পরবর্তী: ") + L.dayLong(dayIdx(c)) + " " + fmtMin(n.first.startMin) + " – " + fmtMin(n.first.endMin)
    }

    private var pillColor = 0
    private val pillBg = GradientDrawable()

    private fun showView(v: View, show: Boolean) {
        val want = if (show) View.VISIBLE else View.GONE
        if (v.visibility == want) return
        v.visibility = want
        if (show) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.EASE).start()
        }
    }

    private fun applyStatus(st: PStatus) {
        if (st == lastStatus) return
        val first = lastStatus == null
        lastStatus = st
        val color = when (st) {
            PStatus.ACTIVE -> C.primary
            PStatus.SCHEDULED -> Color.parseColor("#5AC8FA")
            PStatus.COMPLETED -> C.mint
            PStatus.READY -> C.muted
        }
        val label = when (st) {
            PStatus.ACTIVE -> L.t("PROTECTED", "সুরক্ষিত")
            PStatus.SCHEDULED -> L.t("SCHEDULED", "নির্ধারিত")
            PStatus.COMPLETED -> L.t("COMPLETED", "সম্পন্ন")
            PStatus.READY -> L.t("READY", "প্রস্তুত")
        }
        val from = if (pillColor == 0) color else pillColor
        pillColor = color
        if (first) {
            pillBg.cornerRadius = a.dp(20).toFloat()
            pillBg.setColor(tint(color, 40))
            pill.background = pillBg
            pill.text = label
            pill.setTextColor(color)
            return
        }
        pill.animate().cancel()
        pill.animate().alpha(0f).setDuration(Motion.FAST / 2).withEndAction {
            pill.text = label
            pill.animate().alpha(1f).setDuration(Motion.FAST).start()
        }.start()
        val ev = ArgbEvaluator()
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = Motion.MED
        an.addUpdateListener {
            val c = ev.evaluate(it.animatedFraction, from, color) as Int
            pill.setTextColor(c)
            pillBg.setColor(tint(c, 40))
        }
        an.start()
    }

    private fun updateSchedPill() {
        val on = Store.schedules(a).any { it.enabled }
        if (lastSched == on) return
        lastSched = on
        if (on) {
            schedPill.text = L.t("Enabled", "চালু")
            schedPill.setTextColor(C.primary)
            schedPill.background = rounded(tint(C.primary, 40), a.dp(20).toFloat())
        } else {
            schedPill.text = L.t("Off", "বন্ধ")
            schedPill.setTextColor(C.muted)
            schedPill.background = rounded(C.card2, a.dp(20).toFloat())
        }
    }

    /** Refreshes the Home focus-score card from today's data. */
    private fun refreshFocusScore() {
        if (!::focusScoreText.isInitialized) return
        val fs = Extra.focusScore(a)
        focusScoreText.text = L.num(fs.score.toString()) + " / " + L.num("100")
        val done = if (fs.completedToday) L.t(" · lock completed today", " · আজ লক সম্পন্ন") else ""
        focusScoreDetail.text = L.num(
            L.t(
                "Focus ${fs.focusMinToday}m of ${fs.goalMin}m goal · ${fs.blocksToday} blocks resisted · ${fs.streak}-day streak$done",
                "ফোকাস ${fs.focusMinToday} মিনিট / ${fs.goalMin} মিনিট লক্ষ্য · ${fs.blocksToday}টি ব্লক প্রতিহত · ${fs.streak} দিনের স্ট্রিক$done"
            )
        )
    }

    fun tick() {
        val now = System.currentTimeMillis()
        // focus score moves slowly: refresh it at most every 30 s
        if (now - lastScoreAt >= 30_000) {
            lastScoreAt = now
            refreshFocusScore()
        }
        // backup arrival check (the service listener is the main path)
        if (Store.unlockTarget(a) != null) PlaceWatcher.checkUnlockLastKnown(a)
        val active = Store.isActive(a)
        val st = statusOf(a, active, now)
        applyStatus(st)
        updateSchedPill()
        val p = Store.lock(a)
        val end = p.getLong("end", 0)
        var total = p.getLong("total", 1)
        if (total < 1) total = 1
        val strict = active && p.getBoolean("s_strict", false)
        if (active) {
            val mode = Store.unlockMode(a)
            val target = Store.unlockTarget(a)
            if (mode == UNLOCK_LOC && target != null) {
                // no countdown: the lock ends when the user arrives
                ring.active = true
                ring.icon = Ic.PIN
                ring.main = L.t("On the way", "যাত্রায়")
                ring.mainColor = C.text
                ring.sub = if (target.name.length > 14) target.name.take(13) + "…" else target.name
                ring.animateTo(0f)
                titleText.setTextAnimated(L.t("Protection is Active", "প্রটেকশন সক্রিয়"))
                subText.setTextAnimated(L.t("Opens when you arrive at ${target.name}", "লক খুলবে: ${target.name} পৌঁছালে"))
                previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") +
                    L.t("opens at ${target.name}", "${target.name} পৌঁছালে খুলবে")
            } else {
                val left = end - now
                ring.active = true
                ring.icon = Ic.LOCK
                ring.main = fmtHms(left)
                ring.mainColor = C.text
                ring.sub = L.t("remaining", "বাকি")
                var frac = left.toFloat() / total.toFloat()
                if (frac < 0f) frac = 0f
                if (frac > 1f) frac = 1f
                ring.animateTo(frac)
                titleText.setTextAnimated(L.t("Protection is Active", "প্রটেকশন সক্রিয়"))
                val endAt = fmtClock(end)
                if (mode == UNLOCK_EITHER && target != null) {
                    subText.setTextAnimated(
                        if (strict) L.t("Strict mode · locked until $endAt or arrival at ${target.name}", "স্ট্রিক্ট মোড · $endAt পর্যন্ত অথবা ${target.name} পৌঁছালে")
                        else L.t("Ends at $endAt or at ${target.name}", "শেষ হবে $endAt অথবা ${target.name} পৌঁছালে")
                    )
                    previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") + fmtDur(total) +
                        L.t(" or at ${target.name}", " অথবা ${target.name} পৌঁছালে")
                } else {
                    subText.setTextAnimated(
                        if (strict) L.t("Strict mode · locked until $endAt", "স্ট্রিক্ট মোড · $endAt পর্যন্ত লক")
                        else L.t("Ends at $endAt", "শেষ হবে $endAt")
                    )
                    previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") + fmtDur(total)
                }
            }
        } else {
            val sel = a.selectedMin * 60_000L
            ring.active = false
            ring.icon = Ic.SHIELD
            ring.main = if (st == PStatus.COMPLETED) L.t("DONE", "সম্পন্ন") else L.t("OFF", "বন্ধ")
            ring.mainColor = C.primary
            ring.sub = ""
            ring.animateTo(0f)
            titleText.setTextAnimated(
                if (st == PStatus.COMPLETED) L.t("Session Completed", "সেশন সম্পন্ন")
                else L.t("Protection is Off", "প্রটেকশন বন্ধ আছে")
            )
            subText.setTextAnimated(when (st) {
                PStatus.SCHEDULED -> nextText(now)
                PStatus.COMPLETED -> L.t("You can start a new session", "নতুন সেশন শুরু করতে পারেন")
                else -> L.t("Start protection to activate PowerLock", "পাওয়ার লক চালু করতে প্রটেকশন শুরু করুন")
            })
            val ends = fmtClock(now + sel)
            val mode = unlockMode()
            val dest = unlockDest()
            previewText.text = when {
                mode == UNLOCK_LOC && dest != null -> L.t("Opens when you arrive at ${dest.name}", "লক খুলবে: ${dest.name} পৌঁছালে")
                mode == UNLOCK_LOC -> L.t("Choose a destination on the map", "ম্যাপে গন্তব্য বাছুন")
                mode == UNLOCK_EITHER && dest != null -> L.t("Duration ${fmtDur(sel)} · ends at $ends or at ${dest.name}", "সময় ${fmtDur(sel)} · শেষ হবে $ends অথবা ${dest.name} পৌঁছালে")
                mode == UNLOCK_EITHER -> L.t("Duration ${fmtDur(sel)} · ends at $ends · choose a destination on the map", "সময় ${fmtDur(sel)} · শেষ হবে $ends · ম্যাপে গন্তব্য বাছুন")
                else -> L.t("Duration ${fmtDur(sel)} · ends at $ends", "সময় ${fmtDur(sel)} · শেষ হবে $ends")
            }
        }
        ring.invalidate()
        showView(startBtn, !active)
        showView(stopBtn, active && !strict)
        // location-only sessions have no timer to extend
        showView(extendBtn, active && Store.unlockMode(a) != UNLOCK_LOC)
        if (lastActive != active) {
            lastActive = active
            refreshChips()
            refreshModeChips()
        }
        updateWarnings()
        updateLastSummary()
        maybeShowSummary()
        maybeShowBadge()
    }

    /** One line under the duration preview: what the last completed lock achieved. */
    private fun updateLastSummary() {
        val s = Store.lastSummary(a)
        val sig = if (s == null) "" else "${s.endMs}:${s.durMs}:${s.blocks}"
        if (sig == lastSummarySig) return
        lastSummarySig = sig
        if (s == null) {
            lastSummaryText.text = ""
            return
        }
        val blocksTxt = if (s.blocks > 0) {
            L.num(s.blocks.toString()) + " " + L.t("blocks", "বার ব্লক")
        } else {
            L.t("no blocks needed", "কোনো ব্লক লাগেনি")
        }
        lastSummaryText.text = L.t("Last session: ", "গত সেশন: ") + fmtDur(s.durMs) + " · " + blocksTxt
    }

    /** Shows the session-summary dialog once, the first time Home ticks after a lock completes. */
    private fun maybeShowSummary() {
        if (summaryShown) return
        if (!Store.summaryPending(a)) return
        val s = Store.lastSummary(a) ?: run {
            Store.clearSummaryPending(a)
            return
        }
        summaryShown = true
        Store.clearSummaryPending(a)
        val blocksTxt = if (s.blocks > 0) {
            L.num(s.blocks.toString()) + " " + L.t("times an app was blocked", "বার অ্যাপ ব্লক হয়েছে")
        } else {
            L.t("No app needed blocking", "কোনো অ্যাপ ব্লক করতে হয়নি")
        }
        a.dlg()
            .setTitle(L.t("Session completed!", "সেশন সম্পন্ন!"))
            .setMessage(
                L.t("Focus time: ", "ফোকাস সময়: ") + fmtDur(s.durMs) + "\n" + blocksTxt +
                    "\n\n“" + Quotes.today() + "”"
            )
            .setPositiveButton(L.t("Great!", "ভালো হয়েছে!"), null)
            .show()
    }

    /** Shows the "new badge" dialog once, the first time Home ticks after one is earned. */
    private fun maybeShowBadge() {
        if (badgeShown) return
        val b = Badges.pending(a) ?: return
        badgeShown = true
        Badges.clearPending(a)
        a.dlg()
            .setTitle(L.t("New badge!", "নতুন ব্যাজ!"))
            .setMessage(L.t(b.titleEn, b.titleBn) + "\n" + L.t(b.descEn, b.descBn))
            .setPositiveButton(L.t("Great!", "ভালো হয়েছে!"), null)
            .show()
    }

    private fun warnCard(icon: String, title: String, sub: String, onClick: () -> Unit): View {
        val c = a.card(12)
        c.background = rounded(Color.parseColor("#2A2415"), a.dp(18).toFloat(), C.warn, a.dp(1))
        val r = LinearLayout(a)
        r.gravity = Gravity.CENTER_VERTICAL
        val ic = a.tv(icon, 22f, C.warn, 1)
        ic.gravity = Gravity.CENTER
        r.addView(ic, lp(a.dp(28), WRAP, r = a.dp(12)))
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.addView(a.tv(title, 14f, C.warn, 1))
        col.addView(a.tv(sub, 12f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        c.addView(r)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun updateWarnings() {
        val acc = Perm.accessibilityOn(a)
        val bat = Perm.batteryOk(a)
        val sig = "$acc|$bat|${L.bn}"
        if (sig == warnSig) return
        warnSig = sig
        warnBox.removeAllViews()
        if (!acc) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Accessibility is off", "Accessibility বন্ধ আছে"),
                    L.t("Protection cannot work. Tap to enable.", "প্রটেকশন কাজ করবে না। চালু করতে চাপুন।")
                ) { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
        if (!bat) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Battery optimization is on", "ব্যাটারি অপটিমাইজেশন চালু"),
                    L.t("The system may stop PowerLock 2. Tap to allow.", "সিস্টেম PowerLock 2 বন্ধ করে দিতে পারে। অনুমতি দিতে চাপুন।")
                ) {
                    try {
                        a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: Exception) {
                        Log.e(TAG, "battery settings", e)
                    }
                },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
    }

    private fun onStart() {
        if (Store.isActive(a)) return
        if (!Perm.accessibilityOn(a)) {
            a.toast(L.t("Enable Accessibility first", "আগে Accessibility চালু করুন"))
            return
        }
        val mode = unlockMode()
        if (mode == UNLOCK_TIME) {
            startTimeSession()
            return
        }
        // location modes need a destination + location permission
        val dest = unlockDest()
        if (dest == null) {
            a.toast(L.t("Choose a destination first — pick a place on the map", "আগে গন্তব্য বাছুন — ম্যাপ থেকে জায়গা বাছুন"))
            a.startActivity(Intent(a, MapActivity::class.java))
            return
        }
        if (!Perm.locationOn(a)) {
            a.dlg()
                .setTitle(L.t("Location permission needed", "লোকেশন পারমিশন দরকার"))
                .setMessage(L.t("Location unlock needs location permission, otherwise the lock cannot open on arrival.", "লোকেশনে লক খুলতে লোকেশন পারমিশন লাগবে, না হলে পৌঁছালে লক খুলবে না।"))
                .setPositiveButton(L.t("Allow", "অনুমতি দিন")) { _, _ -> a.requestLocationPerm() }
                .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                .show()
            return
        }
        // already at the destination? warn in the confirm dialog (the lock would open at once)
        Extra.currentLocation(a) { loc ->
            var alreadyThere = false
            if (loc != null) {
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, dest.lat, dest.lng, res)
                alreadyThere = res[0] <= dest.radius
            }
            startLocationSession(mode, dest, alreadyThere)
        }
    }

    private fun startTimeSession() {
        val ms = a.selectedMin * 60_000L
        val ends = fmtClock(System.currentTimeMillis() + ms)
        val d = fmtDur(ms)
        val strictOn = Store.settings(a).getBoolean("strict", false)
        val extra = if (strictOn) {
            L.t(
                "\n\nStrict mode is ON: you will NOT be able to stop early.",
                "\n\nস্ট্রিক্ট মোড চালু: সময় শেষ না হওয়া পর্যন্ত বন্ধ করা যাবে না।"
            )
        } else {
            L.t(
                "\n\nStopping early needs a confirmation code.",
                "\n\nআগে বন্ধ করতে কনফার্মেশন কোড লাগবে।"
            )
        }
        a.dlg()
            .setTitle(L.t("Start protection?", "প্রটেকশন শুরু করবেন?"))
            .setMessage(
                L.t(
                    "The power menu will be blocked for $d, until $ends.",
                    "$d ধরে পাওয়ার মেনু ব্লক থাকবে, $ends পর্যন্ত।"
                ) + extra
            )
            .setPositiveButton(L.t("Start", "শুরু করুন")) { _, _ ->
                Store.startSession(a, ms, "manual")
                a.toast(L.t("Protection started", "প্রটেকশন শুরু হয়েছে"))
                tick()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun startLocationSession(mode: String, dest: UnlockTarget, alreadyThere: Boolean) {
        val ms = a.selectedMin * 60_000L
        val ends = fmtClock(System.currentTimeMillis() + ms)
        val d = fmtDur(ms)
        val strictOn = Store.settings(a).getBoolean("strict", false)
        val body = when (mode) {
            UNLOCK_LOC -> L.t(
                "The power menu will stay blocked until you arrive at ${dest.name} (within ${fmtDistM(dest.radius.toFloat())}).",
                "${dest.name} পৌঁছানো পর্যন্ত (ব্যাসার্ধ ${fmtDistM(dest.radius.toFloat())}) পাওয়ার মেনু ব্লক থাকবে।"
            )
            else -> L.t(
                "The power menu will be blocked for $d, until $ends — or it opens early when you arrive at ${dest.name}.",
                "$d ধরে পাওয়ার মেনু ব্লক থাকবে, $ends পর্যন্ত — অথবা ${dest.name} পৌঁছালে আগেই খুলবে।"
            )
        }
        val extra = if (strictOn) {
            when (mode) {
                UNLOCK_LOC -> L.t("\n\nStrict mode is ON: only arriving at ${dest.name} can open it.", "\n\nস্ট্রিক্ট মোড চালু: ${dest.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না।")
                else -> L.t("\n\nStrict mode is ON: only the timer or arriving at ${dest.name} can open it.", "\n\nস্ট্রিক্ট মোড চালু: সময় শেষ হওয়া বা ${dest.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না।")
            }
        } else {
            L.t("\n\nStopping early needs a confirmation code.", "\n\nআগে বন্ধ করতে কনফার্মেশন কোড লাগবে।")
        }
        val there = if (alreadyThere) L.t("\n\nYou are already at ${dest.name} — the lock will open right away.", "\n\nতুমি ইতিমধ্যে ${dest.name}-এ আছো — লক শুরু করলেই খুলে যাবে।") else ""
        val bgWarn = if (!Perm.backgroundLocationOn(a)) L.t("\n\n\"Allow all the time\" is off: arrival may not be detected with the screen off.", "\n\n\"সবসময় অনুমতি\" বন্ধ: স্ক্রিন বন্ধ থাকলে পৌঁছানো ধরা নাও পড়তে পারে।") else ""
        a.dlg()
            .setTitle(L.t("Start protection?", "প্রটেকশন শুরু করবেন?"))
            .setMessage(L.num(body + extra + there + bgWarn))
            .setPositiveButton(L.t("Start", "শুরু করুন")) { _, _ ->
                // location-only: the 15-day cap is only a safety valve, arrival ends it
                val dur = if (mode == UNLOCK_LOC) MAX_MIN * 60_000L else ms
                Store.startSession(a, dur, "manual", dest, mode)
                PlaceWatcher.sync(a) // start watching for the arrival right away
                PlaceWatcher.checkUnlockNow(a)
                a.toast(L.t("Protection started", "প্রটেকশন শুরু হয়েছে"))
                tick()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun onStop() {
        if (!Store.isActive(a)) return
        if (Store.isStrict(a)) {
            val mode = Store.unlockMode(a)
            val target = Store.unlockTarget(a)
            a.toast(
                when {
                    mode == UNLOCK_LOC && target != null -> L.t("Strict mode: only arriving at ${target.name} can open it", "স্ট্রিক্ট মোড: ${target.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না")
                    mode == UNLOCK_EITHER && target != null -> L.t("Strict mode: only the timer or arriving at ${target.name} can open it", "স্ট্রিক্ট মোড: সময় শেষ হওয়া বা ${target.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না")
                    else -> L.t("Strict mode: cannot stop before the timer ends", "স্ট্রিক্ট মোড: সময় শেষ না হওয়া পর্যন্ত বন্ধ করা যাবে না")
                }
            )
            return
        }
        val code = (100000..999999).random().toString()
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        box.addView(a.tv(L.t("Type this code to stop:", "বন্ধ করতে এই কোডটি লিখুন:"), 14f, C.muted))
        val codeView = a.tv(L.num(code), 28f, C.mint, 1)
        codeView.gravity = Gravity.CENTER
        codeView.letterSpacing = 0.2f
        box.addView(codeView, lp(MATCH, WRAP, t = a.dp(8)))
        val et = a.edit(L.t("Code", "কোড"), true)
        box.addView(et, lp(MATCH, WRAP, t = a.dp(8)))
        val d = a.dlg()
            .setTitle(L.t("Stop protection?", "প্রটেকশন বন্ধ করবেন?"))
            .setView(box)
            .setPositiveButton(L.t("Stop", "বন্ধ করুন"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (et.text.toString().trim() == code) {
                Store.stopSession(a)
                d.dismiss()
                a.toast(L.t("Protection stopped", "প্রটেকশন বন্ধ হয়েছে"))
                tick()
            } else {
                et.error = L.t("Wrong code", "ভুল কোড")
            }
        }
    }

    private fun customDialog() {
        if (Store.isActive(a)) return
        if (unlockMode() == UNLOCK_LOC) return
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        val r = LinearLayout(a)
        val dEt = a.edit(L.t("Days", "দিন"), true)
        val hEt = a.edit(L.t("Hours", "ঘণ্টা"), true)
        val mEt = a.edit(L.t("Minutes", "মিনিট"), true)
        r.addView(dEt, lp(0, WRAP, r = a.dp(8), weight = 1f))
        r.addView(hEt, lp(0, WRAP, r = a.dp(8), weight = 1f))
        r.addView(mEt, lp(0, WRAP, weight = 1f))
        box.addView(r)
        box.addView(a.tv(L.t("Maximum 15 days", "সর্বোচ্চ ১৫ দিন"), 12f, C.muted), lp(WRAP, WRAP, t = a.dp(8)))
        val d = a.dlg()
            .setTitle(L.t("Custom duration", "কাস্টম সময়"))
            .setView(box)
            .setPositiveButton(L.t("Set", "ঠিক আছে"), null)
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
                a.selectedMin = total.toInt()
                d.dismiss()
                refreshChips(true)
                tick()
            }
        }
    }
}

// =====================================================================
// WEEKLY SCHEDULE
// =====================================================================
class ScheduleScreen(private val a: MainActivity) {
    private lateinit var list: LinearLayout

    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Weekly Schedule", "সাপ্তাহিক সময়সূচি"), 24f, C.text, 1))
        col.addView(
            a.tv(
                L.t("Protection starts automatically at the chosen times.", "নির্ধারিত সময়ে প্রটেকশন নিজে থেকে শুরু হবে।"),
                13f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(4), b = a.dp(16))
        )
        val add = a.primaryBtn("＋  " + L.t("Add schedule", "সময়সূচি যোগ করুন"))
        add.setOnClickListener { editor(null) }
        col.addView(add, lp(MATCH, a.dp(52)))
        list = LinearLayout(a)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, lp(MATCH, WRAP, t = a.dp(14)))
        refresh()
        return sv
    }

    private fun refresh() {
        list.removeAllViews()
        val items = Store.schedules(a)
        if (items.isEmpty()) {
            val c = a.card(24)
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(IconView(a, Ic.CAL, C.primary), lp(a.dp(56), a.dp(56)))
            val t = a.tv(L.t("No schedules yet", "এখনো কোনো সময়সূচি নেই"), 15f, C.muted, 1)
            t.gravity = Gravity.CENTER
            c.addView(t, lp(MATCH, WRAP, t = a.dp(8)))
            list.addView(c)
            return
        }
        for (s in items.sortedBy { it.startMin }) {
            list.addView(scheduleCard(s), lp(MATCH, WRAP, b = a.dp(12)))
        }
    }

    private fun scheduleCard(s: Schedule): View {
        val c = a.card()
        val days = LinearLayout(a)
        for (i in 0..6) {
            val on = ((s.days shr i) and 1) == 1
            val d = a.tv(L.dayShort(i), 12f, if (on) C.onPrimary else C.muted, 1)
            d.gravity = Gravity.CENTER
            d.background = rounded(if (on) C.primary else C.card2, a.dp(14).toFloat())
            days.addView(d, lp(0, a.dp(32), r = if (i < 6) a.dp(6) else 0, weight = 1f))
        }
        c.addView(days)
        c.addView(a.tv(fmtMin(s.startMin) + "  →  " + fmtMin(s.endMin), 20f, C.text, 1), lp(MATCH, WRAP, t = a.dp(12)))
        if (s.endMin <= s.startMin) {
            c.addView(a.tv(L.t("Overnight · ends next day", "রাত পেরিয়ে · পরের দিন শেষ"), 12f, C.mint))
        }
        val bottom = LinearLayout(a)
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(
            a.tv(if (s.enabled) L.t("Enabled", "চালু") else L.t("Disabled", "বন্ধ"), 13f, C.muted),
            lp(0, WRAP, weight = 1f)
        )
        bottom.addView(a.toggle(s.enabled) { on ->
            val all = Store.schedules(a).map { if (it.id == s.id) it.copy(enabled = on) else it }
            Store.saveSchedules(a, all)
            Scheduler.onAlarm(a)
            list.post { refresh() }
        })
        val del = a.tv("🗑", 20f)
        del.setPadding(a.dp(16), a.dp(4), a.dp(4), a.dp(4))
        del.setOnClickListener {
            a.dlg()
                .setTitle(L.t("Delete schedule?", "সময়সূচি মুছবেন?"))
                .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
                    Store.saveSchedules(a, Store.schedules(a).filter { it.id != s.id })
                    Scheduler.onAlarm(a)
                    refresh()
                }
                .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                .show()
        }
        bottom.addView(del)
        c.addView(bottom, lp(MATCH, WRAP, t = a.dp(8)))
        c.setOnClickListener { editor(s) }
        return c
    }

    private fun editor(existing: Schedule?) {
        var days = existing?.days ?: 31
        var sMin = existing?.startMin ?: (22 * 60)
        var eMin = existing?.endMin ?: (6 * 60)

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0)

        val dayRow = LinearLayout(a)
        for (i in 0..6) {
            val d = a.chip(L.dayShort(i), ((days shr i) and 1) == 1)
            d.setPadding(0, a.dp(10), 0, a.dp(10))
            d.setOnClickListener {
                days = days xor (1 shl i)
                a.animateChip(d, ((days shr i) and 1) == 1)
            }
            dayRow.addView(d, lp(0, WRAP, r = if (i < 6) a.dp(4) else 0, weight = 1f))
        }
        box.addView(dayRow)

        val startView = a.tv("", 18f, C.text, 1)
        val endView = a.tv("", 18f, C.text, 1)
        val note = a.tv("", 12f, C.mint)
        for (v in listOf(startView, endView)) {
            v.gravity = Gravity.CENTER
            v.background = withRipple(rounded(C.card2, a.dp(12).toFloat()))
            v.setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(14))
        }
        fun render() {
            startView.text = L.t("Start  ", "শুরু  ") + fmtMin(sMin)
            endView.text = L.t("End  ", "শেষ  ") + fmtMin(eMin)
            note.text = if (eMin <= sMin) L.t("Overnight: ends the next day", "রাত পেরিয়ে পরের দিন শেষ হবে") else ""
        }
        startView.setOnClickListener {
            TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
                sMin = h * 60 + m
                render()
            }, sMin / 60, sMin % 60, false).show()
        }
        endView.setOnClickListener {
            TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
                eMin = h * 60 + m
                render()
            }, eMin / 60, eMin % 60, false).show()
        }
        val tr = LinearLayout(a)
        tr.addView(startView, lp(0, WRAP, r = a.dp(8), weight = 1f))
        tr.addView(endView, lp(0, WRAP, weight = 1f))
        box.addView(tr, lp(MATCH, WRAP, t = a.dp(14)))
        box.addView(note, lp(MATCH, WRAP, t = a.dp(8)))
        render()

        val d = a.dlg()
            .setTitle(if (existing == null) L.t("New schedule", "নতুন সময়সূচি") else L.t("Edit schedule", "সময়সূচি পরিবর্তন"))
            .setView(box)
            .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val all = Store.schedules(a)
            if (days == 0) {
                a.toast(L.t("Select at least one day", "কমপক্ষে একটি দিন বাছুন"))
            } else if (sMin == eMin) {
                a.toast(L.t("Start and end must be different", "শুরু ও শেষের সময় আলাদা হতে হবে"))
            } else if (all.any { it.id != existing?.id && it.days == days && it.startMin == sMin && it.endMin == eMin }) {
                a.toast(L.t("This schedule already exists", "এই সময়সূচি আগেই আছে"))
            } else {
                val updated: List<Schedule> = if (existing == null) {
                    all + Schedule(System.currentTimeMillis(), days, sMin, eMin, true)
                } else {
                    all.map { if (it.id == existing.id) Schedule(it.id, days, sMin, eMin, it.enabled) else it }
                }
                Store.saveSchedules(a, updated)
                Scheduler.onAlarm(a)
                d.dismiss()
                refresh()
            }
        }
    }
}

// =====================================================================
// STATISTICS
// =====================================================================
class StatsScreen(private val a: MainActivity) {
    private var calOffset = 0 // months back from the current month (0..-3)
    private lateinit var calGrid: LinearLayout
    private lateinit var calLabel: TextView
    private lateinit var calDetail: TextView
    // live-refresh cards: their bodies are rebuilt by tick() every 5 s
    private lateinit var usageCard: LinearLayout
    private lateinit var usageLogLabel: TextView
    private lateinit var usageLogBody: LinearLayout
    private var usageLogOffset = 0 // days ago shown in the usage log (0 = today, up to 29)
    private lateinit var focusScoreValue: TextView
    private lateinit var focusScoreDetail: TextView
    private lateinit var recordCard: LinearLayout
    private var lastStatsTick = 0L
    // quick-option cards: saved order + card being dragged (press-hold to move)
    private lateinit var gridWrap: LinearLayout
    private var draggingKey: String? = null
    private var cardOrder = ArrayList<String>()
    private var qSummaries = HashMap<String, String>()
    // auto-scroll while dragging: the stats ScrollView + last finger position
    private var statsScroll: ScrollView? = null
    private var lastDragY = -1f
    private var autoScrolling = false
    private val scrollHandler = Handler(Looper.getMainLooper())
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val sv = statsScroll
            val y = lastDragY
            if (draggingKey == null || sv == null || y < 0) { autoScrolling = false; return }
            val edge = a.dp(100).toFloat()
            val step = a.dp(24)
            when {
                y < edge -> sv.scrollBy(0, -step)
                y > sv.height - edge -> sv.scrollBy(0, step)
                else -> { /* finger is mid-screen; keep watching */ }
            }
            scrollHandler.postDelayed(this, 60)
        }
    }

    fun build(): View {
        Store.finalizeIfExpired(a)
        val (sv, col) = a.page()
        statsScroll = sv
        val all = Store.sessions(a)
        val now = System.currentTimeMillis()
        val today = Store.dayStart(now, 0)
        val week = Store.dayStart(now, -6)
        val month = Store.monthStart(now)
        val end = Long.MAX_VALUE
        val completed = all.count { it.ok }
        var sum = 0L
        var longest = 0L
        for (s in all) {
            sum += s.dur
            if (s.dur > longest) longest = s.dur
        }
        val avg = if (all.isEmpty()) 0L else sum / all.size

        col.addView(a.tv(L.t("Statistics", "পরিসংখ্যান"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))

        fun statCard(icon: Ic, label: String, value: String): View {
            val c = a.card(14)
            c.addView(IconView(a, icon, C.primary), lp(a.dp(24), a.dp(24)))
            c.addView(a.tv(value, 19f, C.text, 1), lp(MATCH, WRAP, t = a.dp(6)))
            c.addView(a.tv(label, 12f, C.muted))
            return c
        }

        fun pair(x: View, y: View) {
            val r = LinearLayout(a)
            r.addView(x, lp(0, MATCH, r = a.dp(10), weight = 1f))
            r.addView(y, lp(0, MATCH, weight = 1f))
            col.addView(r, lp(MATCH, WRAP, b = a.dp(10)))
        }

        // streak + total focus (completed locks only)
        val fs = Store.focusStats(a)
        pair(
            statCard(Ic.CHECK, L.t("Day streak", "দিনের স্ট্রিক"), L.num(fs.streak.toString())),
            statCard(Ic.CLOCK, L.t("Total focus", "মোট ফোকাস"), fmtDur(fs.totalMin * 60000L))
        )

        pair(
            statCard(Ic.CLOCK, L.t("Today", "আজ"), fmtDur(Store.sumRange(all, today, end))),
            statCard(Ic.CAL, L.t("Last 7 days", "গত ৭ দিন"), fmtDur(Store.sumRange(all, week, end)))
        )
        pair(
            statCard(Ic.CAL, L.t("This month", "এই মাস"), fmtDur(Store.sumRange(all, month, end))),
            statCard(Ic.CHECK, L.t("Completed sessions", "সম্পন্ন সেশন"), L.num(completed.toString()))
        )
        pair(
            statCard(Ic.BARS, L.t("Average session", "গড় সেশন"), fmtDur(avg)),
            statCard(Ic.SHIELD, L.t("Longest session", "সবচেয়ে বড় সেশন"), fmtDur(longest))
        )

        // আটকানো চেষ্টা — সবার ওপরে (ফোকাস স্কোরের কাছে)।
        col.addView(a.attemptsCard(), lp(MATCH, WRAP, b = a.dp(14)))

        // সব লম্বা সেকশন — কুইক অ্যাকশনের মতো আইকন কার্ডের গ্রিড (২ কলাম)।
        // প্রতিটি কার্ডে ট্যাপ করলে সেকশনের পুরো কনটেন্ট আলাদা উইন্ডোতে খোলে।
        val usageToday = Extra.usageTodayAll(a)
        val usageTodaySec = usageToday.values.sum()
        val logTodaySec = Extra.usageDayTotal(a, Store.dayKey(0))
        val treeStage = FocusTree.stageFor(fs.totalMin)
        val gardenSize = Garden.sync(a).size
        val storyUnlocked = Story.unlockedCount(a)
        val storyRead = Story.readIds(a).size
        val badgesEarned = Badges.earned(a).size
        val lockedDays = Store.focusDayMap(a).size
        val focus7Min = fs.last7.sum()
        val blocksToday = Extra.attempts(a).count { it.first >= today }
        // নতুন আইকন কার্ডগুলোর সারাংশের মান (ফোকাস স্কোর, চার্টের সেরা দিন,
        // এই সপ্তাহের ফোকাস, যাত্রার কিমি)।
        val scoreToday = Extra.focusScore(a)
        val thisWeekFocus = Store.sumRange(all, Store.dayStart(now, -6), end)
        var bestDayVal = 0L
        var bestDayLab = L.dayLong(dayIdx(Calendar.getInstance()))
        for (off in -6..0) {
            val from = Store.dayStart(now, off)
            val to = Store.dayStart(now, off + 1)
            val v = Store.sumRange(all, from, to)
            if (v > bestDayVal) {
                bestDayVal = v
                val cal = Calendar.getInstance()
                cal.timeInMillis = from
                bestDayLab = L.dayLong(dayIdx(cal))
            }
        }
        val journeyKm = Journey.totalKm(a)
        // কুইক কার্ডগুলোর এক লাইনের সারাংশ (সাজানোর সময়ও একই থাকে)।
        qSummaries["session"] = L.num(all.size.toString()) + L.t(" sessions · ", "টি সেশন · ") + L.num(completed.toString()) + L.t(" completed", "টি সম্পন্ন")
        qSummaries["story"] = L.num(storyUnlocked.toString()) + "/" + L.num(Story.TOTAL.toString()) + L.t(" chapters · ", " অধ্যায় · ") + L.num(storyRead.toString()) + L.t(" read", "টি পড়া")
        qSummaries["tree"] = L.t(treeStage.nameEn, treeStage.nameBn) + " · " + L.num(gardenSize.toString()) + L.t(" plants", "টি গাছ")
        qSummaries["usageLog"] = L.t("Today: ", "আজ মোট ") + fmtDur(logTodaySec * 1000L)
        qSummaries["appUsage"] = fmtDur(usageTodaySec * 1000L) + " · " + L.num(usageToday.size.toString()) + L.t(" apps", "টি অ্যাপ")
        qSummaries["badges"] = L.num(badgesEarned.toString()) + "/" + L.num(Badges.ALL.size.toString()) + L.t(" badges earned", "টি ব্যাজ পেয়েছেন")
        qSummaries["lockCal"] = L.num(lockedDays.toString()) + L.t(" days locked", " দিন লক")
        qSummaries["focus7"] = L.t("Total ", "মোট ") + fmtDur(focus7Min * 60000L)
        qSummaries["record"] = L.t("Best: ", "সেরা: ") + fmtDur(longest)
        qSummaries["diag"] = L.t("Today: ", "আজ ") + L.num(blocksToday.toString()) + L.t(" blocks", "টি ব্লক")
        qSummaries["score"] = L.num(scoreToday.score.toString()) + " / " + L.num("100")
        qSummaries["chart"] = L.t("Best: ", "সেরা: ") + bestDayLab + " · " + fmtDur(bestDayVal)
        qSummaries["week"] = L.t("This week: ", "এই সপ্তাহ: ") + fmtDur(thisWeekFocus)
        qSummaries["journey"] = L.num(String.format(Locale.US, "%.1f", journeyKm)) + " / " + L.num(Journey.TOTAL_KM.toString()) + L.t(" km", " কিমি")
        qSummaries["games"] = L.num("3") + L.t(" games", "টি গেম")
        cardOrder = loadCardOrder()
        gridWrap = LinearLayout(a)
        gridWrap.orientation = LinearLayout.VERTICAL
        gridWrap.setOnDragListener(cardDragListener)
        col.addView(gridWrap, lp(MATCH, WRAP))
        renderGrid()
        return sv
    }

    /** কুইক অ্যাকশনের মতো আইকন কার্ড: রঙিন বৃত্তে আইকন + শিরোনাম + এক লাইন সারাংশ।
     * ট্যাপ করলে onOpen() চলে — সেকশনের পুরো কনটেন্ট আলাদা উইন্ডোতে খোলে। */
    private fun iconCard(key: String, icon: Ic, iconColor: Int, title: String, summary: String, onOpen: () -> Unit): View {
        val c = a.card(14)
        val circle = LinearLayout(a)
        circle.gravity = Gravity.CENTER
        circle.background = rounded(tint(iconColor, 0x33), a.dp(32).toFloat())
        circle.addView(IconView(a, icon, iconColor), lp(a.dp(30), a.dp(30)))
        val circleWrap = LinearLayout(a)
        circleWrap.gravity = Gravity.CENTER
        circleWrap.addView(circle, lp(a.dp(64), a.dp(64)))
        c.addView(circleWrap, lp(MATCH, WRAP, t = a.dp(14)))
        val t = a.tv(title, 15f, C.text, 1)
        t.gravity = Gravity.CENTER
        c.addView(t, lp(MATCH, WRAP, t = a.dp(10)))
        val s = a.tv(summary, 12f, C.muted)
        s.gravity = Gravity.CENTER
        c.addView(s, lp(MATCH, WRAP, t = a.dp(2), b = a.dp(14)))
        c.tag = key
        c.setOnDragListener(cardDragListener)
        c.pressFeedback()
        c.setOnClickListener { onOpen() }
        c.setOnLongClickListener {
            // press-hold to drag: the card lifts off, drop it where it should go
            val started = c.startDragAndDrop(null, View.DragShadowBuilder(c), key, 0)
            if (started) {
                draggingKey = key
                c.visibility = View.INVISIBLE
            }
            started
        }
        return c
    }

    /** Builds one quick-option card by key (order comes from cardOrder). */
    private fun buildQuickCard(key: String): View {
        val s = qSummaries[key] ?: ""
        val blue = Color.parseColor("#6AA5FF")
        return when (key) {
            "session" -> iconCard(key, Ic.LIST, C.primary, L.t("Session history", "সেশন ইতিহাস"), s) {
                a.showSectionDialog(L.t("Session history", "সেশন ইতিহাস"), buildHistoryBody(Store.sessions(a)))
            }
            "story" -> iconCard(key, Ic.BOOK, C.warn, L.t("Story — The Quiet Garden", "গল্প — শান্ত বাগান"), s) {
                a.showSectionDialog(L.t("Story — The Quiet Garden", "গল্প — শান্ত বাগান"), a.storyCard())
            }
            "tree" -> iconCard(key, Ic.LEAF, C.mint, L.t("Focus tree & garden", "ফোকাস গাছ ও বাগান"), s) {
                a.showSectionDialog(L.t("Focus tree & garden", "ফোকাস গাছ ও বাগান"), a.treeGardenCard())
            }
            "usageLog" -> iconCard(key, Ic.CAL, blue, L.t("Usage log", "ব্যবহারের লগ"), s) {
                a.showSectionDialog(L.t("Usage log", "ব্যবহারের লগ"), buildUsageLogCard())
            }
            "appUsage" -> iconCard(key, Ic.BARS, C.danger, L.t("App usage today", "আজকের অ্যাপ ব্যবহার"), s) {
                usageCard = a.card()
                buildUsageCardBody()
                a.showSectionDialog(L.t("App usage today", "আজকের অ্যাপ ব্যবহার"), usageCard)
            }
            "badges" -> iconCard(key, Ic.AWARD, C.warn, L.t("Badges", "ব্যাজ"), s) {
                a.showSectionDialog(L.t("Badges", "ব্যাজ"), buildBadgesBody())
            }
            "lockCal" -> iconCard(key, Ic.LOCK, C.primary, L.t("Lock calendar", "লক ক্যালেন্ডার"), s) {
                a.showSectionDialog(L.t("Lock calendar", "লক ক্যালেন্ডার"), buildCalendarCard())
            }
            "focus7" -> iconCard(key, Ic.CLOCK, blue, L.t("Focus — last 7 days", "ফোকাস — গত ৭ দিন"), s) {
                a.showSectionDialog(L.t("Focus — last 7 days", "ফোকাস — গত ৭ দিন"), buildFocus7Body())
            }
            "record" -> iconCard(key, Ic.TROPHY, C.mint, L.t("Record board", "রেকর্ড বোর্ড"), s) {
                recordCard = a.card()
                buildRecordBoardBody()
                a.showSectionDialog(L.t("Record board", "রেকর্ড বোর্ড"), recordCard)
            }
            "diag" -> iconCard(key, Ic.SHIELD, C.danger, L.t("Diagnostics", "ডায়াগনস্টিক"), s) {
                a.showDiagDialog()
            }
            "score" -> iconCard(key, Ic.CHECK, C.mint, L.t("Focus score today", "আজকের ফোকাস স্কোর"), s) {
                a.showSectionDialog(L.t("Focus score today", "আজকের ফোকাস স্কোর"), buildFocusScoreCard())
            }
            "chart" -> iconCard(key, Ic.BARS, blue, L.t("Last 7 days chart", "গত ৭ দিনের চার্ট"), s) {
                a.showSectionDialog(L.t("Last 7 days chart", "গত ৭ দিনের চার্ট"), buildChartCard())
            }
            "week" -> iconCard(key, Ic.CAL, C.primary, L.t("This week vs last week", "এই সপ্তাহ বনাম গত সপ্তাহ"), s) {
                a.showSectionDialog(L.t("This week vs last week", "এই সপ্তাহ বনাম গত সপ্তাহ"), buildWeekCard())
            }
            "journey" -> iconCard(key, Ic.PIN, C.warn, L.t("Focus journey — Dhaka to Cox's Bazar", "ফোকাস যাত্রা — ঢাকা থেকে কক্সবাজার"), s) {
                a.showSectionDialog(L.t("Focus journey — Dhaka to Cox's Bazar", "ফোকাস যাত্রা — ঢাকা থেকে কক্সবাজার"), a.journeyCard())
            }
            "games" -> iconCard(key, Ic.GAME, C.mint, L.t("Mini-games", "মিনি গেম"), s) {
                a.showSectionDialog(L.t("Mini-games", "মিনি গেম"), a.gamesCard())
            }
            else -> View(a)
        }
    }

    /** Default quick-card order (top to bottom, left to right). */
    private fun defaultCardOrder(): ArrayList<String> = arrayListOf(
        "session", "story", "tree", "usageLog", "appUsage", "badges", "lockCal",
        "focus7", "record", "diag", "score", "chart", "week", "journey", "games"
    )

    /** Saved quick-card order, merged with the default (new cards append at the end). */
    private fun loadCardOrder(): ArrayList<String> {
        val def = defaultCardOrder()
        val saved = Store.settings(a).getString("quick_order", "") ?: ""
        val keys = saved.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return def
        val out = ArrayList<String>()
        for (k in keys) if (k in def && k !in out) out.add(k)
        for (k in def) if (k !in out) out.add(k)
        return out
    }

    private fun saveCardOrder() {
        Store.settings(a).edit().putString("quick_order", cardOrder.joinToString(",")).apply()
    }

    /** Rebuilds the quick-card grid from cardOrder (in place, no tab rebuild). */
    private fun renderGrid() {
        if (!::gridWrap.isInitialized) return
        gridWrap.removeAllViews()
        var i = 0
        while (i < cardOrder.size) {
            val x = buildQuickCard(cardOrder[i])
            val y = if (i + 1 < cardOrder.size) buildQuickCard(cardOrder[i + 1]) else View(a)
            if (cardOrder[i] == draggingKey) x.visibility = View.INVISIBLE
            if (i + 1 < cardOrder.size && cardOrder[i + 1] == draggingKey) y.visibility = View.INVISIBLE
            val r = LinearLayout(a)
            r.setOnDragListener(cardDragListener)
            r.addView(x, lp(0, MATCH, r = a.dp(10), weight = 1f))
            r.addView(y, lp(0, MATCH, weight = 1f))
            gridWrap.addView(r, lp(MATCH, WRAP, b = a.dp(10)))
            i += 2
        }
    }

    /** Finds a quick card's view in the grid by its key (or null). */
    private fun findQuickCardView(key: String): View? {
        if (!::gridWrap.isInitialized) return null
        for (i in 0 until gridWrap.childCount) {
            val row = gridWrap.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val v = row.getChildAt(j)
                if (v.tag as? String == key) return v
            }
        }
        return null
    }

    /** Layout params for a card in the left (with gap) or right slot of a row. */
    private fun slotLp(isLeft: Boolean): LinearLayout.LayoutParams =
        if (isLeft) lp(0, MATCH, r = a.dp(10), weight = 1f) else lp(0, MATCH, weight = 1f)

    /** Swaps two quick cards' VIEWS in the grid (no views are created or destroyed,
     * so the drag source stays alive while dragging). Returns false if either is missing. */
    private fun swapQuickCardViews(keyA: String, keyB: String): Boolean {
        if (!::gridWrap.isInitialized) return false
        var rowA: LinearLayout? = null
        var idxA = -1
        var rowB: LinearLayout? = null
        var idxB = -1
        for (i in 0 until gridWrap.childCount) {
            val row = gridWrap.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val v = row.getChildAt(j)
                when (v.tag as? String) {
                    keyA -> { rowA = row; idxA = j }
                    keyB -> { rowB = row; idxB = j }
                }
            }
        }
        val aRow = rowA ?: return false
        val bRow = rowB ?: return false
        val viewA = aRow.getChildAt(idxA)
        val viewB = bRow.getChildAt(idxB)
        if (aRow == bRow) {
            val (lo, hi) = if (idxA < idxB) idxA to idxB else idxB to idxA
            val loView = aRow.getChildAt(lo)
            val hiView = aRow.getChildAt(hi)
            aRow.removeViewAt(hi)
            aRow.removeViewAt(lo)
            aRow.addView(hiView, lo)
            aRow.addView(loView, hi)
            hiView.layoutParams = slotLp(lo == 0)
            loView.layoutParams = slotLp(hi == 0)
        } else {
            aRow.removeView(viewA)
            bRow.removeView(viewB)
            aRow.addView(viewB, idxA)
            bRow.addView(viewA, idxB)
            viewB.layoutParams = slotLp(idxA == 0)
            viewA.layoutParams = slotLp(idxB == 0)
        }
        return true
    }

    /** Drag-and-drop for the quick cards: press-hold a card, drop it where it should go.
     * Hovering over another card swaps the two card VIEWS live (no grid rebuild while
     * dragging — rebuilding mid-drag destroys the drag source and kicks the app out),
     * so the layout follows the finger; dropping anywhere in the grid finishes. */
    private val cardDragListener = View.OnDragListener { v, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                val targetKey = v.tag as? String
                val dragKey = draggingKey
                if (targetKey != null && dragKey != null && targetKey != dragKey) {
                    val from = cardOrder.indexOf(dragKey)
                    val to = cardOrder.indexOf(targetKey)
                    if (from >= 0 && to >= 0 && from != to) {
                        cardOrder.removeAt(from)
                        cardOrder.add(to, dragKey)
                        saveCardOrder()
                        swapQuickCardViews(dragKey, targetKey)
                    }
                }
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                // track the finger for auto-scroll: convert to a y inside the stats ScrollView
                val sv = statsScroll
                if (sv != null && draggingKey != null) {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val svLoc = IntArray(2)
                    sv.getLocationOnScreen(svLoc)
                    lastDragY = (loc[1] + event.y) - svLoc[1]
                    if (!autoScrolling) {
                        autoScrolling = true
                        scrollHandler.post(autoScrollRunnable)
                    }
                }
                true
            }
            DragEvent.ACTION_DROP -> {
                saveCardOrder()
                findQuickCardView(draggingKey ?: "")?.visibility = View.VISIBLE
                draggingKey = null
                scrollHandler.removeCallbacks(autoScrollRunnable)
                autoScrolling = false
                lastDragY = -1f
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                scrollHandler.removeCallbacks(autoScrollRunnable)
                autoScrolling = false
                lastDragY = -1f
                val dk = draggingKey
                if (dk != null) {
                    // the drop was missed (released outside the grid): show the card again
                    // and rebuild the grid once, now that the drag is fully over
                    draggingKey = null
                    findQuickCardView(dk)?.visibility = View.VISIBLE
                    renderGrid()
                }
                true
            }
            else -> true
        }
    }

    /** ব্যাজের পুরো গ্রিড (ডায়ালগের ভেতরে দেখানোর জন্য)। */
    private fun buildBadgesBody(): View {
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        val earned = Badges.earned(a)
        val badgeDefs = Badges.ALL
        for (rowIdx in badgeDefs.indices step 2) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until 2) {
                val def = badgeDefs.getOrNull(rowIdx + j)
                val cell = LinearLayout(a)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.background = rounded(C.card2, a.dp(14).toFloat())
                cell.setPadding(a.dp(10), a.dp(12), a.dp(10), a.dp(12))
                if (def != null) {
                    val whenMs = earned[def.id]
                    val got = whenMs != null
                    cell.alpha = if (got) 1f else 0.45f
                    cell.addView(IconView(a, def.icon, if (got) C.mint else C.muted), lp(a.dp(30), a.dp(30)))
                    val t = a.tv(L.t(def.titleEn, def.titleBn), 13f, if (got) C.text else C.muted, 1)
                    t.gravity = Gravity.CENTER
                    cell.addView(t, lp(MATCH, WRAP, t = a.dp(6)))
                    val d = a.tv(L.t(def.descEn, def.descBn), 11f, C.muted)
                    d.gravity = Gravity.CENTER
                    cell.addView(d, lp(MATCH, WRAP, t = a.dp(2)))
                    val s = a.tv(
                        if (got) fmtDate(whenMs ?: 0L) else L.t("Not earned yet", "এখনো পাননি"),
                        11f, if (got) C.primary else C.muted, 1
                    )
                    s.gravity = Gravity.CENTER
                    cell.addView(s, lp(MATCH, WRAP, t = a.dp(4)))
                }
                row.addView(cell, lp(0, MATCH, r = if (j == 0) a.dp(10) else 0, t = a.dp(10), weight = 1f))
            }
            body.addView(row)
        }
        return body
    }

    /** সেশন ইতিহাসের পুরো তালিকা (শেষ ৩০টি, ডায়ালগের ভেতরে)। */
    private fun buildHistoryBody(all: List<Session>): View {
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        if (all.isEmpty()) {
            val e = a.tv(L.t("No sessions yet", "এখনো কোনো সেশন নেই"), 14f, C.muted)
            e.gravity = Gravity.CENTER
            body.addView(e, lp(MATCH, WRAP, t = a.dp(16), b = a.dp(8)))
        } else {
            for (s in all.reversed().take(30)) {
                val r = LinearLayout(a)
                r.gravity = Gravity.CENTER_VERTICAL
                r.setPadding(0, a.dp(10), 0, a.dp(10))
                val left = LinearLayout(a)
                left.orientation = LinearLayout.VERTICAL
                left.addView(a.tv(fmtDate(s.start) + " · " + fmtClock(s.start), 14f, C.text, 1))
                val src = when (s.src) {
                    "schedule" -> L.t("Scheduled", "সময়সূচি")
                    "routine" -> L.t("Routine", "রুটিন")
                    "pomodoro" -> "Pomodoro"
                    else -> L.t("Manual", "ম্যানুয়াল")
                }
                val state = if (s.ok) L.t("Completed", "সম্পন্ন") else L.t("Stopped early", "আগেই বন্ধ")
                left.addView(a.tv("$src · $state", 12f, C.muted))
                r.addView(left, lp(0, WRAP, weight = 1f))
                r.addView(a.tv(fmtDur(s.dur), 15f, if (s.ok) C.mint else C.warn, 1))
                body.addView(r)
            }
        }
        return body
    }

    /** ব্যবহারের লগের পুরো কার্ড (দিন বদলের ন্যাভ + বডি, ডায়ালগের ভেতরে)। */
    private fun buildUsageLogCard(): View {
        val logCard = a.card()
        logCard.addView(a.section(L.t("Usage log", "ব্যবহারের লগ")))
        val logNav = LinearLayout(a)
        logNav.gravity = Gravity.CENTER_VERTICAL
        val logPrev = a.tv("‹", 28f, C.primary, 1)
        logPrev.setPadding(a.dp(12), 0, a.dp(12), 0)
        logPrev.setOnClickListener {
            if (usageLogOffset < 29) {
                usageLogOffset++
                buildUsageLogBody()
            }
        }
        val logNext = a.tv("›", 28f, C.primary, 1)
        logNext.setPadding(a.dp(12), 0, a.dp(12), 0)
        logNext.setOnClickListener {
            if (usageLogOffset > 0) {
                usageLogOffset--
                buildUsageLogBody()
            }
        }
        usageLogLabel = a.tv("", 16f, C.text, 1)
        usageLogLabel.gravity = Gravity.CENTER
        logNav.addView(logPrev)
        logNav.addView(usageLogLabel, lp(0, WRAP, weight = 1f))
        logNav.addView(logNext)
        logCard.addView(logNav, lp(MATCH, WRAP, t = a.dp(8)))
        usageLogBody = LinearLayout(a)
        usageLogBody.orientation = LinearLayout.VERTICAL
        logCard.addView(usageLogBody, lp(MATCH, WRAP, t = a.dp(4)))
        buildUsageLogBody()
        return logCard
    }

    /** লক ক্যালেন্ডারের পুরো কার্ড (মাস বদলের ন্যাভ + গ্রিড, ডায়ালগের ভেতরে)। */
    private fun buildCalendarCard(): View {
        val calCard = a.card()
        calCard.addView(a.section(L.t("Lock calendar", "লক ক্যালেন্ডার")))
        val navRow = LinearLayout(a)
        navRow.gravity = Gravity.CENTER_VERTICAL
        val prevBtn = a.tv("‹", 28f, C.primary, 1)
        prevBtn.setPadding(a.dp(12), 0, a.dp(12), 0)
        prevBtn.setOnClickListener {
            if (calOffset > -3) {
                calOffset--
                buildCalendar()
            }
        }
        val nextBtn = a.tv("›", 28f, C.primary, 1)
        nextBtn.setPadding(a.dp(12), 0, a.dp(12), 0)
        nextBtn.setOnClickListener {
            if (calOffset < 0) {
                calOffset++
                buildCalendar()
            }
        }
        calLabel = a.tv("", 16f, C.text, 1)
        calLabel.gravity = Gravity.CENTER
        navRow.addView(prevBtn)
        navRow.addView(calLabel, lp(0, WRAP, weight = 1f))
        navRow.addView(nextBtn)
        calCard.addView(navRow, lp(MATCH, WRAP, t = a.dp(8)))
        val headRow = LinearLayout(a)
        for (i in 0 until 7) {
            val h = a.tv(L.dayShort(i), 12f, C.muted, 1)
            h.gravity = Gravity.CENTER
            headRow.addView(h, lp(0, WRAP, weight = 1f))
        }
        calCard.addView(headRow, lp(MATCH, WRAP, t = a.dp(10)))
        calGrid = LinearLayout(a)
        calGrid.orientation = LinearLayout.VERTICAL
        calCard.addView(calGrid, lp(MATCH, WRAP, t = a.dp(4)))
        calDetail = a.tv("", 13f, C.muted)
        calDetail.gravity = Gravity.CENTER
        calCard.addView(calDetail, lp(MATCH, WRAP, t = a.dp(10)))
        buildCalendar()
        return calCard
    }

    /** ফোকাস — গত ৭ দিনের পুরো তালিকা (ডায়ালগের ভেতরে)। */
    private fun buildFocus7Body(): View {
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        val fs = Store.focusStats(a)
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val label = if (i == 0) L.t("Today", "আজ") else L.dayLong(dayIdx(c))
            val min = fs.last7[6 - i]
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(if (min > 0) fmtDur(min * 60000L) else "—", 14f, if (min > 0) C.mint else C.muted, 1))
            body.addView(r)
        }
        return body
    }

    /** Live tick from the MainActivity ticker (throttled to every 5 s): rebuilds
     * the cards whose numbers move while the screen is open. */
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastStatsTick < 5000) return
        lastStatsTick = now
        try {
            if (::usageCard.isInitialized) {
                buildUsageCardBody()
                if (usageLogOffset == 0) buildUsageLogBody() // only today's log moves
            }
            refreshFocusScore()
            if (::recordCard.isInitialized) buildRecordBoardBody()
        } catch (e: Exception) {
            Log.e(TAG, "stats tick failed", e)
        }
    }

    /** (Re)builds the "App usage today" card body: most-used first, top 10,
     * with per-app bars and 7-day totals. Tapping a row opens its daily-limit
     * setting. Rebuilt on every live tick so the numbers move. */
    private fun buildUsageCardBody() {
        usageCard.removeAllViews()
        // (শিরোনাম নেই — ডায়ালগের টাইটেলই শিরোনাম।)
        val usageToday = Extra.usageTodayAll(a)
        val usage7 = Extra.usage7DayTotal(a)
        if (usageToday.isEmpty()) {
            usageCard.addView(
                a.tv(L.t("No usage tracked yet today. Accessibility must be on to track app time.", "আজ এখনো কোনো ব্যবহার ট্র্যাক হয়নি। অ্যাপের সময় ট্র্যাক করতে Accessibility চালু থাকতে হবে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(6))
            )
            return
        }
        data class URow(val pkg: String, val label: String, val icon: Drawable?, val todaySec: Long, val weekSec: Long)
        val rows = ArrayList<URow>()
        val pm = a.packageManager
        for ((pkg, sec) in usageToday) {
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                rows.add(URow(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), sec, usage7[pkg] ?: 0L))
            } catch (e: Exception) {
                // uninstalled: skip the row (its seconds still count in the totals)
            }
        }
        rows.sortByDescending { it.todaySec }
        val maxSec = rows.firstOrNull()?.todaySec ?: 1L
        for (row in rows.take(10)) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(8), 0, a.dp(2))
            if (row.icon != null) {
                val icon = ImageView(a)
                icon.setImageDrawable(row.icon)
                r.addView(icon, lp(a.dp(36), a.dp(36), r = a.dp(12)))
            } else {
                r.addView(View(a), lp(a.dp(36), a.dp(36), r = a.dp(12)))
            }
            val labelCol = LinearLayout(a)
            labelCol.orientation = LinearLayout.VERTICAL
            labelCol.addView(a.tv(row.label, 15f, C.text))
            labelCol.addView(a.tv(L.t("7 days: ", "৭ দিনে: ") + fmtDur(row.weekSec * 1000L), 12f, C.muted))
            r.addView(labelCol, lp(0, WRAP, weight = 1f))
            r.addView(a.tv(fmtDur(row.todaySec * 1000L), 14f, C.text, 1))
            r.pressFeedback()
            r.setOnClickListener { a.limitChoiceDialog(row.pkg, row.label, Extra.dailyLimit(a, row.pkg)) { a.go(2) } }
            usageCard.addView(r, lp(MATCH, WRAP, t = a.dp(4)))
            // usage bar, relative to the most-used app
            val barWrap = LinearLayout(a)
            barWrap.orientation = LinearLayout.HORIZONTAL
            barWrap.background = rounded(C.card2, a.dp(3).toFloat())
            val frac = (row.todaySec.toFloat() / maxSec.toFloat()).coerceIn(0.02f, 1f)
            val fill = View(a)
            fill.background = rounded(C.primary, a.dp(3).toFloat())
            barWrap.addView(fill, lp(0, a.dp(6), weight = frac))
            barWrap.addView(View(a), lp(0, a.dp(6), weight = 1f - frac))
            usageCard.addView(barWrap, lp(MATCH, a.dp(6), t = a.dp(4)))
        }
        val yest = Extra.usageDayTotal(a, Store.dayKey(1))
        val weekTotal = usage7.values.sum()
        usageCard.addView(
            a.tv(
                L.t("Yesterday total: ", "গতকাল মোট: ") + fmtDur(yest * 1000L) + " · " +
                    L.t("Last 7 days total: ", "গত ৭ দিন মোট: ") + fmtDur(weekTotal * 1000L),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(10))
        )
    }

    /** (Re)builds the per-day usage log for [usageLogOffset] days ago:
     * every app's minutes that day, most-used first, plus the day total. */
    private fun buildUsageLogBody() {
        if (!::usageLogBody.isInitialized || !::usageLogLabel.isInitialized) return
        usageLogLabel.text = when (usageLogOffset) {
            0 -> L.t("Today", "আজ")
            1 -> L.t("Yesterday", "গতকাল")
            else -> fmtDate(Store.dayStart(System.currentTimeMillis(), -usageLogOffset))
        }
        usageLogBody.removeAllViews()
        val day = Extra.usageForDay(a, Store.dayKey(usageLogOffset))
        if (day.isEmpty()) {
            usageLogBody.addView(
                a.tv(L.t("No usage tracked on this day.", "এই দিনে কোনো ব্যবহার ট্র্যাক হয়নি।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(6))
            )
            return
        }
        val rows = day.entries.sortedByDescending { it.value }
        val pm = a.packageManager
        for ((pkg, sec) in rows.take(15)) {
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(fmtDur(sec * 1000L), 14f, C.text, 1))
            usageLogBody.addView(r)
        }
        if (rows.size > 15) {
            usageLogBody.addView(
                a.tv(L.num(L.t("and ${rows.size - 15} more apps", "আরও ${rows.size - 15}টি অ্যাপ")), 12f, C.muted),
                lp(MATCH, WRAP, t = a.dp(4))
            )
        }
        val total = day.values.sum()
        usageLogBody.addView(
            a.tv(L.t("Total: ", "মোট: ") + fmtDur(total * 1000L), 13f, C.mint, 1),
            lp(MATCH, WRAP, t = a.dp(8))
        )
    }

    /** Refreshes the focus-score card from today's data. */
    private fun refreshFocusScore() {
        if (!::focusScoreValue.isInitialized) return
        val fs = Extra.focusScore(a)
        focusScoreValue.text = L.num(fs.score.toString()) + " / " + L.num("100")
        val done = if (fs.completedToday) L.t(" · lock completed today", " · আজ লক সম্পন্ন") else ""
        focusScoreDetail.text = L.num(
            L.t(
                "Focus ${fs.focusMinToday}m of ${fs.goalMin}m goal · ${fs.blocksToday} blocks resisted · ${fs.streak}-day streak$done",
                "ফোকাস ${fs.focusMinToday} মিনিট / ${fs.goalMin} মিনিট লক্ষ্য · ${fs.blocksToday}টি ব্লক প্রতিহত · ${fs.streak} দিনের স্ট্রিক$done"
            )
        )
    }

    /** The 8 stat tiles (the board at the top of the tab), built fresh — shown inside the focus score dialog. */
    private fun buildStatTiles(): View {
        val all = Store.sessions(a)
        val now = System.currentTimeMillis()
        val today = Store.dayStart(now, 0)
        val week = Store.dayStart(now, -6)
        val month = Store.monthStart(now)
        val end = Long.MAX_VALUE
        val completed = all.count { it.ok }
        var sum = 0L
        var longest = 0L
        for (s in all) {
            sum += s.dur
            if (s.dur > longest) longest = s.dur
        }
        val avg = if (all.isEmpty()) 0L else sum / all.size
        val fs = Store.focusStats(a)
        val board = LinearLayout(a)
        board.orientation = LinearLayout.VERTICAL
        fun statCard(icon: Ic, label: String, value: String): View {
            val c = a.card(14)
            c.addView(IconView(a, icon, C.primary), lp(a.dp(24), a.dp(24)))
            c.addView(a.tv(value, 19f, C.text, 1), lp(MATCH, WRAP, t = a.dp(6)))
            c.addView(a.tv(label, 12f, C.muted))
            return c
        }
        fun pair(x: View, y: View) {
            val r = LinearLayout(a)
            r.addView(x, lp(0, MATCH, r = a.dp(10), weight = 1f))
            r.addView(y, lp(0, MATCH, weight = 1f))
            board.addView(r, lp(MATCH, WRAP, b = a.dp(10)))
        }
        pair(
            statCard(Ic.CHECK, L.t("Day streak", "দিনের স্ট্রিক"), L.num(fs.streak.toString())),
            statCard(Ic.CLOCK, L.t("Total focus", "মোট ফোকাস"), fmtDur(fs.totalMin * 60000L))
        )
        pair(
            statCard(Ic.CLOCK, L.t("Today", "আজ"), fmtDur(Store.sumRange(all, today, end))),
            statCard(Ic.CAL, L.t("Last 7 days", "গত ৭ দিন"), fmtDur(Store.sumRange(all, week, end)))
        )
        pair(
            statCard(Ic.CAL, L.t("This month", "এই মাস"), fmtDur(Store.sumRange(all, month, end))),
            statCard(Ic.CHECK, L.t("Completed sessions", "সম্পন্ন সেশন"), L.num(completed.toString()))
        )
        pair(
            statCard(Ic.BARS, L.t("Average session", "গড় সেশন"), fmtDur(avg)),
            statCard(Ic.SHIELD, L.t("Longest session", "সবচেয়ে বড় সেশন"), fmtDur(longest))
        )
        return board
    }

    /** Focus-score card, built fresh on every open: score + detail + the stat-tiles board + share button. */
    private fun buildFocusScoreCard(): View {
        val wrap = LinearLayout(a)
        wrap.orientation = LinearLayout.VERTICAL
        val fs = Extra.focusScore(a)
        val c = a.card()
        c.addView(a.section(L.t("Focus score today", "আজকের ফোকাস স্কোর")))
        c.addView(a.tv(L.num(fs.score.toString()) + " / " + L.num("100"), 34f, C.mint, 1), lp(MATCH, WRAP, t = a.dp(8)))
        val done = if (fs.completedToday) L.t(" · lock completed today", " · আজ লক সম্পন্ন") else ""
        c.addView(
            a.tv(
                L.num(
                    L.t(
                        "Focus ${fs.focusMinToday}m of ${fs.goalMin}m goal · ${fs.blocksToday} blocks resisted · ${fs.streak}-day streak$done",
                        "ফোকাস ${fs.focusMinToday} মিনিট / ${fs.goalMin} মিনিট লক্ষ্য · ${fs.blocksToday}টি ব্লক প্রতিহত · ${fs.streak} দিনের স্ট্রিক$done"
                    )
                ), 13f, C.muted
            ), lp(MATCH, WRAP, t = a.dp(4))
        )
        wrap.addView(c, lp(MATCH, WRAP))
        wrap.addView(buildStatTiles(), lp(MATCH, WRAP, t = a.dp(12)))
        val shareBtn = a.primaryBtn(L.t("Share my focus card", "আমার ফোকাস কার্ড শেয়ার করুন"))
        shareBtn.setOnClickListener { FocusCardShare.share(a) }
        wrap.addView(shareBtn, lp(MATCH, a.dp(52), t = a.dp(12)))
        return wrap
    }

    /** Last-7-days bar chart, built fresh on every open. */
    private fun buildChartCard(): View {
        val c = a.card()
        c.addView(a.section(L.t("Last 7 days", "গত ৭ দিন")))
        val chart = BarChartView(a)
        val all = Store.sessions(a)
        val now = System.currentTimeMillis()
        val vals = ArrayList<Long>()
        val labs = ArrayList<String>()
        for (off in -6..0) {
            val from = Store.dayStart(now, off)
            val to = Store.dayStart(now, off + 1)
            vals.add(Store.sumRange(all, from, to))
            val cal = Calendar.getInstance()
            cal.timeInMillis = from
            labs.add(L.dayLong(dayIdx(cal)))
        }
        chart.values = vals
        chart.labels = labs
        c.addView(chart, lp(MATCH, a.dp(150), t = a.dp(12)))
        return c
    }

    /** This-week-vs-last-week comparison, built fresh on every open. */
    private fun buildWeekCard(): View {
        val all = Store.sessions(a)
        val now = System.currentTimeMillis()
        val end = Long.MAX_VALUE
        val weekCard = a.card()
        weekCard.addView(a.section(L.t("This week vs last week", "এই সপ্তাহ বনাম গত সপ্তাহ")))
        val thisFrom = Store.dayStart(now, -6)
        val lastFrom = Store.dayStart(now, -13)
        val thisFocus = Store.sumRange(all, thisFrom, end)
        val lastFocus = Store.sumRange(all, lastFrom, thisFrom)
        val thisSessions = all.count { it.ok && it.start >= thisFrom }
        val lastSessions = all.count { it.ok && it.start >= lastFrom && it.start < thisFrom }
        val attemptsList = Extra.attempts(a)
        val thisBlocks = attemptsList.count { it.first >= thisFrom }
        val lastBlocks = attemptsList.count { it.first >= lastFrom && it.first < thisFrom }
        fun weekRow(label: String, thisVal: String, lastVal: String, thisN: Long, lastN: Long) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(8), 0, a.dp(2))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(thisVal, 15f, C.text, 1))
            val arrow = when {
                thisN > lastN -> "▲"
                thisN < lastN -> "▼"
                else -> "—"
            }
            val arrowColor = when {
                thisN > lastN -> C.mint
                thisN < lastN -> C.danger
                else -> C.muted
            }
            val arrowTv = a.tv(arrow, 14f, arrowColor, 1)
            arrowTv.setPadding(a.dp(8), 0, 0, 0)
            r.addView(arrowTv)
            weekCard.addView(r)
            weekCard.addView(
                a.tv(L.t("Last week: ", "গত সপ্তাহ: ") + lastVal, 12f, C.muted),
                lp(MATCH, WRAP, b = a.dp(4))
            )
        }
        weekRow(L.t("Focus time", "ফোকাস সময়"), fmtDur(thisFocus), fmtDur(lastFocus), thisFocus, lastFocus)
        weekRow(
            L.t("Completed locks", "সম্পন্ন লক"),
            L.num(thisSessions.toString()), L.num(lastSessions.toString()),
            thisSessions.toLong(), lastSessions.toLong()
        )
        weekRow(
            L.t("Blocks resisted", "প্রতিহত ব্লক"),
            L.num(thisBlocks.toString()), L.num(lastBlocks.toString()),
            thisBlocks.toLong(), lastBlocks.toLong()
        )
        return weekCard
    }

    /** (Re)builds the record board from stored history: longest single lock,
     * most locks in one day, most blocks in one day, longest streak. Because
     * the values are computed live, a broken record shows up at once. */
    private fun buildRecordBoardBody() {
        if (!::recordCard.isInitialized) return
        recordCard.removeAllViews()
        recordCard.addView(a.section(L.t("Record board", "রেকর্ড বোর্ড")))
        val all = Store.sessions(a)
        val longest = all.maxOfOrNull { it.dur } ?: 0L
        val locksPerDay = HashMap<String, Int>()
        for (s in all) {
            if (!s.ok) continue
            val k = Store.dayKeyFor(s.start)
            locksPerDay[k] = (locksPerDay[k] ?: 0) + 1
        }
        val mostLocks = locksPerDay.values.maxOrNull() ?: 0
        val blocksPerDay = HashMap<String, Int>()
        for ((t, _) in Extra.attempts(a)) {
            val k = Store.dayKeyFor(t)
            blocksPerDay[k] = (blocksPerDay[k] ?: 0) + 1
        }
        val mostBlocks = blocksPerDay.values.maxOrNull() ?: 0
        // longest streak: walk the stored focus days oldest-first, count the
        // longest run of consecutive days with a completed lock
        val focus = Store.focusDayMap(a)
        var longestStreak = 0
        var run = 0
        for (i in 89 downTo 0) {
            if ((focus[Store.dayKey(i)] ?: 0) > 0) {
                run++
                if (run > longestStreak) longestStreak = run
            } else {
                run = 0
            }
        }
        fun recordRow(label: String, value: String) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(8), 0, a.dp(8))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(value, 15f, C.mint, 1))
            recordCard.addView(r)
        }
        recordRow(L.t("Longest single lock", "সবচেয়ে লম্বা একক লক"), fmtDur(longest))
        recordRow(L.t("Most locks in a day", "একদিনে সর্বোচ্চ লক"), L.num(mostLocks.toString()))
        recordRow(L.t("Most blocks in a day", "একদিনে সর্বোচ্চ ব্লক"), L.num(mostBlocks.toString()))
        recordRow(L.t("Longest streak", "সবচেয়ে লম্বা স্ট্রিক"), L.num(longestStreak.toString()) + L.t(" days", " দিন"))
    }

    /** Rebuilds the month grid for the current [calOffset]. */
    private fun buildCalendar() {
        if (!::calGrid.isInitialized) return
        val focus = Store.focusDayMap(a)
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, calOffset)
        val year = c.get(Calendar.YEAR)
        val month = c.get(Calendar.MONTH)
        calLabel.text = L.month(month) + " " + L.num(year.toString())
        calGrid.removeAllViews()
        val first = Calendar.getInstance()
        first.set(year, month, 1)
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // blank cells before the 1st (Monday first)
        val todayKey = Store.dayKey(0)
        var day = 1
        while (day <= daysInMonth) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            for (col in 0 until 7) {
                val cell = LinearLayout(a)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.setPadding(0, a.dp(5), 0, a.dp(5))
                if ((day == 1 && col < lead) || day > daysInMonth) {
                    // blank cell
                } else {
                    val key = String.format(Locale.US, "%04d%02d%02d", year, month + 1, day)
                    val min = focus[key] ?: 0
                    val isToday = key == todayKey
                    if (isToday) cell.background = rounded(C.card2, a.dp(10).toFloat())
                    val num = a.tv(L.num(day.toString()), 14f, if (min > 0) C.mint else C.text, if (min > 0 || isToday) 1 else 0)
                    num.gravity = Gravity.CENTER
                    cell.addView(num, lp(MATCH, WRAP))
                    val dot = a.tv(if (min > 0) "●" else "", 8f, C.primary, 1)
                    dot.gravity = Gravity.CENTER
                    cell.addView(dot, lp(MATCH, WRAP))
                    val d = day
                    val m = min
                    cell.pressFeedback()
                    cell.setOnClickListener {
                        calDetail.text = if (m > 0) {
                            L.num(d.toString()) + " " + L.month(month) + ": " + fmtDur(m * 60000L) + " " + L.t("focus", "ফোকাস")
                        } else {
                            L.num(d.toString()) + " " + L.month(month) + ": " + L.t("no completed lock", "কোনো লক সম্পন্ন হয়নি")
                        }
                    }
                    day++
                }
                row.addView(cell, lp(0, WRAP, weight = 1f))
            }
            calGrid.addView(row)
        }
        calDetail.text = L.t("Tap a day to see its focus time", "ফোকাস সময় দেখতে দিনে চাপুন")
    }
}

// =====================================================================
// SETTINGS
// =====================================================================
class SettingsScreen(private val a: MainActivity) {

    /** One strict-protection option as its own glowing card. */
    private fun glowOption(title: String, sub: String, checked: Boolean, locked: Boolean, onChange: (Boolean) -> Unit): View {
        val glow = GlowBorderDrawable(a.dp(20).toFloat(), a.dp(8).toFloat(), a.dp(2).toFloat())
        glow.level = if (checked) 1f else 0f
        val box = LinearLayout(a)
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = glow
        box.setPadding(a.dp(22), a.dp(20), a.dp(22), a.dp(20))
        val texts = LinearLayout(a)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(a.tv(title, 16f, C.text, 1))
        texts.addView(a.tv(sub, 12f, C.muted), lp(MATCH, WRAP, t = a.dp(4)))
        box.addView(texts, lp(0, WRAP, r = a.dp(12), weight = 1f))

        var pulser: ValueAnimator? = null
        fun stopPulse() {
            pulser?.cancel()
            pulser = null
            glow.pulse = 1f
        }
        fun startPulse() {
            pulser?.cancel()
            val p = ValueAnimator.ofFloat(0.55f, 1f)
            p.duration = 1100
            p.repeatMode = ValueAnimator.REVERSE
            p.repeatCount = ValueAnimator.INFINITE
            p.addUpdateListener { glow.pulse = it.animatedValue as Float }
            p.start()
            pulser = p
        }
        val sw = a.toggle(checked) { v ->
            onChange(v)
            val la = ValueAnimator.ofFloat(glow.level, if (v) 1f else 0f)
            la.duration = 300
            la.addUpdateListener { glow.level = it.animatedValue as Float }
            la.start()
            if (v) startPulse() else stopPulse()
        }
        if (locked) sw.isEnabled = false
        box.addView(sw)
        if (checked) startPulse()
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                pulser?.cancel()
            }
        })
        return box
    }

    private fun launch(intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "open settings failed", e)
        }
    }

    private fun blockMessageDialog() {
        val et = a.edit(L.t("Your message", "তোমার মেসেজ"), false)
        et.setText(Extra.blockMessage(a))
        et.setSelection(et.text.length)
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(4))
        body.addView(et, lp(MATCH, WRAP))
        body.addView(
            a.tv(L.t("Shown on the block screen when an app is blocked. Empty means the default message.", "অ্যাপ ব্লক হলে এই মেসেজ ব্লক স্ক্রিনে দেখাবে। খালি রাখলে আগের মেসেজই দেখাবে।"), 13f, C.muted),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        a.dlg()
            .setTitle(L.t("Block screen message", "ব্লক স্ক্রিনের মেসেজ"))
            .setView(body)
            .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
                Extra.setBlockMessage(a, et.text.toString().trim())
                a.go(3)
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Pick the morning-reminder time. Choosing a time turns the reminder on. */
    private fun morningTimeDialog() {
        val cur = MorningReminder.minutes(a)
        TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
            MorningReminder.setMinutes(a, h * 60 + m)
            if (!MorningReminder.isOn(a)) MorningReminder.setOn(a, true)
            a.toast(L.t("Morning reminder set for ", "সকালের রিমাইন্ডার ঠিক হয়েছে ") + fmtMin(h * 60 + m))
            a.go(3)
        }, cur / 60, cur % 60, false).show()
    }

    /** Pick the lock sound, mix sounds together, or hear today's quote spoken. */
    private fun focusSoundDialog() {
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(4))

        // --- lock sound (single choice, plays during a lock) ---
        body.addView(a.section(L.t("Lock sound", "লকের সাউন্ড")))
        val choices = listOf(FocusSound.OFF, FocusSound.WHITE, FocusSound.BROWN, FocusSound.RAIN)
        val markers = HashMap<String, TextView>()
        fun refreshMarkers() {
            for ((ch, m) in markers) {
                val sel = ch == FocusSound.choice(a)
                m.text = if (sel) "●" else "○"
                m.setTextColor(if (sel) C.primary else C.muted)
            }
        }
        for (ch in choices) {
            val marker = a.tv("○", 16f, C.muted, 1)
            markers[ch] = marker
            body.addView(
                a.row(FocusSound.label(ch), null, marker) {
                    FocusSound.setChoice(a, ch)
                    if (ch == FocusSound.OFF) {
                        FocusSound.stop()
                    } else if (Store.isActive(a)) {
                        // explicit choice: start even on silent/vibrate
                        FocusSound.startExplicit(a)
                    }
                    refreshMarkers()
                }
            )
        }
        refreshMarkers()

        // --- mixer: play sounds together, each with its own volume ---
        body.addView(a.section(L.t("Sound mixer", "সাউন্ড মিক্সার")), lp(MATCH, WRAP, t = a.dp(18)))
        body.addView(
            a.tv(L.t("Play sounds together — each with its own volume.", "সাউন্ডগুলো একসাথে বাজান — প্রতিটার আলাদা ভলিউম।"), 12f, C.muted),
            lp(MATCH, WRAP, t = a.dp(4))
        )
        val mix = FocusSound.mixVolumes(a)
        for (ch in FocusSound.SOUNDS) {
            val vol = mix[ch] ?: 0.6f
            val row = LinearLayout(a)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, a.dp(10), 0, a.dp(10))
            row.addView(a.tv(FocusSound.label(ch), 15f, C.text, 1), lp(0, WRAP, weight = 1f))
            lateinit var sw: PremiumSwitch
            val seek = SeekBar(a)
            seek.max = 100
            seek.progress = (vol * 100).toInt()
            seek.visibility = if (mix.containsKey(ch)) View.VISIBLE else View.GONE
            sw = a.toggle(mix.containsKey(ch)) { v ->
                if (v) {
                    val started = FocusSound.startMixSound(a, ch, seek.progress / 100f)
                    if (!started) {
                        sw.setCheckedImmediate(false)
                        a.toast(L.t("Could not play — another app is using audio", "বাজানো যায়নি — অন্য অ্যাপ অডিও ব্যবহার করছে"))
                    } else {
                        seek.visibility = View.VISIBLE
                    }
                } else {
                    FocusSound.stopMixSound(a, ch)
                    seek.visibility = View.GONE
                }
            }
            row.addView(sw)
            body.addView(row)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) FocusSound.setMixVolume(a, ch, progress / 100f)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            body.addView(seek, lp(MATCH, WRAP))
        }

        // --- voice quote: today's quote, spoken aloud ---
        body.addView(a.section(L.t("Voice quote", "ভয়েস উক্তি")), lp(MATCH, WRAP, t = a.dp(18)))
        val quoteBtn = a.primaryBtn(L.t("Play today's quote", "আজকের উক্তি শুনুন"))
        quoteBtn.setOnClickListener { VoiceQuotes.speak(a) }
        body.addView(quoteBtn, lp(MATCH, a.dp(52), t = a.dp(10)))
        body.addView(
            a.tv(L.t("Spoken in Bengali when a Bengali voice is installed, otherwise in English.", "বাংলা ভয়েস থাকলে বাংলায়, না থাকলে ইংরেজিতে শোনাবে।"), 12f, C.muted),
            lp(MATCH, WRAP, t = a.dp(8))
        )

        // the body is tall: wrap it in a ScrollView so small screens can reach the bottom
        val scroll = ScrollView(a)
        scroll.addView(body)
        a.dlg()
            .setTitle(L.t("Focus sounds", "ফোকাস সাউন্ড"))
            .setView(scroll)
            .setPositiveButton(L.t("Done", "ঠিক আছে"), null)
            .setOnDismissListener { a.go(3) }
            .show()
    }

    /** Pick a gallery photo as the Home background, or remove it. */
    private fun wallpaperDialog() {
        val has = Wallpaper.hasWallpaper(a)
        val b = a.dlg()
            .setTitle(L.t("Home wallpaper", "হোম ওয়ালপেপার"))
            .setMessage(
                if (has) L.t("A photo is set as your Home background.", "হোমের ব্যাকগ্রাউন্ডে একটা ছবি দেওয়া আছে।")
                else L.t("Choose a photo from your gallery as the Home background.", "গ্যালারি থেকে একটা ছবি বেছে হোমের ব্যাকগ্রাউন্ড বানান।")
            )
            .setPositiveButton(L.t("Choose photo", "ছবি বাছুন")) { _, _ -> a.pickWallpaper() }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
        if (has) {
            b.setNeutralButton(L.t("Remove wallpaper", "ওয়ালপেপার সরান")) { _, _ ->
                Wallpaper.clear(a)
                a.toast(L.t("Wallpaper removed", "ওয়ালপেপার সরানো হয়েছে"))
                a.go(3)
            }
        }
        b.show()
    }

    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Settings", "সেটিংস"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))

        // language: card with two chips (বাংলা | English)
        val lang = a.card()
        lang.addView(a.section(L.t("Language", "ভাষা")))
        val lr = LinearLayout(a)
        val bnChip = a.chip("বাংলা", L.bn)
        val enChip = a.chip("English", !L.bn)
        fun pickLang(code: String) {
            if (Store.settings(a).getString("lang", "bn") == code) return
            a.animateChip(bnChip, code == "bn")
            a.animateChip(enChip, code == "en")
            Store.settings(a).edit().putString("lang", code).apply()
            lr.postDelayed({
                a.crossfade {
                    L.load(a)
                    Store.kickService(a)
                }
            }, 240)
        }
        bnChip.setOnClickListener { pickLang("bn") }
        enChip.setOnClickListener { pickLang("en") }
        lr.addView(bnChip, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        lr.addView(enChip, lp(0, WRAP, t = a.dp(10), weight = 1f))
        lang.addView(lr)
        col.addView(lang, lp(MATCH, WRAP, b = a.dp(12)))

        // theme: card with two chips (এমারেল্ড ডার্ক | অ্যামোলেড ব্ল্যাক)
        val theme = a.card()
        theme.addView(a.section(L.t("Theme", "থিম")))
        val tr = LinearLayout(a)
        val cur = Store.settings(a).getInt("theme", 0)
        val t0 = a.chip(L.t("Emerald Dark", "এমারেল্ড ডার্ক"), cur == 0)
        val t1 = a.chip(L.t("AMOLED Black", "অ্যামোলেড ব্ল্যাক"), cur == 1)
        fun pickTheme(i: Int) {
            if (Store.settings(a).getInt("theme", 0) == i) return
            a.animateChip(t0, i == 0)
            a.animateChip(t1, i == 1)
            Store.settings(a).edit().putInt("theme", i).apply()
            tr.postDelayed({ a.crossfade { C.load(a) } }, 240)
        }
        t0.setOnClickListener { pickTheme(0) }
        t1.setOnClickListener { pickTheme(1) }
        tr.addView(t0, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        tr.addView(t1, lp(0, WRAP, t = a.dp(10), weight = 1f))
        theme.addView(tr)
        // accent colour: 4 free colours + 2 unlockable by achievement
        theme.addView(a.tv(L.t("Accent colour", "অ্যাকসেন্ট রঙ"), 13f, C.muted, 1), lp(MATCH, WRAP, t = a.dp(18)))
        val chosenAccent = Accents.chosen(a).id
        fun accentCell(ac: AccentTheme): View {
            val unlocked = Accents.isUnlocked(a, ac)
            val selected = ac.id == chosenAccent
            val cell = LinearLayout(a)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            val ring = LinearLayout(a)
            ring.gravity = Gravity.CENTER
            ring.background = if (selected) rounded(Color.TRANSPARENT, a.dp(28).toFloat(), C.mint, a.dp(2))
            else rounded(Color.TRANSPARENT, a.dp(28).toFloat())
            ring.setPadding(a.dp(5), a.dp(5), a.dp(5), a.dp(5))
            val dotWrap = FrameLayout(a)
            val dot = View(a)
            dot.background = rounded(ac.primary, a.dp(22).toFloat())
            dotWrap.addView(dot, FrameLayout.LayoutParams(a.dp(44), a.dp(44)))
            if (!unlocked) {
                val lock = a.tv("🔒", 16f, C.text, 1)
                lock.gravity = Gravity.CENTER
                dotWrap.addView(lock, FrameLayout.LayoutParams(a.dp(44), a.dp(44)))
            }
            ring.addView(dotWrap, lp(a.dp(44), a.dp(44)))
            cell.addView(ring, lp(WRAP, WRAP, t = a.dp(10)))
            cell.addView(a.tv(L.t(ac.nameEn, ac.nameBn), 13f, if (unlocked) C.text else C.muted, 1), lp(WRAP, WRAP, t = a.dp(6)))
            val req = Accents.requirement(ac)
            if (req != null && !unlocked) {
                cell.addView(a.tv(req, 11f, C.muted), lp(WRAP, WRAP))
            }
            if (!unlocked) cell.alpha = 0.75f
            cell.pressFeedback()
            cell.setOnClickListener {
                if (!Accents.isUnlocked(a, ac)) {
                    val r = Accents.requirement(ac) ?: ""
                    a.toast(L.t("Locked — unlock with a $r", "লক করা — $r হলে আনলক হবে"))
                    return@setOnClickListener
                }
                if (Accents.chosen(a).id == ac.id) return@setOnClickListener
                Accents.setChosen(a, ac.id)
                a.crossfade { C.load(a) }
            }
            return cell
        }
        for (rowIdx in 0..1) {
            val ar = LinearLayout(a)
            ar.orientation = LinearLayout.HORIZONTAL
            for (i in 0..2) {
                ar.addView(accentCell(Accents.ALL[rowIdx * 3 + i]), lp(0, WRAP, weight = 1f))
            }
            theme.addView(ar)
        }
        col.addView(theme, lp(MATCH, WRAP, b = a.dp(12)))

        // strict protection
        val strictCard = a.card()
        strictCard.addView(a.section(L.t("Strict protection", "কঠোর সুরক্ষা")))
        val locked = Store.isActive(a)
        val prefs = Store.settings(a)
        strictCard.addView(
            glowOption(
                L.t("Strict mode", "স্ট্রিক্ট মোড"),
                L.t("No early stop. Locked until the timer ends.", "আগে বন্ধ করা যাবে না। সময় শেষ না হওয়া পর্যন্ত লক।"),
                prefs.getBoolean("strict", false),
                locked
            ) { v -> prefs.edit().putBoolean("strict", v).apply() },
            lp(MATCH, WRAP, t = a.dp(8))
        )
        strictCard.addView(
            glowOption(
                L.t("Protect app settings", "অ্যাপ সেটিংস সুরক্ষা"),
                L.t("Block App info and Accessibility pages while the timer runs.", "টাইমার চলাকালীন App info ও Accessibility পাতা খোলা যাবে না।"),
                prefs.getBoolean("guard", true),
                locked
            ) { v -> prefs.edit().putBoolean("guard", v).apply() },
            lp(MATCH, WRAP, t = a.dp(2))
        )
        val note = if (locked) L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
        else L.t("Changes apply from the next session.", "পরিবর্তন পরের সেশন থেকে কার্যকর হবে।")
        strictCard.addView(a.tv(note, 12f, if (locked) C.warn else C.muted), lp(MATCH, WRAP, t = a.dp(8)))
        col.addView(strictCard, lp(MATCH, WRAP, b = a.dp(12)))

        // location lock first, then focus mode (defined in Features.kt)
        col.addView(a.locationCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.historyCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.focusCard(), lp(MATCH, WRAP, b = a.dp(12)))
        // the site block section hides behind the art pad trick lock: the
        // card only shows after the secret art is drawn on the pad
        if (Extra.artRevealed(a)) {
            col.addView(a.siteBlockCard(), lp(MATCH, WRAP, b = a.dp(12)))
            val hide = a.outlineBtn(L.t("Hide this section", "এই সেকশন লুকান"), C.muted)
            hide.setOnClickListener { Extra.setArtRevealed(a, false); a.go(3) }
            col.addView(hide, lp(MATCH, a.dp(48), b = a.dp(12)))
        } else {
            col.addView(a.artPadCard(), lp(MATCH, WRAP, b = a.dp(12)))
        }
        col.addView(a.uninstallBlockCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.uninstallProtectCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockProfilesCard(), lp(MATCH, WRAP, b = a.dp(12)))

        // general
        val gen = a.card()
        gen.addView(a.section(L.t("General", "সাধারণ")))
        gen.addView(
            a.row(
                L.t("Sound and vibration", "সাউন্ড ও ভাইব্রেশন"),
                L.t("When a session ends", "সেশন শেষ হলে"),
                a.toggle(Store.soundVib(a)) { v -> Store.settings(a).edit().putBoolean("sound", v).apply() }
            )
        )
        // silence notifications during a lock (DND) — if the permission was
        // revoked, the toggle turns itself off with a toast, never a crash
        if (Dnd.isOn(a) && !Dnd.isGranted(a)) {
            Store.settings(a).edit().putBoolean("dnd_on", false).apply()
            a.toast(L.t("Do Not Disturb permission was revoked — silencing turned off", "Do Not Disturb পারমিশন তুলে নেওয়া হয়েছে — নোটিফিকেশন বন্ধের অপশন বন্ধ করা হলো"))
        }
        lateinit var dndSw: PremiumSwitch
        dndSw = a.toggle(Dnd.isOn(a)) { v ->
            if (v && !Dnd.isGranted(a)) {
                dndSw.setCheckedImmediate(false)
                a.toast(L.t("Allow Do Not Disturb access first, then turn this on again", "আগে Do Not Disturb অনুমতি দিন, তারপর আবার চালু করুন"))
                try {
                    a.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Log.e(TAG, "DND settings failed", e)
                }
                return@toggle
            }
            Store.settings(a).edit().putBoolean("dnd_on", v).apply()
        }
        gen.addView(
            a.row(
                L.t("Silence notifications during lock", "লক চলাকালীন নোটিফিকেশন বন্ধ"),
                L.t("Calls are silenced too — alarms still ring.", "কলও চুপ থাকবে — অ্যালার্ম বাজবে।"),
                dndSw
            )
        )
        // custom message on the block screen
        val msg = Extra.blockMessage(a)
        gen.addView(
            a.row(
                L.t("Block screen message", "ব্লক স্ক্রিনের মেসেজ"),
                if (msg.isBlank()) L.t("Tap to write your own motivational message", "নিজের মোটিভেশনাল মেসেজ লিখতে চাপুন") else msg,
                a.tv("›", 22f, C.muted)
            ) { blockMessageDialog() }
        )
        // focus sound during a lock (generated in code — no downloads)
        val mixN = FocusSound.mixVolumes(a).size
        val soundSub = if (mixN > 0) {
            L.t("Mixer: ", "মিক্সার: ") + L.num(mixN.toString()) + L.t(" sounds playing", "টা সাউন্ড বাজছে")
        } else {
            FocusSound.label(FocusSound.choice(a)) + " · " + L.t("plays during a lock", "লক চলাকালীন বাজবে")
        }
        gen.addView(
            a.row(
                L.t("Focus sounds", "ফোকাস সাউন্ড"),
                soundSub,
                a.tv("›", 22f, C.muted)
            ) { focusSoundDialog() }
        )
        // home wallpaper: a gallery photo behind the Home screen
        gen.addView(
            a.row(
                L.t("Home wallpaper", "হোম ওয়ালপেপার"),
                if (Wallpaper.hasWallpaper(a)) L.t("Photo set — tap to change or remove", "ছবি দেওয়া আছে — বদলাতে বা সরাতে চাপুন")
                else L.t("Off — tap to choose a photo", "বন্ধ — ছবি বাছতে চাপুন"),
                a.tv("›", 22f, C.muted)
            ) { wallpaperDialog() }
        )
        // morning reminder: a daily nudge to start a focus session
        gen.addView(
            a.row(
                L.t("Morning reminder", "সকালের রিমাইন্ডার"),
                if (MorningReminder.isOn(a)) L.t("Every day at ", "প্রতিদিন ") + fmtMin(MorningReminder.minutes(a)) + L.t(" — tap to change the time", " — সময় বদলাতে চাপুন")
                else L.t("Off — tap to pick a time", "বন্ধ — সময় বাছতে চাপুন"),
                a.toggle(MorningReminder.isOn(a)) { v ->
                    MorningReminder.setOn(a, v)
                    a.go(3)
                }
            ) { morningTimeDialog() }
        )
        gen.addView(
            a.row(L.t("Notification settings", "নোটিফিকেশন সেটিংস"), null, a.tv("›", 22f, C.muted)) {
                launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
            }
        )
        gen.addView(a.row(L.t("Weekly schedule", "সাপ্তাহিক সময়সূচি"), null, a.tv("›", 22f, C.muted)) { a.go(1) })
        gen.addView(a.row(L.t("Statistics", "পরিসংখ্যান"), null, a.tv("›", 22f, C.muted)) { a.go(2) })
        col.addView(gen, lp(MATCH, WRAP, b = a.dp(12)))

        // permissions
        val perm = a.card()
        perm.addView(a.section(L.t("Permission status", "পারমিশনের অবস্থা")))
        fun permRow(title: String, ok: Boolean, fix: () -> Unit) {
            val mark = a.tv(if (ok) "✔" else "✘", 18f, if (ok) C.primary else C.danger, 1)
            val sub = if (ok) L.t("Granted", "অনুমোদিত") else L.t("Tap to fix", "ঠিক করতে চাপুন")
            perm.addView(a.row(title, sub, mark, if (ok) null else fix))
        }
        permRow("Accessibility", Perm.accessibilityOn(a)) {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permRow(L.t("Notifications", "নোটিফিকেশন"), Perm.notificationsOn(a)) {
            if (Build.VERSION.SDK_INT >= 33) {
                a.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
            }
        }
        permRow(L.t("Battery optimization off", "ব্যাটারি অপটিমাইজেশন বন্ধ"), Perm.batteryOk(a)) {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        permRow(L.t("Exact alarms (schedules)", "নির্দিষ্ট সময়ের অ্যালার্ম"), Perm.exactAlarmOk(a)) {
            if (Build.VERSION.SDK_INT >= 31) {
                launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.packageName)))
            }
        }
        col.addView(perm, lp(MATCH, WRAP, b = a.dp(12)))

        // about
        val about = a.card()
        about.addView(a.section(L.t("About PowerLock 2", "PowerLock 2 সম্পর্কে")))
        about.addView(a.tv("PowerLock 2", 18f, C.text, 1), lp(MATCH, WRAP, t = a.dp(10)))
        about.addView(a.tv(L.t("Version ", "সংস্করণ ") + L.num(APP_VERSION), 13f, C.muted))
        about.addView(a.tv(L.t("Build ", "বিল্ড ") + L.num(Extra.BUILD_ID), 13f, C.muted))
        about.addView(a.tv(a.packageName, 12f, C.muted))
        about.addView(
            a.tv(
                L.t(
                    "Note: a normal Android app cannot block the hardware power button, force restart (Power + Volume Down), recovery mode or every system power menu. PowerLock only closes the supported power menu while protection is active.",
                    "দ্রষ্টব্য: সাধারণ অ্যাপ হার্ডওয়্যার পাওয়ার বাটন, ফোর্স রিস্টার্ট (Power + Volume Down), রিকভারি মোড বা সব সিস্টেম পাওয়ার মেনু আটকাতে পারে না। প্রটেকশন চলাকালীন PowerLock শুধু সমর্থিত পাওয়ার মেনুটি বন্ধ করে দেয়।"
                ),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        col.addView(about)
        return sv
    }
}

// =====================================================================
// PROTECTION (app block: full block + reels block + search block)
// =====================================================================
class ProtectionScreen(private val a: MainActivity) {
    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Protection", "সুরক্ষা"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))
        col.addView(a.protectionIntroCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("full"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("full"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("reels"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("reels"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("msg"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("msg"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.dailyLimitsCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appGroupsCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.diagnosticCard(), lp(MATCH, WRAP, b = a.dp(12)))
        return sv
    }
}
