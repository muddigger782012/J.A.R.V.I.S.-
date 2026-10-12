package com.assistant.core.services

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import com.assistant.core.BuildConfig

object NeuralVoiceDiagnostics {
    fun record(context: Context, source: String, voice: String, event: String, firstAudioMs: Long = -1, cacheHits: Int = 0, error: String = "") {
        if (source !in setOf("preview", "assistant")) return
        runCatching {
            val data = JSONObject().put("time_ms", System.currentTimeMillis()).put("source", source)
                .put("voice", voice).put("event", event).put("first_queued_audio_ms", firstAudioMs).put("cache_hits", cacheHits)
                .put("error", error.take(100))
            val file = AtomicFile(File(context.filesDir, "voice-event-$source.json"))
            synchronized(writeLock) {
                RandomAccessFile(File(context.filesDir, "voice-event-$source.lock"), "rw").use { lock ->
                    lock.channel.lock().use {
                        val stream = file.startWrite()
                        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                        catch (failure: Exception) { file.failWrite(stream); throw failure }
                    }
                }
            }
        }
    }
    private val writeLock = Any()
    fun report(context: Context): String = buildString {
        val saved = AssistantVoicePreferences(context).load()
        val pack = NeuralVoicePack(context)
        appendLine("JARVIS voice debug · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android API: ${android.os.Build.VERSION.SDK_INT}; ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}; build: ${BuildConfig.BUILD_TYPE}")
        appendLine("Saved profile: ${saved.profile.label}")
        appendLine("Saved Original preset: ${NeuralVoiceCatalog.resolve(saved.originalVoice).label}")
        appendLine("Saved female voice ID: ${saved.femaleVoice.ifBlank { "Automatic" }}")
        appendLine("Verified Kokoro pack installed: ${pack.installed()}")
        appendLine("Configured response engine: ${if (saved.profile == AssistantVoiceProfile.ORIGINAL && pack.installed()) "Kokoro offline" else "Android TTS (female; availability depends on engine)"}")
        appendLine("Kokoro configuration: v0.19 FP32 · 2 threads · speed 1.0")
        appendLine("Synthesis: native generate without JVM callback")
        appendLine("Native playback: PCM16 · media-volume route · transient audio focus")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        appendLine("Media volume: ${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}, muted=${audio.isStreamMute(android.media.AudioManager.STREAM_MUSIC)}")
        appendLine("Phrase audio cache bytes: ${NeuralSpeechCache(File(context.cacheDir, "neural-phrases")).bytes()}")
        val options = VoiceResponsePreferences.load(context)
        appendLine("Speech enabled: regular=${options.regularSpeech}, live=${options.liveSpeech}")
        for (source in listOf("preview", "assistant")) {
            val json = runCatching { JSONObject(String(AtomicFile(File(context.filesDir, "voice-event-$source.json")).readFully(), Charsets.UTF_8)) }.getOrNull()
            appendLine("Last $source audio event: ${json?.toString() ?: "No recorded event"}")
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull()?.forEach {
                appendLine("Recent process exit: time=${it.timestamp}, process=${it.processName}, reason=${it.reason}, status=${it.status}")
            }
        }
        appendLine("Events describe the last recorded audio operation, not current microphone or playback state.")
        append("Report excludes spoken text, phrase contents, conversation history and credentials.")
    }
}
