package com.zaynikhlaq.dodostt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager

/** The three things that have to be true before Dodo can type a word. */
object Setup {
    fun hasMic(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True once the user has ticked Dodo in Android's keyboard list. */
    fun imeEnabled(c: Context): Boolean {
        val imm = c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == c.packageName }
    }

    fun hasKey(c: Context): Boolean = Prefs.apiKey(c).isNotEmpty()
}
