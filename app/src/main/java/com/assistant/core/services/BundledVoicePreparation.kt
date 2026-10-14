package com.assistant.core.services

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** Does not hold an Activity or microphone; installation is serialized across processes by the pack. */
object BundledVoicePreparation {
    private val running = AtomicBoolean(false)
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_bundled_voices", Context.MODE_PRIVATE)
    fun status(context: Context): String = prefs(context).getString("status", "Preparing bundled voices…").orEmpty()
    fun ensure(context: Context) {
        val app = context.applicationContext
        val pack = NeuralVoicePack(app)
        if (!pack.bundled()) return
        if (pack.installed()) { prefs(app).edit().putString("status", "Offline voices ready: George, Lewis and Michael.").apply(); return }
        if (!running.compareAndSet(false, true)) return
        prefs(app).edit().putString("status", "Preparing bundled voices…").apply()
        Thread {
            try {
                pack.install(AtomicBoolean(false)) { status -> prefs(app).edit().putString("status", status).apply() }
                prefs(app).edit().putString("status", "Offline voices ready: George, Lewis and Michael.").apply()
            } catch (error: Exception) {
                prefs(app).edit().putString("status", "Voice preparation failed: ${error.message ?: "unknown error"}. Free storage if needed, then tap Prepare voices again.").apply()
            } finally { running.set(false) }
        }.apply { name = "jarvis-bundled-voice-preparation"; start() }
    }
}
