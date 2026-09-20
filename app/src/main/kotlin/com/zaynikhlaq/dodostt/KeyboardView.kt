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
 * The keyboard deck. Dodo's own design stops at the bar above this — down here the job is to be the
 * keyboard the platform would have given you, because that is what Wispr Flow does and it is the
 * right call: nobody wants to relearn where the comma lives.
 *
 * Geometry and colour are measured off a reference screenshot rather than invented; see the
 * constants below and `kb_*` in colors.xml.
 *
 * Drawn by hand because the framework's KeyboardView was deprecated in API 29, and AppCompat and
 * Material are deliberately not dependencies.
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

    private enum class Layer { LETTERS, SYMBOLS, MORE }

    private class Key(
        val label: String,
        val kind: Kind = Kind.CHAR,
        val weight: Float = 1f,
        /** Typed on a long press. Only the top letter row carries one. */
        val secondary: String? = null,
    ) {
        val bounds = RectF()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    // Measured off the reference screenshot at ~1.9 px per dp.
    private val rowHeight = 54f * density
    private val keyGapX = 5f * density
    private val keyGapY = 10f * density
    private val radius = 5f * density

    private val deck = context.getColor(R.color.kb_bg)
    private val keyColor = context.getColor(R.color.kb_key)
    private val modColor = context.getColor(R.color.kb_mod)
    private val labelColor = context.getColor(R.color.kb_label)
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

    private var layer = Layer.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var pressed: Key? = null
    private var longPressed = false

    private val onLetters get() = layer == Layer.LETTERS

    /** Row 3 on the punctuation layers: two wide modifiers around five wide character keys. */
    private fun punctuationRow(layerLabel: String) =
        listOf(Key(layerLabel, Kind.LAYER, 1.7f)) +
            listOf(".", ",", "?", "!", "'").map { Key(it, weight = 1.32f) } +
            listOf(Key("del", Kind.BACKSPACE, 1.7f))

    private fun bottomRow(layerLabel: String) = listOf(
        Key(layerLabel, Kind.LAYER, 1.6f),
        Key(",", weight = 1.1f),
        Key("space", Kind.SPACE, 4.6f),
        Key(".", weight = 1.1f),
        Key("enter", Kind.ENTER, 1.6f),
    )

    private val letterRows: List<List<Key>> by lazy {
        listOf(
            "qwertyuiop".mapIndexed { i, c -> Key(c.toString(), secondary = "1234567890"[i].toString()) },
            "asdfghjkl".map { Key(it.toString()) },
            listOf(Key("shift", Kind.SHIFT, 1.5f)) + "zxcvbnm".map { Key(it.toString()) } +
                listOf(Key("del", Kind.BACKSPACE, 1.5f)),
            bottomRow("?123"),
        )
    }

    private val symbolRows: List<List<Key>> by lazy {
        listOf(
            "1234567890".map { Key(it.toString()) },
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"").map { Key(it) },
            punctuationRow("#+="),
            bottomRow("ABC"),
        )
    }

    private val moreRows: List<List<Key>> by lazy {
        listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { Key(it) },
            listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { Key(it) },
            punctuationRow("123"),
            bottomRow("ABC"),
        )
    }

    private var rows: List<List<Key>> = letterRows

    init {
        isClickable = true
        contentDescription = context.getString(R.string.cd_keys)
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
            // The letters' middle row is nine keys where the top row is ten: inset it so the
            // columns interlock, the way every phone keyboard since the first one has.
            val inset = if (total < 9.5f) (10f - total) / 2f * (usable / 10f) else 0f
            var x = paddingStart + inset
            val unit = (usable - inset * 2) / total
            val top = paddingTop + r * rowHeight
            for (key in row) {
                val w = unit * key.weight
                key.bounds.set(
                    x + keyGapX / 2, top + keyGapY / 2,
                    x + w - keyGapX / 2, top + rowHeight - keyGapY / 2,
                )
                x += w
            }
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in rows) for (key in row) if (key.bounds.contains(x, y)) return key
        return null
    }

    // --- input -----------------------------------------------------------------------------------

    /** Holding a top-row letter types the number that shares its column. */
    private val longPress = Runnable {
        val alt = pressed?.secondary?.takeIf { onLetters }
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
                    if (key.secondary != null && onLetters) handler.postDelayed(longPress, 320)
                    // Backspace fires immediately and repeats; everything else waits for the lift,
                    // so a finger that lands wrong can still slide to the key it meant.
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
            Kind.LAYER -> go(
                when (key.label) {
                    "?123", "123" -> Layer.SYMBOLS
                    "#+=" -> Layer.MORE
                    else -> Layer.LETTERS
                }
            )
            Kind.SHIFT -> {
                val doubleTapped = SystemClock.uptimeMillis() - lastShiftTap < 300
                lastShiftTap = SystemClock.uptimeMillis()
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

    private fun go(target: Layer) {
        layer = target
        shift = Shift.OFF
        rows = when (target) {
            Layer.LETTERS -> letterRows
            Layer.SYMBOLS -> symbolRows
            Layer.MORE -> moreRows
        }
        layoutKeys()
        invalidate()
    }

    /** What the key types and shows right now: only letters follow shift. */
    private fun faceOf(key: Key) =
        if (onLetters && shift != Shift.OFF) key.label.uppercase() else key.label

    private fun setShift(value: Shift) {
        if (shift == value) return
        shift = value
        invalidate()
    }

    /** Lets the IME drop back to plain lower-case letters when the editor changes under it. */
    fun reset() {
        pressed = null
        longPressed = false
        handler.removeCallbacksAndMessages(null)
        go(Layer.LETTERS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // --- drawing ---------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(deck)
        for (row in rows) {
            for (key in row) {
                val modifier = key.kind != Kind.CHAR && key.kind != Kind.SPACE
                // Pressing swaps a key to the other tone, which is how the reference shows a hit.
                val resting = if (modifier) modColor else keyColor
                keyFill.color = if (key === pressed) (if (modifier) keyColor else modColor) else resting
                canvas.drawRoundRect(key.bounds, radius, radius, keyFill)
                drawFace(canvas, key)
            }
        }
    }

    private fun drawFace(canvas: Canvas, key: Key) {
        val cx = key.bounds.centerX()
        val cy = key.bounds.centerY()
        fun baseline() = cy - (label.descent() + label.ascent()) / 2f
        when (key.kind) {
            Kind.CHAR -> {
                label.color = labelColor
                label.textSize = 22f * scaled
                canvas.drawText(faceOf(key), cx, baseline(), label)
            }
            Kind.LAYER -> {
                label.color = labelColor
                label.textSize = 15f * scaled
                canvas.drawText(key.label, cx, baseline(), label)
            }
            Kind.SPACE -> Unit
            Kind.SHIFT -> {
                val icon = if (shift == Shift.LOCKED) shiftLockIcon else shiftIcon
                icon.setTint(if (shift == Shift.LOCKED) accent else labelColor)
                drawIcon(canvas, icon, cx, cy)
            }
            Kind.BACKSPACE -> {
                backspaceIcon.setTint(labelColor)
                drawIcon(canvas, backspaceIcon, cx, cy)
            }
            Kind.ENTER -> {
                enterIcon.setTint(labelColor)
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
