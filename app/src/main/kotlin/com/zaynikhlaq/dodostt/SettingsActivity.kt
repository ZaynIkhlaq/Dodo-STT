package com.zaynikhlaq.dodostt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * The settings screen, and the app's launcher entry. On a first run it hands straight over to
 * [OnboardingActivity]; after that it shows only what is actually configurable, plus a
 * "needs attention" block that disappears entirely once nothing does.
 */
class SettingsActivity : Activity() {
    private lateinit var setupLabel: View
    private lateinit var setupGroup: View
    private lateinit var setupDivider: View
    private lateinit var micRow: View
    private lateinit var barRow: View
    private lateinit var recordRow: View
    private lateinit var recordDivider: View
    private lateinit var micState: TextView
    private lateinit var barState: TextView
    private lateinit var recordState: TextView
    private lateinit var keyField: EditText
    private lateinit var keyState: TextView
    private lateinit var updateTitle: TextView
    private lateinit var updateState: TextView
    private lateinit var updateAction: Button

    private var askedForMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.onboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
            return
        }
        setContentView(R.layout.activity_settings)

        setupLabel = findViewById(R.id.setup_label)
        setupGroup = findViewById(R.id.setup_group)
        setupDivider = findViewById(R.id.setup_divider)
        micRow = findViewById(R.id.mic_row)
        barRow = findViewById(R.id.bar_row)
        recordRow = findViewById(R.id.record_row)
        recordDivider = findViewById(R.id.record_divider)
        micState = findViewById(R.id.mic_state)
        barState = findViewById(R.id.bar_state)
        recordState = findViewById(R.id.record_state)
        keyField = findViewById(R.id.key)
        keyState = findViewById(R.id.key_state)
        updateTitle = findViewById(R.id.update_title)
        updateState = findViewById(R.id.update_state)
        updateAction = findViewById(R.id.update_action)

        Updater.schedule(this)
        updateAction.setOnClickListener {
            if (!Updater.canInstall(this)) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    )
                }
            } else {
                Updater.checkNow(this) { if (!isDestroyed) renderUpdates() }
                renderUpdates()
            }
        }

        micState.setText(R.string.step_mic_why)
        barState.setText(R.string.step_bar_why)
        recordState.setText(R.string.step_record_why)

        findViewById<Button>(R.id.mic_action).setOnClickListener { askForMic() }
        findViewById<Button>(R.id.bar_action).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        findViewById<Button>(R.id.record_action).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }

        // The key is written to the vault on focus loss, on Test, and on pause — not per keystroke.
        keyField.setText(Prefs.apiKey(this))
        keyField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitKey() }
        findViewById<Button>(R.id.key_test).setOnClickListener { testKey() }

        val model = findViewById<SegmentedControl>(R.id.model_segments)
        model.setOptions(
            listOf(getString(R.string.model_turbo), getString(R.string.model_large)),
            if (Prefs.model(this) == Prefs.MODEL_LARGE) 1 else 0,
        )
        model.onSelect = { Prefs.setModel(this, if (it == 1) Prefs.MODEL_LARGE else Prefs.MODEL_TURBO) }

        val language = findViewById<SegmentedControl>(R.id.language_segments)
        language.setOptions(
            listOf(getString(R.string.language_en), getString(R.string.language_auto)),
            if (Prefs.language(this).isEmpty()) 1 else 0,
        )
        language.onSelect = { Prefs.setLanguage(this, if (it == 1) "" else "en") }
    }

    /** False on the run that bounced to onboarding, where setContentView never happened. */
    private val inflated get() = ::keyField.isInitialized

    override fun onResume() {
        super.onResume()
        if (inflated) refresh()
    }

    override fun onPause() {
        super.onPause()
        commitKey()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && inflated) refresh()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (inflated) refresh()
    }

    /** Shows only the setup rows that still need doing, and hides the section when none do. */
    private fun refresh() {
        val micTodo = !Setup.hasMic(this)
        val barTodo = !Setup.barOn(this)
        val recordTodo = !Setup.canRecordInBackground(this)
        micRow.visibility = if (micTodo) View.VISIBLE else View.GONE
        barRow.visibility = if (barTodo) View.VISIBLE else View.GONE
        recordRow.visibility = if (recordTodo) View.VISIBLE else View.GONE
        setupDivider.visibility = if (micTodo && barTodo) View.VISIBLE else View.GONE
        recordDivider.visibility = if (recordTodo && (micTodo || barTodo)) View.VISIBLE else View.GONE
        val any = micTodo || barTodo || recordTodo
        setupLabel.visibility = if (any) View.VISIBLE else View.GONE
        setupGroup.visibility = if (any) View.VISIBLE else View.GONE
        renderUpdates()
    }

    private fun renderUpdates() {
        updateTitle.text = getString(R.string.update_version, Updater.installedName(this))
        val allowed = Updater.canInstall(this)
        val waiting = Updater.waiting
        val error = Updater.lastError
        updateState.text = when {
            !allowed -> getString(R.string.update_needs_permission)
            Updater.isChecking -> getString(R.string.update_checking)
            error != null -> getString(R.string.update_failed, error)
            waiting != null -> getString(R.string.update_waiting, waiting)
            Updater.checkedAt > 0 -> getString(
                R.string.update_current,
                DateUtils.getRelativeTimeSpanString(Updater.checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            else -> getString(R.string.update_auto)
        }
        updateState.setTextColor(getColor(if (!allowed || error != null) R.color.warn else R.color.text_secondary))
        updateAction.setText(if (allowed) R.string.update_check else R.string.update_allow)
        updateAction.isEnabled = !Updater.isChecking
    }

    private fun askForMic() {
        if (Setup.hasMic(this)) return
        // After a hard "don't allow", Android stops showing the dialog, so send the user to app settings.
        if (askedForMic && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        } else {
            askedForMic = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun commitKey() {
        if (!inflated) return
        val typed = keyField.text?.toString().orEmpty()
        if (typed.trim() != Prefs.apiKey(this)) Prefs.setApiKey(this, typed)
    }

    private fun testKey() {
        commitKey()
        val key = Prefs.apiKey(this)
        if (key.isEmpty()) return state(getString(R.string.key_missing), R.color.warn)
        state(getString(R.string.key_testing), R.color.text_secondary)
        GroqClient.testKey(key) { result ->
            when (result) {
                is GroqClient.Result.Ok -> state(getString(R.string.key_ok), R.color.ok)
                is GroqClient.Result.Err -> state(result.message, R.color.warn)
            }
        }
    }

    private fun state(text: String, color: Int) {
        keyState.text = text
        keyState.setTextColor(getColor(color))
    }
}
