package com.powerlock.app

import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Theme palette: Emerald Dark (default) and AMOLED Black. */
object C {
    var bg = Color.parseColor("#0A1510")
    var card = Color.parseColor("#10261B")
    var card2 = Color.parseColor("#183626")
    var chipFill = Color.parseColor("#12271D")
    var border = Color.parseColor("#1F4A38")
    var selFill = Color.parseColor("#17694B")
    // accent colour: chosen in Settings (see Accents) — every card, chip,
    // button and ring reads these live, so a change re-themes the whole app
    var primary = Color.parseColor("#1FCB85")
    var mint = Color.parseColor("#A8F0D1")
    val text = Color.parseColor("#F2FBF6")
    val textSoft = Color.parseColor("#D5E8DE")
    val muted = Color.parseColor("#8FA89B")
    val danger = Color.parseColor("#FF6B6B")
    val warn = Color.parseColor("#FFB84D")
    val onPrimary = Color.parseColor("#062B1D")

    fun load(ctx: Context) {
        val amoled = Store.settings(ctx).getInt("theme", 0) == 1
        if (amoled) {
            bg = Color.BLACK
            card = Color.parseColor("#09100C")
            card2 = Color.parseColor("#111C16")
            chipFill = Color.parseColor("#0A120E")
            border = Color.parseColor("#143025")
            selFill = Color.parseColor("#0F4A35")
        } else {
            bg = Color.parseColor("#0A1510")
            card = Color.parseColor("#10261B")
            card2 = Color.parseColor("#183626")
            chipFill = Color.parseColor("#12271D")
            border = Color.parseColor("#1F4A38")
            selFill = Color.parseColor("#17694B")
        }
        // accent colour (default Emerald keeps the exact current look)
        val accent = Accents.chosen(ctx)
        primary = accent.primary
        mint = accent.mint
        selFill = if (amoled) darken(accent.selFill, 0.68f) else accent.selFill
    }

    private fun darken(color: Int, f: Float): Int {
        val r = (Color.red(color) * f).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}

/**
 * One accent colour: its main colour, its light tint (used for highlights),
 * and its dark fill (used for selected chips and switches).
 * [unlock] is null for always-available colours, or the achievement that
 * unlocks it: "streak7" (7-day streak) or "locks25" (25 completed locks).
 */
data class AccentTheme(
    val id: String,
    val nameEn: String,
    val nameBn: String,
    val primary: Int,
    val mint: Int,
    val selFill: Int,
    val unlock: String?
)

object Accents {
    val ALL = listOf(
        AccentTheme("emerald", "Emerald", "এমারেল্ড", Color.parseColor("#1FCB85"), Color.parseColor("#A8F0D1"), Color.parseColor("#17694B"), null),
        AccentTheme("ocean", "Ocean", "ওশান", Color.parseColor("#38BDF8"), Color.parseColor("#BAE6FD"), Color.parseColor("#0E7490"), null),
        AccentTheme("violet", "Violet", "ভায়োলেট", Color.parseColor("#A78BFA"), Color.parseColor("#DDD6FE"), Color.parseColor("#6D28D9"), null),
        AccentTheme("amber", "Amber", "অ্যাম্বার", Color.parseColor("#FBBF24"), Color.parseColor("#FDE68A"), Color.parseColor("#B45309"), null),
        AccentTheme("crimson", "Crimson", "ক্রিমসন", Color.parseColor("#FB7185"), Color.parseColor("#FECDD3"), Color.parseColor("#9F1239"), "streak7"),
        AccentTheme("lime", "Lime", "লাইম", Color.parseColor("#A3E635"), Color.parseColor("#D9F99D"), Color.parseColor("#4D7C0F"), "locks25")
    )

    fun byId(id: String): AccentTheme = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** The unlock requirement text, or null when the colour is free. */
    fun requirement(a: AccentTheme): String? {
        return when (a.unlock) {
            "streak7" -> L.t("7-day streak", "৭ দিনের স্ট্রিক")
            "locks25" -> L.t("25 completed locks", "২৫টা লক সম্পন্ন")
            else -> null
        }
    }

