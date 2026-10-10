package com.assistant.core

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.assistant.core.services.VoiceConfig
import com.assistant.core.services.VoicePreferences
import com.google.android.material.switchmaterial.SwitchMaterial

class VoiceSettingsActivity : ThemedActivity() {

    private lateinit var voicePreferences: VoicePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.voice_settings_title)

        voicePreferences = VoicePreferences(this)
        val current = voicePreferences.load()

        val dedicatedSwitch: SwitchMaterial = findViewById(R.id.switchDedicatedWakeWord)
        val wakeWordInput: EditText = findViewById(R.id.etWakeWord)
        val autoStartSwitch: SwitchMaterial = findViewById(R.id.switchAutoStartVoice)
        val foregroundModeSwitch: SwitchMaterial = findViewById(R.id.switchForegroundServiceMode)
        val autoStartForegroundSwitch: SwitchMaterial = findViewById(R.id.switchAutoStartForegroundService)
        val offlineSwitch: SwitchMaterial = findViewById(R.id.switchPreferOfflineCommand)
        val sensitivitySeek: SeekBar = findViewById(R.id.seekWakeSensitivity)
        val sensitivityValue: TextView = findViewById(R.id.tvWakeSensitivityValue)
        val confidenceSeek: SeekBar = findViewById(R.id.seekCommandConfidenceThreshold)
        val confidenceValue: TextView = findViewById(R.id.tvConfidenceThresholdValue)
        val defaultProjectNameInput: EditText = findViewById(R.id.etDefaultProjectName)
        val customProjectPhrasesInput: EditText = findViewById(R.id.etCustomProjectPhrases)
        val customStatusPhrasesInput: EditText = findViewById(R.id.etCustomStatusPhrases)
        val customShellPhrasesInput: EditText = findViewById(R.id.etCustomShellPhrases)
        val customRebootPhrasesInput: EditText = findViewById(R.id.etCustomRebootPhrases)
        val customSettingsPhrasesInput: EditText = findViewById(R.id.etCustomSettingsPhrases)
        val customStartVoicePhrasesInput: EditText = findViewById(R.id.etCustomStartVoicePhrases)
        val customStopVoicePhrasesInput: EditText = findViewById(R.id.etCustomStopVoicePhrases)
        findViewById<Button>(R.id.btnCloseVoiceSettings).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnToggleAdvancedVoice).setOnClickListener { button ->
            val panel = findViewById<android.view.View>(R.id.voiceAdvancedOptions)
            val expanded = panel.visibility != android.view.View.VISIBLE
            panel.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
            (button as Button).setText(if (expanded) R.string.ui_hide_advanced_voice else R.string.ui_advanced_voice)
        }
        findViewById<Button>(R.id.btnToggleCommandExamples).setOnClickListener {
            val examples = findViewById<android.view.View>(R.id.tvVoiceCommandCatalog)
            examples.visibility = if (examples.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }
        val saveButton: Button = findViewById(R.id.btnSaveVoiceSettings)

        val themeButton = com.google.android.material.button.MaterialButton(this).apply {
            id = R.id.btnThemes
            text = "Themes • " + ThemePreferences.selected(this@VoiceSettingsActivity).replaceFirstChar { it.uppercase() }
            isAllCaps = false
            setOnClickListener { startActivity(android.content.Intent(this@VoiceSettingsActivity, ThemeSettingsActivity::class.java)) }
        }
        (saveButton.parent as android.view.ViewGroup).addView(themeButton, 3)

        val gatewayPrefs = getSharedPreferences("jarvis_cloud_ai", MODE_PRIVATE)
        val gatewayButton = com.google.android.material.button.MaterialButton(this).apply {
            text = "Weather and AI"
            isAllCaps = false
            setOnClickListener {
                val fields = android.widget.LinearLayout(this@VoiceSettingsActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(32, 16, 32, 16)
                }
                fun field(label: String, key: String, secret: Boolean = false): EditText {
                    fields.addView(TextView(this@VoiceSettingsActivity).apply { text = label })
                    return EditText(this@VoiceSettingsActivity).apply {
                        setSingleLine(true)
                        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        setText(gatewayPrefs.getString(key, ""))
                        fields.addView(this)
                    }
                }
                fields.addView(TextView(this@VoiceSettingsActivity).apply {
                    text = "Weather uses National Weather Service directly. Gemini sends unresolved questions to Google using your own API key."
                })
                val keyStore = com.assistant.core.services.ApiKeyStore(this@VoiceSettingsActivity)
                val geminiKey = field("Gemini API key (Google AI Studio)", "unused", true).apply { setText(keyStore.read()) }
                val geminiModel = field("Gemini model", "gemini_model").apply {
                    if (text.isBlank()) setText("gemini-3.5-flash-lite")
                }
                val endpoint = field("Optional gateway HTTPS URL ending in /chat", "endpoint")
                val token = field("Gateway token", "gateway_token", true)
                val deviceLocation = SwitchMaterial(this@VoiceSettingsActivity).apply {
                    text = "Use my precise phone location for weather"
                    isChecked = gatewayPrefs.getBoolean("weather_device_location", false)
                }
                fields.addView(deviceLocation)
                val latitude = field("Weather latitude", "weather_latitude")
                val longitude = field("Weather longitude", "weather_longitude")
                fields.addView(com.google.android.material.button.MaterialButton(this@VoiceSettingsActivity).apply {
                    text = "Use Chesapeake, VA for weather"
                    setOnClickListener { deviceLocation.isChecked = false; latitude.setText("36.7682"); longitude.setText("-76.2875") }
                })
                val scroll = android.widget.ScrollView(this@VoiceSettingsActivity).apply { addView(fields) }
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this@VoiceSettingsActivity)
                    .setTitle("Weather and AI")
                    .setView(scroll).setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", null).create()
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val url = endpoint.text.toString().trim()
                        val uri = android.net.Uri.parse(url)
                        val lat = latitude.text.toString().trim()
                        val lon = longitude.text.toString().trim()
                        if (url.isNotBlank() && (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null)) {
                            endpoint.error = "Enter a valid HTTPS gateway URL"
                        } else if ((lat.isNotBlank() || lon.isNotBlank()) &&
                            (lat.toDoubleOrNull()?.let { it.isFinite() && it in -90.0..90.0 } != true ||
                             lon.toDoubleOrNull()?.let { it.isFinite() && it in -180.0..180.0 } != true)) {
                            latitude.error = "Enter valid latitude and longitude together"
                        } else if (!Regex("[a-zA-Z0-9._-]+").matches(geminiModel.text.toString().trim())) {
                            geminiModel.error = "Enter a valid Gemini model name"
                        } else {
                            try { keyStore.save(geminiKey.text.toString().trim()) } catch (_: Exception) {
                                geminiKey.error = "Couldn't save the API key securely. Try again."
                                return@setOnClickListener
                            }
                            gatewayPrefs.edit().putBoolean("weather_device_location", deviceLocation.isChecked).putString("gemini_model", geminiModel.text.toString().trim()).putString("endpoint", url)
                                .putString("gateway_token", token.text.toString().trim())
                                .putString("weather_latitude", lat).putString("weather_longitude", lon).apply()
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
        }
        (saveButton.parent as android.view.ViewGroup).addView(gatewayButton, 4)

        (saveButton.parent as android.view.ViewGroup).addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Optional assistant connections"
            isAllCaps = false
            setOnClickListener { startActivity(android.content.Intent(this@VoiceSettingsActivity, AssistantConnectionsActivity::class.java)) }
        }, 5)

        dedicatedSwitch.isChecked = current.enableDedicatedWakeWord
        wakeWordInput.setText(current.wakeWord)
        autoStartSwitch.isChecked = current.autoStartVoice
        foregroundModeSwitch.isChecked = current.useForegroundServiceMode
        autoStartForegroundSwitch.isChecked = current.autoStartForegroundService
        offlineSwitch.isChecked = current.preferOfflineCommandRecognition
        sensitivitySeek.progress = (current.wakeSensitivity * 100f).toInt()
        sensitivityValue.text = formatSensitivity(current.wakeSensitivity)
        confidenceSeek.progress = (current.commandConfidenceThreshold * 100f).toInt()
        confidenceValue.text = formatConfidence(current.commandConfidenceThreshold)
        defaultProjectNameInput.setText(current.defaultProjectName)
        customProjectPhrasesInput.setText(current.customProjectPhrases)
        customStatusPhrasesInput.setText(current.customStatusPhrases)
        customShellPhrasesInput.setText(current.customShellPhrases)
        customRebootPhrasesInput.setText(current.customRebootPhrases)
        customSettingsPhrasesInput.setText(current.customSettingsPhrases)
        customStartVoicePhrasesInput.setText(current.customStartVoicePhrases)
        customStopVoicePhrasesInput.setText(current.customStopVoicePhrases)

        sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress.coerceIn(10, 100)) / 100f
                sensitivityValue.text = formatSensitivity(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        confidenceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress.coerceIn(20, 95)) / 100f
                confidenceValue.text = formatConfidence(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        saveButton.setOnClickListener {
            val config = VoiceConfig(
                enableDedicatedWakeWord = dedicatedSwitch.isChecked,
                wakeWord = wakeWordInput.text?.toString().orEmpty(),
                wakeSensitivity = (sensitivitySeek.progress.coerceIn(10, 100)) / 100f,
                autoStartVoice = autoStartSwitch.isChecked,
                preferOfflineCommandRecognition = offlineSwitch.isChecked,
                commandConfidenceThreshold = (confidenceSeek.progress.coerceIn(20, 95)) / 100f,
                defaultProjectName = defaultProjectNameInput.text?.toString().orEmpty(),
                useForegroundServiceMode = foregroundModeSwitch.isChecked,
                autoStartForegroundService = autoStartForegroundSwitch.isChecked,
                customProjectPhrases = customProjectPhrasesInput.text?.toString().orEmpty(),
                customStatusPhrases = customStatusPhrasesInput.text?.toString().orEmpty(),
                customShellPhrases = customShellPhrasesInput.text?.toString().orEmpty(),
                customRebootPhrases = customRebootPhrasesInput.text?.toString().orEmpty(),
                customSettingsPhrases = customSettingsPhrasesInput.text?.toString().orEmpty(),
                customStartVoicePhrases = customStartVoicePhrasesInput.text?.toString().orEmpty(),
                customStopVoicePhrases = customStopVoicePhrasesInput.text?.toString().orEmpty()
            )
            voicePreferences.save(config)
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun formatSensitivity(value: Float): String {
        return String.format("Sensitivity: %.2f", value)
    }

    private fun formatConfidence(value: Float): String {
        return String.format("Command confidence threshold: %.2f", value)
    }
}
