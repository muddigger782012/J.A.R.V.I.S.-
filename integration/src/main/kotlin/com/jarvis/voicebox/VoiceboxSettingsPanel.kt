package com.jarvis.voicebox

import android.content.Context
import android.widget.*

/** Embedded settings; caller supplies colors resolved from its current theme. */
class VoiceboxSettingsPanel(context: Context, textColor: Int) : LinearLayout(context) {
    private val speech = VoiceboxSpeech(context)
    private val host = EditText(context)
    private val profile = EditText(context)
    private val enabled = Switch(context)
    private val status = TextView(context)
    private var revision = 0
    private var closed = false
    init {
        orientation = VERTICAL
        val config = VoiceboxPreferences.load(context)
        fun label(value: String) { addView(TextView(context).apply { text = value; setTextColor(textColor) }) }
        label("Voicebox")
        label("Speech is sent to your Voicebox host. Use HTTPS for another computer. Localhost means this device. Create voices and install models in Voicebox first.")
        enabled.text = "Use Voicebox for spoken replies"; enabled.isChecked = config.enabled
        enabled.setTextColor(textColor); enabled.minHeight = (48 * resources.displayMetrics.density).toInt()
        addView(enabled)
        host.hint = "Voicebox host URL"; host.setText(config.endpoint)
        profile.hint = "Voice profile name or ID"; profile.setText(config.profile)
        listOf(host, profile).forEach { it.setTextColor(textColor); it.setHintTextColor(textColor); it.minHeight = (48 * resources.displayMetrics.density).toInt(); it.setSingleLine(); addView(it) }
        host.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        status.setTextColor(textColor); status.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        fun save(): Boolean = runCatching {
            VoiceboxPreferences.save(context, VoiceboxConfig(enabled.isChecked, host.text.toString(), profile.text.toString()))
            status.text = "Voicebox settings saved"
        }.onFailure { status.text = it.message }.isSuccess
        addView(Button(context).apply { text = "Save Voicebox"; setOnClickListener { speech.stop(); save() } })
        addView(Button(context).apply { text = "List voices"; setOnClickListener {
            if (save()) {
                val current = ++revision
                val endpoint = host.text.toString()
                status.text = "Connecting to Voicebox…"
                Thread {
                    val result = runCatching { VoiceboxClient(endpoint).profiles().joinToString("\n") { "${it.second} (${it.first})" }.ifBlank { "No voice profiles. Create one in Voicebox." } }
                    post { if (!closed && revision == current) status.text = result.getOrElse { it.message ?: "Connection failed" } }
                }.start()
            }
        } })
        addView(Button(context).apply { text = "Test Voicebox voice"; setOnClickListener {
            if (save()) {
                status.text = "Generating speech…"
                speech.speak("Systems are ready. Shall we get to work?", { status.text = "Voice test complete" }, { status.text = it })
            }
        } })
        addView(Button(context).apply { text = "Stop Voicebox playback"; setOnClickListener { speech.stop(); status.text = "Playback stopped" } })
        addView(status)
        val form = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
        while (childCount > 0) {
            val child = getChildAt(0)
            removeViewAt(0)
            form.addView(child)
        }
        addView(Button(context).apply {
            text = "Voicebox settings"
            contentDescription = "Expand or collapse Voicebox settings"
            setOnClickListener { form.visibility = if (form.visibility == GONE) VISIBLE else GONE }
        })
        addView(form)
    }
    fun stop() { revision++; speech.stop() }
    fun close() { closed = true; revision++; speech.shutdown() }
}