    /**
     * True when the colour can be picked. Unlocks are sticky: once the
     * achievement is reached it stays unlocked even if the streak later drops.
     */
    fun isUnlocked(ctx: Context, a: AccentTheme): Boolean {
        if (a.unlock == null) return true
        val st = Store.settings(ctx)
        if (st.getBoolean("accent_unlocked_" + a.id, false)) return true
        val fs = Store.focusStats(ctx)
        val met = when (a.unlock) {
            "streak7" -> fs.streak >= 7
            "locks25" -> fs.completed >= 25
            else -> false
        }
        if (met) st.edit().putBoolean("accent_unlocked_" + a.id, true).apply()
        return met
    }

    fun chosen(ctx: Context): AccentTheme =
        byId(Store.settings(ctx).getString("theme_color", "emerald") ?: "emerald")

    fun setChosen(ctx: Context, id: String) {
        Store.settings(ctx).edit().putString("theme_color", id).apply()
    }
}

fun tint(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(l, t, r, b)
    return p
}

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = radius
    if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor)
    return d
}

fun withRipple(bg: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(tint(C.primary, 46)), bg, null)

/** style: 0 regular, 1 medium, 2 light */
fun Context.tv(t: String, size: Float = 14f, color: Int = C.text, style: Int = 0): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    v.typeface = when (style) {
        1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        2 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
    return v
}

fun Context.card(pad: Int = 16): LinearLayout {
    val v = LinearLayout(this)
    v.orientation = LinearLayout.VERTICAL
    v.background = rounded(C.card, dp(20).toFloat())
    v.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    return v
}

fun Context.section(t: String): TextView {
    val v = tv(t.uppercase(), 12f, C.primary, 1)
    v.letterSpacing = 0.08f
    return v
}

fun Context.primaryBtn(label: String): TextView {
    val v = tv(label, 16f, C.onPrimary, 1)
    v.gravity = Gravity.CENTER
    val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.primary, C.mint))
    g.cornerRadius = dp(16).toFloat()
    v.background = withRipple(g)
    v.pressFeedback()
    return v
}

fun Context.outlineBtn(label: String, color: Int): TextView {
    val v = tv(label, 15f, color, 1)
    v.gravity = Gravity.CENTER
    v.background = withRipple(rounded(Color.TRANSPARENT, dp(16).toFloat(), color, dp(1)))
    v.pressFeedback()
    return v
}

fun Context.styleChip(v: TextView, selected: Boolean) {
    v.tag = selected
    v.setTextColor(if (selected) C.text else C.textSoft)
    v.background = withRipple(
        rounded(
            if (selected) C.selFill else C.chipFill,
            dp(18).toFloat(),
            if (selected) C.primary else C.border,
            if (selected) dp(2) else dp(1)
        )
    )
}

fun Context.chip(label: String, selected: Boolean): TextView {
    val v = tv(label, 15f, C.textSoft, 1)
    v.gravity = Gravity.CENTER
    v.setPadding(dp(6), dp(14), dp(6), dp(14))
    styleChip(v, selected)
    v.pressFeedback()
    return v
}

fun Context.heading(t: String): TextView = tv(t, 22f, C.text, 1)

fun Context.bigButton(kind: Ic, label: String, fill: Int, fg: Int, stroke: Int = 0): LinearLayout {
    val b = LinearLayout(this)
    b.orientation = LinearLayout.HORIZONTAL
    b.gravity = Gravity.CENTER
    b.background = withRipple(rounded(fill, dp(32).toFloat(), stroke, if (stroke != 0) dp(1) else 0))
    b.addView(IconView(this, kind, fg), lp(dp(24), dp(24), r = dp(10)))
    val t = tv(label.uppercase(), 16f, fg, 1)
    t.letterSpacing = 0.04f
    b.addView(t)
    b.isClickable = true
    b.pressFeedback()
    return b
}

fun Context.toggle(checked: Boolean, onChange: (Boolean) -> Unit): PremiumSwitch {
    val s = PremiumSwitch(this)
    s.setCheckedImmediate(checked)
    s.onChange = onChange
    return s
}

