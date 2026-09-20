package com.zaynikhlaq.dodostt

import android.content.Context
import android.content.SharedPreferences

/** The handful of settings the app has, stored in app-private preferences. */
object Prefs {
    const val MODEL_TURBO = "whisper-large-v3-turbo"
    const val MODEL_LARGE = "whisper-large-v3"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("dodo", Context.MODE_PRIVATE)

    /** Decrypted once per process; the IME reads this on every dictation. */
    @Volatile private var cachedKey: String? = null

    fun apiKey(c: Context): String {
        cachedKey?.let { return it }
        val prefs = sp(c)
        // Builds before 1.0.2 stored the key as plain text: move it into the vault on first read.
        prefs.getString("api_key", null)?.let { legacy ->
            setApiKey(c, legacy)
            return legacy.trim()
        }
        val key = prefs.getString("api_key_enc", null)?.let(KeyVault::decrypt).orEmpty()
        cachedKey = key
        return key
    }

    fun setApiKey(c: Context, v: String) {
        val key = v.trim()
        cachedKey = key
        val edit = sp(c).edit().remove("api_key")
        if (key.isEmpty()) {
            edit.remove("api_key_enc")
        } else {
            // If the keystore is unavailable the key is kept for this session only rather than written in the clear.
            KeyVault.encrypt(key)?.let { edit.putString("api_key_enc", it) } ?: edit.remove("api_key_enc")
        }
        edit.apply()
    }

    fun model(c: Context): String = sp(c).getString("model", MODEL_TURBO) ?: MODEL_TURBO
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v).apply()

    /** ISO code sent to Whisper, or "" to let it detect the language. */
    fun language(c: Context): String = sp(c).getString("language", "en").orEmpty()
    fun setLanguage(c: Context, v: String) = sp(c).edit().putString("language", v).apply()

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("auto_start", true)
    fun setAutoStart(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_start", v).apply()

    fun autoReturn(c: Context): Boolean = sp(c).getBoolean("auto_return", false)
    fun setAutoReturn(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_return", v).apply()
}
