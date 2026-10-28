package com.hand.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Colour tokens. Two palettes, picked from the system dark-mode setting. */
class Palette(
    val bg: Int, val surface: Int, val surface2: Int, val outline: Int,
    val text: Int, val muted: Int, val accent: Int, val onAccent: Int,
    val good: Int, val warn: Int, val bad: Int
)

object Ui {
    private val DARK = Palette(
        bg = 0xFF0E1014.toInt(), surface = 0xFF171A21.toInt(), surface2 = 0xFF222631.toInt(),
        outline = 0xFF2C3140.toInt(), text = 0xFFEDEFF4.toInt(), muted = 0xFF9199A8.toInt(),
        accent = 0xFF8AA4FF.toInt(), onAccent = 0xFF0B1230.toInt(),
        good = 0xFF4ADE80.toInt(), warn = 0xFFF5B83D.toInt(), bad = 0xFFF87171.toInt()
    )
    private val LIGHT = Palette(
        bg = 0xFFF4F5F9.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFECEEF4.toInt(),
        outline = 0xFFDDE1EA.toInt(), text = 0xFF14161C.toInt(), muted = 0xFF636B7A.toInt(),
        accent = 0xFF3D5AFE.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        good = 0xFF16A34A.toInt(), warn = 0xFFB7791F.toInt(), bad = 0xFFDC2626.toInt()
    )

    lateinit var p: Palette
        private set
    var dark = true
        private set
    private var density = 3f

    fun init(ctx: Context) {
        density = ctx.resources.displayMetrics.density
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        p = if (dark) DARK else LIGHT
    }

    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Float) = v * density

    /** [color] at [alpha] (0..255). */
    fun tint(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun ripple(content: Drawable, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(tint(p.text, 0x22)), content, rounded(Color.WHITE, radiusDp))

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

// ---- building blocks ------------------------------------------------------------------------

fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(Ui.dp(l), Ui.dp(t), Ui.dp(r), Ui.dp(b))
}

fun Context.vbox(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    block()
}

fun Context.hbox(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

fun Context.label(s: CharSequence, size: Float = 15f, color: Int = Ui.p.text, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    if (bold) typeface = Ui.medium
    setLineSpacing(0f, 1.15f)
}

/** Rounded surface with comfortable padding; the basic container of every screen. */
fun Context.card(block: LinearLayout.() -> Unit = {}) = vbox {
    background = Ui.rounded(Ui.p.surface, 22)
    setPadding(Ui.dp(18), Ui.dp(18), Ui.dp(18), Ui.dp(18))
    layoutParams = lp().margins(b = 12)
    block()
}

fun Context.sectionTitle(s: String) = label(s.uppercase(), 12f, Ui.p.muted, bold = true).apply {
    letterSpacing = 0.08f
    layoutParams = lp().margins(l = 6, t = 10, b = 8)
}

fun View.onTap(action: () -> Unit) {
    isClickable = true
    setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
    }
}

enum class BtnStyle { PRIMARY, SECONDARY, DANGER, TEXT }

/** Pill button with optional leading icon. */
fun Context.pill(text: String, style: BtnStyle = BtnStyle.PRIMARY, icon: Glyph.Shape? = null, onClick: () -> Unit) = hbox {
    gravity = Gravity.CENTER
    minimumHeight = Ui.dp(if (style == BtnStyle.TEXT) 40 else 52)
    setPadding(Ui.dp(20), 0, Ui.dp(20), 0)
    val (bg, fg) = when (style) {
        BtnStyle.PRIMARY -> Ui.p.accent to Ui.p.onAccent
        BtnStyle.SECONDARY -> Ui.p.surface2 to Ui.p.text
        BtnStyle.DANGER -> Ui.tint(Ui.p.bad, 0x22) to Ui.p.bad
        BtnStyle.TEXT -> Color.TRANSPARENT to Ui.p.accent
    }
    background = Ui.ripple(Ui.rounded(bg, 26), 26)
    if (icon != null) {
        addView(Glyph(context, icon, fg, 20), lp(Ui.dp(20), Ui.dp(20)).margins(r = 10))
    }
    addView(label(text, 15f, fg, bold = true))
    onTap(onClick)
}