fun Context.row(title: String, sub: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
    val r = LinearLayout(this)
    r.orientation = LinearLayout.HORIZONTAL
    r.gravity = Gravity.CENTER_VERTICAL
    r.setPadding(0, dp(10), 0, dp(10))
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.addView(tv(title, 15f, C.text, 1))
    if (sub != null) col.addView(tv(sub, 12f, C.muted))
    r.addView(col, lp(0, WRAP, weight = 1f))
    if (trailing != null) r.addView(trailing)
    if (onClick != null) {
        r.setOnClickListener { onClick() }
        r.background = withRipple(ColorDrawable(Color.TRANSPARENT))
    }
    return r
}

fun Context.edit(hint: String, number: Boolean): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setHintTextColor(C.muted)
    e.setTextColor(C.text)
    e.textSize = 16f
    e.gravity = Gravity.CENTER
    if (number) e.inputType = InputType.TYPE_CLASS_NUMBER
    e.background = rounded(C.card2, dp(12).toFloat())
    e.setPadding(dp(14), dp(12), dp(14), dp(12))
    return e
}

fun Context.dlg(): AlertDialog.Builder =
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

fun Context.page(): Pair<ScrollView, LinearLayout> {
    val sv = ScrollView(this)
    sv.isVerticalScrollBarEnabled = false
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(20), dp(16), dp(20), dp(24))
    sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
    return Pair(sv, col)
}

enum class Ic { HOME, CAL, BARS, GEAR, SHIELD, LOCK, CLOCK, CHECK, PIN, LIST, BOOK, LEAF, AWARD, TROPHY }

