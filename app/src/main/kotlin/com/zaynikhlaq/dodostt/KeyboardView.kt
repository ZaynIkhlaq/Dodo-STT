package com.zaynikhlaq.dodostt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * A QWERTY keyboard drawn from scratch. The framework's own KeyboardView was deprecated in API 29
 * and looks its age, and AppCompat/Material are deliberately not dependencies here — so this draws
 * and hit-tests its own keys.
 *
 * Rows are laid out by weight across the full width, which keeps every layout to a list of keys and
 * no XML.
 */
class KeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
    }

    private enum class Kind { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, LAYER }

    private enum class Shift { OFF, ONCE, LOCKED }

    private class Key(
        val label: String,
        val kind: Kind = Kind.CHAR,
        val weight: Float = 1f,
        /** Typed on a long press, and shown small above the letter. */
        val secondary: String? = null,
    ) {
        val bounds = RectF()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val rowHeight = 52f * density
    private val gapX = 3f * density
    private val gapY = 5f * density
    private val radius = 10f * density

    private val surface = context.getColor(R.color.surface)
    private val hairline = context.getColor(R.color.hairline)
    private val colorPrimary = context.getColor(R.color.text_primary)
    private val colorSecondary = context.getColor(R.color.text_secondary)
    private val colorTertiary = context.getColor(R.color.text_tertiary)
    private val accent = context.getColor(R.color.accent)

    private val keyFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val shiftIcon: Drawable = context.getDrawable(R.drawable.ic_shift)!!.mutate()
    private val shiftLockIcon: Drawable = context.getDrawable(R.drawable.ic_shift_lock)!!.mutate()
    private val backspaceIcon: Drawable = context.getDrawable(R.drawable.ic_backspace)!!.mutate()
    private val enterIcon: Drawable = context.getDrawable(R.drawable.ic_enter)!!.mutate()

    private val handler = Handler(Looper.getMainLooper())

    private var symbols = false
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var pressed: Key? = null
    private var longPressed = false

    // Row 1 is inset by half a key so the letters nest the way a physical keyboard does.
    private val letterRows: List<List<Key>> by lazy { listOf(
        "qwertyuiop".mapIndexed { i, c -> Key(c.toString(), secondary = "1234567890"[i].toString()) },
        "asdfghjkl".map { Key(it.toString()) },
        listOf(Key("shift", Kind.SHIFT, 1.5f)) + "zxcvbnm".map { Key(it.toString()) } +
            listOf(Key("del", Kind.BACKSPACE, 1.5f)),
        bottomRow("?123"),
    ) }

    private val symbolRows: List<List<Key>> by lazy { listOf(
        "1234567890".map { Key(it.toString()) },
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map { Key(it) },
        listOf(Key("=", weight = 1.5f)) + listOf("*", "\"", "'", ":", ";", "!", "?").map { Key(it) } +
            listOf(Key("del", Kind.BACKSPACE, 1.5f)),
        bottomRow("ABC"),
    ) }

    private fun bottomRow(layerLabel: String) = listOf(
        Key(layerLabel, Kind.LAYER, 1.5f),
        Key(","),
        Key("space", Kind.SPACE, 5f),
        Key("."),
        Key("enter", Kind.ENTER, 1.5f),
    )

    private var rows: List<List<Key>> = letterRows

    init {
        isClickable = true
        contentDescription = context.getString(R.string.cd_keys)
        label.textSize = 20f * resources.displayMetrics.scaledDensity
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((320 * density).toInt(), widthMeasureSpec)
        val height = (rowHeight * rows.size + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        val usable = width - paddingStart - paddingEnd
        if (usable <= 0) return
        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            // Letter row 2 has nine keys where row 1 has ten: inset it so the columns interlock.
            val inset = if (total < 10f && row.none { it.kind != Kind.CHAR }) {
                (10f - total) / 2f * (usable / 10f)
            } else 0f
            var x = paddingStart + inset
            val unit = (usable - inset * 2) / total
            val top = paddingTop + r * rowHeight
            for (key in row) {
                val w = unit * key.weight
                key.bounds.set(x + gapX / 2, top + gapY / 2, x + w - gapX / 2, top + rowHeight - gapY / 2)
                x += w
            }
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in rows) for (key in row) if (key.bounds.contains(x, y)) return key
        return null
    }

    // --- input -----------------------------------------------------------------------------------

    /** Holding a top-row key types the number printed above it. */
    private val longPress = Runnable {
        val alt = pressed?.secondary?.takeUnless { symbols }
        if (alt != null) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onText(alt)
            longPressed = true
            press(null)
        }
    }

    private val repeatBackspace = object : Runnable {
        override fun run() {
            listener?.onBackspace()
            handler.postDelayed(this, 45)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                press(keyAt(event.x, event.y))
                pressed?.let { key ->
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (key.secondary != null && !symbols) handler.postDelayed(longPress, 320)
                    // Backspace fires immediately and repeats; everything else waits for the lift,
                    // so a finger that lands wrong can still slide to the right key.
                    if (key.kind == Kind.BACKSPACE) {
                        listener?.onBackspace()
                        handler.postDelayed(repeatBackspace, 380)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val over = keyAt(event.x, event.y)
                if (over !== pressed) {
                    handler.removeCallbacks(repeatBackspace)
                    handler.removeCallbacks(longPress)
                    press(if (pressed?.kind == Kind.BACKSPACE) null else over)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeatBackspace)
                handler.removeCallbacks(longPress)
                pressed?.let { if (it.kind != Kind.BACKSPACE && !longPressed) emit(it) }
                press(null)
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacksAndMessages(null)
                press(null)
            }
        }
        return true
    }

    private fun press(key: Key?) {
        if (key === pressed) return
        pressed = key
        invalidate()
    }

    private fun emit(key: Key) {
        when (key.kind) {
            Kind.CHAR -> {
                listener?.onText(faceOf(key))
                if (shift == Shift.ONCE) setShift(Shift.OFF)
            }
            Kind.SPACE -> listener?.onText(" ")
            Kind.ENTER -> listener?.onEnter()
            Kind.BACKSPACE -> listener?.onBackspace()
            Kind.LAYER -> {
                symbols = !symbols
                setShift(Shift.OFF)
                rows = if (symbols) symbolRows else letterRows
                layoutKeys()
                invalidate()
            }
            Kind.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                val doubleTapped = now - lastShiftTap < 300
                lastShiftTap = now
                setShift(
                    when {
                        doubleTapped -> Shift.LOCKED
                        shift == Shift.OFF -> Shift.ONCE
                        else -> Shift.OFF
                    }
                )
            }
        }
    }

    /** What the key types and shows right now: symbols ignore shift, letters follow it. */
    private fun faceOf(key: Key) = if (symbols) key.label else applyShift(key.label)

    private fun applyShift(text: String) = if (shift == Shift.OFF) text else text.uppercase()

    private fun setShift(value: Shift) {
        if (shift == value) return
        shift = value
        invalidate()
    }

    /** Lets the IME reset one-shot shift when the editor changes under it. */
    fun reset() {
        symbols = false
        rows = letterRows
        shift = Shift.OFF
        pressed = null
        longPressed = false
        handler.removeCallbacksAndMessages(null)
        layoutKeys()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // --- drawing ---------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        for (row in rows) {
            for (key in row) {
                val seated = key.kind == Kind.SPACE || key.kind == Kind.ENTER
                val fill = when {
                    key === pressed -> hairline
                    seated -> surface
                    else -> 0
                }
                if (fill != 0) {
                    keyFill.color = fill
                    canvas.drawRoundRect(key.bounds, radius, radius, keyFill)
                }
                drawFace(canvas, key)
            }
        }
    }

    private fun drawFace(canvas: Canvas, key: Key) {
        val cx = key.bounds.centerX()
        val cy = key.bounds.centerY()
        when (key.kind) {
            Kind.CHAR -> {
                val hint = key.secondary?.takeUnless { symbols }
                if (hint != null) {
                    label.color = colorTertiary
                    label.textSize = 10f * resources.displayMetrics.scaledDensity
                    canvas.drawText(hint, cx, key.bounds.top + 15f * density, label)
                }
                label.color = colorPrimary
                label.textSize = 21f * resources.displayMetrics.scaledDensity
                // Nudge the letter down when a number sits above it, so the pair reads as one key.
                val baseline = cy - (label.descent() + label.ascent()) / 2f + if (hint != null) 5f * density else 0f
                canvas.drawText(faceOf(key), cx, baseline, label)
            }
            Kind.LAYER -> {
                label.color = colorSecondary
                label.textSize = 14f * resources.displayMetrics.scaledDensity
                canvas.drawText(key.label, cx, cy - (label.descent() + label.ascent()) / 2f, label)
            }
            Kind.SPACE -> Unit
            Kind.SHIFT -> {
                val locked = shift == Shift.LOCKED
                val icon = if (locked) shiftLockIcon else shiftIcon
                icon.setTint(
                    when (shift) {
                        Shift.OFF -> colorSecondary
                        Shift.ONCE -> colorPrimary
                        Shift.LOCKED -> accent
                    }
                )
                drawIcon(canvas, icon, cx, cy)
            }
            Kind.BACKSPACE -> {
                backspaceIcon.setTint(colorSecondary)
                drawIcon(canvas, backspaceIcon, cx, cy)
            }
            Kind.ENTER -> {
                enterIcon.setTint(colorSecondary)
                drawIcon(canvas, enterIcon, cx, cy)
            }
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, cx: Float, cy: Float) {
        val half = (11f * density).toInt()
        icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon.draw(canvas)
    }
}
