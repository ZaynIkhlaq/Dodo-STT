package com.zaynikhlaq.dodostt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Setup as five steps rather than one long screen: welcome, microphone, keyboard list, Groq key,
 * and how to actually reach the panel. Only one step is on screen at a time, and the two steps that
 * depend on a system dialog advance themselves once the user comes back having done the thing.
 */
class OnboardingActivity : Activity() {
    private companion object {
        const val WELCOME = 0
        const val MIC = 1
        const val KEYBOARD = 2
        const val KEY = 3
        const val DONE = 4
        const val MIC_REQUEST = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var stepViews: List<View>
    private lateinit var segments: List<View>
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var keyField: EditText
    private lateinit var keyState: TextView

    private var step = WELCOME
    private var askedForMic = false

    /**
     * The step that is waiting on a system dialog, or -1. Only this step may advance itself, so
     * stepping back onto an already-satisfied step doesn't bounce the user forward again.
     */
    private var armed = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        stepViews = listOf(
            findViewById(R.id.step_welcome),
            findViewById(R.id.step_mic),
            findViewById(R.id.step_keyboard),
            findViewById(R.id.step_key),
            findViewById(R.id.step_done),
        )
        segments = listOf(
            findViewById(R.id.seg_0),
            findViewById(R.id.seg_1),
            findViewById(R.id.seg_2),
            findViewById(R.id.seg_3),
            findViewById(R.id.seg_4),
        )
        primary = findViewById(R.id.action_primary)
        secondary = findViewById(R.id.action_secondary)
        keyField = findViewById(R.id.key)
        keyState = findViewById(R.id.key_state)

        primary.setOnClickListener { onPrimary() }
        secondary.setOnClickListener { goTo(step + 1) }

        keyField.setText(Prefs.apiKey(this))
        keyField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitKey() }
        // Typing unlocks Continue. The key itself is only written to the vault on focus loss,
        // Test, or pause — encrypting it on every keystroke would be a Keystore round trip a
        // character.
        keyField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        findViewById<Button>(R.id.key_test).setOnClickListener { testKey() }
        findViewById<Button>(R.id.key_get).setOnClickListener { open("https://console.groq.com/keys") }
        findViewById<Button>(R.id.switch_keyboard).setOnClickListener {
            findViewById<EditText>(R.id.try_field).requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        commitKey()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Coming back from the system keyboard settings is the moment to check whether it worked.
        if (hasFocus) {
            render()
            if (armed == KEYBOARD && step == KEYBOARD && Setup.imeEnabled(this)) advanceShortly()
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        render()
        if (armed == MIC && step == MIC && Setup.hasMic(this)) advanceShortly()
    }

    override fun onBackPressed() {
        if (step == WELCOME) super.onBackPressed() else goTo(step - 1)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- flow ------------------------------------------------------------------------------------

    private fun onPrimary() = when (step) {
        MIC -> if (Setup.hasMic(this)) goTo(step + 1) else askForMic()
        KEYBOARD -> if (Setup.imeEnabled(this)) goTo(step + 1) else openKeyboardSettings()
        DONE -> finishOnboarding()
        else -> goTo(step + 1)
    }

    /** Lets the green tick register before the step slides away. */
    private fun advanceShortly() {
        armed = -1
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (step < DONE) goTo(step + 1) }, 520)
    }

    private fun goTo(target: Int) {
        val next = target.coerceIn(WELCOME, DONE)
        if (next == step) return
        if (step == KEY) commitKey()

        val leaving = stepViews[step]
        val entering = stepViews[next]
        val drift = 14f * resources.displayMetrics.density * if (next > step) 1f else -1f

        leaving.animate().alpha(0f).setDuration(110).withEndAction {
            leaving.visibility = View.GONE
            leaving.alpha = 1f
            leaving.translationY = 0f
        }.start()

        entering.alpha = 0f
        entering.translationY = drift
        entering.visibility = View.VISIBLE
        entering.animate().alpha(1f).translationY(0f).setStartDelay(90).setDuration(230).start()

        step = next
        armed = when {
            next == MIC && !Setup.hasMic(this) -> MIC
            next == KEYBOARD && !Setup.imeEnabled(this) -> KEYBOARD
            else -> -1
        }
        render()
    }

    private fun finishOnboarding() {
        commitKey()
        Prefs.setOnboarded(this)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }

    // --- rendering -------------------------------------------------------------------------------

    private fun render() {
        val accent = ColorStateList.valueOf(getColor(R.color.accent))
        val idle = ColorStateList.valueOf(getColor(R.color.hairline))
        segments.forEachIndexed { i, seg -> seg.backgroundTintList = if (i <= step) accent else idle }

        val micDone = Setup.hasMic(this)
        val imeDone = Setup.imeEnabled(this)
        findViewById<View>(R.id.mic_ok).visibility = if (micDone) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.kb_ok).visibility = if (imeDone) View.VISIBLE else View.INVISIBLE

        primary.text = when (step) {
            WELCOME -> getString(R.string.ob_start)
            MIC -> getString(if (micDone) R.string.ob_continue else R.string.ob_mic_action)
            KEYBOARD -> getString(if (imeDone) R.string.ob_continue else R.string.ob_kb_action)
            KEY -> getString(R.string.ob_continue)
            else -> getString(R.string.ob_done_action)
        }

        // On the key step there is nothing to continue to until a key is present; "Skip for now" is
        // the way past it, so the primary action stays inert rather than silently doing nothing.
        val keyBlocked = step == KEY && keyField.text.isNullOrBlank()
        primary.isEnabled = !keyBlocked
        primary.alpha = if (keyBlocked) 0.4f else 1f

        val skippable = step == MIC && !micDone || step == KEYBOARD && !imeDone || keyBlocked
        secondary.visibility = if (skippable) View.VISIBLE else View.GONE
    }

    // --- steps -----------------------------------------------------------------------------------

    private fun askForMic() {
        // After a hard "don't allow" Android stops showing the dialog, so send the user to app settings.
        if (askedForMic && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            primary.text = getString(R.string.ob_mic_settings)
            open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        } else {
            askedForMic = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
    }

    private fun openKeyboardSettings() = open(Settings.ACTION_INPUT_METHOD_SETTINGS, null)

    private fun commitKey() {
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
            render()
        }
    }

    private fun state(text: String, color: Int) {
        keyState.text = text
        keyState.setTextColor(getColor(color))
    }

    private fun open(action: String, data: Uri?) {
        runCatching { startActivity(Intent(action).apply { data?.let { setData(it) } }) }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