/** Line icons drawn with Canvas (no image files needed). */
object Icons {
    fun draw(cv: Canvas, kind: Ic, cx: Float, cy: Float, s: Float, p: Paint) {
        val l = cx - s / 2f
        val t = cy - s / 2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.085f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val path = Path()
        fun x(u: Float): Float = l + s * u
        fun y(u: Float): Float = t + s * u
        when (kind) {
            Ic.HOME -> {
                path.moveTo(x(0.14f), y(0.50f))
                path.lineTo(x(0.50f), y(0.18f))
                path.lineTo(x(0.86f), y(0.50f))
                path.moveTo(x(0.24f), y(0.44f))
                path.lineTo(x(0.24f), y(0.82f))
                path.lineTo(x(0.76f), y(0.82f))
                path.lineTo(x(0.76f), y(0.44f))
                path.moveTo(x(0.42f), y(0.82f))
                path.lineTo(x(0.42f), y(0.60f))
                path.lineTo(x(0.58f), y(0.60f))
                path.lineTo(x(0.58f), y(0.82f))
                cv.drawPath(path, p)
            }
            Ic.CAL -> {
                cv.drawRoundRect(RectF(x(0.16f), y(0.22f), x(0.84f), y(0.84f)), s * 0.1f, s * 0.1f, p)
                cv.drawLine(x(0.16f), y(0.42f), x(0.84f), y(0.42f), p)
                cv.drawLine(x(0.34f), y(0.12f), x(0.34f), y(0.30f), p)
                cv.drawLine(x(0.66f), y(0.12f), x(0.66f), y(0.30f), p)
                cv.drawLine(x(0.30f), y(0.60f), x(0.42f), y(0.60f), p)
                cv.drawLine(x(0.58f), y(0.60f), x(0.70f), y(0.60f), p)
                cv.drawLine(x(0.30f), y(0.74f), x(0.42f), y(0.74f), p)
            }
            Ic.BARS -> {
                p.strokeWidth = s * 0.15f
                cv.drawLine(x(0.25f), y(0.80f), x(0.25f), y(0.55f), p)
                cv.drawLine(x(0.50f), y(0.80f), x(0.50f), y(0.25f), p)
                cv.drawLine(x(0.75f), y(0.80f), x(0.75f), y(0.42f), p)
            }
            Ic.GEAR -> {
                cv.drawCircle(cx, cy, s * 0.14f, p)
                cv.drawCircle(cx, cy, s * 0.30f, p)
                p.strokeWidth = s * 0.12f
                for (i in 0 until 8) {
                    val a = Math.toRadians(45.0 * i)
                    val ca = Math.cos(a).toFloat()
                    val sa = Math.sin(a).toFloat()
                    cv.drawLine(cx + ca * s * 0.32f, cy + sa * s * 0.32f, cx + ca * s * 0.42f, cy + sa * s * 0.42f, p)
                }
            }
            Ic.SHIELD -> {
                path.moveTo(x(0.50f), y(0.10f))
                path.lineTo(x(0.82f), y(0.22f))
                path.lineTo(x(0.82f), y(0.48f))
                path.cubicTo(x(0.82f), y(0.70f), x(0.66f), y(0.82f), x(0.50f), y(0.90f))
                path.cubicTo(x(0.34f), y(0.82f), x(0.18f), y(0.70f), x(0.18f), y(0.48f))
                path.lineTo(x(0.18f), y(0.22f))
                path.close()
                path.moveTo(x(0.36f), y(0.50f))
                path.lineTo(x(0.46f), y(0.60f))
                path.lineTo(x(0.66f), y(0.38f))
                cv.drawPath(path, p)
            }
            Ic.LOCK -> {
                cv.drawRoundRect(RectF(x(0.22f), y(0.46f), x(0.78f), y(0.86f)), s * 0.08f, s * 0.08f, p)
                path.moveTo(x(0.34f), y(0.46f))
                path.lineTo(x(0.34f), y(0.34f))
                path.arcTo(RectF(x(0.34f), y(0.14f), x(0.66f), y(0.54f)), 180f, 180f)
                path.lineTo(x(0.66f), y(0.46f))
                cv.drawPath(path, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(x(0.50f), y(0.64f), s * 0.05f, p)
                p.style = Paint.Style.STROKE
            }
            Ic.CLOCK -> {
                cv.drawCircle(cx, cy, s * 0.36f, p)
                cv.drawLine(cx, cy, cx, y(0.28f), p)
                cv.drawLine(cx, cy, x(0.66f), y(0.58f), p)
            }
            Ic.CHECK -> {
                cv.drawCircle(cx, cy, s * 0.36f, p)
                path.moveTo(x(0.34f), y(0.52f))
                path.lineTo(x(0.46f), y(0.64f))
                path.lineTo(x(0.68f), y(0.38f))
                cv.drawPath(path, p)
            }
            Ic.PIN -> {
                // map pin: teardrop outline + centre dot
                path.moveTo(x(0.50f), y(0.90f))
                path.cubicTo(x(0.36f), y(0.74f), x(0.20f), y(0.62f), x(0.20f), y(0.40f))
                path.cubicTo(x(0.20f), y(0.22f), x(0.34f), y(0.10f), x(0.50f), y(0.10f))
                path.cubicTo(x(0.66f), y(0.10f), x(0.80f), y(0.22f), x(0.80f), y(0.40f))
                path.cubicTo(x(0.80f), y(0.62f), x(0.64f), y(0.74f), x(0.50f), y(0.90f))
                path.close()
                cv.drawPath(path, p)
                cv.drawCircle(cx, y(0.40f), s * 0.09f, p)
            }
            Ic.LIST -> {
                // list: three lines with dots
                p.strokeWidth = s * 0.12f
                cv.drawLine(x(0.30f), y(0.28f), x(0.82f), y(0.28f), p)
                cv.drawLine(x(0.30f), y(0.50f), x(0.82f), y(0.50f), p)
                cv.drawLine(x(0.30f), y(0.72f), x(0.82f), y(0.72f), p)
                p.style = Paint.Style.FILL
                cv.drawCircle(x(0.18f), y(0.28f), s * 0.05f, p)
                cv.drawCircle(x(0.18f), y(0.50f), s * 0.05f, p)
                cv.drawCircle(x(0.18f), y(0.72f), s * 0.05f, p)
                p.style = Paint.Style.STROKE
            }
            Ic.BOOK -> {
                // closed book with text lines
                cv.drawRoundRect(RectF(x(0.16f), y(0.20f), x(0.84f), y(0.82f)), s * 0.08f, s * 0.08f, p)
                cv.drawLine(x(0.50f), y(0.20f), x(0.50f), y(0.82f), p)
                cv.drawLine(x(0.58f), y(0.34f), x(0.76f), y(0.34f), p)
                cv.drawLine(x(0.58f), y(0.50f), x(0.76f), y(0.50f), p)
                cv.drawLine(x(0.24f), y(0.34f), x(0.42f), y(0.34f), p)
                cv.drawLine(x(0.24f), y(0.50f), x(0.42f), y(0.50f), p)
            }
            Ic.LEAF -> {
                // leaf outline + centre vein
                path.moveTo(x(0.50f), y(0.88f))
                path.cubicTo(x(0.20f), y(0.70f), x(0.16f), y(0.38f), x(0.50f), y(0.12f))
                path.cubicTo(x(0.84f), y(0.38f), x(0.80f), y(0.70f), x(0.50f), y(0.88f))
                path.close()
                path.moveTo(x(0.50f), y(0.82f))
                path.lineTo(x(0.50f), y(0.28f))
                cv.drawPath(path, p)
            }
            Ic.AWARD -> {
                // medal: circle + two ribbons below + centre dot
                cv.drawCircle(cx, y(0.38f), s * 0.24f, p)
                path.moveTo(x(0.38f), y(0.58f))
                path.lineTo(x(0.30f), y(0.86f))
                path.moveTo(x(0.62f), y(0.58f))
                path.lineTo(x(0.70f), y(0.86f))
                cv.drawPath(path, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(cx, y(0.38f), s * 0.06f, p)
                p.style = Paint.Style.STROKE
            }
            Ic.TROPHY -> {
                // cup: bowl + stem + base
                path.moveTo(x(0.28f), y(0.14f))
                path.lineTo(x(0.72f), y(0.14f))
                path.lineTo(x(0.68f), y(0.40f))
                path.cubicTo(x(0.66f), y(0.52f), x(0.58f), y(0.56f), x(0.50f), y(0.56f))
                path.cubicTo(x(0.42f), y(0.56f), x(0.34f), y(0.52f), x(0.32f), y(0.40f))
                path.lineTo(x(0.28f), y(0.14f))
                path.close()
                path.moveTo(x(0.50f), y(0.56f))
                path.lineTo(x(0.50f), y(0.74f))
                path.moveTo(x(0.34f), y(0.86f))
                path.lineTo(x(0.66f), y(0.86f))
                cv.drawPath(path, p)
            }
        }
    }
}

class IconView(ctx: Context, private val kind: Ic, color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var color: Int = color
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val s = Math.min(width, height).toFloat() * 0.86f
        Icons.draw(canvas, kind, width / 2f, height / 2f, s, paint)
    }
}

/** Large circular status ring: outline + icon + "OFF" when idle, countdown arc when active. */
class RingView(ctx: Context) : View(ctx) {
    var main: String = "OFF"
    var sub: String = ""
    var active = false
    var mainColor = C.primary
    var icon: Ic = Ic.SHIELD
    private var shown = 0f
    private var target = -1f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tMain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tSub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var breath = 0f
    private var breathAnim: ValueAnimator? = null

