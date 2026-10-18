package com.assistant.core.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class SherpaWakeWordEngine(
    private val context: Context,
    private val wakePhrase: String,
    private val sensitivity: Float,
    private val onDetected: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFailure: () -> Unit = {}
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    }

    @Volatile private var running = false
    @Volatile private var stalled = false
    private var lastDecodeDurationMs = 0L
    private val watchdog = Handler(Looper.getMainLooper())

    fun hasLiveWorker(): Boolean = worker?.isAlive == true
    fun hasStalled(): Boolean = stalled
    private fun stage(value: String) {
        runCatching { java.io.File(context.filesDir, "wake-stage.txt").writeText(value) }
    }
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    fun start(): Boolean {
        if (running) return true
        if (hasLiveWorker()) {
            onStatus("Sherpa native worker is still stopping. Restart the app before retrying.")
            return false
        }
        stalled = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Microphone permission is required for Hey Jarvis.")
            return false
        }
        return try {
            onStatus("Sherpa: verifying model and wake tokens...")
            val modelFiles = context.assets.list(MODEL_DIR)?.toSet().orEmpty()
            fun modelFile(prefix: String): String {
                // Use the FP32 CPU path while investigating devices that stall
                // on their first quantized decode. Do not silently select INT8.
                val name = modelFiles.sorted().firstOrNull {
                    it.startsWith(prefix) && it.endsWith(".onnx") && !it.endsWith(".int8.onnx")
                } ?: error("Missing FP32 $prefix model in $MODEL_DIR")
                return "$MODEL_DIR/$name"
            }
            val keywordsPath = "$MODEL_DIR/jarvis-keywords.txt"
            val keywords = context.assets.open(keywordsPath).bufferedReader().use { it.readText().trim() }
            require(keywords.isNotBlank() && keywords.contains("@HEY_JARVIS")) { "Missing generated HEY_JARVIS BPE tokens" }
            onStatus("Sherpa: loading English KWS model...")
            val config = KeywordSpotterConfig(
                featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = modelFile("encoder-"),
                        decoder = modelFile("decoder-"),
                        joiner = modelFile("joiner-")
                    ),
                    tokens = "$MODEL_DIR/tokens.txt",
                    modelType = "zipformer2",
                    provider = "cpu",
                    numThreads = 1
                ),
                keywordsFile = keywordsPath,
                // Sherpa's own KWS examples use a stronger keyword boost and
                // low acoustic threshold. The previous 1.5/0.27 defaults were
                // too conservative for an always-on wake phrase.
                keywordsScore = 3.0f,
                keywordsThreshold = WakeWordTuning.thresholdFor(sensitivity),
                numTrailingBlanks = 1
            )
            stage("sherpa-before-native-model")
            val kws = KeywordSpotter(assetManager = context.assets, config = config)
            stage("sherpa-after-native-model")
            val phrase = wakePhrase.trim().ifBlank { "hey jarvis" }
            // Do not load the model's bundled generic keyword list in addition
            // to the configured wake phrase. This stream should react only to
            // the user's JARVIS wake phrase.
            // The generated keywords file contains BPE pieces; plain English is invalid here.
            stage("sherpa-before-stream")
            val onlineStream = kws.createStream()
            stage("sherpa-after-stream")
            if (onlineStream.ptr == 0L) {
                kws.release()
                onStatus("Sherpa could not create a stream for '$phrase'.")
                return false
            }

            onStatus("Sherpa: keyword stream created; opening microphone...")
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) {
                onlineStream.release(); kws.release()
                return false
            }
            val audio = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBytes * 2
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release(); onlineStream.release(); kws.release()
                return false
            }

            spotter = kws
            stream = onlineStream
            recorder = audio
            running = true
            // The worker owns all native resources once it starts. A stop request
            // may unblock AudioRecord, but must never delete objects inside JNI.
            worker = thread(start = false, isDaemon = true, name = "jarvis-sherpa-kws") {
                processAudio(kws, onlineStream, audio)
            }
            worker?.start()
            true
        } catch (t: Throwable) {
            onStatus("Sherpa wake engine failed: ${t.message ?: t.javaClass.simpleName}")
            stop()
            false
        }
    }

    fun stop() {
        running = false
        watchdog.removeCallbacksAndMessages(null)
        runCatching { recorder?.stop() }
        worker?.interrupt()
        // Thread.interrupt cannot cancel native inference. The worker's finally
        // block releases resources only after the native call actually returns.
        if (worker?.isAlive != true) releaseSession(recorder, stream, spotter)
    }

    private fun decodeChecked(kws: KeywordSpotter, onlineStream: OnlineStream, label: String) {
        val started = SystemClock.elapsedRealtime()
        val timeout = Runnable {
            if (running) {
                stalled = true
                running = false
                stage("sherpa-native-decode-timeout-$label")
                runCatching { recorder?.stop() }
                onStatus("Wake word unavailable: native decode stalled for 10 seconds ($label). Restart the app; push-to-talk is available.")
                onFailure()
            }
        }
        stage("sherpa-before-decode-$label")
        watchdog.postDelayed(timeout, 10_000L)
        try {
            kws.decode(onlineStream)
            stage("sherpa-after-decode-$label")
        } finally {
            watchdog.removeCallbacks(timeout)
        }
        lastDecodeDurationMs = SystemClock.elapsedRealtime() - started
        if (stalled) error("Native decode returned after timeout")
        if (label == "self-test") {
            onStatus("Sherpa native decode self-test passed in ${SystemClock.elapsedRealtime() - started} ms.")
        }
    }

    private fun processAudio(kws: KeywordSpotter, onlineStream: OnlineStream, audio: AudioRecord) {
        val buffer = ShortArray(1600)
        var chunks = 0
        var peakSinceReport = 0
        var sumSquaresSinceReport = 0.0
        var samplesSinceReport = 0L
        var lowSignalReports = 0
        var lastReportAt = System.currentTimeMillis()
        var totalChunks = 0
        var detected = false
        var sessionPeak = 0
        var sessionSumSquares = 0.0
        var sessionSamples = 0L
        var slowDecodeCount = 0
        var maxDecodeMs = 0L
        try {
            // Exercise the exact native inference path before claiming the
            // microphone is listening. Silence is never treated as a wake event.
            onStatus("Sherpa checking native decoder before opening microphone...")
            onlineStream.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
            check(kws.isReady(onlineStream)) { "Native decoder did not become ready for self-test" }
            while (running && kws.isReady(onlineStream)) decodeChecked(kws, onlineStream, "self-test")
            if (!running) return
            kws.reset(onlineStream)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not enter recording state" }
            stage("sherpa-mic-recording")
            onStatus("Sherpa microphone active at 16 kHz; listening for '$wakePhrase'.")
            while (running) {
                if (totalChunks == 0) {
                    stage("sherpa-worker-before-read")
                    onStatus("Sherpa audio worker started; waiting for microphone samples.")
                }
                val n = audio.read(buffer, 0, buffer.size)
                if (n < 0) {
                    onStatus("Sherpa microphone read failed ($n).")
                    error("Microphone read failed ($n)")
                }
                if (n == 0) continue
                if (totalChunks == 0) onStatus("Sherpa received first microphone audio buffer.")
                var peak = 0
                for (i in 0 until n) {
                    val normalized = buffer[i].toDouble() / 32768.0
                    sumSquaresSinceReport += normalized * normalized
                    sessionSumSquares += normalized * normalized
                    sessionSamples++
                }
                samplesSinceReport += n
                for (i in 0 until n) {
                    val level = abs(buffer[i].toInt())
                    if (level > peak) peak = level
                }
                if (peak > peakSinceReport) peakSinceReport = peak
                if (peak > sessionPeak) sessionPeak = peak
                chunks += 1
                totalChunks += 1
                val now = System.currentTimeMillis()
                if (now - lastReportAt >= 3000L) {
                    val pct = (peakSinceReport * 100 / 32767).coerceIn(0, 100)
                    val rmsPct = if (samplesSinceReport == 0L) 0 else
                        (sqrt(sumSquaresSinceReport / samplesSinceReport) * 100).toInt().coerceIn(0, 100)
                    lowSignalReports = if (pct < 2) lowSignalReports + 1 else 0
                    stage("sherpa-audio-running-peak-$pct-rms-$rmsPct")
                    val warning = if (lowSignalReports >= 3) " Low microphone signal for 9+ seconds; check microphone access and distance." else ""
                    onStatus("Sherpa audio active: peak $pct%, RMS $rmsPct%, chunks $chunks, last decode ${lastDecodeDurationMs}ms; waiting for '$wakePhrase'." + warning)
                    peakSinceReport = 0
                    sumSquaresSinceReport = 0.0
                    samplesSinceReport = 0L
                    chunks = 0
                    lastReportAt = now
                }
                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                if (totalChunks == 1) {
                    stage("sherpa-worker-before-accept")
                    onStatus("Sherpa submitting first audio buffer to native stream.")
                }
                onlineStream.acceptWaveform(samples, SAMPLE_RATE)
                if (totalChunks == 1) {
                    stage("sherpa-worker-after-accept")
                    onStatus("Sherpa accepted first audio buffer; decoder running.")
                }
                stage("sherpa-worker-before-is-ready-$totalChunks")
                val ready = kws.isReady(onlineStream)
                stage("sherpa-worker-after-is-ready-$totalChunks-$ready")
                if (totalChunks <= 20) onStatus("Sherpa native readiness at chunk $totalChunks: $ready.")
                var decoderReady = ready
                while (running && decoderReady) {
                    stage("sherpa-worker-before-decode-$totalChunks")
                    if (totalChunks <= 20) onStatus("Sherpa decoder ready after $totalChunks audio chunks; entering native decode.")
                    decodeChecked(kws, onlineStream, "chunk-$totalChunks")
                    if (lastDecodeDurationMs > maxDecodeMs) maxDecodeMs = lastDecodeDurationMs
                    if (lastDecodeDurationMs >= 500L) slowDecodeCount++
                    stage("sherpa-worker-after-decode-$totalChunks")
                    if (totalChunks <= 20) onStatus("Sherpa native decode completed at chunk $totalChunks.")
                    val keyword = kws.getResult(onlineStream).keyword
                    if (keyword.isNotBlank()) {
                        val peakPct = (sessionPeak * 100 / 32767).coerceIn(0, 100)
                        val rmsPct = if (sessionSamples == 0L) 0 else (sqrt(sessionSumSquares / sessionSamples) * 100).toInt().coerceIn(0, 100)
                        onStatus("Sherpa detected keyword '$keyword' after $totalChunks audio buffers; session peak $peakPct%, RMS $rmsPct%, max decode ${maxDecodeMs}ms, slow decodes $slowDecodeCount (>=500ms).")
                        running = false
                        detected = true
                        return
                    }
                    decoderReady = running && kws.isReady(onlineStream)
                }
            }
        } catch (t: Throwable) {
            stage("sherpa-worker-failed-" + t.javaClass.simpleName + "-" + (t.message ?: "unknown"))
            if (running) {
                onStatus("Wake word unavailable: ${t.message ?: t.javaClass.simpleName}")
                onFailure()
            }
        } finally {
            running = false
            watchdog.removeCallbacksAndMessages(null)
            releaseSession(audio, onlineStream, kws)
            if (detected) onDetected()
        }
    }

    private fun releaseSession(audio: AudioRecord?, onlineStream: OnlineStream?, kws: KeywordSpotter?) {
        runCatching { audio?.stop() }
        runCatching { audio?.release() }
        if (recorder === audio) recorder = null
        runCatching { onlineStream?.release() }
        if (stream === onlineStream) stream = null
        runCatching { kws?.release() }
        if (spotter === kws) spotter = null
    }

}
