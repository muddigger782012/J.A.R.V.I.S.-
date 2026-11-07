package com.jarvis.voicebox

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/** All player state and callbacks live on the main thread. Stale downloads cannot speak. */
class VoiceboxSpeech(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var token = 0L
    private var closed = false
    private var client: VoiceboxClient? = null
    private var player: MediaPlayer? = null
    private var audio: File? = null
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var focus: android.media.AudioFocusRequest? = null
    fun speak(text: String, done: () -> Unit, error: (String) -> Unit, onAudio: () -> Unit = {}, onProgress: () -> Unit = {}) {
        stop()
        if (closed) return
        val current = token
        val started = android.os.SystemClock.elapsedRealtime()
        var lastPosition = -1
        val heartbeat = object : Runnable {
            override fun run() {
                if (!closed && token == current) {
                    val output = player
                    if (output == null) {
                        if (android.os.SystemClock.elapsedRealtime() - started > 65000) {
                            stop(); error("Voicebox generation timed out"); return
                        }
                        onProgress()
                    } else if (runCatching { output.isPlaying }.getOrDefault(false)) {
                        val position = runCatching { output.currentPosition }.getOrDefault(-1)
                        if (position > lastPosition) { lastPosition = position; onProgress() }
                    }
                    main.postDelayed(this, 2000)
                }
            }
        }
        main.post(heartbeat)
        val config = VoiceboxPreferences.load(app)
        val request = runCatching { VoiceboxClient(config.endpoint) }.getOrElse { stop(); error(it.message ?: "Invalid Voicebox host"); return }
        client = request
        worker.execute {
            var file: File? = null
            try {
                val bytes = request.speech(text, config.profile)
                file = File.createTempFile("voicebox-", ".wav", app.cacheDir).also { it.writeBytes(bytes) }
                val ready = file
                main.post {
                    if (closed || token != current) { ready.delete(); return@post }
                    audio = ready
                    try {
                        val output = MediaPlayer()
                        player = output
                        output.setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        output.setOnCompletionListener { if (token == current) { stop(); done() } }
                        output.setOnErrorListener { _, _, _ ->
                            if (token == current) { stop(); error("Voicebox audio playback failed") }; true
                        }
                        output.setOnPreparedListener { if (token == current) {
                            val requestFocus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setOnAudioFocusChangeListener({ change ->
                                    if (change == android.media.AudioManager.AUDIOFOCUS_LOSS && token == current) { stop(); done() }
                                }, main).build()
                            focus = requestFocus
                            if (manager.requestAudioFocus(requestFocus) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                                it.start(); onAudio()
                            } else { stop(); error("Voicebox could not acquire audio focus") }
                        } }
                        output.setDataSource(ready.absolutePath)
                        output.prepareAsync()
                    } catch (failure: Exception) { if (token == current) { stop(); error("Voicebox audio playback failed") } }
                }
            } catch (failure: Exception) {
                file?.delete()
                main.post { if (!closed && token == current) { stop(); error(failure.message ?: "Voicebox connection failed") } }
            }
        }
    }
    fun stop() {
        token++
        client?.cancel(); client = null
        player?.release(); player = null
        audio?.delete(); audio = null
        focus?.let { manager.abandonAudioFocusRequest(it) }; focus = null
    }
    fun shutdown() { closed = true; stop(); worker.shutdownNow() }
}