    init {
        track.style = Paint.Style.STROKE
        arc.style = Paint.Style.STROKE
        arc.strokeCap = Paint.Cap.ROUND
        glow.style = Paint.Style.STROKE
        glow.strokeCap = Paint.Cap.ROUND
        tMain.textAlign = Paint.Align.CENTER
        tSub.textAlign = Paint.Align.CENTER
        tSub.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun startBreath() {
        if (breathAnim != null) return
        val an = ValueAnimator.ofFloat(0f, 1f)
        // 2 s out + 2 s back = one slow ~4 s breath; drives the halo glow too
        an.duration = 2000
        an.repeatCount = ValueAnimator.INFINITE
        an.repeatMode = ValueAnimator.REVERSE
        an.addUpdateListener {
            breath = it.animatedValue as Float
            invalidate()
        }
        an.start()
        breathAnim = an
    }

    private fun stopBreath() {
        breathAnim?.cancel()
        breathAnim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) startBreath()
    }

    override fun onDetachedFromWindow() {
        stopBreath()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startBreath() else stopBreath()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val shader = SweepGradient(w / 2f, h / 2f, intArrayOf(C.primary, C.mint, C.primary), null)
        val m = Matrix()
        m.setRotate(-90f, w / 2f, h / 2f)
        shader.setLocalMatrix(m)
        arc.shader = shader
        glow.shader = shader
        // relaxing glow: soft radial halo behind the ring, accent colour fading out
        halo.shader = RadialGradient(
            w / 2f, h / 2f, w / 2f,
            intArrayOf(C.primary, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    fun animateTo(p: Float) {
        // smooth continuous sweep: the 1 s tick feeds a new target every
        // second, so a linear 1 s animation flows into the next one with no
        // pause — no stepping, even on 24 h locks (tiny per-second moves)
        if (Math.abs(p - target) < 0.00001f) return
        target = p
        anim?.cancel()
        val a = ValueAnimator.ofFloat(shown, p)
        a.duration = 1000
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val pad = w * 0.07f
        rect.set(pad, pad, w - pad, h - pad)

        // relaxing glow: the halo breathes gently behind the ring (stronger while locked)
        val haloMin = if (active) 70 else 30
        val haloMax = if (active) 150 else 70
        halo.alpha = (haloMin + (haloMax - haloMin) * breath).toInt()
        canvas.drawCircle(cx, cy, w / 2f, halo)

        if (active) {
            val stroke = w * 0.055f
            track.strokeWidth = stroke
            track.color = C.card2
            canvas.drawOval(rect, track)
            if (shown > 0.002f) {
                glow.strokeWidth = stroke * 1.9f
                glow.alpha = 50
                canvas.drawArc(rect, -90f, 360f * shown, false, glow)
                arc.strokeWidth = stroke
                canvas.drawArc(rect, -90f, 360f * shown, false, arc)
            }
        } else {
            inner.color = tint(C.bg, 150)
            canvas.drawCircle(cx, cy, (w - 2f * pad) / 2f, inner)
            track.strokeWidth = w * 0.016f
            track.color = C.primary
            canvas.drawOval(rect, track)
        }

        iconPaint.color = C.primary
        val breathScale = if (active) 1f else 1f + 0.045f * breath
        Icons.draw(canvas, icon, cx, cy - w * 0.20f, w * 0.21f * breathScale, iconPaint)

        tMain.color = mainColor
        if (active) {
            tMain.typeface = tfLight
            tMain.textSize = w * 0.135f
            canvas.drawText(main, cx, cy + w * 0.10f, tMain)
        } else {
            tMain.typeface = tfMedium
            tMain.textSize = w * 0.20f
            canvas.drawText(main, cx, cy + w * 0.15f, tMain)
        }
        if (sub.isNotEmpty()) {
            tSub.color = C.muted
            tSub.textSize = w * 0.058f
            canvas.drawText(sub, cx, cy + w * 0.22f, tSub)
        }
    }
}

/** Seven-day bar chart for the statistics screen. */
class BarChartView(ctx: Context) : View(ctx) {
    var values: List<Long> = emptyList()
    var labels: List<String> = emptyList()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        txt.textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = h * 0.18f
        val top = h * 0.05f
        val chartH = h - labelH - top
        var max = 1L
        for (v in values) if (v > max) max = v
        val slot = w / n
        val bw = slot * 0.5f
        txt.textSize = h * 0.11f
        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            bar.color = C.card2
            rect.set(cx - bw / 2f, top, cx + bw / 2f, top + chartH)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, bar)
            val frac = values[i].toFloat() / max.toFloat()
            if (frac > 0f) {
                var bh = chartH * frac
                if (bh < bw) bh = bw
                bar.color = if (i == n - 1) C.mint else C.primary
                rect.set(cx - bw / 2f, top + chartH - bh, cx + bw / 2f, top + chartH)
                canvas.drawRoundRect(rect, bw / 2f, bw / 2f, bar)
            }
            txt.color = if (i == n - 1) C.mint else C.muted
            val label = if (i < labels.size) labels[i] else ""
            canvas.drawText(label, cx, h - labelH * 0.25f, txt)
        }
    }
}



// =====================================================================
// MOTION TOOLKIT (consistent timing and easing)
// =====================================================================

object Motion {
    const val FAST = 140L
    const val MED = 260L
    const val SLOW = 340L
    val EASE: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
}

/** Subtle press feedback: scales down slightly while pressed. Click handling is not affected. */
fun View.pressFeedback() {
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.FAST).setInterpolator(Motion.EASE).start()
        }
        false
    }
}

