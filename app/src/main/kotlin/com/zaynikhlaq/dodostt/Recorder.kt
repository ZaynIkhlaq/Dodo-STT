package com.zaynikhlaq.dodostt

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** One finished chunk of speech, ready to send. */
class Segment(val file: File, val durationMs: Long, val peak: Int)

/**
 * Records a dictation as a run of segments rather than one clip, cutting at pauses so each piece can
 * be transcribed while the next is still being spoken.
 *
 * MediaRecorder cannot split a stream, so a cut stops one recorder and starts another. That loses
 * roughly 150 ms at the boundary — harmless, because cuts are only ever made during silence.
 */
class Recorder(private val context: Context) {
    private companion object {
        /** Amplitude below which we call it silence, on MediaRecorder's 0..32767 scale. */
        const val SILENCE = 900
    }

    private var recorder: MediaRecorder? = null
    private var segmentStartedAt = 0L
    private var lastLoudAt = 0L
    private var index = 0

    /** Loudest sample in the current segment; used to drop segments that hold no speech. */
    var peak = 0
        private set

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds since the current segment began. */
    val segmentMs: Long
        get() = if (recorder == null) 0 else SystemClock.elapsedRealtime() - segmentStartedAt

    /** Milliseconds since the input was last above [SILENCE]. */
    val silentMs: Long
        get() = if (recorder == null) 0 else SystemClock.elapsedRealtime() - lastLoudAt

    /** True once this segment has heard anything worth sending. */
    val hasSpeech: Boolean get() = peak >= SILENCE

    private fun fileFor(i: Int) = File(context.cacheDir, "seg-$i.m4a")

    /** Throws if the microphone can't be opened (e.g. another app holds it). */
    fun start() {
        cancel()
        // Sweep anything a previous session left behind after a crash.
        context.cacheDir.listFiles { f -> f.name.startsWith("seg-") }?.forEach { it.delete() }
        index = 0
        open()
    }

    private fun open() {
        val target = fileFor(index)
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            throw e
        }
        recorder = r
        val now = SystemClock.elapsedRealtime()
        segmentStartedAt = now
        lastLoudAt = now
        peak = 0
    }

    /** Current input level, 0..1. Also tracks [peak] and the silence clock. */
    fun level(): Float {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amp > peak) peak = amp
        if (amp >= SILENCE) lastLoudAt = SystemClock.elapsedRealtime()
        return (amp / 32767f).coerceIn(0f, 1f)
    }

    /** Ends the current segment and immediately begins the next. Null if this one held nothing usable. */
    fun cut(): Segment? {
        val done = close()
        index++
        // If the microphone can't be reopened, isRecording goes false and the IME notices.
        runCatching { open() }
        return done
    }

    /** Ends the current segment and stops recording. */
    fun finish(): Segment? = close()

    private fun close(): Segment? {
        val r = recorder ?: return null
        val duration = segmentMs
        val capturedPeak = peak
        recorder = null
        level()
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val target = fileFor(index)
        if (!ok || duration < 400 || capturedPeak < SILENCE) {
            target.delete()
            return null
        }
        return Segment(target, duration, capturedPeak)
    }

    fun cancel() {
        val r = recorder
        recorder = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        // Segments still in flight own their own files and delete them after upload; only the one
        // being written right now is ours to remove.
        fileFor(index).delete()
    }
}
