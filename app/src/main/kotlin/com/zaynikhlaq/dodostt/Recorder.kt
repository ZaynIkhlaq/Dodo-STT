package com.zaynikhlaq.dodostt

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Records one dictation to a small AAC file in the cache dir. */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L

    /** Loudest sample seen in the current recording (0..32767); used to skip silent clips. */
    var peak = 0
        private set

    val file: File get() = File(context.cacheDir, "dictation.m4a")
    val isRecording: Boolean get() = recorder != null
    val elapsedMs: Long get() = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Throws if the microphone can't be opened (e.g. another app holds it). */
    fun start() {
        cancel()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            throw e
        }
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        peak = 0
    }

    /** Current input level, 0..1. Also tracks [peak]. */
    fun level(): Float {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amp > peak) peak = amp
        return (amp / 32767f).coerceIn(0f, 1f)
    }

    /** Stops and returns the clip length in ms, or -1 if nothing usable was captured. */
    fun stop(): Long {
        val r = recorder ?: return -1
        val length = elapsedMs
        recorder = null
        level()
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok) {
            file.delete()
            return -1
        }
        return length
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
