package com.zaynikhlaq.dodostt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
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
    private lateinit var imeRow: View
    private lateinit var micState: TextView
    private lateinit var imeState: TextView
    private lateinit var keyField: EditText
    private lateinit var keyState: TextView

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
        imeRow = findViewById(R.id.ime_row)
        micState = findViewById(R.id.mic_state)
        imeState = findViewById(R.id.ime_state)
        keyField = findViewById(R.id.key)
        keyState = findViewById(R.id.key_state)

        micState.setText(R.string.step_mic_why)
        imeState.setText(R.string.step_enable_why)

        findViewById<Button>(R.id.mic_action).setOnClickListener { askForMic() }
        findViewById<Button>(R.id.ime_action).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        findViewById<Button>(R.id.switch_keyboard).setOnClickListener {
            findViewById<EditText>(R.id.try_field).requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
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

        val autoStart = findViewById<Switch>(R.id.opt_autostart)
        autoStart.isChecked = Prefs.autoStart(this)
        autoStart.setOnCheckedChangeListener { _, on -> Prefs.setAutoStart(this, on) }
        findViewById<View>(R.id.autostart_row).setOnClickListener { autoStart.toggle() }

        val autoReturn = findViewById<Switch>(R.id.opt_autoreturn)
        autoReturn.isChecked = Prefs.autoReturn(this)
        autoReturn.setOnCheckedChangeListener { _, on -> Prefs.setAutoReturn(this, on) }
        findViewById<View>(R.id.autoreturn_row).setOnClickListener { autoReturn.toggle() }
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
        val imeTodo = !Setup.imeEnabled(this)
        micRow.visibility = if (micTodo) View.VISIBLE else View.GONE
        imeRow.visibility = if (imeTodo) View.VISIBLE else View.GONE
        setupDivider.visibility = if (micTodo && imeTodo) View.VISIBLE else View.GONE
        val any = micTodo || imeTodo
        setupLabel.visibility = if (any) View.VISIBLE else View.GONE
        setupGroup.visibility = if (any) View.VISIBLE else View.GONE
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
