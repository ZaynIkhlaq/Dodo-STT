package com.zaynikhlaq.dodostt

import android.content.Intent
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView

/**
 * A voice-only input method: instead of keys it shows one mic. Speak, tap, and the Groq transcript is
 * typed into whatever text field is focused.
 */
class DodoIme : InputMethodService() {
    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recorder: Recorder
    private var state = State.IDLE
    /** Bumped whenever a recording is abandoned so a late transcript can't be typed into the wrong place. */
    private var generation = 0

    private var status: TextView? = null
    private var mic: MicButtonView? = null
    private var cancel: ImageButton? = null

    private val meter = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            mic?.setLevel(recorder.level())
            val seconds = recorder.elapsedMs / 1000
            status?.text = getString(R.string.status_listening, "%d:%02d".format(seconds / 60, seconds % 60))
            handler.postDelayed(this, 60)
        }
    }

    override fun onCreate() {
        super.onCreate()
        recorder = Recorder(this)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_panel, null)
        status = view.findViewById(R.id.status)
        mic = view.findViewById(R.id.mic)
        cancel = view.findViewById(R.id.btn_cancel)

        mic?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onMicTapped()
        }
        cancel?.setOnClickListener { discard() }
        status?.setOnClickListener { if (!isReady()) openSettings() }
        view.findViewById<ImageButton>(R.id.btn_keyboard).setOnClickListener { backToKeyboard() }
        view.findViewById<ImageButton>(R.id.btn_enter).setOnClickListener { pressEnter() }
        bindBackspace(view.findViewById(R.id.btn_backspace))
        render()
        return view
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        render()
        if (!restarting && state == State.IDLE && isReady() && Prefs.autoStart(this)) startRecording()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (state == State.RECORDING) discard()
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        recorder.cancel()
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
            render(getString(R.string.status_mic_busy))
            return
        }
        state = State.RECORDING
        render()
        handler.post(meter)
    }

    private fun finishRecording() {
        handler.removeCallbacks(meter)
        val length = recorder.stop()
        state = State.IDLE
        // Whisper invents text ("Thank you.") for silent audio, so don't send it.
        val rejected = when {
            length < 400 -> R.string.status_too_short
            recorder.peak < 700 -> R.string.status_silence
            else -> null
        }
        if (rejected != null) {
            recorder.file.delete()
            return render(getString(rejected))
        }

        state = State.TRANSCRIBING
        render()
        val ticket = ++generation
        GroqClient.transcribe(Prefs.apiKey(this), Prefs.model(this), Prefs.language(this), recorder.file) { result ->
            recorder.file.delete()
            if (ticket != generation) return@transcribe
            state = State.IDLE
            when (result) {
                is GroqClient.Result.Ok -> {
                    render()
                    if (result.text.isNotEmpty()) {
                        type(result.text)
                        if (Prefs.autoReturn(this)) handler.postDelayed({ backToKeyboard() }, 120)
                    }
                }
                is GroqClient.Result.Err -> render(result.message)
            }
        }
    }

    private fun discard() {
        generation++
        handler.removeCallbacks(meter)
        recorder.cancel()
        state = State.IDLE
        render()
    }

    /** Inserts the transcript, adding a leading space when it would otherwise run into the previous word. */
    private fun type(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'“‘/-"
        ic.commitText(if (needsSpace) " $text" else text, 1)
    }

    // --- keys ------------------------------------------------------------------------------------

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

    /** Tap deletes one character; holding repeats, like a normal keyboard. */
    private fun bindBackspace(button: ImageButton) {
        val repeat = object : Runnable {
            override fun run() {
                backspace()
                handler.postDelayed(this, 45)
            }
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    backspace()
                    handler.postDelayed(repeat, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                }
            }
            true
        }
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

    private fun render(message: String? = null) {
        mic?.mode = when (state) {
            State.IDLE -> MicButtonView.Mode.IDLE
            State.RECORDING -> MicButtonView.Mode.RECORDING
            State.TRANSCRIBING -> MicButtonView.Mode.BUSY
        }
        val live = state == State.RECORDING
        cancel?.visibility = if (live) View.VISIBLE else View.INVISIBLE
        cancel?.imageTintList = ColorStateList.valueOf(getColor(if (live) R.color.rec else R.color.text_secondary))
        status?.text = message ?: when {
            state == State.TRANSCRIBING -> getString(R.string.status_transcribing)
            state == State.RECORDING -> getString(R.string.status_listening, "0:00")
            !hasMic() -> getString(R.string.status_no_mic)
            !Setup.hasKey(this) -> getString(R.string.status_no_key)
            else -> getString(R.string.status_idle)
        }
    }
}
