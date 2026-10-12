package com.assistant.core.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
    @Volatile private var track: AudioTrack? = null
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
                    val buffer = maxOf(4096, AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT))
                    val output = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build()
                    try {
                        track = output
                        if (generation.get() != token || closed) return@execute
                        output.play()
                        var offset = 0
                        while (offset < audio.samples.size && generation.get() == token && !closed) {
                            val written = output.write(audio.samples, offset, minOf(2048, audio.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                            check(written > 0) { "Audio playback failed" }
                            offset += written
                            if (firstAudioMs < 0) {
                                firstAudioMs = android.os.SystemClock.elapsedRealtime() - started
                                NeuralVoiceDiagnostics.record(app, source, voice.id, "audio_queued", firstAudioMs, cacheHits)
                            }
                        }
                        // A blocking write only queues frames; wait for the final audio to play
                        // before letting live chat reopen the microphone.
                        while (generation.get() == token && !closed && output.playbackHeadPosition.toLong() < audio.samples.size) {
                            Thread.sleep(15)
                        }
                    } finally {
                        if (track === output) track = null
                        runCatching { output.stop() }
                        output.release()
                    }
                }
                NeuralVoiceDiagnostics.record(app, source, voice.id, "completed", firstAudioMs, cacheHits)
                main.post { if (generation.get() == token && !closed) done() }
            } catch (failure: Throwable) {
                NeuralVoiceDiagnostics.record(app, source, voice.id, "error", firstAudioMs, cacheHits, failure.javaClass.simpleName)
                main.post { if (generation.get() == token && !closed) error(failure.javaClass.simpleName) }
            } finally {
                if (generation.get() != token || closed)
                    NeuralVoiceDiagnostics.record(app, source, voice.id, "cancelled", firstAudioMs, cacheHits)
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } }
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
