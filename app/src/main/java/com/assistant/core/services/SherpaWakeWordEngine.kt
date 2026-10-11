package com.assistant.core.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread
import kotlin.math.abs

class SherpaWakeWordEngine(
    private val context: Context,
    private val wakePhrase: String,
    private val sensitivity: Float,
    private val onDetected: () -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    }

    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Microphone permission is required for Hey Jarvis.")
            return false
        }
        return try {
            onStatus("Sherpa: verifying model and wake tokens...")
            val modelFiles = context.assets.list(MODEL_DIR)?.toSet().orEmpty()
            fun modelFile(prefix: String): String {
                val name = modelFiles.firstOrNull { it.startsWith(prefix) && it.endsWith(".int8.onnx") }
                    ?: modelFiles.firstOrNull { it.startsWith(prefix) && it.endsWith(".onnx") }
                    ?: error("Missing $prefix model in $MODEL_DIR")
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
                    numThreads = 1
                ),
                keywordsFile = keywordsPath,
                // Sherpa's own KWS examples use a stronger keyword boost and
                // low acoustic threshold. The previous 1.5/0.27 defaults were
                // too conservative for an always-on wake phrase.
                keywordsScore = 3.0f,
                keywordsThreshold = thresholdFor(sensitivity),
                numTrailingBlanks = 2
            )
            val kws = KeywordSpotter(assetManager = context.assets, config = config)
            val phrase = wakePhrase.trim().ifBlank { "hey jarvis" }
            // Do not load the model's bundled generic keyword list in addition
            // to the configured wake phrase. This stream should react only to
            // the user's JARVIS wake phrase.
            // The generated keywords file contains BPE pieces; plain English is invalid here.
            val onlineStream = kws.createStream()
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
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audio.release(); onlineStream.release(); kws.release()
                recorder = null; stream = null; spotter = null; running = false
                onStatus("Sherpa microphone did not enter recording state.")
                return false
            }
            onStatus("Sherpa microphone active at 16 kHz; listening for '$phrase'.")
            worker = thread(start = true, isDaemon = true, name = "jarvis-sherpa-kws") {
                processAudio(kws, onlineStream, audio)
            }
            true
        } catch (t: Throwable) {
            onStatus("Sherpa wake engine failed: ${t.message ?: t.javaClass.simpleName}")
            stop()
            false
        }
    }

    fun stop() {
        running = false
        runCatching { recorder?.stop() }
        worker?.interrupt()
        worker = null
        runCatching { recorder?.release() }
        recorder = null
        runCatching { stream?.release() }
        stream = null
        runCatching { spotter?.release() }
        spotter = null
    }

    private fun processAudio(kws: KeywordSpotter, onlineStream: OnlineStream, audio: AudioRecord) {
        val buffer = ShortArray(1600)
        var chunks = 0
        var peakSinceReport = 0
        var lastReportAt = System.currentTimeMillis()
        try {
            while (running) {
                val n = audio.read(buffer, 0, buffer.size)
                if (n < 0) {
                    onStatus("Sherpa microphone read failed ($n).")
                    break
                }
                if (n == 0) continue
                var peak = 0
                for (i in 0 until n) {
                    val level = abs(buffer[i].toInt())
                    if (level > peak) peak = level
                }
                if (peak > peakSinceReport) peakSinceReport = peak
                chunks += 1
                val now = System.currentTimeMillis()
                if (now - lastReportAt >= 3000L) {
                    val pct = (peakSinceReport * 100 / 32767).coerceIn(0, 100)
                    onStatus("Sherpa audio active: peak $pct%, chunks $chunks; waiting for '$wakePhrase'.")
                    peakSinceReport = 0
                    chunks = 0
                    lastReportAt = now
                }
                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                onlineStream.acceptWaveform(samples, SAMPLE_RATE)
                while (running && kws.isReady(onlineStream)) {
                    kws.decode(onlineStream)
                    val keyword = kws.getResult(onlineStream).keyword
                    if (keyword.isNotBlank()) {
                        onStatus("Sherpa detected keyword '$keyword'.")
                        kws.reset(onlineStream)
                        // Release the continuous KWS microphone before command
                        // SpeechRecognizer is started by the detection callback.
                        running = false
                        releaseDetectedSession(audio, onlineStream, kws)
                        onDetected()
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) onStatus("Sherpa audio loop stopped: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun releaseDetectedSession(
        audio: AudioRecord,
        onlineStream: OnlineStream,
        kws: KeywordSpotter
    ) {
        runCatching { audio.stop() }
        runCatching { audio.release() }
        if (recorder === audio) recorder = null

        runCatching { onlineStream.release() }
        if (stream === onlineStream) stream = null

        runCatching { kws.release() }
        if (spotter === kws) spotter = null

        // The worker is about to return; clear the reference without joining
        // the current thread.
        worker = null
    }

    private fun thresholdFor(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return (0.22f - (v * 0.20f)).coerceIn(0.02f, 0.20f)
    }
}
