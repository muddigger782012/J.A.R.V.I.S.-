package com.assistant.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.assistant.core.services.ApiKeyStore
import com.assistant.core.services.DirectOnlineClient
import com.assistant.core.services.JarvisAccessibilityService
import com.assistant.core.services.VoicePreferences
import com.google.android.material.button.MaterialButton

/** Resumable, optional first-run setup; Android permissions remain user controlled. */
class BetaIntroductionActivity : ThemedActivity() {
    private val setup by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private var voiceProgress: TextView? = null
    private val voiceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val voicePoll = object : Runnable {
        override fun run() {
            voiceProgress?.text = com.assistant.core.services.BundledVoicePreparation.status(this@BetaIntroductionActivity)
            voiceHandler.postDelayed(this, 1000)
        }
    }
    private var step = 0
    private var checking = false
    private var testReply = ""
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isBeta(this)) { finish(); return }
        step = setup.getInt("step", 0).coerceIn(0, 4)
        supportActionBar?.title = "Beta introduction"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val value = android.util.TypedValue()
        theme.resolveAttribute(R.attr.jarvis_background, value, true)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(if (value.resourceId != 0) ContextCompat.getColor(this@BetaIntroductionActivity, value.resourceId) else value.data)
            isFillViewport = true
            addView(content)
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (step > 0) move(-1) else defer() }
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        com.assistant.core.services.BundledVoicePreparation.ensure(applicationContext)
        if (::content.isInitialized) render()
        voiceHandler.removeCallbacks(voicePoll)
        if (com.assistant.core.BuildConfig.BUNDLED_NEURAL_VOICES) voiceHandler.post(voicePoll)
    }
    override fun onPause() { voiceHandler.removeCallbacks(voicePoll); super.onPause() }
    override fun onSupportNavigateUp(): Boolean { if (step > 0) move(-1) else defer(); return true }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun text(value: String, heading: Boolean = false) {
        content.addView(TextView(this).apply {
            text = value
            textSize = if (heading) 24f else 16f
            setTextColor(ThemePreferences.color(this@BetaIntroductionActivity,
                if (heading) R.color.jarvis_on_dark else R.color.jarvis_on_dark_muted))
            setPadding(0, dp(8), 0, dp(12))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, heading)
        })
    }
    private fun button(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        minHeight = dp(48)
        setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "This settings page is unavailable. Open Android Settings manually.", Toast.LENGTH_LONG).show()
        }
    }
    private fun move(delta: Int) {
        step = (step + delta).coerceIn(0, 4)
        setup.edit().putInt("step", step).apply()
        render()
    }
    private fun render() {
        content.removeAllViews()
        text("Step ${step + 1} of 5")
        voiceProgress = null
        if (com.assistant.core.BuildConfig.BUNDLED_NEURAL_VOICES) {
            text(com.assistant.core.services.BundledVoicePreparation.status(this))
            voiceProgress = content.getChildAt(content.childCount - 1) as TextView
            if (!com.assistant.core.services.NeuralVoicePack(this).installed()) {
                button("Prepare voices again") { com.assistant.core.services.BundledVoicePreparation.ensure(applicationContext) }
            }
        }
        when (step) {
            0 -> {
                text("Welcome to J.A.R.V.I.S. Beta", true)
                text("Your assistant for conversation, voice commands, web search and phone tasks. Beta receives the same updates as J.A.R.V.I.S.; its Gemini connection is preconfigured. You can use typed chat immediately and add voice when ready.")
                text("We'll help you set up voice, choose your appearance and enable the optional Android features you want. Nothing requires root or another assistant app.")
                text(if (ApiKeyStore(this).read().isNotBlank()) "Gemini key: configured. An internet connection is needed for AI answers." else "Gemini key: missing. Open Weather and AI in Settings to configure it; local commands still work.")
            }
            1 -> {
                text("Enable your microphone", true)
                text("Microphone access lets you use the Talk button and say Hey Jarvis. Android asks you to approve it. You can decline and continue using typed chat.")
                text("Microphone: " + if (granted(Manifest.permission.RECORD_AUDIO)) "allowed" else "not allowed")
                button(if (granted(Manifest.permission.RECORD_AUDIO)) "Microphone is enabled" else "Allow microphone") {
                    if (!granted(Manifest.permission.RECORD_AUDIO)) permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    text("Notifications: " + if (granted(Manifest.permission.POST_NOTIFICATIONS)) "allowed" else "not allowed")
                    button("Allow service notifications") { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                }
                button("Open app permissions") { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
            2 -> {
                text("Make Jarvis your own", true)
                text("Choose a theme, readable fonts and an assistant voice. Return here after saving your preferences. George, Lewis and Michael are included in this APK and prepare automatically. No separate voice download is needed.")
                button("Themes and appearance") { open(Intent(this, AppearanceSettingsActivity::class.java)) }
                button("Assistant voice and responses") { open(Intent(this, AssistantVoiceSettingsActivity::class.java)) }
                button("Practice recognition with my voice") { open(Intent(this, VoiceProfileActivity::class.java)) }
                button("Wake word and voice settings") { open(Intent(this, VoiceSettingsActivity::class.java)) }
                text("The default wake phrase is Hey Jarvis. Voice practice helps recognition hints; it does not clone your voice.")
            }
            3 -> {
                text("Optional background features", true)
                text("These features are optional. Enable only the ones you want; each opens Android's own settings. Return to this guide when finished.")
                text("Chat overlay: " + if (Settings.canDrawOverlays(this)) "allowed" else "not allowed")
                button("Allow chat over other apps") { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                val component = ComponentName(this, JarvisAccessibilityService::class.java).flattenToString()
                val accessibility = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty().split(':').any { it.equals(component, ignoreCase = true) }
                text("Jarvis Accessibility: " + if (accessibility) "enabled" else "not enabled")
                button("Set up Accessibility") {
                    ThemedDialogBuilder(this).setTitle("Enable Beta Accessibility")
                        .setMessage("Open Accessibility → Installed apps → J.A.R.V.I.S. Accessibility. If Android blocks it, open Settings → Apps → Beta → three-dot menu → Allow restricted settings, then return to Accessibility. This is optional and must be approved manually.")
                        .setPositiveButton("Accessibility") { _, _ -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .setNeutralButton("App settings") { _, _ -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                        .setNegativeButton("Later", null).show()
                }
                button("Choose default digital assistant") { open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                button("Review battery restrictions") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                text("If background wake listening stops, review Beta's battery settings and voice settings. Changing the default assistant may replace your current assistant.")
            }
            4 -> {
                text("Try your first command", true)
                text("Tap Talk and ask What is today's date? Then try Hey Jarvis followed by Open Chrome. For AI conversation, ask Explain what you can help me with. Web search and Gemini need internet; phone actions may need their own permissions.")
                val testButton = button(if (checking) "Checking Gemini…" else "Test Gemini connection") { testGemini() }
                testButton.isEnabled = !checking
                if (testReply.isNotBlank()) text("Gemini test response:\n$testReply")
                text("For a problem, open Live chat history in the menu and copy the chat for debugging. System status and updates installs future Beta updates after Android confirmation.")
                button("Finish and open chat") { complete(false) }
                button("Finish and start voice") {
                    if (granted(Manifest.permission.RECORD_AUDIO)) complete(true)
                    else { Toast.makeText(this, "Allow the microphone on step 2, or finish with typed chat.", Toast.LENGTH_LONG).show(); step = 1; setup.edit().putInt("step", step).apply(); render() }
                }
            }
        }
        if (step > 0) button("Back") { move(-1) }
        if (step < 4) button("Next") { move(1) }
        button("Finish setup later") { defer() }
        FontPreferences.applyTo(content, this)
    }
    private fun testGemini() {
        if (checking) return
        checking = true
        render()
        Thread {
            val answer = runCatching { DirectOnlineClient(applicationContext).gemini("Say hello in one short sentence and tell me you are ready for a beta test.") }
                .getOrDefault("The connection test could not complete. Check your internet connection and AI settings.")
            runOnUiThread {
                if (!isFinishing && !isDestroyed) { checking = false; testReply = answer.take(2000); render() }
            }
        }.start()
    }
    private fun complete(startVoice: Boolean) {
        val voices = VoicePreferences(this)
        val current = voices.load()
        voices.save(current.copy(autoStartVoice = false,
            useForegroundServiceMode = if (startVoice) true else current.useForegroundServiceMode,
            autoStartForegroundService = startVoice))
        setup.edit().putBoolean("complete", true).putBoolean("deferred", false).putInt("step", 0).apply()
        setResult(RESULT_OK, Intent().putExtra("start_voice", startVoice))
        finish()
    }
    private fun defer() {
        setup.edit().putBoolean("deferred", true).apply()
        setResult(RESULT_OK)
        finish()
    }
    companion object {
        private const val PREFS = "jarvis_beta_introduction"
        fun isBeta(context: Context) = context.packageName == "com.assistant.core.beta"
        fun shouldShow(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return isBeta(context) && !prefs.getBoolean("complete", false) && !prefs.getBoolean("deferred", false)
        }
    }
}
