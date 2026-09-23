package com.zaynikhlaq.dodostt

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.hypot

/**
 * What the bar is doing, for the settings screen to report. A phone has no logcat, so without this
 * a button that never appears gives the user nothing to go on.
 */
object BarStatus {
    /** The service is switched on and running. */
    @Volatile var connected = false
    /** The button is on screen right now. */
    @Volatile var showing = false
    /** A keyboard was visible the last time the button looked. */
    @Volatile var keyboardSeen = false
    /** Why the window wouldn't go up, if it wouldn't. */
    @Volatile var lastError: String? = null
}

/**
 * Dodo as a button on the edge of the screen: your own keyboard stays exactly as it is, and
 * dictation is one disc parked against the side, out of the way of the keys.
 *
 * Android runs one input method at a time and gives no way to add anything to somebody else's
 * keyboard, so the only place Dodo can live is a window of its own. An accessibility service is what
 * makes that work: its overlay window is layered above the keyboard — an ordinary "draw over other
 * apps" window is layered *below* it — and it is also the only way to put text into a field this app
 * doesn't own.
 *
 * One button, three gestures. Hold it and talk, like a walkie-talkie: let go and what you said is
 * typed. Double-tap instead and it locks on, hands free, until you tap it again. Drag it anywhere
 * along either edge. A single tap does nothing on its own, because a button that lives under a
 * thumb would otherwise record every time it was brushed.
 */
class DodoAccessibility : AccessibilityService(), Dictation.Sink {

