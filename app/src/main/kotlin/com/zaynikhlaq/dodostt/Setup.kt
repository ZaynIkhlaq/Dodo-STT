package com.zaynikhlaq.dodostt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** The things that have to be true before Dodo can type a word. */
object Setup {
    fun hasMic(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True once the user has switched Dodo's bar on under Accessibility. */
    fun barOn(c: Context): Boolean {
        val manager = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(0)
            .any { it.resolveInfo.serviceInfo.packageName == c.packageName }
    }

    /**
     * Android refuses a background app the microphone unless it holds this, so without it a
     * dictation started from someone else's app records silence.
     */
    fun canRecordInBackground(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun hasKey(c: Context): Boolean = Prefs.apiKey(c).isNotEmpty()
}
