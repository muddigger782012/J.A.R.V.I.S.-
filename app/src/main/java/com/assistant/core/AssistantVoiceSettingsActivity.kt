package com.assistant.core

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import com.assistant.core.services.AssistantSpeechVoice
import com.assistant.core.services.AssistantVoicePreferences
import com.assistant.core.services.AssistantVoiceProfile

class AssistantVoiceSettingsActivity : ThemedActivity() {
    private lateinit var speech: TextToSpeech
    private lateinit var preferences: AssistantVoicePreferences
    private var ready = false
    private var closed = false
    private var previewing = false
    private var previewId = 0L
    private var selected = AssistantVoiceProfile.ORIGINAL
    private var selectedName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant_voice)
        preferences = AssistantVoicePreferences(this)
        selected = AssistantVoiceProfile.fromId(savedInstanceState?.getString("profile") ?: preferences.selected().id)
        selectedName = savedInstanceState?.getString("voice") ?: preferences.voiceName(selected)
        val group = findViewById<RadioGroup>(R.id.assistantVoiceProfiles)
        group.check(if (selected == AssistantVoiceProfile.ORIGINAL) R.id.voiceOriginal else R.id.voiceFemale)
        group.setOnCheckedChangeListener { _, id ->
            stopPreview()
            selected = if (id == R.id.voiceFemale) AssistantVoiceProfile.FEMALE else AssistantVoiceProfile.ORIGINAL
            selectedName = preferences.voiceName(selected)
            updateVoice()
        }
        findViewById<Button>(R.id.btnCloseAssistantVoice).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnPreviewAssistantVoice).setOnClickListener {
            if (previewing) stopPreview() else preview()
        }
        findViewById<Button>(R.id.btnChooseAssistantVoice).setOnClickListener { chooseVoice() }
        findViewById<Button>(R.id.btnUseAssistantVoice).setOnClickListener {
            if (updateVoice()) {
                preferences.save(selected, selectedName)
                android.widget.Toast.makeText(this, "Assistant voice saved", android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        findViewById<Button>(R.id.btnAndroidSpeechSettings).setOnClickListener {
            stopPreview()
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { android.widget.Toast.makeText(this, "Speech settings are unavailable on this phone", android.widget.Toast.LENGTH_LONG).show() }
        }
        setButtonsEnabled(false)
        speech = TextToSpeech(this) { status ->
            // Post even if the engine completes synchronously during construction.
            runOnUiThread {
                findViewById<TextView>(R.id.tvAssistantVoiceStatus).post {
                    if (!closed) {
                        ready = status == TextToSpeech.SUCCESS
                        if (ready) {
                            speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(id: String?) = Unit
                                override fun onDone(id: String?) = finishPreview(id, false)
                                @Deprecated("Deprecated in Java")
                                override fun onError(id: String?) = finishPreview(id, true)
                            })
                            AssistantSpeechVoice.rememberPreviousVoice(speech, preferences)
                            if (selectedName.isBlank() && selected == AssistantVoiceProfile.FEMALE) selectedName = preferences.voiceName(selected)
                            updateVoice()
                        } else {
                            findViewById<TextView>(R.id.tvAssistantVoiceStatus).text = "Speech engine could not start. Check Android speech settings."
                        }
                    }
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(R.id.btnPreviewAssistantVoice, R.id.btnChooseAssistantVoice, R.id.btnUseAssistantVoice)
            .forEach { findViewById<Button>(it).isEnabled = enabled }
    }

    private fun updateVoice(): Boolean {
        if (!ready) return false
        val result = AssistantSpeechVoice.apply(speech, selected, selectedName)
        findViewById<TextView>(R.id.tvAssistantVoiceStatus).text = "${selected.label}\n${result.description}"
        setButtonsEnabled(result.usable)
        return result.usable
    }

    private fun chooseVoice() {
        stopPreview()
        val voices = AssistantSpeechVoice.available(speech)
        val labels = arrayOf("Automatic • prefer British English") + voices.map { "${it.name} • ${it.locale.displayName}" }
        val checked = voices.indexOfFirst { it.name == selectedName } + 1
        ThemedDialogBuilder(this).setTitle("Choose a voice to preview")
            .setSingleChoiceItems(labels, checked) { dialog, index ->
                selectedName = if (index == 0) "" else voices[index - 1].name
                dialog.dismiss()
                updateVoice()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun preview() {
        if (!updateVoice()) return
        previewing = true
        findViewById<Button>(R.id.btnPreviewAssistantVoice).text = "Stop preview"
        val id = "voice-preview-${++previewId}"
        val text = "Good morning. Systems are ready. Shall we get to work? I suspect the trucks won't repair themselves."
        if (speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) finishPreview(id, true)
    }

    private fun finishPreview(id: String?, error: Boolean) {
        runOnUiThread {
            if (!closed && id == "voice-preview-$previewId") {
                stopPreview()
                if (error) findViewById<TextView>(R.id.tvAssistantVoiceStatus).text = "This voice could not speak. Choose another installed voice."
            }
        }
    }

    private fun stopPreview() {
        previewId++
        previewing = false
        if (::speech.isInitialized) speech.stop()
        findViewById<Button>(R.id.btnPreviewAssistantVoice).text = "Preview voice"
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("profile", selected.id)
        state.putString("voice", selectedName)
        super.onSaveInstanceState(state)
    }

    override fun onResume() {
        super.onResume()
        if (ready) updateVoice()
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }

    override fun onDestroy() {
        closed = true
        if (::speech.isInitialized) speech.shutdown()
        super.onDestroy()
    }
}
