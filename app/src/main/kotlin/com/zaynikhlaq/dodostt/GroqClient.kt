package com.zaynikhlaq.dodostt

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/** Minimal client for Groq's OpenAI-compatible speech-to-text endpoint. No third-party HTTP library. */
object GroqClient {
    private const val BASE = "https://api.groq.com/openai/v1"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val message: String) : Result()
    }

    /** Uploads [file] and calls [callback] on the main thread with the transcript or an error. */
    fun transcribe(apiKey: String, model: String, language: String, file: File, callback: (Result) -> Unit) {
        executor.execute {
            val result = runCatching { doTranscribe(apiKey, model, language, file) }
                .getOrElse { Result.Err(it.message ?: it.javaClass.simpleName) }
            main.post { callback(result) }
        }
    }

    /** Cheap authenticated call to check that a key is valid. */
    fun testKey(apiKey: String, callback: (Result) -> Unit) {
        executor.execute {
            val result = runCatching {
                val conn = (URL("$BASE/models").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                try {
                    if (conn.responseCode in 200..299) Result.Ok("") else Result.Err(errorMessage(conn))
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { Result.Err(it.message ?: it.javaClass.simpleName) }
            main.post { callback(result) }
        }
    }

    private fun doTranscribe(apiKey: String, model: String, language: String, file: File): Result {
        val boundary = "----dodo" + UUID.randomUUID().toString().replace("-", "")
        val fields = linkedMapOf("model" to model, "response_format" to "json", "temperature" to "0")
        if (language.isNotBlank()) fields["language"] = language

        val head = StringBuilder()
        for ((name, value) in fields) {
            head.append("--").append(boundary).append("\r\n")
            head.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
            head.append(value).append("\r\n")
        }
        head.append("--").append(boundary).append("\r\n")
        head.append("Content-Disposition: form-data; name=\"file\"; filename=\"dictation.m4a\"\r\n")
        head.append("Content-Type: audio/mp4\r\n\r\n")
        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        val tailBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val conn = (URL("$BASE/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(headBytes.size.toLong() + file.length() + tailBytes.size)
        }
        try {
            conn.outputStream.use { out ->
                out.write(headBytes)
                file.inputStream().use { it.copyTo(out) }
                out.write(tailBytes)
            }
            if (conn.responseCode !in 200..299) return Result.Err(errorMessage(conn))
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return Result.Ok(JSONObject(body).optString("text").trim())
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val detail = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            401 -> "Groq rejected the API key"
            429 -> "Groq rate limit reached — wait a moment"
            else -> detail ?: "Groq error $code"
        }
    }
}
