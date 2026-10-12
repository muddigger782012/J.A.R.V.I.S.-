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
        bindResponseOptions()
        bindPersonality()
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
                // Persist the actual auditioned voice, not an unresolved Automatic placeholder.
                runCatching { preferences.save(selected, speech.voice?.name ?: selectedName) }.onFailure {
                    android.widget.Toast.makeText(this, "Couldn't save this voice. Try again.", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
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

    private fun bindPersonality() {
        val saved = com.assistant.core.services.AssistantPersonalityPreferences.load(this)
        val phrases = com.assistant.core.services.AssistantPhrasePreferences.load(this)
        val enabled = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchPersonality)
        val humor = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchPersonalityHumor)
        val initiative = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchPersonalityInitiative)
        val address = findViewById<android.widget.EditText>(R.id.editPersonalityAddress)
        val intro = findViewById<android.widget.EditText>(R.id.editAssistantIntroduction)
        val wake = findViewById<android.widget.EditText>(R.id.editWakeReplies)
        val detail = findViewById<RadioGroup>(R.id.personalityDetail)
        enabled.isChecked = saved.enabled; humor.isChecked = saved.humor; initiative.isChecked = saved.initiative
        address.setText(saved.address); intro.setText(phrases.introduction); wake.setText(phrases.wakeReplies)
        detail.check(when (saved.detail) { "brief" -> R.id.detailBrief; "detailed" -> R.id.detailDetailed; else -> R.id.detailBalanced })
        findViewById<Button>(R.id.btnSavePersonality).setOnClickListener {
            val value = com.assistant.core.services.AssistantPersonality(
                enabled.isChecked, address.text.toString(),
                when (detail.checkedRadioButtonId) { R.id.detailBrief -> "brief"; R.id.detailDetailed -> "detailed"; else -> "balanced" },
                humor.isChecked, initiative.isChecked)
            runCatching {
                com.assistant.core.services.AssistantPersonalityPreferences.save(this, value)
                com.assistant.core.services.AssistantPhrasePreferences.save(this,
                    com.assistant.core.services.AssistantPhrases(intro.text.toString(), wake.text.toString()))
            }.onSuccess {
                android.widget.Toast.makeText(this, "Personality and phrases saved", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(this, "Some settings could not be saved. Try again.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(R.id.btnPreviewAssistantVoice, R.id.btnChooseAssistantVoice, R.id.btnUseAssistantVoice)
            .forEach { findViewById<Button>(it).isEnabled = enabled }
    }

    private fun bindResponseOptions() {
        val options = com.assistant.core.services.VoiceResponsePreferences.load(this)
        val regular = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchRegularSpeech)
        val live = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchLiveSpeech)
        val dialogue = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchLiveDialogue)
        regular.isChecked = options.regularSpeech
        live.isChecked = options.liveSpeech
        dialogue.isChecked = options.liveDialogue
        var reverting = false
        fun change(button: android.widget.CompoundButton, checked: Boolean, update: (com.assistant.core.services.VoiceResponseOptions) -> com.assistant.core.services.VoiceResponseOptions) {
            if (reverting) return
            runCatching { com.assistant.core.services.VoiceResponsePreferences.save(this, update(com.assistant.core.services.VoiceResponsePreferences.load(this))) }
                .onFailure {
                    reverting = true; button.isChecked = !checked; reverting = false
                    android.widget.Toast.makeText(this, "Couldn't save response settings. Try again.", android.widget.Toast.LENGTH_LONG).show()
                }
            refreshDialoguePermission()
        }
        regular.setOnCheckedChangeListener { button, checked -> change(button, checked) { it.copy(regularSpeech = checked) } }
        live.setOnCheckedChangeListener { button, checked -> change(button, checked) { it.copy(liveSpeech = checked) } }
        dialogue.setOnCheckedChangeListener { button, checked -> change(button, checked) { it.copy(liveDialogue = checked) } }
        findViewById<Button>(R.id.btnLiveChatHistory).setOnClickListener { startActivity(Intent(this, LiveChatHistoryActivity::class.java)) }
        findViewById<Button>(R.id.btnLiveDialoguePermission).setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                .onFailure { android.widget.Toast.makeText(this, "Overlay settings are unavailable on this phone", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    private fun refreshDialoguePermission() {
        val enabled = com.assistant.core.services.VoiceResponsePreferences.load(this).liveDialogue
        val allowed = android.provider.Settings.canDrawOverlays(this)
        findViewById<Button>(R.id.btnLiveDialoguePermission).visibility = if (enabled && !allowed) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<TextView>(R.id.tvLiveDialoguePermission).apply {
            visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
            text = if (allowed) "Dialogue can appear inside and outside J.A.R.V.I.S." else "Dialogue works inside J.A.R.V.I.S. Allow display over other apps to see it outside the app."
        }
    }

    private fun updateVoice(): Boolean {
        if (!ready) return false
        val result = AssistantSpeechVoice.apply(speech, selected, selectedName, preferences.voiceName(AssistantVoiceProfile.FEMALE))
        findViewById<TextView>(R.id.tvAssistantVoiceStatus).text = "${selected.label}\n${result.description}"
        setButtonsEnabled(result.usable)
        findViewById<Button>(R.id.btnChooseAssistantVoice).isEnabled = AssistantSpeechVoice.available(speech).isNotEmpty()
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
        refreshDialoguePermission()
        com.assistant.core.services.VoiceResponsePreferences.notifyChanged(this)
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
