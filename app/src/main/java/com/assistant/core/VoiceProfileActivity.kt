package com.assistant.core

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.assistant.core.services.LiveChatStore
import com.assistant.core.services.LiveSpeechCapture
import com.assistant.core.services.VoiceForegroundService
import com.assistant.core.services.VoicePracticeMicrophone
import com.assistant.core.services.VoiceProfileStore
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import java.util.UUID

/** Calibration results stay here. Nothing is sent to the assistant or action engine. */
class VoiceProfileActivity : ThemedActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { VoiceProfileStore(filesDir) }
    private val capture by lazy { LiveSpeechCapture(this) }
    private var phraseIndex = 0
    private var token: String? = null
    private var heard = ""
    private var attemptLanguage = ""
    private var attemptMode = "default"
    private var attemptHinted = false
    private var listening = false
    private var visible = false
    private var showingEntries = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status(if (granted) "Microphone ready. Tap Record phrase." else "Allow microphone access in Android settings to practice.")
    }
    private fun label(id: Int) = findViewById<TextView>(id)
    private fun field(id: Int) = findViewById<EditText>(id)
    private fun toggle(id: Int) = findViewById<SwitchMaterial>(id)
    private fun status(text: String) { label(R.id.voiceProfileStatus).text = text }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_profile)
        phraseIndex = savedInstanceState?.getInt("phrase", 0)?.coerceIn(0, PHRASES.lastIndex) ?: 0
        val deviceAvailable = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)
        toggle(R.id.voiceProfileDevice).apply { isEnabled = deviceAvailable; isChecked = deviceAvailable }
        label(R.id.voiceProfileEngine).text = "Language: ${Locale.getDefault().toLanguageTag()}. " +
            (if (deviceAvailable) "On-device practice is available." else "On-device practice is unavailable; the default engine may use a network service.") +
            (if (Build.VERSION.SDK_INT >= 33) " Android supports optional vocabulary hints; your engine may ignore them." else "Vocabulary hints require Android 13 or newer.")
        label(R.id.voiceProfileBack).setOnClickListener { finish() }
        label(R.id.voiceProfileRecord).setOnClickListener { if (listening) endCapture("Practice stopped. Nothing saved.") else record() }
        label(R.id.voiceProfileApprove).setOnClickListener {
            if (heard.isBlank()) return@setOnClickListener
            val intended = field(R.id.voiceProfileIntended).text.toString()
            if (store.approve(heard, intended, attemptLanguage, attemptMode, attemptHinted)) {
                discard(); status("Approved wording saved as a recognition hint. No command was run."); refreshScore()
            } else status("Unable to save. Use 1–160 characters without line breaks, then retry.")
        }
        label(R.id.voiceProfileDiscard).setOnClickListener { discard(); status("Attempt discarded. Nothing saved.") }
        label(R.id.voiceProfileNext).setOnClickListener { nextPhrase() }
        label(R.id.voiceProfileRetest).setOnClickListener { endCapture(""); discard(); phraseIndex = 0; showPhrase(); status("Practice restarted. Previous approved scores remain for comparison.") }
        label(R.id.voiceProfileManage).setOnClickListener {
            showingEntries = !showingEntries
            findViewById<View>(R.id.voiceProfileEntries).visibility = if (showingEntries) View.VISIBLE else View.GONE
            refreshEntries()
        }
        label(R.id.voiceProfileReset).setOnClickListener {
            endCapture("")
            ThemedDialogBuilder(this).setTitle("Reset my voice profile?")
                .setMessage("Remove approved transcripts, vocabulary hints and practice scores from this device? Learned commands are separate.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ ->
                    if (store.clear()) { discard(); refreshScore(); status("Voice profile reset.") }
                    else status("Unable to reset the profile. Please retry.")
                }.show()
        }
        showPhrase(); refreshScore(); status("Tap Record phrase and speak naturally. Wake listening pauses only during each attempt.")
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; endCapture("Practice stopped when leaving the screen."); discard(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("phrase", phraseIndex); super.onSaveInstanceState(outState) }
    private fun showPhrase() {
        label(R.id.voiceProfileProgress).text = "Practice ${phraseIndex + 1} of ${PHRASES.size}"
        field(R.id.voiceProfilePhrase).setText(PHRASES[phraseIndex])
    }
    private fun nextPhrase() {
        endCapture(""); discard()
        if (phraseIndex == PHRASES.lastIndex) status("Guided practice complete. Restart to retest, or edit the phrase to practice your own vocabulary.")
        else { phraseIndex++; showPhrase(); status("Ready for the next phrase.") }
    }
    private fun discard() { heard = ""; findViewById<View>(R.id.voiceProfileReview).visibility = View.GONE }
    private fun busy(value: Boolean) {
        listening = value
        (label(R.id.voiceProfileRecord) as Button).text = if (value) "Stop practice" else "Record phrase"
        field(R.id.voiceProfilePhrase).isEnabled = !value
        toggle(R.id.voiceProfileDevice).isEnabled = !value && Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)
        toggle(R.id.voiceProfileHints).isEnabled = !value
    }
    private fun record() {
        val expected = field(R.id.voiceProfilePhrase).text.toString().trim()
        if (!VoiceProfileStore.valid(expected)) { status("Enter a phrase of 1–160 characters without line breaks."); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (LiveChatStore.state(this).optBoolean("active")) { status("End live chat before practicing. Live chat has not been interrupted."); return }
        val device = toggle(R.id.voiceProfileDevice).isChecked
        fun begin() {
            if (!visible) return
            discard(); busy(true)
            attemptLanguage = Locale.getDefault().toLanguageTag()
            attemptMode = if (device) "device" else "default"
            attemptHinted = toggle(R.id.voiceProfileHints).isChecked && Build.VERSION.SDK_INT >= 33 && store.hints(attemptLanguage).isNotEmpty()
            val current = UUID.randomUUID().toString()
            token = current
            status("Pausing wake listening…")
            try { VoiceForegroundService.pauseForPractice(this, current) }
            catch (_: Exception) { endCapture("Unable to release the wake microphone. Stop wake listening and retry."); return }
            var polls = 0
            val wait = object : Runnable {
                override fun run() {
                    if (!visible || token != current) return
                    when (VoicePracticeMicrophone.available(this@VoiceProfileActivity, current)) {
                        true -> {
                            status("Listening. Say: $expected")
                            handler.postDelayed({ if (token == current) endCapture("No result arrived. Try again in a quiet place.") }, 20000L)
                            capture.listen(true, { partial -> status("Hearing: $partial") }, { result ->
                                endCapture("")
                                heard = result
                                label(R.id.voiceProfileHeard).text = "Heard: $result"
                                field(R.id.voiceProfileIntended).setText(expected)
                                findViewById<View>(R.id.voiceProfileReview).visibility = View.VISIBLE
                                status("Review the intended wording before approving. Nothing has been saved or run.")
                            }, { error -> endCapture(errorMessage(error)) }, onDeviceOnly = device, useProfileHints = attemptHinted)
                        }
                        false -> endCapture("The microphone is in use. End live chat or other practice before retrying.")
                        null -> if (++polls >= 30) endCapture("Wake listening did not release the microphone. Retry shortly.") else handler.postDelayed(this, 100L)
                    }
                }
            }
            handler.post(wait)
        }
        if (device) begin() else ThemedDialogBuilder(this).setTitle("Use default speech recognition?")
            .setMessage("Your Android speech provider may send audio and approved vocabulary hints to its online service. Jarvis does not save recordings. Continue with one practice attempt?")
            .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> begin() }.show()
    }
    private fun endCapture(message: String) {
        handler.removeCallbacksAndMessages(null)
        capture.stop()
        val previous = token; token = null
        if (previous != null) runCatching { VoiceForegroundService.releasePractice(this, previous) }
        busy(false)
        if (message.isNotBlank()) status(message)
    }
    private fun refreshScore() {
        val entries = store.entries()
        val groups = entries.groupBy { "${it.language} • ${if (it.mode == "device") "on device" else "default"} • ${if (it.hinted) "hints supplied" else "no hints"}" }
        label(R.id.voiceProfileScore).text = if (entries.isEmpty()) "No approved practice yet." else
            "Approved attempts: ${entries.size} (most recent ${VoiceProfileStore.LIMIT} retained). Exact matches ignore case and punctuation. These are practice scores, not overall command accuracy.\n\n" +
            groups.entries.joinToString("\n") { (group, trials) -> "$group: ${trials.count { it.matched }}/${trials.size} matched" }
        refreshEntries()
    }
    private fun refreshEntries() {
        val panel = findViewById<LinearLayout>(R.id.voiceProfileEntries)
        panel.removeAllViews()
        if (!showingEntries) return
        store.entries().withIndex().toList().asReversed().forEach { (index, entry) ->
            val row = layoutInflater.inflate(R.layout.voice_profile_entry, panel, false)
            row.findViewById<TextView>(R.id.voiceProfileEntryText).text = "${entry.intended}\nHeard: ${entry.heard}\n${entry.language} • ${if (entry.matched) "Matched" else "Corrected"}"
            row.findViewById<Button>(R.id.voiceProfileEntryRemove).setOnClickListener {
                if (store.remove(index)) { refreshScore(); status("Approved attempt removed.") } else status("Unable to remove. Please retry.")
            }
            panel.addView(row)
        }
    }
    private fun errorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No clear phrase recognized. Move closer to the microphone and retry."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> "The microphone or recognizer is busy. Close other recording apps and retry."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access in Android settings."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech provider could not connect. Check the connection or select on-device recognition if available."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This recognizer does not have the selected language ready. Check Android speech language downloads or try the default engine."
        else -> "Speech recognition failed (code $error). Check Android speech settings or try the default engine. Nothing was saved or run."
    }
    companion object {
        val PHRASES = listOf("Hey Jarvis", "Open Chrome", "Start a timer for five minutes", "Make that ten minutes", "Set media volume to twenty five percent",
            "Turn volume to maximum", "Show saved commands", "Show assistant memory", "Try capability request number one", "Review capability request number two",
            "Teach capability request number one as work break", "Work break", "What can I do to fix it", "Cancel that request", "It worked")
    }
}
