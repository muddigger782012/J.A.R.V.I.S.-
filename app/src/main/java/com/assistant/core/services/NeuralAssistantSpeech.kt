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
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Kokoro uses native per-sentence streaming, keeping one uninterrupted PCM output session. */
class NeuralAssistantSpeech(context: Context, private val source: String = "assistant") {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val playbackWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val player = NeuralPcmPlayer(app)
    @Volatile private var closed = false
    private var engine: OfflineTts? = null
    private val cache = NeuralSpeechCache(File(app.cacheDir, "neural-phrases"))

    fun speak(text: String, voice: NeuralVoiceCatalog.Voice, done: () -> Unit,
              error: (String) -> Unit, cachePhrase: Boolean = false,
              onAudio: () -> Unit = {}, onProgress: () -> Unit = {}) {
        stop()
        if (closed) return
        val token = generation.get()
        val started = android.os.SystemClock.elapsedRealtime()
        worker.execute {
            val firstAudioMs = AtomicLong(-1L)
            var cacheHits = 0
            var terminalRecorded = false
            val aborted = AtomicBoolean(false)
            val pending = java.util.ArrayDeque<Future<*>>()
            var session: NeuralPcmPlayer.Session? = null
            val active = { generation.get() == token && !closed && !aborted.get() }
            try {
                val pack = NeuralVoicePack(app)
                check(pack.installed()) { "Offline voices are not installed" }
                val fingerprint = File(pack.root, ".ready").readText()
                NeuralVoiceDiagnostics.record(app, source, voice.id, "preparing")
                if (!active()) return@execute

                val output = player.newSession()
                session = output
                // The JNI callback owns FloatArray memory only until it
                // returns. Copy every sentence chunk before queueing it.
                fun deliver(rate: Int, samples: FloatArray) {
                    if (!active()) return
                    check(samples.isNotEmpty() && samples.all { it.isFinite() }) { "Invalid native audio samples" }
                    val clip = NeuralSpeechCache.Clip(rate, samples.copyOf())
                    pending.addLast(playbackWorker.submit {
                        if (active()) output.enqueue(clip, active = active, firstQueued = {
                            val millis = android.os.SystemClock.elapsedRealtime() - started
                            if (firstAudioMs.compareAndSet(-1L, millis)) {
                                NeuralVoiceDiagnostics.record(app, source, voice.id, "audio_queued", millis, cacheHits)
                                main.post { if (active()) onAudio() }
                            }
                        })
                    })
                    // Keep at most two sentence segments in flight. Model
                    // synthesis can overlap uninterrupted output playback.
                    if (pending.size >= 3) pending.removeFirst().get()
                }

                val key = if (cachePhrase && text.length <= 220)
                    cache.key(fingerprint, voice.id, text) else null
                val cached = key?.let(cache::read)
                if (cached != null) {
                    cacheHits = 1
                    deliver(cached.sampleRate, cached.samples)
                    main.post { if (active()) onProgress() }
                } else {
                    NeuralVoiceDiagnostics.record(app, source, voice.id, "generating_audio")
                    val synth = engine ?: OfflineTts(config = configuration(pack.root)).also { engine = it }
                    if (!active()) return@execute
                    val stats = SpeechStreamAccounting()
                    val rate = synth.sampleRate()
                    // Native model emits each completed sentence as PCM.
                    // Avoid re-running ONNX inference for artificial 70/180
                    // character splits, which caused long pauses mid-answer.
                    val generated = synth.generateWithCallback(text, sid = voice.speaker, speed = 1.0f) { samples ->
                        if (!active()) 0 else {
                            if (samples.isNotEmpty()) {
                                stats.receivedCallback(samples.size)
                                deliver(rate, samples)
                                main.post { if (active()) onProgress() }
                            }
                            if (active()) 1 else 0
                        }
                    }
                    if (!active()) return@execute
                    val firstMissingFrame = stats.undeliveredStart(generated.samples.size)
                    if (firstMissingFrame < generated.samples.size) {
                        deliver(generated.sampleRate,
                            generated.samples.copyOfRange(firstMissingFrame, generated.samples.size))
                        main.post { if (active()) onProgress() }
                    }
                    if (key != null && generated.samples.isNotEmpty()) {
                        runCatching { cache.write(key, NeuralSpeechCache.Clip(generated.sampleRate, generated.samples)) }
                    }
                }
                while (pending.isNotEmpty()) pending.removeFirst().get()
                if (!active()) return@execute
                playbackWorker.submit {
                    output.drain(active, onPlaybackProgress = {
                        main.post { if (active()) onProgress() }
                    })
                }.get()
                if (!active()) return@execute
                NeuralVoiceDiagnostics.record(app, source, voice.id, "completed", firstAudioMs.get(), cacheHits)
                terminalRecorded = true
                main.post { if (active()) done() }
            } catch (failure: Throwable) {
                aborted.set(true)
                session?.cancel()
                if (generation.get() == token && !closed) {
                    NeuralVoiceDiagnostics.record(app, source, voice.id, "error",
                        firstAudioMs.get(), cacheHits, failure.javaClass.simpleName)
                    terminalRecorded = true
                    main.post { if (generation.get() == token && !closed) error(failure.javaClass.simpleName) }
                }
            } finally {
                aborted.set(true)
                val ending = session
                if (ending != null) {
                    runCatching { playbackWorker.submit { ending.close() }.get() }
                }
                if (!terminalRecorded && (generation.get() != token || closed))
                    NeuralVoiceDiagnostics.record(app, source, voice.id, "cancelled", firstAudioMs.get(), cacheHits)
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
        playbackWorker.shutdown()
    }

    companion object {
        private fun configuration(root: File) = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(model = File(root, "model.onnx").absolutePath,
                voices = File(root, "voices.bin").absolutePath,
                tokens = File(root, "tokens.txt").absolutePath, dataDir = File(root, "espeak-ng-data").absolutePath),
            numThreads = 2, debug = false, provider = "cpu"), maxNumSentences = 1)
    }
}
