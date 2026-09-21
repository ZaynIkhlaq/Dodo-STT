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
import kotlin.math.abs

/**
 * The keyboard deck, modelled on Samsung Keyboard: soft rounded keys with no borders, letters in
 * white and modifiers a step greyer, a tint that lands on touch-down and fades on release, a preview
 * bubble over the key you're holding, and long-press alternates you slide to pick.
 *
 * Most of what makes a stock keyboard feel smooth is behaviour rather than looks, so that is where
 * the work is: every pixel of the deck belongs to some key (no dead gaps), a second finger commits the
 * first key immediately so fast typing never drops a letter, dragging on the space bar moves the
 * cursor, and a held backspace speeds up from letters to whole words.
 *
 * Drawn by hand because the framework's KeyboardView was deprecated in API 29, and AppCompat and
 * Material are deliberately not dependencies. Nothing in [onDraw] allocates.
 */
class KeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        /** A backspace held long enough deletes a word at a time. */
        fun onDeleteWord()
        fun onEnter()
        /** Dragging on the space bar: move the cursor this many characters, negative for left. */
        fun onCursor(steps: Int)
    }

    /** What the enter key will do, which decides the icon it wears. */
    enum class Enter { NEWLINE, GO, SEARCH, SEND, NEXT, DONE }

    private enum class Kind { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, MODE, PAGE }

    private enum class Shift { OFF, ONCE, LOCKED }

    private enum class Layer { LETTERS, SYMBOLS, SYMBOLS2 }

    private class Key(
        val label: String,
        val kind: Kind = Kind.CHAR,
        val weight: Float = 1f,
        /** Offered on a long press, first one pre-selected. */
        val alternates: List<String> = emptyList(),
        /** Drawn small in the corner: the top row's numbers. */
        val hint: String? = null,
    ) {
        val upper = label.uppercase()
        val upperAlternates = alternates.map { it.uppercase() }
        /** Where the key is drawn. */
        val bounds = RectF()
        /** Where it can be hit: its whole cell, gaps included, stretched to the deck's edges. */
        val hit = RectF()
        /** When the finger left it, for the fade-out; 0 while held or idle. */
        var releasedAt = 0L
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    private val rowHeight = dp(56f)
    private val keyGapX = dp(6f)
    private val keyGapY = dp(10f)
    private val radius = dp(9f)

    private val keyColor = context.getColor(R.color.kb_key)
    private val modColor = context.getColor(R.color.kb_mod)
    private val labelColor = context.getColor(R.color.kb_label)
    private val hintColor = context.getColor(R.color.kb_hint)
    private val pressColor = context.getColor(R.color.kb_press)
    private val accent = context.getColor(R.color.kb_accent)
    private val onAccent = context.getColor(R.color.kb_on_accent)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val press = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = keyColor
        setShadowLayer(dp(6f), 0f, dp(1.5f), 0x33000000)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // On a Galaxy the default face is Samsung's own, which is half of looking native there.
        typeface = Typeface.DEFAULT
    }
    private val scratch = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)

    private fun icon(id: Int): Drawable = context.getDrawable(id)!!.mutate()
    private val shiftIcon = icon(R.drawable.ic_shift)
    private val shiftOnIcon = icon(R.drawable.ic_shift_on)
    private val shiftLockIcon = icon(R.drawable.ic_shift_lock)
    private val backspaceIcon = icon(R.drawable.ic_backspace)
    private val enterIcons = mapOf(
        Enter.NEWLINE to icon(R.drawable.ic_enter),
        Enter.GO to icon(R.drawable.ic_arrow),
        Enter.NEXT to icon(R.drawable.ic_arrow),
        Enter.SEARCH to icon(R.drawable.ic_search),
        Enter.SEND to icon(R.drawable.ic_send),
        Enter.DONE to icon(R.drawable.ic_check),
    )

    private val handler = Handler(Looper.getMainLooper())

    private var layer = Layer.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var enter = Enter.NEWLINE
    /** The layout is English whatever Whisper is set to hear, so that is what the space bar says. */
    private val spaceLabel = context.getString(R.string.language_en)

    // --- the finger currently driving a key ---
    private var pointerId = -1
    private var pressed: Key? = null
    /** Space bar turned into a cursor slider. */
    private var sliding = false
    private var slideAnchor = 0f
    private var backspaceRepeats = 0

    // --- long-press alternates ---
    private var popupKey: Key? = null
    private var popupItems: List<String> = emptyList()
    private var popupSelected = 0
    private val popupRect = RectF()
    private var popupCell = 0f

    // --- preview bubble, which lingers a moment after release ---
    private var previewKey: Key? = null
    private var previewUntil = 0L

    private val onLetters get() = layer == Layer.LETTERS

    private fun bottomRow(mode: String) = listOf(
        Key(mode, Kind.MODE, 1.5f),
        Key(","),
        Key("space", Kind.SPACE, 5f),
        Key(".", alternates = listOf(".", ",", "?", "!", "'", "\"", ":", ";", "-", "@", "/", "#")),
        Key("enter", Kind.ENTER, 1.5f),
    )

    private val letterRows: List<List<Key>> by lazy {
        val accents = mapOf(
            'e' to "èéêëē", 'y' to "ýÿ", 'u' to "ùúûüū", 'i' to "ìíîïī", 'o' to "òóôöõøœ",
            'a' to "àáâäãåæ", 's' to "ßśš", 'c' to "çćč", 'n' to "ñń", 'l' to "ł", 'z' to "žźż",
        )
        fun letter(c: Char, digit: Char? = null) = Key(
            c.toString(),
            alternates = listOfNotNull(digit?.toString()) + accents[c].orEmpty().map { it.toString() },
            hint = digit?.toString(),
        )
        listOf(
            "qwertyuiop".mapIndexed { i, c -> letter(c, "1234567890"[i]) },
            "asdfghjkl".map { letter(it) },
            listOf(Key("shift", Kind.SHIFT, 1.5f)) + "zxcvbnm".map { letter(it) } +
                listOf(Key("del", Kind.BACKSPACE, 1.5f)),
            bottomRow("!#1"),
        )
    }

    private fun symbolPage(page: String, top: String, middle: List<String>, third: List<String>) = listOf(
        top.map { Key(it.toString()) },
        middle.map { Key(it) },
        listOf(Key(page, Kind.PAGE, 1.5f)) + third.map { Key(it) } + listOf(Key("del", Kind.BACKSPACE, 1.5f)),
        bottomRow("ABC"),
    )

    private val symbolRows: List<List<Key>> by lazy {
        symbolPage(
            "1/2", "1234567890",
            listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
            listOf("*", "\"", "'", ":", ";", "!", "?"),
        )
    }

    private val symbolRows2: List<List<Key>> by lazy {
        symbolPage(
            "2/2", "~`|•√π÷×§∆",
            listOf("£", "€", "¥", "₹", "^", "°", "=", "{", "}", "\\"),
            listOf("%", "©", "®", "™", "[", "]", "<"),
        )
    }

    private var rows: List<List<Key>> = letterRows

    init {
        isClickable = true
        contentDescription = context.getString(R.string.cd_keys)
    }

    /**
     * Where the preview bubble and the alternates popup are drawn. They rise above the top row into
     * the toolbar, and drawing outside a view's own bounds leaves smears on devices that only
     * repaint what changed, so they go on a transparent layer that covers the whole panel.
     */
    var overlay: View? = null

    private fun refresh() {
        invalidate()
        overlay?.invalidate()
    }

    // --- what the IME tells us -------------------------------------------------------------------

    /** Back to letters (or numbers, for a numeric field) whenever a new editor starts. */
    fun reset(numeric: Boolean = false) {
        cancelTouch()
        go(if (numeric) Layer.SYMBOLS else Layer.LETTERS)
    }

    fun setEnterAction(action: Enter) {
        if (enter == action) return
        enter = action
        refresh()
    }

    /**
     * Auto-capitalisation: the IME says whether the cursor sits where a sentence starts. Never
     * overrides caps lock, and only applies to letters.
     */
    fun setAutoShift(on: Boolean) {
        if (!onLetters || shift == Shift.LOCKED) return
        setShift(if (on) Shift.ONCE else Shift.OFF)
    }

    // --- layout ----------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(320f).toInt(), widthMeasureSpec)
        val height = (rowHeight * 4 + paddingTop + paddingBottom).toInt()
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
            // A nine-key row is inset by half a key so its columns interlock with the row above.
            val inset = if (total < 9.5f) (10f - total) / 2f * (usable / 10f) else 0f
            var x = paddingStart + inset
            val unit = (usable - inset * 2) / total
            val top = paddingTop + r * rowHeight
            row.forEachIndexed { i, key ->
                val w = unit * key.weight
                key.bounds.set(x + keyGapX / 2, top + keyGapY / 2, x + w - keyGapX / 2, top + rowHeight - keyGapY / 2)
                key.hit.set(
                    if (i == 0) 0f else x,
                    if (r == 0) 0f else top,
                    if (i == row.lastIndex) width.toFloat() else x + w,
                    if (r == rows.lastIndex) height.toFloat() else top + rowHeight,
                )
                x += w
            }
        }
    }

    /** Every point on the deck belongs to a key; a tap in a gap goes to the nearest one. */
    private fun keyAt(x: Float, y: Float): Key {
        val r = ((y - paddingTop) / rowHeight).toInt().coerceIn(0, rows.lastIndex)
        val row = rows[r]
        return row.firstOrNull { x < it.hit.right } ?: row.last()
    }

    // --- touch -----------------------------------------------------------------------------------

    private val longPress = Runnable { openPopup() }

    private val repeatBackspace = object : Runnable {
        override fun run() {
            backspaceRepeats++
            // Letters for the first second or so, then whole words, the way a held key should.
            if (backspaceRepeats > 20) {
                listener?.onDeleteWord()
                handler.postDelayed(this, 160)
            } else {
                listener?.onBackspace()
                handler.postDelayed(this, 50)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landing means the first one's key is decided: commit it now, so
                // quick two-thumb typing never loses a letter to the overlap.
                if (pressed != null) finish(commit = popupKey == null && !sliding)
                val i = event.actionIndex
                pointerId = event.getPointerId(i)
                down(keyAt(event.getX(i), event.getY(i)), event.getX(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(pointerId)
                if (i >= 0) move(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) finish(commit = true)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun down(key: Key, x: Float) {
        pressed = key
        key.releasedAt = 0L
        sliding = false
        slideAnchor = x
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (key.kind == Kind.CHAR) showPreview(key)
        if (key.alternates.isNotEmpty() && (onLetters || key.label == ".")) handler.postDelayed(longPress, 300)
        // Backspace acts on touch and repeats; everything else waits for the lift, so a finger that
        // lands on the wrong key can still slide to the right one.
        if (key.kind == Kind.BACKSPACE) {
            listener?.onBackspace()
            backspaceRepeats = 0
            handler.postDelayed(repeatBackspace, 400)
        }
        refresh()
    }

    private fun move(x: Float, y: Float) {
        val key = pressed ?: return
        if (popupKey != null) {
            val selected = ((x - popupRect.left) / popupCell).toInt().coerceIn(0, popupItems.lastIndex)
            if (selected != popupSelected) {
                popupSelected = selected
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                refresh()
            }
            return
        }
        if (key.kind == Kind.SPACE) {
            val step = dp(10f)
            if (!sliding && abs(x - slideAnchor) > dp(8f)) {
                sliding = true
                slideAnchor = x
                refresh()
            }
            if (sliding) {
                val steps = ((x - slideAnchor) / step).toInt()
                if (steps != 0) {
                    listener?.onCursor(steps)
                    slideAnchor += steps * step
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            return
        }
        if (key.kind == Kind.BACKSPACE) return
        val over = keyAt(x, y)
        if (over !== key) {
            handler.removeCallbacks(longPress)
            release(key)
            pressed = over
            over.releasedAt = 0L
            if (over.kind == Kind.CHAR) showPreview(over) else previewKey = null
            if (over.alternates.isNotEmpty() && (onLetters || over.label == ".")) handler.postDelayed(longPress, 300)
            refresh()
        }
    }

    /** The finger driving [pressed] is done: type what it chose, unless [commit] is false. */
    private fun finish(commit: Boolean) {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeatBackspace)
        val key = pressed ?: return
        val popup = popupKey
        pressed = null
        pointerId = -1
        when {
            popup != null -> {
                if (commit) typeText(popupItems[popupSelected])
                popupKey = null
            }
            sliding -> Unit
            commit && key.kind != Kind.BACKSPACE -> emit(key)
        }
        sliding = false
        release(key)
        refresh()
    }

    private fun cancelTouch() {
        handler.removeCallbacksAndMessages(null)
        pressed?.let(::release)
        pressed = null
        pointerId = -1
        popupKey = null
        previewKey = null
        sliding = false
    }

    private fun release(key: Key) {
        key.releasedAt = SystemClock.uptimeMillis()
        if (previewKey === key) previewUntil = key.releasedAt + PREVIEW_LINGER_MS
    }

    private fun showPreview(key: Key) {
        previewKey = key
        previewUntil = Long.MAX_VALUE
    }

    private fun openPopup() {
        val key = pressed ?: return
        val items = if (onLetters && shift != Shift.OFF) key.upperAlternates else key.alternates
        if (items.isEmpty()) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        popupKey = key
        popupItems = items
        popupSelected = 0
        previewKey = null
        popupCell = minOf(key.bounds.width(), dp(38f))
        // The first choice sits right over the key, so hold-and-lift types it without a slide.
        val w = popupCell * items.size
        var left = key.bounds.centerX() - popupCell / 2
        left = left.coerceAtMost(width - dp(4f) - w).coerceAtLeast(dp(4f))
        val bottom = key.bounds.top - dp(3f)
        popupRect.set(left, bottom - dp(46f), left + w, bottom)
        refresh()
    }

    private fun typeText(s: String) {
        listener?.onText(s)
        if (shift == Shift.ONCE) setShift(Shift.OFF)
    }

    private fun emit(key: Key) {
        when (key.kind) {
            Kind.CHAR -> typeText(faceOf(key))
            Kind.SPACE -> listener?.onText(" ")
            Kind.ENTER -> listener?.onEnter()
            Kind.BACKSPACE -> Unit
            Kind.MODE -> go(if (onLetters) Layer.SYMBOLS else Layer.LETTERS)
            Kind.PAGE -> go(if (layer == Layer.SYMBOLS) Layer.SYMBOLS2 else Layer.SYMBOLS)
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

    /** Layers switch instantly: a slide here would only be something to wait for. */
    private fun go(target: Layer) {
        layer = target
        if (target != Layer.LETTERS) shift = Shift.OFF
        rows = when (target) {
            Layer.LETTERS -> letterRows
            Layer.SYMBOLS -> symbolRows
            Layer.SYMBOLS2 -> symbolRows2
        }
        previewKey = null
        layoutKeys()
        refresh()
    }

    private fun faceOf(key: Key) = if (onLetters && shift != Shift.OFF) key.upper else key.label

    private fun setShift(value: Shift) {
        if (shift == value) return
        shift = value
        refresh()
    }

    override fun onDetachedFromWindow() {
        cancelTouch()
        super.onDetachedFromWindow()
    }

    // --- drawing ---------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = false
        for (row in rows) {
            for (key in row) {
                val modifier = key.kind != Kind.CHAR && key.kind != Kind.SPACE
                val locked = key.kind == Kind.SHIFT && shift == Shift.LOCKED
                fill.color = when {
                    locked -> accent
                    modifier -> modColor
                    else -> keyColor
                }
                canvas.drawRoundRect(key.bounds, radius, radius, fill)

                // The tint lands the instant a finger does and fades once it lifts.
                val strength = when {
                    key === pressed && !sliding -> 1f
                    key.releasedAt > 0L -> {
                        val t = (now - key.releasedAt) / PRESS_FADE_MS.toFloat()
                        if (t >= 1f) {
                            key.releasedAt = 0L
                            0f
                        } else {
                            animating = true
                            1f - t
                        }
                    }
                    else -> 0f
                }
                if (strength > 0f) {
                    press.color = pressColor
                    press.alpha = (press.alpha * strength).toInt()
                    canvas.drawRoundRect(key.bounds, radius, radius, press)
                }
                drawFace(canvas, key)
            }
        }
        if (animating) postInvalidateOnAnimation()
    }

    /** Draws the bubble and popup onto [host], a layer over the whole panel; see [overlay]. */
    fun drawFloating(canvas: Canvas, host: View) {
        val preview = previewKey
        val popup = popupKey != null
        if (preview == null && !popup) return
        getLocationInWindow(here)
        host.getLocationInWindow(there)
        canvas.save()
        canvas.translate((here[0] - there[0]).toFloat(), (here[1] - there[1]).toFloat())
        if (preview != null && !popup) {
            val now = SystemClock.uptimeMillis()
            if (now < previewUntil) {
                drawPreview(canvas, preview)
                if (previewUntil != Long.MAX_VALUE) host.postInvalidateOnAnimation()
            } else {
                previewKey = null
            }
        }
        if (popup) drawPopup(canvas)
        canvas.restore()
    }

    private fun baseline(cy: Float) = cy - (text.descent() + text.ascent()) / 2f

    private fun drawFace(canvas: Canvas, key: Key) {
        val b = key.bounds
        val cx = b.centerX()
        val cy = b.centerY()
        when (key.kind) {
            Kind.CHAR -> {
                text.color = labelColor
                text.textSize = (if (key.label.length > 1) 16f else 23f) * scaled
                canvas.drawText(faceOf(key), cx, baseline(cy), text)
                if (key.hint != null && onLetters) {
                    text.color = hintColor
                    text.textSize = 10f * scaled
                    canvas.drawText(key.hint, b.right - dp(7f), b.top + dp(12f), text)
                }
            }
            Kind.MODE, Kind.PAGE -> {
                text.color = labelColor
                text.textSize = 15f * scaled
                canvas.drawText(key.label, cx, baseline(cy), text)
            }
            Kind.SPACE -> if (!sliding) {
                text.color = hintColor
                text.textSize = 12f * scaled
                canvas.drawText(spaceLabel, cx, baseline(cy), text)
            }
            Kind.SHIFT -> {
                val icon = when (shift) {
                    Shift.OFF -> shiftIcon
                    Shift.ONCE -> shiftOnIcon
                    Shift.LOCKED -> shiftLockIcon
                }
                icon.setTint(when (shift) {
                    Shift.OFF -> labelColor
                    Shift.ONCE -> accent
                    Shift.LOCKED -> onAccent
                })
                drawIcon(canvas, icon, cx, cy)
            }
            Kind.BACKSPACE -> {
                backspaceIcon.setTint(labelColor)
                drawIcon(canvas, backspaceIcon, cx, cy)
            }
            Kind.ENTER -> {
                val icon = enterIcons.getValue(enter)
                icon.setTint(labelColor)
                drawIcon(canvas, icon, cx, cy)
            }
        }
    }

    /** A taller copy of the key, floating above it, so the letter isn't hidden under the thumb. */
    private fun drawPreview(canvas: Canvas, key: Key) {
        val b = key.bounds
        val w = maxOf(b.width() * 1.25f, dp(40f))
        val bottom = b.top - dp(3f)
        var left = b.centerX() - w / 2
        left = left.coerceAtMost(width - dp(2f) - w).coerceAtLeast(dp(2f))
        scratch.set(left, bottom - dp(46f), left + w, bottom)
        canvas.drawRoundRect(scratch, radius, radius, bubble)
        text.color = labelColor
        text.textSize = 28f * scaled
        canvas.drawText(faceOf(key), scratch.centerX(), baseline(scratch.centerY()), text)
    }

    private fun drawPopup(canvas: Canvas) {
        canvas.drawRoundRect(popupRect, radius, radius, bubble)
        text.textSize = 21f * scaled
        popupItems.forEachIndexed { i, item ->
            val left = popupRect.left + i * popupCell
            scratch.set(left + dp(2f), popupRect.top + dp(3f), left + popupCell - dp(2f), popupRect.bottom - dp(3f))
            if (i == popupSelected) {
                fill.color = accent
                canvas.drawRoundRect(scratch, radius - dp(2f), radius - dp(2f), fill)
            }
            text.color = if (i == popupSelected) onAccent else labelColor
            canvas.drawText(item, scratch.centerX(), baseline(scratch.centerY()), text)
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, cx: Float, cy: Float) {
        val half = dp(11f).toInt()
        icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon.draw(canvas)
    }

    private companion object {
        const val PRESS_FADE_MS = 120L
        const val PREVIEW_LINGER_MS = 80L
    }
}

/** A transparent layer over the whole panel for [KeyboardView]'s floating bits. Never takes a touch. */
class KeyOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var keyboard: KeyboardView? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        keyboard?.drawFloating(canvas, this)
    }
}