/** Small rounded status tag. */
fun Context.chip(text: String, color: Int, dot: Boolean = false) = hbox {
    background = Ui.rounded(Ui.tint(color, 0x26), 14)
    setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(12), Ui.dp(5))
    if (dot) addView(Glyph(context, Glyph.Shape.DOT, color, 8), lp(Ui.dp(8), Ui.dp(8)).margins(r = 6))
    addView(label(text, 12.5f, color, bold = true))
}

/** Title + optional explanation on the left, switch on the right. Whole row toggles. */
@SuppressLint("UseSwitchCompatOrMaterialCode")
fun Context.toggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) = hbox {
    setPadding(0, Ui.dp(10), 0, Ui.dp(10))
    val texts = vbox {
        addView(label(title, 15.5f))
        if (sub != null) addView(label(sub, 13f, Ui.p.muted).apply { setPadding(0, Ui.dp(2), 0, 0) })
    }
    addView(texts, lp(0, weight = 1f).margins(r = 12))
    val sw = Switch(context).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Ui.p.onAccent, Ui.p.muted))
        trackTintList = ColorStateList(states, intArrayOf(Ui.p.accent, Ui.p.outline))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
    addView(sw)
    onTap { sw.toggle() }
}

/** Equal-width choice control (Mouse | Remote | Phone). */
fun Context.segmented(options: List<Pair<String, Glyph.Shape>>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
    val row = hbox {
        background = Ui.rounded(Ui.p.surface2, 16)
        setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
    }
    fun paint(sel: Int) {
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i) as LinearLayout
            val on = i == sel
            item.background = if (on) Ui.rounded(Ui.p.accent, 12) else null
            val fg = if (on) Ui.p.onAccent else Ui.p.muted
            (item.getChildAt(0) as Glyph).color = fg
            (item.getChildAt(1) as TextView).setTextColor(fg)
        }
    }
    options.forEachIndexed { i, (text, icon) ->
        row.addView(hbox {
            gravity = Gravity.CENTER
            minimumHeight = Ui.dp(40)
            addView(Glyph(context, icon, Ui.p.muted, 16), lp(Ui.dp(16), Ui.dp(16)).margins(r = 6))
            addView(label(text, 13.5f, Ui.p.muted, bold = true))
            onTap { paint(i); onSelect(i) }
        }, lp(0, weight = 1f))
    }
    paint(selected)
    return row
}

/** Tappable list row: leading icon tile, title/subtitle, trailing view (defaults to a chevron). */
fun Context.listRow(
    title: String, sub: String? = null, icon: Glyph.Shape? = null, iconColor: Int = Ui.p.accent,
    trailing: View? = null, onClick: (() -> Unit)? = null
) = hbox {
    // Full width: without this the row sizes to its content and the title column collapses to 0.
    layoutParams = lp()
    setPadding(Ui.dp(4), Ui.dp(12), Ui.dp(4), Ui.dp(12))
    if (icon != null) {
        val tile = FrameLayout(context).apply {
            background = Ui.rounded(Ui.tint(iconColor, 0x22), 12)
            addView(Glyph(context, icon, iconColor, 20), FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER))
        }
        addView(tile, lp(Ui.dp(40), Ui.dp(40)).margins(r = 14))
    }
    addView(vbox {
        addView(label(title, 15.5f))
        if (sub != null) addView(label(sub, 13f, Ui.p.muted).apply { setPadding(0, Ui.dp(2), 0, 0) })
    }, lp(0, weight = 1f))
    val end = trailing ?: if (onClick != null) Glyph(context, Glyph.Shape.CHEVRON, Ui.p.muted, 18) else null
    if (end != null) {
        addView(end, if (end is Glyph) lp(Ui.dp(18), Ui.dp(18)).margins(l = 8) else lp(LinearLayout.LayoutParams.WRAP_CONTENT).margins(l = 8))
    }
    if (onClick != null) {
        background = Ui.ripple(Ui.rounded(Color.TRANSPARENT, 14), 14)
        onTap(onClick)
    }
}