    private companion object {
        /** Events arrive in bursts — a focus change is three or four — so measure once they settle. */
        const val SETTLE_MS = 50L
        /** Hold this long and it starts listening, for as long as you keep holding. */
        const val HOLD_MS = 280L
        /** Two taps inside this lock it on instead. */
        const val DOUBLE_TAP_MS = 320L
        /** Slide this far from the button while holding and letting go throws it away. */
        const val CANCEL_DP = 110f
        /** How long a word of help stays up. */
        const val HINT_MS = 1900L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dictation: Dictation
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    private var tab: DodoTab? = null
    private var attachedToWindow = false

    /** Text with nowhere to go, waiting for a tap to place it. */
    private var pendingInsert: String? = null
    private var shownSeconds = -1L

    private val bounds = Rect()

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // --- the finger on the button ---
    private var downX = 0f
    private var downY = 0f
    private var startY = 0
    private var dragging = false
    private var held = false
    private var lastTapAt = 0L
    /** Recording because the finger is still down, as opposed to locked on by a double-tap. */
    private var pushing = false
    /** The finger has slid away from the button: letting go now throws the recording away. */
    private var cancelArmed = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        dictation = Dictation(this, this)
        wm = getSystemService(WindowManager::class.java)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable and not touch-modal: the field you are typing in keeps the cursor, and
            // everything outside the button itself goes to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or side()
            y = Prefs.orbY(this@DodoAccessibility, defaultY())
        }
        build()
        BarStatus.connected = true
        BarStatus.lastError = null
        Updater.schedule(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, SETTLE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        BarStatus.connected = false
        dictation.destroy()
        hide()
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    // --- the button ------------------------------------------------------------------------------

    private fun build() {
        val view = DodoTab(this)
        view.contentDescription = getString(R.string.cd_mic)
        view.dockedLeft = Prefs.orbOnLeft(this)
        view.setOnTouchListener { _, event -> onOrbTouch(event) }
        tab = view
        render()
    }

    private fun side() = if (Prefs.orbOnLeft(this)) Gravity.START else Gravity.END

    private fun defaultY() = (screenHeight() * 0.55f).toInt()

    /** Tap, hold, double-tap and drag, all off one button. */
    private fun onOrbTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startY = params.y
                dragging = false
                held = false
                handler.postDelayed(hold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pushing) {
                    // Sliding away from the button is how you take it back mid-sentence.
                    val far = hypot(event.rawX - downX, event.rawY - downY) > dp(CANCEL_DP)
                    if (far != cancelArmed) {
                        cancelArmed = far
                        tab?.armed = far
                        if (far) say(getString(R.string.hint_release_cancel))
                    }
                    return true
                }
                if (!dragging && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                    dragging = true
                    handler.removeCallbacks(hold)
                }
                if (dragging) {
                    params.y = (startY + (event.rawY - downY)).toInt()
                        .coerceIn(0, screenHeight() - (tab?.height ?: 0))
                    runCatching { wm.updateViewLayout(tab, params) }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                when {
                    pushing -> release()
                    dragging -> settle(event.rawX)
                    !held -> onTap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hold)
                if (pushing) release()
            }
        }
        return true
    }

    /** Dropped after a drag: snap to whichever edge is nearer, and remember it for next time. */
    private fun settle(x: Float) {
        val onLeft = x < screenWidth() / 2f
        Prefs.setOrbPosition(this, onLeft, params.y)
        params.gravity = Gravity.TOP or (if (onLeft) Gravity.START else Gravity.END)
        tab?.dockedLeft = onLeft
        runCatching { wm.updateViewLayout(tab, params) }
    }

    private val hold = Runnable {
        held = true
        when (dictation.state) {
            // Hold to talk: it listens for as long as the finger stays down.
            Dictation.State.IDLE -> pushing = start()
            // Holding one that is locked on is how you throw it away.
            Dictation.State.RECORDING -> {
                tab?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                dictation.discard()
                say(getString(R.string.hint_discarded))
                render()
            }
            Dictation.State.TRANSCRIBING -> Unit
        }
    }

    /** The finger came off a hold: send what was said, or throw it away if it slid off first. */
    private fun release() {
        pushing = false
        tab?.armed = false
        tab?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (cancelArmed) {
            cancelArmed = false
            dictation.discard()
            say(getString(R.string.hint_discarded))
            render()
        } else {
            dictation.stop()
        }
    }

    private fun onTap() {
        when (dictation.state) {
            Dictation.State.RECORDING -> {
                tab?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dictation.stop()
                render()
            }
            Dictation.State.TRANSCRIBING -> Unit
            Dictation.State.IDLE -> {
                val held = pendingInsert
                val now = SystemClock.uptimeMillis()
                when {
                    // Text that had nowhere to go: the next tap places it.
                    held != null -> {
                        if (insert(held)) pendingInsert = null
                        render()
                    }
                    // Double-tap locks it on: talk with both hands free, tap once to finish.
                    now - lastTapAt < DOUBLE_TAP_MS -> {
                        lastTapAt = 0L
                        if (start()) say(getString(R.string.hint_locked))
                    }
                    else -> {
                        lastTapAt = now
                        say(getString(R.string.hint_hold))
                    }
                }
            }
        }
    }

    /** True if it is now recording. */
    private fun start(): Boolean {
        val missing = when {
            !Setup.hasMic(this) -> R.string.hint_no_mic
            !Setup.canRecordInBackground(this) -> R.string.hint_no_record
            !Setup.hasKey(this) -> R.string.hint_no_key
            else -> 0
        }
        if (missing != 0) {
            say(getString(missing))
            openSettings()
            return false
        }
        // Dictating into nothing would strand every word on the clipboard; say so instead.
        if (focusedEditable() == null) {
            say(getString(R.string.hint_no_field))
            return false
        }
        tab?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        shownSeconds = -1L
        val started = dictation.start()
        if (!started) say(getString(R.string.status_mic_busy))
        render()
        return started
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A word beside the glyph, in the tile itself, gone again in a moment. */
    private fun say(text: String) {
        tab?.label = text
        handler.removeCallbacks(clearHint)
        handler.postDelayed(clearHint, HINT_MS)
    }

    private val clearHint = Runnable {
        if (dictation.state != Dictation.State.RECORDING) tab?.label = null
    }

    /** Shows the button where it belongs, or takes it away when there is nothing to dictate into. */
    private val refresh = Runnable {
        val live = dictation.state != Dictation.State.IDLE
        val keyboardTop = keyboardTop()
        BarStatus.keyboardSeen = keyboardTop > 0
        // A keyboard on screen is enough: some apps never report a focused node, and hiding the
        // button in those is worse than showing one that can't start.
        val wanted = live || pendingInsert != null || keyboardTop > 0 || focusedEditable() != null
        if (wanted) show() else hide()
    }

    private fun show() {
        val view = tab ?: return
        if (attachedToWindow) return
        runCatching { wm.addView(view, params) }
            .onSuccess {
                attachedToWindow = true
                BarStatus.showing = true
                BarStatus.lastError = null
            }
            .onFailure { BarStatus.lastError = it.message ?: it.javaClass.simpleName }
    }

    private fun hide() {
        val view = tab ?: return
        if (!attachedToWindow) return
        attachedToWindow = false
        BarStatus.showing = false
        runCatching { wm.removeView(view) }
    }

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else resources.displayMetrics.widthPixels

    /** The top edge of the keyboard's own window, or 0 when no keyboard is up. */
    private fun keyboardTop(): Int {
        val open = runCatching { windows }.getOrNull().orEmpty()
        val ime = open.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return 0
        ime.getBoundsInScreen(bounds)
        return if (bounds.height() > 0) bounds.top else 0
    }

    private fun render() {
        val state = dictation.state
        tab?.mode = when (state) {
            Dictation.State.IDLE -> DodoTab.Mode.IDLE
            Dictation.State.RECORDING -> DodoTab.Mode.RECORDING
            Dictation.State.TRANSCRIBING -> DodoTab.Mode.BUSY
        }
        val held = pendingInsert
        when {
            state == Dictation.State.RECORDING -> Unit // the clock takes the label over
            held != null -> say(getString(R.string.status_pending, held.split(' ').size))
            else -> handler.post(clearHint)
        }
        Updater.setDictating(this, state != Dictation.State.IDLE)
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    // --- putting text in the field ---------------------------------------------------------------

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val focused = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        return focused?.takeIf { it.isEditable }
    }

    override val canReceive: Boolean get() = focusedEditable() != null

    /**
     * Types [text] at the cursor of whatever field has focus. Without an input connection of our own
     * this means rewriting the field's whole text around the selection, then putting the cursor back
     * after what we inserted; fields that refuse that get the clipboard and a paste instead.
     */
    override fun insert(text: String): Boolean {
        val node = focusedEditable() ?: return false
        val existing = node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start !in 0..existing.length || end !in 0..existing.length) {
            start = existing.length
            end = existing.length
        }
        if (start > end) {
            val from = end
            end = start
            start = from
        }

        // Add a leading space when the new text would otherwise run into the previous word.
        val previous = existing.getOrNull(start - 1)
        val spaced = if (previous != null && !previous.isWhitespace() && previous !in "([{\"'“‘/-") " $text" else text
        val updated = existing.substring(0, start) + spaced + existing.substring(end)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return paste(node, spaced)

        val caret = start + spaced.length
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            },
        )
        return true
    }

    /** For fields that won't take ACTION_SET_TEXT: put it on the clipboard and press paste. */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        copyToClipboard(text)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        }
    }

    // --- Dictation.Sink --------------------------------------------------------------------------

    override fun onState(state: Dictation.State) = render()

    override fun onLevel(level: Float) {
        tab?.setLevel(level)
        // Sixteen times a second is far too often to touch the label; only the clock moves.
        val seconds = dictation.elapsedMs / 1000
        if (seconds != shownSeconds) {
            shownSeconds = seconds
            tab?.label = "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }

    override fun onFinished(delivered: Boolean, stranded: String?, error: String?) {
        if (stranded != null) {
            // Don't guess where it belongs — the field may be long gone. Offer it, and put it on the
            // clipboard so it survives even if the offer is never taken.
            pendingInsert = stranded
            copyToClipboard(stranded)
        }
        val message = error ?: if (!delivered && stranded == null) getString(R.string.status_silence) else null
        message?.let(::say)
        render()
    }
}