/** Fades the old text out and the new text in. */
fun TextView.setTextAnimated(t: String) {
    if (text.toString() == t) return
    if (tag == t) return
    tag = t
    if (text.isEmpty() || !isAttachedToWindow) {
        text = t
        return
    }
    animate().cancel()
    animate().alpha(0f).setDuration(Motion.FAST / 2).withEndAction {
        text = t
        animate().alpha(1f).setDuration(Motion.FAST).start()
    }.start()
}

/** Page entrance: children of a page column rise and fade in one after another. */
fun Context.staggerIn(vg: ViewGroup) {
    val n = Math.min(vg.childCount, 9)
    for (i in 0 until n) {
        val c = vg.getChildAt(i)
        c.alpha = 0f
        c.translationY = dp(14).toFloat()
        c.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(i * 40L)
            .setDuration(Motion.SLOW)
            .setInterpolator(Motion.EASE)
            .start()
    }
}

/** Animated selected / unselected change for a chip (colour blend + small pop). */
fun Context.animateChip(v: TextView, selected: Boolean) {
    val from = (v.tag as? Boolean) ?: !selected
    if (from == selected) {
        styleChip(v, selected)
        return
    }
    v.tag = selected
    val g = GradientDrawable()
    g.cornerRadius = dp(18).toFloat()
    v.background = withRipple(g)
    val ev = ArgbEvaluator()
    val fFill = if (from) C.selFill else C.chipFill
    val tFill = if (selected) C.selFill else C.chipFill
    val fStroke = if (from) C.primary else C.border
    val tStroke = if (selected) C.primary else C.border
    val fText = if (from) C.text else C.textSoft
    val tText = if (selected) C.text else C.textSoft
    val fW = if (from) dp(2) else dp(1)
    val tW = if (selected) dp(2) else dp(1)
    val an = ValueAnimator.ofFloat(0f, 1f)
    an.duration = Motion.MED
    an.addUpdateListener {
        val t = it.animatedFraction
        g.setColor(ev.evaluate(t, fFill, tFill) as Int)
        var w = (fW + (tW - fW) * t).toInt()
        if (w < 1) w = 1
        g.setStroke(w, ev.evaluate(t, fStroke, tStroke) as Int)
        v.setTextColor(ev.evaluate(t, fText, tText) as Int)
    }
    an.start()
    if (selected) {
        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(Motion.FAST).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.MED).setInterpolator(Motion.EASE).start()
        }.start()
    }
}

