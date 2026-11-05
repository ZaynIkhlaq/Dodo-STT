package com.zaynikhlaq.dodostt

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** The things that have to be true before Doodoo can type a word. */
object Setup {
    fun hasMic(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True once the user has switched Doodoo's bar on under Accessibility. */
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
     * Straight to Doodoo's own page in the accessibility list, rather than the list itself. Android 12
     * has an intent for exactly this; older versions get the list with the fragment arguments that
     * most skins honour anyway.
     */
    fun accessibilityIntent(c: Context): Intent {
        val component = ComponentName(c, DodoAccessibility::class.java).flattenToString()
        if (Build.VERSION.SDK_INT >= 31) {
            // Not in the SDK's constants, but it is the documented action and Samsung honours it.
            val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            if (direct.resolveActivity(c.packageManager) != null) return direct
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                putString(":settings:fragment_args_key", component)
            })
    }

    /**
     * Opens the accessibility switch, but only after the user has agreed to what the service does.
     * Onboarding's own step is that disclosure; from anywhere else it is shown here first.
     */
    fun openAccessibility(a: Activity) {
        val open = { runCatching { a.startActivity(accessibilityIntent(a)) } }
        if (Prefs.accessibilityConsented(a)) {
            open()
            return
        }
        AlertDialog.Builder(a)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.ob_bar_body)
            .setPositiveButton(R.string.consent_agree) { _, _ ->
                Prefs.setAccessibilityConsented(a)
                open()
            }
            .setNegativeButton(R.string.consent_later, null)
            .show()
    }

    fun overlayIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))

    /**
     * The GitHub build asks outright. Play only allows that permission for a short list of app
     * types, so the Play build opens the app's own page instead, where Battery is one tap away.
     */
    @Suppress("BatteryLife")
    fun batteryIntent(c: Context): Intent =
        Intent(
            if (BuildConfig.PLAY) Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${c.packageName}"),
        )
}
