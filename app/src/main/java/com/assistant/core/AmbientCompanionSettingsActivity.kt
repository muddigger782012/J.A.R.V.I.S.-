package com.assistant.core

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.assistant.core.services.LiveChatCompanionSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/** Real Personal Jarvis Settings page, saved across app restarts and :wake process. */
class AmbientCompanionSettingsActivity : MenuActivity("Ambient companion") {
    private fun dp(value: Int) = SettingsMenuViews.dp(this, value)
    private fun color(attr: Int) = SettingsMenuViews.color(this, attr)
    private fun label(message: String, size: Float = 15f) = TextView(this).apply {
        text = message
        textSize = size
        setTextColor(color(R.attr.jarvis_on_dark))
        setPadding(dp(4), dp(6), dp(4), dp(8))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val current = LiveChatCompanionSettings.load(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
            setBackgroundResource(R.drawable.glass_panel_surface)
        }
        UnifiedContentPanel.apply(panel)
        panel.addView(label("Ambient Companion", 23f))
        panel.addView(label("Live Chat can listen without replying to every sentence. You decide how often Jarvis participates.", 14f))
        panel.addView(SettingsMenuViews.heading(this, "Conversational initiative"))
        val progress = label("Initiative: ${current.initiative}%")
        panel.addView(progress)
        val initiative = SeekBar(this).apply {
            max = 100
            this.progress = current.initiative
            contentDescription = "Conversational initiative"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    progress.text = "Initiative: ${value}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        panel.addView(initiative, LinearLayout.LayoutParams(-1, dp(52)))
        panel.addView(label("0% mostly silent  •  100% more proactive", 12f))

        panel.addView(SettingsMenuViews.heading(this, "Continuous microphone speech detection"))
        val triggerProgress = label("Speech detection sensitivity: ${current.speechDetectionSensitivity}%")
        panel.addView(triggerProgress)
        val voiceSensitivity = SeekBar(this).apply {
            max = 100
            this.progress = current.speechDetectionSensitivity
            contentDescription = "Continuous microphone speech detection sensitivity"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    triggerProgress.text = "Speech detection sensitivity: ${value}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        panel.addView(voiceSensitivity, LinearLayout.LayoutParams(-1, dp(52)))
        panel.addView(label("Higher catches quieter speech but may react to workshop noise. Affects the experimental PCM input, not the Hey Jarvis wake-word model.", 12f))

        panel.addView(SettingsMenuViews.heading(this, "Participation style"))
        val styles = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fun choice(tagValue: String, title: String) = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            tag = tagValue
            text = title
            textSize = 15f
            isChecked = current.style == tagValue
            setTextColor(color(R.attr.jarvis_on_dark))
            contentDescription = title
        }
        styles.addView(choice("quiet", "Quiet observer — speak only when addressed"))
        styles.addView(choice("companion", "Companion — respond to direct address and follow-ups"))
        styles.addView(choice("active", "Active participant — may offer occasional useful input"))
        panel.addView(styles)
        panel.addView(SettingsMenuViews.heading(this, "Conversation preferences"))
        fun option(title: String, enabled: Boolean): SwitchMaterial {
            val view = SwitchMaterial(this).apply {
                text = title
                isChecked = enabled
                setTextColor(color(R.attr.jarvis_on_dark))
                textSize = 15f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            panel.addView(view, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            return view
        }
        val context = option("Maintain conversational context", current.contextEnabled)
        val keepListening = option("Keep Live Chat available until I stop it", current.keepListeningUntilStopped)
        val avoidChimes = option("Quiet microphone: no wake tone or repeated startup beeps", current.avoidRepeatedChimes)
        val continuousPcm = option("Experimental continuous microphone pipeline (Android 13+)", current.experimentalPcmStream)
        continuousPcm.isEnabled = android.os.Build.VERSION.SDK_INT >= 33
        val interruptions = option("Allow natural interruptions and barge-in", current.interruptionsEnabled)
        val otherSpeakers = option("Respond when other people address Jarvis", current.replyToOtherSpeakers)
        panel.addView(label("Quiet microphone mode disables Jarvis's own wake-word acknowledgment beep and pauses repeated normal-system recognition after silence to avoid recurring provider tones. Tap the microphone to restart it. The experimental PCM option can remain ready for speech without repeatedly starting recognition, if supported on your device.", 12f))
        panel.addView(label("High initiative (80%+ in Companion or 60%+ in Active) allows occasional useful replies without saying Jarvis first. Jarvis still does not know who is speaking, and direct commands require explicit interaction.", 12f))
        panel.addView(label("The experimental microphone pipeline is OFF by default. If enabled, it tries one persistent audio input with speech-activity detection; some Android recognizers ignore audio injection and will fall back. This is not speaker identification or full-duplex speech.", 12f))
        panel.addView(label("With this option on, Live Chat remains available through long quiet periods. Until the continuous audio engine is finished, Android speech recognition still restarts between turns, and the system provider takes longer rests to reduce repeated chimes. These rests are not active listening. Stop Live Chat when anyone needs privacy.", 12f))
        panel.addView(label("Speaker identification and automatic spoken barge-in are still being developed. These choices are saved now; unknown voices cannot yet be reliably identified as you or another person. The microphone button still works.", 12f))
        panel.addView(label("While listening, Jarvis stays quiet unless addressed. With conversational context enabled, he can locally recall up to 90 seconds of nearby speech if you explicitly ask what was discussed. No speaker identity is assumed, and ambient context is cleared when Live Chat ends. The system recognizer may use a network provider.", 12f))
        panel.addView(MaterialButton(this).apply {
            text = "Save companion settings"
            isAllCaps = false
            setOnClickListener {
                val selected = styles.findViewById<RadioButton>(styles.checkedRadioButtonId)?.tag as? String ?: current.style
                val saved = LiveChatCompanionSettings(
                    style = selected,
                    initiative = initiative.progress,
                    interruptionsEnabled = interruptions.isChecked,
                    contextEnabled = context.isChecked,
                    replyToOtherSpeakers = otherSpeakers.isChecked,
                    keepListeningUntilStopped = keepListening.isChecked,
                    experimentalPcmStream = continuousPcm.isChecked && continuousPcm.isEnabled,
                    avoidRepeatedChimes = avoidChimes.isChecked,
                    speechDetectionSensitivity = voiceSensitivity.progress
                )
                runCatching { LiveChatCompanionSettings.save(this@AmbientCompanionSettingsActivity, saved) }
                    .onSuccess {
                        Toast.makeText(this@AmbientCompanionSettingsActivity, "Companion settings saved", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .onFailure { Toast.makeText(this@AmbientCompanionSettingsActivity, "Could not save companion settings", Toast.LENGTH_LONG).show() }
            }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val root = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(panel)
        }
        setContentView(root)
        FontPreferences.applyTo(panel, this)
    }
}
