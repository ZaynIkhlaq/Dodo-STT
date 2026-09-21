package com.zaynikhlaq.dodostt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.transition.Fade
import android.transition.TransitionManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import kotlin.math.abs

/**
 * A voice-first input method: a mic over a keyboard. Speech is cut into segments at pauses and each
 * one is transcribed while the next is still being spoken, so text lands in the field a few seconds
 * behind the voice instead of all at once at the end.
 */
class DodoIme : InputMethodService() {
    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private companion object {
        /** A segment has to be worth sending before a pause can end it. */
        const val MIN_SEGMENT_MS = 1200L
        /** Silence this long ends a segment. Roughly the gap between sentences. */
        const val PAUSE_MS = 700L
        /** Someone talking without pausing still gets text; this bounds how far behind it runs. */
        const val MAX_SEGMENT_MS = 18_000L
        /** With no field on screen, this much silence means the phone was put down. */
        const val ABANDONED_MS = 30_000L
        /** Two spaces this close together become ". ". */
        const val DOUBLE_SPACE_MS = 700L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recorder: Recorder
    private var state = State.IDLE

    /** Bumped whenever a session is abandoned, so late transcripts can't be typed into the wrong place. */
    private var generation = 0
    private var sessionStartedAt = 0L

    // --- the segment pipeline ---
    private var nextSeq = 0
    private var nextToCommit = 0
    private val ready = HashMap<Int, String>()
    private val awaiting = HashSet<Int>()
    private var delivered = 0
    private var errorMessage: String? = null

    /** False while no input view is attached — during a screen-off, say. */
    private var attached = false
    /** Text transcribed while there was nowhere to put it. */
    private val stranded = StringBuilder()
    /** Stranded text waiting for the user to say where it goes. */
    private var pendingInsert: String? = null

    private var status: TextView? = null
    private var pill: StartPillView? = null
    private var keyboard: KeyboardView? = null
    private var bar: ViewGroup? = null
    private var menuButton: View? = null
    private var cancelButton: View? = null
    /** Whether the toolbar is currently showing its recording face, so transitions run only on a change. */
    private var barLive = false

    private val meter = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            if (!recorder.isRecording) return finishRecording()
            val level = recorder.level()
            pill?.setLevel(level)
            renderTimer()
            considerCut()
            handler.postDelayed(this, 60)
        }
    }

    /** A quick cross-fade between the toolbar's idle and recording faces. */
    private val barTransition = Fade().setDuration(160)

    override fun onCreate() {
        super.onCreate()
        recorder = Recorder(this)
        Updater.schedule(this)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_panel, null)
        status = view.findViewById(R.id.status)
        pill = view.findViewById(R.id.pill)
        bar = view.findViewById(R.id.bar)
        menuButton = view.findViewById(R.id.btn_menu)
        cancelButton = view.findViewById(R.id.btn_cancel)
        barLive = false

        pill?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onMicTapped()
        }
        view.findViewById<ImageButton>(R.id.btn_cancel).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            discard()
        }
        status?.setOnClickListener {
            when {
                pendingInsert != null -> insertStranded()
                !isReady() -> openSettings()
            }
        }
        // Tap for Dodo's own settings; hold to hand back to whichever keyboard you came from.
        view.findViewById<ImageButton>(R.id.btn_menu).apply {
            setOnClickListener { openSettings() }
            setOnLongClickListener { backToKeyboard(); true }
        }

        keyboard = view.findViewById(R.id.keyboard)
        val overlay = view.findViewById<KeyOverlayView>(R.id.key_overlay)
        overlay.keyboard = keyboard
        keyboard?.overlay = overlay
        keyboard?.listener = object : KeyboardView.Listener {
            override fun onText(text: String) = typeKey(text)
            override fun onBackspace() = backspace()
            override fun onDeleteWord() = deleteWord()
            override fun onEnter() = pressEnter()
            override fun onCursor(steps: Int) = moveCursor(steps)
        }
        render()
        return view
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        attached = true
        if (!restarting) keyboard?.reset(numeric = isNumeric(info))
        keyboard?.setEnterAction(enterAction(info))
        updateShift()
        render()
        Updater.maybeCheck(this)
        val idle = state == State.IDLE && pendingInsert == null
        if (!restarting && idle && isReady() && Prefs.autoStart(this)) startRecording()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateShift()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        attached = false
        // Recording deliberately carries on. A screen timeout in the middle of a long dictation used
        // to throw all of it away; MicService keeps the microphone open until there is a real pause.
        reportBusy()
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        recorder.cancel()
        MicService.stop(this)
        super.onDestroy()
    }

    // --- dictation -------------------------------------------------------------------------------

    private fun onMicTapped() {
        when (state) {
            State.IDLE -> if (isReady()) startRecording() else openSettings()
            State.RECORDING -> finishRecording()
            State.TRANSCRIBING -> Unit
        }
    }

    private fun startRecording() {
        try {
            recorder.start()
        } catch (e: Exception) {
            return render(getString(R.string.status_mic_busy))
        }
        generation++
        nextSeq = 0
        nextToCommit = 0
        delivered = 0
        ready.clear()
        awaiting.clear()
        stranded.setLength(0)
        errorMessage = null
        sessionStartedAt = SystemClock.elapsedRealtime()
        MicService.start(this)
        state = State.RECORDING
        render()
        handler.post(meter)
    }

    /** Ends a segment at a pause, or when one has run long enough to be worth sending regardless. */
    private fun considerCut() {
        val atPause = recorder.segmentMs >= MIN_SEGMENT_MS && recorder.hasSpeech && recorder.silentMs >= PAUSE_MS
        if (atPause || recorder.segmentMs >= MAX_SEGMENT_MS) send(recorder.cut())
        if (!attached && recorder.silentMs >= ABANDONED_MS) finishRecording()
    }

    private fun finishRecording() {
        handler.removeCallbacks(meter)
        send(recorder.finish())
        MicService.stop(this)
        state = State.TRANSCRIBING
        render()
        // Nothing outstanding means this drops straight back to idle.
        flush()
    }

    private fun send(segment: Segment?) {
        val seg = segment ?: return
        val seq = nextSeq++
        val ticket = generation
        awaiting.add(seq)
        GroqClient.transcribe(Prefs.apiKey(this), Prefs.model(this), Prefs.language(this), seg.file) { result ->
            seg.file.delete()
            if (ticket != generation) return@transcribe
            awaiting.remove(seq)
            // An error still has to take its turn in the queue, or everything behind it stalls.
            when (result) {
                is GroqClient.Result.Ok -> ready[seq] = result.text
                is GroqClient.Result.Err -> {
                    errorMessage = result.message
                    ready[seq] = ""
                }
            }
            flush()
            render()
        }
    }

    /** Commits whatever is now contiguous from the front of the queue, so text never lands out of order. */
    private fun flush() {
        while (ready.containsKey(nextToCommit)) {
            val text = ready.remove(nextToCommit)!!
            nextToCommit++
            if (text.isNotEmpty()) deliver(text)
        }
        if (state == State.TRANSCRIBING && awaiting.isEmpty() && ready.isEmpty()) endSession()
    }

    private fun deliver(text: String) {
        delivered++
        if (attached && currentInputConnection != null) {
            type(text)
        } else {
            if (stranded.isNotEmpty()) stranded.append(' ')
            stranded.append(text)
        }
    }

    private fun endSession() {
        state = State.IDLE
        if (stranded.isNotEmpty()) {
            // Don't guess which field this belongs in — the user may have unlocked into another app.
            // Offer it, and put it on the clipboard so it survives even if they never take the offer.
            val text = stranded.toString()
            stranded.setLength(0)
            pendingInsert = text
            copyToClipboard(text)
        } else if (delivered > 0 && Prefs.autoReturn(this)) {
            handler.postDelayed({ backToKeyboard() }, 120)
        }
        render(if (delivered == 0 && errorMessage == null) getString(R.string.status_silence) else null)
    }

    private fun discard() {
        generation++
        handler.removeCallbacks(meter)
        recorder.cancel()
        MicService.stop(this)
        ready.clear()
        awaiting.clear()
        stranded.setLength(0)
        nextSeq = 0
        nextToCommit = 0
        state = State.IDLE
        render()
    }

    private fun insertStranded() {
        val text = pendingInsert ?: return
        pendingInsert = null
        type(text)
        render()
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        }
    }

    /** Inserts text, adding a leading space when it would otherwise run into the previous word. */
    private fun type(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'“‘/-"
        ic.commitText(if (needsSpace) " $text" else text, 1)
    }

    // --- keys ------------------------------------------------------------------------------------

    private var lastSpaceAt = 0L

    /** Types a key, turning a quick double space after a word into ". " the way stock keyboards do. */
    private fun typeKey(text: String) {
        val ic = currentInputConnection ?: return
        if (text == " ") {
            val now = SystemClock.uptimeMillis()
            val before = ic.getTextBeforeCursor(2, 0)
            val doubled = now - lastSpaceAt < DOUBLE_SPACE_MS && isProse(currentInputEditorInfo) &&
                before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()
            lastSpaceAt = if (doubled) 0L else now
            if (doubled) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                return
            }
        }
        ic.commitText(text, 1)
    }

    /** Capitalises the next letter wherever the field asks for it: a sentence start, say. */
    private fun updateShift() {
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = info.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(info.inputType) != 0
        keyboard?.setAutoShift(caps)
    }

    private fun moveCursor(steps: Int) {
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    /** Deletes back to the start of the previous word, spaces after it included. */
    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) return backspace()
        val before = ic.getTextBeforeCursor(64, 0) ?: return
        var start = before.length
        while (start > 0 && before[start - 1].isWhitespace()) start--
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val count = before.length - start
        if (count > 0) ic.deleteSurroundingText(count, 0) else backspace()
    }

    private fun isNumeric(info: EditorInfo?): Boolean = when ((info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }

    /** Ordinary text, as opposed to a URL, an address, or a password, where ". " would be wrong. */
    private fun isProse(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> false
            else -> true
        }
    }

    private fun enterAction(info: EditorInfo?): KeyboardView.Enter {
        val options = info?.imeOptions ?: return KeyboardView.Enter.NEWLINE
        if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return KeyboardView.Enter.NEWLINE
        return when (options and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> KeyboardView.Enter.GO
            EditorInfo.IME_ACTION_SEARCH -> KeyboardView.Enter.SEARCH
            EditorInfo.IME_ACTION_SEND -> KeyboardView.Enter.SEND
            EditorInfo.IME_ACTION_NEXT -> KeyboardView.Enter.NEXT
            EditorInfo.IME_ACTION_DONE -> KeyboardView.Enter.DONE
            else -> KeyboardView.Enter.NEWLINE
        }
    }

    private fun pressEnter() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun backToKeyboard() {
        if (state == State.RECORDING) discard()
        val switched = runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
        if (!switched) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // --- state -----------------------------------------------------------------------------------

    private fun hasMic() = Setup.hasMic(this)

    private fun isReady() = hasMic() && Setup.hasKey(this)

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "Listening · 0:07" in the toolbar, so a long dictation never loses track of itself. */
    private fun renderTimer() {
        val seconds = (SystemClock.elapsedRealtime() - sessionStartedAt) / 1000
        val text = getString(R.string.status_listening, "%d:%02d".format(seconds / 60, seconds % 60))
        if (status?.text?.toString() != text) status?.text = text
    }

    private fun render(message: String? = null) {
        pill?.mode = when (state) {
            State.IDLE -> StartPillView.Mode.IDLE
            State.RECORDING -> StartPillView.Mode.RECORDING
            State.TRANSCRIBING -> StartPillView.Mode.BUSY
        }
        // Recording changes the toolbar and nothing else: the keys stay put and stay live.
        val live = state == State.RECORDING
        if (live != barLive) {
            barLive = live
            bar?.let { TransitionManager.beginDelayedTransition(it, barTransition) }
            menuButton?.visibility = if (live) View.GONE else View.VISIBLE
            cancelButton?.visibility = if (live) View.VISIBLE else View.GONE
        }

        val pending = pendingInsert
        if (live && message == null) {
            renderTimer()
            reportBusy()
            return
        }
        status?.text = message ?: when {
            pending != null -> getString(R.string.status_pending, pending.split(' ').size)
            state == State.TRANSCRIBING -> getString(R.string.status_transcribing)
            errorMessage != null -> errorMessage
            !hasMic() -> getString(R.string.status_no_mic)
            !Setup.hasKey(this) -> getString(R.string.status_no_key)
            else -> getString(R.string.status_idle)
        }
        reportBusy()
    }

    /** An update installs by replacing this process, so it waits until the keyboard is put away. */
    private fun reportBusy() = Updater.setKeyboardBusy(this, attached || state != State.IDLE)
}