/** Toggle switch with an animated thumb and colour blend. */
class PremiumSwitch(ctx: Context) : View(ctx) {
    var onChange: ((Boolean) -> Unit)? = null
    private var checked = false
    private var f = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val ev = ArgbEvaluator()
    private val tw = ctx.dp(52)
    private val th = ctx.dp(30)
    private val inset = ctx.dp(3).toFloat()

    init {
        isClickable = true
        setOnClickListener { setChecked(!checked) }
    }

    fun setCheckedImmediate(v: Boolean) {
        checked = v
        f = if (v) 1f else 0f
        invalidate()
    }

    private fun setChecked(v: Boolean) {
        if (checked == v) return
        checked = v
        anim?.cancel()
        val an = ValueAnimator.ofFloat(f, if (v) 1f else 0f)
        an.duration = Motion.MED
        an.interpolator = Motion.EASE
        an.addUpdateListener {
            f = it.animatedValue as Float
            invalidate()
        }
        an.start()
        anim = an
        onChange?.invoke(v)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(tw, th)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        var t = f
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        paint.color = ev.evaluate(t, C.card2, C.selFill) as Int
        rect.set(0f, 0f, tw.toFloat(), th.toFloat())
        canvas.drawRoundRect(rect, th / 2f, th / 2f, paint)
        val r = th / 2f - inset
        val cx = (th / 2f) + (tw - th) * t
        paint.color = ev.evaluate(t, C.muted, C.primary) as Int
        canvas.drawCircle(cx, th / 2f, r, paint)
    }
}