fun Context.divider() = View(this).apply {
    setBackgroundColor(Ui.p.outline)
    layoutParams = lp(h = Ui.dp(1)).margins(t = 2, b = 2)
}

/** Square icon key for the remote screen. */
fun Context.iconKey(icon: Glyph.Shape, text: String?, accent: Boolean = false, sizeDp: Int = 64, onClick: () -> Unit) = vbox {
    gravity = Gravity.CENTER_HORIZONTAL
    val bg = if (accent) Ui.p.accent else Ui.p.surface2
    val fg = if (accent) Ui.p.onAccent else Ui.p.text
    val key = FrameLayout(context).apply {
        background = Ui.ripple(Ui.rounded(bg, sizeDp / 2), sizeDp / 2)
        addView(Glyph(context, icon, fg, 26), FrameLayout.LayoutParams(Ui.dp(26), Ui.dp(26), Gravity.CENTER))
        onTap(onClick)
        contentDescription = text ?: icon.name
    }
    addView(key, lp(Ui.dp(sizeDp), Ui.dp(sizeDp)))
    if (text != null) addView(label(text, 12f, Ui.p.muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, Ui.dp(6), 0, 0)
    })
}

// ---- icons ---------------------------------------------------------------------------------

/**
 * Minimal stroke icon set drawn on a 24-unit grid, so icons look identical on every phone
 * (emoji/unicode symbols vary by vendor and render in colour).
 */
class Glyph(ctx: Context, var shape: Shape, color: Int, private val sizeDp: Int) : View(ctx) {
    enum class Shape {
        HOME, DEVICES, GESTURE, REMOTE, UP, DOWN, LEFT, RIGHT, CHEVRON, CHECK, PLUS, DOT,
        PLAY_PAUSE, PREV, NEXT, VOL_DOWN, VOL_UP, MUTE, BACK, MENU, BLUETOOTH, PHONE, MOUSE, POWER,
        CAR, SEARCH, BOLT, TRASH
    }

    var color: Int = color
        set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onMeasure(w: Int, h: Int) {
        val s = Ui.dp(sizeDp)
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDraw(c: Canvas) {
        stroke.color = color
        fill.color = color
        val scale = minOf(width, height) / 24f
        c.save()
        c.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2)
        c.scale(scale, scale)
        paintShape(c)
        c.restore()
    }

    private fun Canvas.line(d: String) = drawPath(svg(d), stroke)
    private fun Canvas.solid(d: String) = drawPath(svg(d), fill)
    private fun Canvas.box(l: Float, t: Float, r: Float, b: Float, rad: Float) = drawRoundRect(RectF(l, t, r, b), rad, rad, stroke)

