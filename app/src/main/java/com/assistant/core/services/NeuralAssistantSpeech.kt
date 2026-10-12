package com.assistant.core.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Exact Kokoro male speaker IDs; no pitch shifting or Android gender guessing. */
class NeuralAssistantSpeech(context: Context, private val source: String = "assistant") {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val player = NeuralPcmPlayer(app)
    @Volatile private var closed = false
    private var engine: OfflineTts? = null
    private val cache = NeuralSpeechCache(File(app.cacheDir, "neural-phrases"))

    fun speak(text: String, voice: NeuralVoiceCatalog.Voice, done: () -> Unit, error: (String) -> Unit, cachePhrase: Boolean = false) {
        stop()
        if (closed) return
        val token = generation.get()
        val started = android.os.SystemClock.elapsedRealtime()
        worker.execute {
            var firstAudioMs = -1L
            var cacheHits = 0
            var terminalRecorded = false
            try {
                val pack = NeuralVoicePack(app)
                check(pack.installed()) { "Offline voices are not installed" }
                val fingerprint = File(pack.root, ".ready").readText()
                NeuralVoiceDiagnostics.record(app, source, voice.id, "preparing")
                if (generation.get() != token || closed) return@execute
                for (chunk in SpeechChunks.split(text, 180)) {
                    if (generation.get() != token || closed) return@execute
                    val key = if (cachePhrase) cache.key(fingerprint, voice.id, chunk) else null
                    val cached = key?.let(cache::read)
                    val audio = cached ?: run {
                        val synthesizer = engine ?: OfflineTts(config = configuration(pack.root)).also { engine = it }
                        if (generation.get() != token || closed) return@execute
                        val generated = synthesizer.generateWithCallback(chunk, sid = voice.speaker, speed = 1.0f) {
                            if (generation.get() == token && !closed) 1 else 0
                        }
                        NeuralSpeechCache.Clip(generated.sampleRate, generated.samples)
                    }
                    if (cached != null) cacheHits++
                    if (generation.get() != token || closed) return@execute
                    check(audio.samples.isNotEmpty() && audio.samples.all { it.isFinite() }) { "No valid audio was generated" }
                    if (key != null && cached == null) runCatching { cache.write(key, audio) }
                    player.play(audio, active = { generation.get() == token && !closed }, firstQueued = {
                        if (firstAudioMs < 0) {
                            firstAudioMs = android.os.SystemClock.elapsedRealtime() - started
                            NeuralVoiceDiagnostics.record(app, source, voice.id, "audio_queued", firstAudioMs, cacheHits)
                        }
                    })
                }
                if (generation.get() != token || closed) return@execute
                NeuralVoiceDiagnostics.record(app, source, voice.id, "completed", firstAudioMs, cacheHits)
                terminalRecorded = true
                main.post { if (generation.get() == token && !closed) done() }
            } catch (failure: Throwable) {
                if (generation.get() == token && !closed) {
                    NeuralVoiceDiagnostics.record(app, source, voice.id, "error", firstAudioMs, cacheHits, failure.javaClass.simpleName)
                    terminalRecorded = true
                    main.post { if (generation.get() == token && !closed) error(failure.javaClass.simpleName) }
                }
            } finally {
                if (!terminalRecorded && (generation.get() != token || closed))
                    NeuralVoiceDiagnostics.record(app, source, voice.id, "cancelled", firstAudioMs, cacheHits)
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        player.stop()
    }
    fun shutdown() {
        closed = true
        stop()
        worker.execute { engine?.release(); engine = null }
        worker.shutdown()
    }

    companion object {
        private fun configuration(root: File) = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(model = File(root, "model.onnx").absolutePath,
                voices = File(root, "voices.bin").absolutePath,
                tokens = File(root, "tokens.txt").absolutePath, dataDir = File(root, "espeak-ng-data").absolutePath),
            numThreads = 2, debug = false, provider = "cpu"), maxNumSentences = 1)
    }
}
