package com.zaynikhlaq.dodostt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/** The only screen: two setup steps, the Groq key, and a few choices. */
class SettingsActivity : Activity() {
    private lateinit var micState: TextView
    private lateinit var imeState: TextView
    private lateinit var keyState: TextView
    private var askedForMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        micState = findViewById(R.id.mic_state)
        imeState = findViewById(R.id.ime_state)
        keyState = findViewById(R.id.key_state)

        findViewById<Button>(R.id.mic_action).setOnClickListener { askForMic() }
        findViewById<Button>(R.id.ime_action).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.switch_keyboard).setOnClickListener {
            findViewById<EditText>(R.id.try_field).requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        val key = findViewById<EditText>(R.id.key)
        key.setText(Prefs.apiKey(this))
        key.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                Prefs.setApiKey(this@SettingsActivity, s?.toString().orEmpty())
                keyState.text = ""
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        findViewById<Button>(R.id.key_test).setOnClickListener { testKey() }

        val models = findViewById<RadioGroup>(R.id.model_group)
        models.check(if (Prefs.model(this) == Prefs.MODEL_LARGE) R.id.model_large else R.id.model_turbo)
        models.setOnCheckedChangeListener { _, id ->
            Prefs.setModel(this, if (id == R.id.model_large) Prefs.MODEL_LARGE else Prefs.MODEL_TURBO)
        }

        val languages = findViewById<RadioGroup>(R.id.language_group)
        languages.check(if (Prefs.language(this).isEmpty()) R.id.language_auto else R.id.language_en)
        languages.setOnCheckedChangeListener { _, id ->
            Prefs.setLanguage(this, if (id == R.id.language_auto) "" else "en")
        }

        val autoStart = findViewById<Switch>(R.id.opt_autostart)
        autoStart.isChecked = Prefs.autoStart(this)
        autoStart.setOnCheckedChangeListener { _, on -> Prefs.setAutoStart(this, on) }

        val autoReturn = findViewById<Switch>(R.id.opt_autoreturn)
        autoReturn.isChecked = Prefs.autoReturn(this)
        autoReturn.setOnCheckedChangeListener { _, on -> Prefs.setAutoReturn(this, on) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askForMic() {
        if (hasMic()) return
        // After a hard "don't allow", Android stops showing the dialog, so send the user to app settings.
        if (askedForMic && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            askedForMic = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun testKey() {
        val key = Prefs.apiKey(this)
        if (key.isEmpty()) {
            show(keyState, getString(R.string.key_missing), false)
            return
        }
        keyState.setTextColor(getColor(R.color.panel_text))
        keyState.text = getString(R.string.key_testing)
        GroqClient.testKey(key) { result ->
            when (result) {
                is GroqClient.Result.Ok -> show(keyState, getString(R.string.key_ok), true)
                is GroqClient.Result.Err -> show(keyState, result.message, false)
            }
        }
    }

    private fun refresh() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        show(micState, getString(if (hasMic()) R.string.step_done else R.string.step_todo), hasMic())
        show(imeState, getString(if (enabled) R.string.step_done else R.string.step_todo), enabled)
    }

    private fun show(view: TextView, text: String, good: Boolean) {
        view.text = text
        view.setTextColor(getColor(if (good) R.color.ok else R.color.warn))
    }
}