    private fun paintShape(c: Canvas) = with(c) {
        when (shape) {
            Shape.HOME -> { line("M3 11 L12 3.5 L21 11"); line("M5.5 9.5 V20.5 H18.5 V9.5"); line("M10 20.5 V15 H14 V20.5") }
            Shape.DEVICES -> { box(2.5f, 4f, 21.5f, 16f, 2.5f); line("M8.5 20 H15.5"); line("M12 16 V20") }
            Shape.GESTURE -> {
                drawCircle(12f, 12f, 2.2f, fill)
                drawArc(RectF(6.5f, 6.5f, 17.5f, 17.5f), 200f, 140f, false, stroke)
                drawArc(RectF(6.5f, 6.5f, 17.5f, 17.5f), 20f, 140f, false, stroke)
                drawArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), 210f, 120f, false, stroke)
                drawArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), 30f, 120f, false, stroke)
            }
            Shape.REMOTE -> line("M9 3 H15 V9 H21 V15 H15 V21 H9 V15 H3 V9 H9 Z")
            Shape.UP -> line("M6 15 L12 9 L18 15")
            Shape.DOWN -> line("M6 9 L12 15 L18 9")
            Shape.LEFT -> line("M15 6 L9 12 L15 18")
            Shape.RIGHT, Shape.CHEVRON -> line("M9 6 L15 12 L9 18")
            Shape.CHECK -> line("M5 12.5 L10 17.5 L19 7")
            Shape.PLUS -> line("M12 5 V19 M5 12 H19")
            Shape.DOT -> drawCircle(12f, 12f, 10f, fill)
            Shape.PLAY_PAUSE -> { solid("M4 6 L4 18 L12.5 12 Z"); line("M16 6.5 V17.5 M20 6.5 V17.5") }
            Shape.PREV -> { line("M6 6 V18"); solid("M19 6 L19 18 L9 12 Z") }
            Shape.NEXT -> { line("M18 6 V18"); solid("M5 6 L5 18 L15 12 Z") }
            Shape.VOL_DOWN -> { line("M3.5 9.5 H7.5 L12.5 5.5 V18.5 L7.5 14.5 H3.5 Z"); line("M16 12 H21") }
            Shape.VOL_UP -> { line("M3.5 9.5 H7.5 L12.5 5.5 V18.5 L7.5 14.5 H3.5 Z"); line("M16 12 H21 M18.5 9.5 V14.5") }
            Shape.MUTE -> { line("M3.5 9.5 H7.5 L12.5 5.5 V18.5 L7.5 14.5 H3.5 Z"); line("M16 9.5 L21 14.5 M21 9.5 L16 14.5") }
            Shape.BACK -> line("M10 6 L4 12 L10 18 M4 12 H20")
            Shape.MENU -> line("M4 7 H20 M4 12 H20 M4 17 H20")
            Shape.BLUETOOTH -> line("M7 7.5 L17 16.5 L12 21 V3 L17 7.5 L7 16.5")
            Shape.PHONE -> { box(6.5f, 2.5f, 17.5f, 21.5f, 2.5f); line("M10.5 18 H13.5") }
            Shape.MOUSE -> { box(6f, 3f, 18f, 21f, 6f); line("M12 7 V10.5") }
            Shape.POWER -> { line("M12 3 V11"); drawArc(RectF(4.5f, 5.5f, 19.5f, 20.5f), -60f, 300f, false, stroke) }
            Shape.CAR -> {
                line("M3.5 16 V12.5 L6 7 H18 L20.5 12.5 V16 Z"); line("M3.5 12.5 H20.5")
                drawCircle(7.5f, 17.5f, 1.8f, fill); drawCircle(16.5f, 17.5f, 1.8f, fill)
            }
            Shape.SEARCH -> { drawCircle(10.5f, 10.5f, 6f, stroke); line("M15 15 L20.5 20.5") }
            Shape.BOLT -> solid("M13 2.5 L5 13.5 H11 L10.5 21.5 L19 10 H13 Z")
            Shape.TRASH -> { line("M4 7 H20 M9.5 7 V4.5 H14.5 V7"); line("M6.5 7 L7.5 20.5 H16.5 L17.5 7") }
        }
    }

    companion object {
        /** Parses absolute M/L/H/V/Z path data. */
        fun svg(d: String): Path {
            val p = Path()
            val tok = Regex("[MLHVZ]|-?[0-9.]+").findAll(d).map { it.value }.toList()
            var i = 0
            var cmd = 'M'
            var x = 0f
            var y = 0f
            fun num() = tok[i++].toFloat()
            while (i < tok.size) {
                val t = tok[i]
                if (t[0].isLetter()) { cmd = t[0]; i++; if (cmd == 'Z') { p.close(); continue } }
                when (cmd) {
                    'M' -> { x = num(); y = num(); p.moveTo(x, y); cmd = 'L' }
                    'L' -> { x = num(); y = num(); p.lineTo(x, y) }
                    'H' -> { x = num(); p.lineTo(x, y) }
                    'V' -> { y = num(); p.lineTo(x, y) }
                    else -> i++
                }
            }
            return p
        }
    }
}
