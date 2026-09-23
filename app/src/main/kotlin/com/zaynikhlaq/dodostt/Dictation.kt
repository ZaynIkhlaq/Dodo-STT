package com.zaynikhlaq.dodostt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * One dictation, from the first word to the last bit of text landing in the field.
 *
 * Speech is cut into segments at pauses and each one is transcribed while the next is still being
 * spoken, so text arrives a few seconds behind the voice instead of all at once at the end. Results
 * can come back out of order, so they are committed by sequence number and held until their
 * predecessors land; errors take their turn in that queue too, or one failed segment would stall
 * everything behind it forever.
 *
 * Knows nothing about where the text goes — [Sink] does that.
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
         * The session is over. [stranded] is text that never found a field — the phone locked
         * mid-sentence, say — and is the caller's to offer or drop.
         */
        fun onFinished(delivered: Int, stranded: String?, error: String?)
    }

    private companion object {
        /** A segment has to be worth sending before a pause can end it. */
        const val MIN_SEGMENT_MS = 1200L
        /** Silence this long ends a segment. Roughly the gap between sentences. */
        const val PAUSE_MS = 700L
        /** Someone talking without pausing still gets text; this bounds how far behind it runs. */
        const val MAX_SEGMENT_MS = 18_000L
        /** With nowhere to put text, this much silence means the phone was put down. */
        const val ABANDONED_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recorder = Recorder(context)

    var state = State.IDLE
        private set

    /** Bumped whenever a session ends, so late transcripts can't be typed into the wrong place. */
    private var generation = 0
    private var startedAt = 0L

    private var nextSeq = 0
    private var nextToCommit = 0
    private val ready = HashMap<Int, String>()
    private val awaiting = HashSet<Int>()
    private var delivered = 0
    private var errorMessage: String? = null
    private val stranded = StringBuilder()

    /** How long the current session has been running. */
    val elapsedMs: Long get() = if (state == State.IDLE) 0 else SystemClock.elapsedRealtime() - startedAt

    private val meter = object : Runnable {
        override fun run() {
            if (state != State.RECORDING) return
            if (!recorder.isRecording) return stop()
            val level = recorder.level()
            sink.onLevel(level)
            considerCut()
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
        nextSeq = 0
        nextToCommit = 0
        delivered = 0
        ready.clear()
        awaiting.clear()
        stranded.setLength(0)
        errorMessage = null
        startedAt = SystemClock.elapsedRealtime()
        MicService.start(context)
        setState(State.RECORDING)
        handler.post(meter)
        return true
    }

    /** Ends the recording and transcribes what's left. */
    fun stop() {
        if (state != State.RECORDING) return
        handler.removeCallbacks(meter)
        send(recorder.finish())
        MicService.stop(context)
        setState(State.TRANSCRIBING)
        // Nothing outstanding means this drops straight back to idle.
        flush()
    }

    /** Throws the session away: the recording, anything in flight, and anything already held. */
    fun discard() {
        generation++
        handler.removeCallbacks(meter)
        recorder.cancel()
        MicService.stop(context)
        ready.clear()
        awaiting.clear()
        stranded.setLength(0)
        nextSeq = 0
        nextToCommit = 0
        errorMessage = null
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

    /** Ends a segment at a pause, or when one has run long enough to be worth sending regardless. */
    private fun considerCut() {
        val atPause = recorder.segmentMs >= MIN_SEGMENT_MS && recorder.hasSpeech && recorder.silentMs >= PAUSE_MS
        if (atPause || recorder.segmentMs >= MAX_SEGMENT_MS) send(recorder.cut())
        if (!sink.canReceive && recorder.silentMs >= ABANDONED_MS) stop()
    }

    private fun send(segment: Segment?) {
        val seg = segment ?: return
        val seq = nextSeq++
        val ticket = generation
        awaiting.add(seq)
        GroqClient.transcribe(Prefs.apiKey(context), Prefs.model(context), Prefs.language(context), seg.file) { result ->
            seg.file.delete()
            if (ticket != generation) return@transcribe
            awaiting.remove(seq)
            when (result) {
                is GroqClient.Result.Ok -> ready[seq] = result.text
                is GroqClient.Result.Err -> {
                    errorMessage = result.message
                    ready[seq] = ""
                }
            }
            flush()
        }
    }

    /** Commits whatever is now contiguous from the front of the queue, so text never lands out of order. */
    private fun flush() {
        while (ready.containsKey(nextToCommit)) {
            val text = ready.remove(nextToCommit)!!
            nextToCommit++
            if (text.isEmpty()) continue
            if (sink.canReceive && sink.insert(text)) {
                delivered++
            } else {
                if (stranded.isNotEmpty()) stranded.append(' ')
                stranded.append(text)
            }
        }
        if (state == State.TRANSCRIBING && awaiting.isEmpty() && ready.isEmpty()) {
            setState(State.IDLE)
            val held = stranded.toString().takeIf { it.isNotEmpty() }
            stranded.setLength(0)
            sink.onFinished(delivered, held, errorMessage)
        }
    }
}
