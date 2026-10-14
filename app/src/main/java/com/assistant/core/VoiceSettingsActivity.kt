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
        supportActionBar?.title = "Wake word and listening"

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