/** Two-or-more option selector with a thumb that slides between the options. */
class SegmentedControl(
    ctx: Context,
    labels: List<String>,
    private var index: Int,
    private val onPick: (Int) -> Unit,
    private val onDone: (Int) -> Unit
) : FrameLayout(ctx) {
    private val n = labels.size
    private val pad = ctx.dp(4)
    private val thumb = View(ctx)
    private val views = ArrayList<TextView>()
    private var thumbW = 0
    private val ev = ArgbEvaluator()

    init {
        background = rounded(C.chipFill, ctx.dp(18).toFloat(), C.border, ctx.dp(1))
        thumb.background = rounded(C.selFill, ctx.dp(14).toFloat(), C.primary, ctx.dp(1))
        addView(thumb, FrameLayout.LayoutParams(0, 0))
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(pad, pad, pad, pad)
        for ((i, l) in labels.withIndex()) {
            val t = ctx.tv(l, 15f, if (i == index) C.text else C.muted, 1)
            t.gravity = Gravity.CENTER
            t.setPadding(0, ctx.dp(13), 0, ctx.dp(13))
            val s = if (i == index) 1.04f else 1f
            t.scaleX = s
            t.scaleY = s
            t.setOnClickListener { select(i) }
            views.add(t)
            row.addView(t, lp(0, WRAP, weight = 1f))
        }
        addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (n == 0) return
        thumbW = (w - 2 * pad) / n
        val p = FrameLayout.LayoutParams(thumbW, h - 2 * pad)
        p.leftMargin = pad
        p.topMargin = pad
        thumb.layoutParams = p
        thumb.translationX = index * thumbW.toFloat()
    }

    private fun select(i: Int) {
        if (i == index) return
        val old = index
        index = i
        onPick(i)
        thumb.scaleX = 0.94f
        thumb.scaleY = 0.94f
        thumb.animate()
            .translationX(i * thumbW.toFloat())
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(Motion.MED + 40)
            .setInterpolator(Motion.EASE)
            .withEndAction { onDone(i) }
            .start()
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = Motion.MED
        an.addUpdateListener {
            val t = it.animatedFraction
            views[i].setTextColor(ev.evaluate(t, C.muted, C.text) as Int)
            views[old].setTextColor(ev.evaluate(t, C.text, C.muted) as Int)
        }
        an.start()
        views[i].animate().scaleX(1.04f).scaleY(1.04f).setDuration(Motion.MED).start()
        views[old].animate().scaleX(1f).scaleY(1f).setDuration(Motion.MED).start()
    }
}

// =====================================================================
// GLOW CARD BORDER
// =====================================================================

/** Neon-style card border: milk-white core with a soft green glow that grows when switched on. */
class GlowBorderDrawable(
    private val radius: Float,
    private val inset: Float,
    private val coreW: Float
) : Drawable() {
    var level = 0f
        set(v) {
            field = v
            invalidateSelf()
        }
    var pulse = 1f
        set(v) {
            field = v
            invalidateSelf()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val evaluator = ArgbEvaluator()
    private val milk = Color.parseColor("#F6F3EA")

    init {
        stroke.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        fill.color = C.card
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.color = tint(C.primary, (26f * level).toInt())
        canvas.drawRoundRect(rect, radius, radius, fill)

        val g = level * pulse
        for (i in 4 downTo 1) {
            stroke.strokeWidth = coreW + 1.6f * inset * i / 4f
            stroke.color = tint(C.primary, (g * 80f * (5 - i) / 4f).toInt())
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
        stroke.strokeWidth = coreW
        stroke.color = evaluator.evaluate(level, C.border, milk) as Int
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
