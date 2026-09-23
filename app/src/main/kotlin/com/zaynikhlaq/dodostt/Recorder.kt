package com.zaynikhlaq.dodostt

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** A finished recording, ready to send. */
class Clip(val file: File, val durationMs: Long)

/**
 * Records one clip of speech to a file.
 *
 * It used to cut the stream into segments at pauses so text could arrive while you were still
 * talking. That bought a few seconds and cost accuracy at every cut, since each piece was
 * transcribed with no idea what came before it. One clip, sent when you stop, is what Groq is good
 * at — a minute of audio comes back in about a second.
 */
class Recorder(private val context: Context) {
    private companion object {
        /** Amplitude below which we call it silence, on MediaRecorder's 0..32767 scale. */
        const val SILENCE = 900
        /** Anything shorter than this is a mis-tap, not a sentence. */
        const val TOO_SHORT_MS = 400L
    }

    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private var lastLoudAt = 0L

    /** Loudest sample so far; used to drop a recording that holds no speech. */
    private var peak = 0

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds since recording began. */
    val elapsedMs: Long
        get() = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Milliseconds since the input was last above [SILENCE]. */
    val silentMs: Long
        get() = if (recorder == null) 0 else SystemClock.elapsedRealtime() - lastLoudAt

    private val file get() = File(context.cacheDir, "dictation.m4a")

    /** Throws if the microphone can't be opened (e.g. another app holds it). */
    fun start() {
        cancel()
        val target = file
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
        startedAt = now
        lastLoudAt = now
        peak = 0
    }

    /** Current input level, 0..1. Also tracks the peak and the silence clock. */
    fun level(): Float {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amp > peak) peak = amp
        if (amp >= SILENCE) lastLoudAt = SystemClock.elapsedRealtime()
        return (amp / 32767f).coerceIn(0f, 1f)
    }

    /** Stops. Null when nothing worth sending was captured. */
    fun finish(): Clip? {
        val r = recorder ?: return null
        val duration = elapsedMs
        val loudest = peak
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val target = file
        if (!ok || duration < TOO_SHORT_MS || loudest < SILENCE) {
            target.delete()
            return null
        }
        return Clip(target, duration)
    }

    fun cancel() {
        val r = recorder
        recorder = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        file.delete()
    }
}
