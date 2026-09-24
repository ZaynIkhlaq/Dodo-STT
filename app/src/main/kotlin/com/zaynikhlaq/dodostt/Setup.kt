package com.zaynikhlaq.dodostt

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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

    /** Samsung's battery saver stops a service it thinks is idle, and the tile goes with it. */
    fun batteryUnrestricted(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /**
     * Straight to Vodo's own page in the accessibility list, rather than the list itself. Android 12
     * has an intent for exactly this; older versions get the list with the fragment arguments that
     * most skins honour anyway.
     */
    fun accessibilityIntent(c: Context): Intent {
        val component = ComponentName(c, DodoAccessibility::class.java).flattenToString()
        if (Build.VERSION.SDK_INT >= 31) {
            val direct = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            if (direct.resolveActivity(c.packageManager) != null) return direct
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                putString(":settings:fragment_args_key", component)
            })
    }

    fun overlayIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))

    @Suppress("BatteryLife")
    fun batteryIntent(c: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))
}
