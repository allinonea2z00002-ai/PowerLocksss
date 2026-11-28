package com.powerlock.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Build 137 (user 2026-11-28 17:35, voice call): the secret-shape window.
 *
 * The user asked for a hidden window that opens when they draw a shape
 * (e.g. 5 or 6) on the screen, with the PowerLock app inside it — and no
 * Uninstall option in that window. During a lock, tapping the KeepAlive
 * notification opens this activity (the pad). Drawing the secret shape
 * (set in Settings > "গোপন জানালা", draw twice — the same $1 matcher as
 * the art pad trick lock) swaps the pad for a window showing the
 * PowerLock icon; tapping the icon opens MainActivity. The window is a
 * plain Activity view, not the system launcher, so long-pressing the
 * icon shows no popup and no Uninstall option. Until a shape is set, any
 * drawing opens the window. The dialer code (*#*#1#*#*) still opens
 * MainActivity directly as a backup. When no lock runs, the notification
 * opens MainActivity directly, so there is no extra step.
 */
class SecretWindowActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)
        C.load(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.bg)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        setContentView(root)
        showPad()
    }

    /** The drawing pad: draw the secret shape to open the window. */
    private fun showPad() {
        root.removeAllViews()
        val title = TextView(this).apply {
            text = L.t("Secret window", "গোপন জানালা")
            textSize = 20f
            setTextColor(C.text)
        }
        root.addView(title)
        val status = TextView(this).apply {
            text = if (Extra.winSecret(this@SecretWindowActivity) == null)
                L.t("No shape set yet — any drawing opens the window.", "আকার সেট করা নেই — যেকোনো আঁকা জানালা খুলবে।")
            else
                L.t("Draw your secret shape.", "গোপন আকার আঁকুন।")
            textSize = 13f
            setTextColor(C.muted)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(status)
        val pad = ArtPadView(this).apply {
            background = rounded(C.card2, dp(12).toFloat())
        }
        val padParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(14) }
        root.addView(pad, padParams)
        pad.onStrokeDone = { raw ->
            if (raw.size < ART_MIN_POINTS) {
                // too short to be a drawing (a tap) — ignore it
                pad.clear()
            } else {
                val drawn = artNormalize(artResample(raw))
                val saved = Extra.winSecret(this)
                if (saved == null) {
                    // no secret set yet: any drawing opens the window
                    showWindow()
                } else {
                    val secretPts = artDecode(saved)
                    if (secretPts.size == ART_POINTS && artMatchScore(drawn, secretPts) < ART_MATCH_DIST) {
                        showWindow()
                    } else {
                        // wrong shape: nothing happens — it just looks like a doodle
                        pad.clear()
                    }
                }
            }
        }
        val close = TextView(this).apply {
            text = L.t("Close", "বন্ধ করুন")
            textSize = 15f
            setTextColor(C.muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { finish() }
        }
        root.addView(close)
    }

    /** The window: the PowerLock icon in our own view — no launcher popup, no Uninstall option. */
    private fun showWindow() {
        root.removeAllViews()
        val title = TextView(this).apply {
            text = L.t("Secret window", "গোপন জানালা")
            textSize = 20f
            setTextColor(C.text)
        }
        root.addView(title)
        val hint = TextView(this).apply {
            text = L.t("Tap the app to open it.", "খুলতে অ্যাপটায় ট্যাপ করুন।")
            textSize = 13f
            setTextColor(C.muted)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(hint)
        val iconBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(24))
            // a plain view: long-press does nothing, there is no popup and no Uninstall option
            isClickable = true
            isLongClickable = false
            setOnClickListener { openApp() }
        }
        val icon = ImageView(this).apply {
            try {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
            } catch (_: Exception) {
            }
            val s = dp(120)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        iconBox.addView(icon)
        val label = TextView(this).apply {
            text = L.t("PowerLock 2", "PowerLock 2")
            textSize = 17f
            setTextColor(C.text)
            setPadding(0, dp(12), 0, 0)
        }
        iconBox.addView(label)
        val boxParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(14) }
        root.addView(iconBox, boxParams)
        val close = TextView(this).apply {
            text = L.t("Close", "বন্ধ করুন")
            textSize = 15f
            setTextColor(C.muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { finish() }
        }
        root.addView(close)
    }

    private fun openApp() {
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, L.t("Could not open the app", "অ্যাপ খোলা যায়নি"), Toast.LENGTH_SHORT).show()
        }
    }
}
