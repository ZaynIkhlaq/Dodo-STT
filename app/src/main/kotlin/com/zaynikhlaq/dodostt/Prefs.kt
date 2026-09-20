package com.zaynikhlaq.dodostt

import android.content.Context
import android.content.SharedPreferences

/** The handful of settings the app has, stored in app-private preferences. */
object Prefs {
    const val MODEL_TURBO = "whisper-large-v3-turbo"
    const val MODEL_LARGE = "whisper-large-v3"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("dodo", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "").orEmpty().trim()
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("api_key", v.trim()).apply()

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
