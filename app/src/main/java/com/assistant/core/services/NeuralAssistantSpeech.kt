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

/** Exact Kokoro male speaker IDs; no pitch shifting or Android gender guessing. */
class NeuralAssistantSpeech(context: Context, private val source: String = "assistant") {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    // Synthesize the next phrase while the preceding phrase is already playing.
    private val playbackWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val player = NeuralPcmPlayer(app)
    @Volatile private var closed = false
    private var engine: OfflineTts? = null
    private val cache = NeuralSpeechCache(File(app.cacheDir, "neural-phrases"))

    fun speak(text: String, voice: NeuralVoiceCatalog.Voice, done: () -> Unit, error: (String) -> Unit, cachePhrase: Boolean = false,
              onAudio: () -> Unit = {}, onProgress: () -> Unit = {}) {
        stop()
        if (closed) return
        val token = generation.get()
        val started = android.os.SystemClock.elapsedRealtime()
        worker.execute {
            var firstAudioMs = -1L
            var cacheHits = 0
            var synthesisMillis = 0L
            var generatedChunks = 0
            var terminalRecorded = false
            val playbackAborted = AtomicBoolean(false)
            val queuedPlayback = java.util.ArrayDeque<Future<*>>()
            var session: NeuralPcmPlayer.Session? = null
            try {
                val pack = NeuralVoicePack(app)
                check(pack.installed()) { "Offline voices are not installed" }
                val fingerprint = File(pack.root, ".ready").readText()
                NeuralVoiceDiagnostics.record(app, source, voice.id, "preparing")
                if (generation.get() != token || closed) return@execute
                // A single output session owns audio focus and AudioTrack for
                // the complete reply; never reopen the device at each sentence.
                val replySession = player.newSession()
                session = replySession
                for (chunk in SpeechChunks.lowLatency(text)) {
                    if (generation.get() != token || closed) return@execute
                    val key = if (cachePhrase) cache.key(fingerprint, voice.id, chunk) else null
                    val cached = key?.let(cache::read)
                    val audio = cached ?: run {
                        // Avoid synchronous file writes on every phrase: that
                        // needlessly starves CPU synthesis between spoken chunks.
                        if (engine == null) NeuralVoiceDiagnostics.record(app, source, voice.id, "initializing_model")
                        val synthesizer = engine ?: OfflineTts(config = configuration(pack.root)).also { engine = it }
                        if (generation.get() != token || closed) return@execute
                        if (generatedChunks == 0) NeuralVoiceDiagnostics.record(app, source, voice.id, "generating_audio")
                        val began = android.os.SystemClock.elapsedRealtime()
                        // Stop invalidates the token; a cancelled native chunk
                        // is discarded before playback or caching.
                        val generated = synthesizer.generate(chunk, sid = voice.speaker, speed = 1.0f)
                        synthesisMillis += android.os.SystemClock.elapsedRealtime() - began
                        generatedChunks++
                        NeuralSpeechCache.Clip(generated.sampleRate, generated.samples)
                    }
                    if (cached != null) cacheHits++
                    if (generation.get() != token || closed) return@execute
                    check(audio.samples.isNotEmpty() && audio.samples.all { it.isFinite() }) { "No valid audio was generated" }
                    if (key != null && cached == null) runCatching { cache.write(key, audio) }
                    // Playback uses a dedicated queue. Keeping at most one phrase
                    // prefetched removes synth-time gaps without buffering whole replies.
                    val hitsSoFar = cacheHits
                    queuedPlayback.addLast(playbackWorker.submit {
                        if (generation.get() == token && !closed && !playbackAborted.get()) {
                            replySession.enqueue(audio, active = {
                                generation.get() == token && !closed && !playbackAborted.get()
                            }, firstQueued = {
                                if (firstAudioMs < 0) {
                                    firstAudioMs = android.os.SystemClock.elapsedRealtime() - started
                                    NeuralVoiceDiagnostics.record(app, source, voice.id, "audio_queued", firstAudioMs, hitsSoFar)
                                    main.post { if (generation.get() == token && !closed) onAudio() }
                                }
                            })
                            // Playback progress keeps Live Chat's stall watchdog
                            // alive during healthy multi-minute utterances.
                            main.post { if (generation.get() == token && !closed) onProgress() }
                        }
                    })
                    if (queuedPlayback.size >= 2) queuedPlayback.removeFirst().get()
                }
                while (queuedPlayback.isNotEmpty()) queuedPlayback.removeFirst().get()
                if (generation.get() != token || closed) return@execute
                // The last PCM writes can still be playing. Do not advance
                // the conversation until every queued frame has been heard.
                playbackWorker.submit {
                    replySession.drain { generation.get() == token && !closed && !playbackAborted.get() }
                }.get()
                if (generation.get() != token || closed) return@execute
                NeuralVoiceDiagnostics.record(app, source, voice.id, "completed", firstAudioMs, cacheHits,
                    synthesisMillis = synthesisMillis, generatedChunks = generatedChunks,
                    outputUnderruns = replySession.underrunCount())
                terminalRecorded = true
                main.post { if (generation.get() == token && !closed) done() }
            } catch (failure: Throwable) {
                playbackAborted.set(true)
                // Cancel only this reply. A late error from an obsolete
                // generation must never stop the next reply's audio.
                session?.cancel()
                if (generation.get() == token && !closed) {
                    NeuralVoiceDiagnostics.record(app, source, voice.id, "error", firstAudioMs, cacheHits,
                        failure.javaClass.simpleName, synthesisMillis, generatedChunks, session?.underrunCount() ?: -1)
                    terminalRecorded = true
                    main.post { if (generation.get() == token && !closed) error(failure.javaClass.simpleName) }
                }
            } finally {
                playbackAborted.set(true)
                val finishingSession = session
                if (finishingSession != null) {
                    // Output cleanup stays serialized behind previous PCM writes,
                    // so cancelling speech cannot release AudioTrack mid-write.
                    runCatching { playbackWorker.submit { finishingSession.close() }.get() }
                }
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
