package com.zaynikhlaq.dodostt

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * One dictation: hold the button, talk, let go, and the whole clip goes to Groq in a single request.
 *
 * Knows nothing about where the text ends up — [Sink] does that.
 */
class Dictation(private val context: Context, private val sink: Sink) {

    enum class State { IDLE, RECORDING, TRANSCRIBING }

    interface Sink {
        /** Whether there is somewhere to put text at this moment. */
        val canReceive: Boolean

        fun onState(state: State)

        /** The live input level, 0..1, roughly every 60 ms while recording. */
        fun onLevel(level: Float)

        /** Puts [text] in the field. False means it couldn't land, and it will be held instead. */
        fun insert(text: String): Boolean

        /**
         * The dictation is over. [stranded] is text that never found a field — the phone locked
         * mid-sentence, say — and is the caller's to offer or drop.
         */
        fun onFinished(delivered: Boolean, stranded: String?, error: String?)
    }

    private companion object {
        /** With nowhere to put text, this much silence means the phone was put down. */
        const val ABANDONED_MS = 30_000L
        /** Groq takes 25 MB; at this bitrate that is hours. Stop well before anything breaks. */
        const val MAX_MS = 10 * 60 * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recorder = Recorder(context)

    var state = State.IDLE
        private set

    /** Bumped whenever a dictation ends, so a late transcript can't be typed into the wrong place. */
    private var generation = 0

    /** How long the current recording has been running. */
    val elapsedMs: Long get() = recorder.elapsedMs

    private val meter = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            if (!recorder.isRecording) return stop()
            sink.onLevel(recorder.level())
            val abandoned = !sink.canReceive && recorder.silentMs >= ABANDONED_MS
            if (abandoned || recorder.elapsedMs >= MAX_MS) return stop()
            handler.postDelayed(this, 60)
        }
    }

    /** Starts recording. False if the microphone can't be opened, which usually means another app has it. */
    fun start(): Boolean {
        if (state != State.IDLE) return true
        try {
            recorder.start()
        } catch (e: Exception) {
            return false
        }
        generation++
        MicService.start(context)
        setState(State.RECORDING)
        handler.post(meter)
        return true
    }

    /** Ends the recording and sends it to be transcribed. */
    fun stop() {
        if (state != State.RECORDING) return
        handler.removeCallbacks(meter)
        MicService.stop(context)
        val clip = recorder.finish()
        if (clip == null) {
            setState(State.IDLE)
            sink.onFinished(delivered = false, stranded = null, error = null)
            return
        }
        setState(State.TRANSCRIBING)
        val ticket = generation
        GroqClient.transcribe(Prefs.apiKey(context), Prefs.model(context), Prefs.language(context), clip.file) { result ->
            clip.file.delete()
            if (ticket != generation) return@transcribe
            setState(State.IDLE)
            when (result) {
                is GroqClient.Result.Ok -> {
                    val text = result.text
                    when {
                        text.isEmpty() -> sink.onFinished(delivered = false, stranded = null, error = null)
                        sink.canReceive && sink.insert(text) ->
                            sink.onFinished(delivered = true, stranded = null, error = null)
                        // Nowhere to put it: hand it back rather than drop it on the floor.
                        else -> sink.onFinished(delivered = false, stranded = text, error = null)
                    }
                }
                is GroqClient.Result.Err ->
                    sink.onFinished(delivered = false, stranded = null, error = result.message)
            }
        }
    }

    /** Throws the recording away. */
    fun discard() {
        generation++
        handler.removeCallbacks(meter)
        recorder.cancel()
        MicService.stop(context)
        setState(State.IDLE)
    }

    fun destroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        recorder.cancel()
        MicService.stop(context)
    }

    private fun setState(value: State) {
        if (state == value) return
        state = value
        sink.onState(value)
    }
}
