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
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView

/**
 * Dodo as a bar rather than a keyboard: your own keyboard stays, and dictation floats just above it.
 *
 * Android runs one input method at a time and gives no way to add anything to somebody else's
 * keyboard, so the only place a bar can live is a window of its own. An accessibility service is what
 * makes that work: its overlay window is layered above the keyboard — an ordinary "draw over other
 * apps" window is layered *below* it — and it is also the only way to put text into a field this app
 * doesn't own.
 *
 * The service watches for a focused editable field, parks the bar over the top edge of the keyboard,
 * and types what you said into whatever has the cursor.
 */
/**
 * What the bar is doing, for the settings screen to report. A phone has no logcat, so without this
 * a bar that never appears gives the user nothing to go on.
 */
object BarStatus {
    /** The service is switched on and running. */
    @Volatile var connected = false
    /** The bar is on screen right now. */
    @Volatile var showing = false
    /** A keyboard was visible the last time the bar looked. */
    @Volatile var keyboardSeen = false
    /** Why the window wouldn't go up, if it wouldn't. */
    @Volatile var lastError: String? = null
}

class DodoAccessibility : AccessibilityService(), Dictation.Sink {

    private companion object {
        /** Events arrive in bursts; settle before measuring anything. */
        const val SETTLE_MS = 50L
        /** The gap between the bar and the top of the keyboard. */
        const val LIFT_DP = 8f
        /** Where the bar sits when there is no keyboard on screen. */
        const val FLOOR_DP = 96f
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dictation: Dictation
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    private var bar: View? = null
    private var pill: StartPillView? = null
    private var status: TextView? = null
    private var cancel: View? = null
    private var attachedToWindow = false

    /** Text with nowhere to go, waiting for a tap to place it. */
    private var pendingInsert: String? = null
    private var message: String? = null
    /** The second the clock is showing, so the status line is only rewritten when it changes. */
    private var shownSeconds = -1L

    private val bounds = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        dictation = Dictation(this, this)
        wm = getSystemService(WindowManager::class.java)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, so the field you are typing in keeps the cursor and the keyboard stays up.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        buildBar()
        BarStatus.connected = true
        BarStatus.lastError = null
        Updater.schedule(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = scheduleReposition()

    /** Events arrive in bursts — a focus change is three or four — so measure once they settle. */
    private fun scheduleReposition() {
        handler.removeCallbacks(reposition)
        handler.postDelayed(reposition, SETTLE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        BarStatus.connected = false
        dictation.destroy()
        hide()
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    // --- the bar ---------------------------------------------------------------------------------

    private fun buildBar() {
        val view = LayoutInflater.from(this).inflate(R.layout.floating_bar, null)
        pill = view.findViewById(R.id.pill)
        status = view.findViewById(R.id.status)
        cancel = view.findViewById(R.id.btn_cancel)

        pill?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPillTapped()
        }
        cancel?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            message = null
            dictation.discard()
            render()
        }
        status?.setOnClickListener {
            val held = pendingInsert
            when {
                held != null -> {
                    if (insert(held)) pendingInsert = null
                    message = null
                    render()
                }
                !Setup.hasKey(this) || !Setup.hasMic(this) -> openSettings()
            }
        }
        view.findViewById<View>(R.id.btn_menu).setOnClickListener { openSettings() }
        bar = view
        render()
    }

    private fun onPillTapped() {
        message = null
        when (dictation.state) {
            Dictation.State.IDLE -> {
                if (!Setup.hasKey(this) || !Setup.hasMic(this) || !Setup.canRecordInBackground(this)) {
                    return openSettings()
                }
                shownSeconds = -1L
                if (!dictation.start()) message = getString(R.string.status_mic_busy)
                Updater.maybeCheck(this)
            }
            Dictation.State.RECORDING -> dictation.stop()
            Dictation.State.TRANSCRIBING -> Unit
        }
        render()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shows the bar where it belongs, or takes it away when there is nothing to dictate into. */
    private val reposition = Runnable {
        val live = dictation.state != Dictation.State.IDLE
        val keyboardTop = keyboardTop()
        BarStatus.keyboardSeen = keyboardTop > 0
        // A keyboard on screen is enough: some apps never report a focused node, and hiding the bar
        // in those is worse than showing one that has nowhere to type — it would insert by clipboard.
        val wanted = live || pendingInsert != null || keyboardTop > 0 || focusedEditable() != null
        if (!wanted) return@Runnable hide()
        params.y = if (keyboardTop > 0) (screenHeight() - keyboardTop + dp(LIFT_DP)).toInt() else dp(FLOOR_DP).toInt()
        show()
    }

    private fun show() {
        val view = bar ?: return
        if (attachedToWindow) {
            runCatching { wm.updateViewLayout(view, params) }
            return
        }
        runCatching { wm.addView(view, params) }
            .onSuccess {
                attachedToWindow = true
                BarStatus.showing = true
                BarStatus.lastError = null
            }
            .onFailure { BarStatus.lastError = it.message ?: it.javaClass.simpleName }
    }

    private fun hide() {
        val view = bar ?: return
        if (!attachedToWindow) return
        attachedToWindow = false
        BarStatus.showing = false
        runCatching { wm.removeView(view) }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels

    /** The top edge of the keyboard's own window, or 0 when no keyboard is up. */
    private fun keyboardTop(): Int {
        val open = runCatching { windows }.getOrNull().orEmpty()
        val ime = open.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return 0
        ime.getBoundsInScreen(bounds)
        return if (bounds.height() > 0) bounds.top else 0
    }

    private fun render() {
        val state = dictation.state
        pill?.mode = when (state) {
            Dictation.State.IDLE -> StartPillView.Mode.IDLE
            Dictation.State.RECORDING -> StartPillView.Mode.RECORDING
            Dictation.State.TRANSCRIBING -> StartPillView.Mode.BUSY
        }
        cancel?.visibility = if (state == Dictation.State.RECORDING) View.VISIBLE else View.GONE

        val held = pendingInsert
        val text = when {
            message != null -> message
            held != null -> getString(R.string.status_pending, held.split(' ').size)
            state == Dictation.State.RECORDING -> {
                val seconds = dictation.elapsedMs / 1000
                getString(R.string.status_listening, "%d:%02d".format(seconds / 60, seconds % 60))
            }
            !Setup.hasMic(this) || !Setup.canRecordInBackground(this) -> getString(R.string.status_no_mic)
            !Setup.hasKey(this) -> getString(R.string.status_no_key)
            else -> null
        }
        status?.text = text
        status?.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        Updater.setDictating(this, state != Dictation.State.IDLE)
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

    override fun onState(state: Dictation.State) {
        render()
        scheduleReposition()
    }

    override fun onLevel(level: Float) {
        pill?.setLevel(level)
        // Sixty times a second is far too often to rebuild the bar; only the clock moves.
        val seconds = dictation.elapsedMs / 1000
        if (seconds != shownSeconds && message == null && pendingInsert == null) {
            shownSeconds = seconds
            status?.text = getString(R.string.status_listening, "%d:%02d".format(seconds / 60, seconds % 60))
        }
    }

    override fun onFinished(delivered: Int, stranded: String?, error: String?) {
        if (stranded != null) {
            // Don't guess where it belongs — the field may be long gone. Offer it, and put it on the
            // clipboard so it survives even if the offer is never taken.
            pendingInsert = stranded
            copyToClipboard(stranded)
        }
        message = error ?: if (delivered == 0 && stranded == null) getString(R.string.status_silence) else null
        render()
    }
}
