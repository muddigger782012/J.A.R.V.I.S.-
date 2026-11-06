package com.assistant.core.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Experimental Live Chat mic owner, Android 13+. One AudioRecord persists
 * between utterances and TTS turns; the current Android recognizer receives
 * PCM via an ephemeral pipe, rather than opening the microphone itself.
 *
 * Some vendor recognizers ignore EXTRA_AUDIO_SOURCE. Keep this opt-in, never
 * run alongside legacy microphone capture, and fall back on provider errors.
 */
internal class PersistentPcmAudioSource(
    private val context: Context,
    private val onFailure: (String) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAMES = 320 // 20 milliseconds @ 16 kHz
        fun supported() = Build.VERSION.SDK_INT >= 33
    }

    private val lock = Any()
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var pipe: PipeWriter? = null
    private val activity = PcmSpeechActivityGate()

    private class PipeWriter(private val output: ParcelFileDescriptor.AutoCloseOutputStream) {
        private val chunks = ArrayBlockingQueue<ByteArray>(40)
        @Volatile private var aborting = false
        @Volatile private var sealed = false
        private val thread = Thread({
            try {
                while (!aborting) {
                    val frame = chunks.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (frame.isEmpty()) break
                    output.write(frame)
                }
            } catch (_: IOException) {
                // Provider abandoned the descriptor: the next turn will retry
                // using legacy recognition, not a second AudioRecord.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { output.close() }
            }
        }, "jarvis-pcm-provider-pipe").apply { isDaemon = true }

        fun start() = thread.start()
        fun feed(frame: ByteArray) {
            if (!sealed && !aborting) chunks.offer(frame) // Never block the microphone thread.
        }
        fun finish() {
            if (sealed || aborting) return
            sealed = true
            // Close after queued trailing silence; the provider sees EOF.
            if (!chunks.offer(ByteArray(0))) {
                chunks.poll()
                chunks.offer(ByteArray(0))
            }
        }
        fun abort() {
            aborting = true
            sealed = true
            chunks.clear()
            runCatching { output.close() }
            thread.interrupt()
        }
    }

    fun start(): Boolean {
        if (!supported() || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return false
        synchronized(lock) {
            if (running) return true
            return try {
                val minimum = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val mic = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 2, FRAMES * 2 * 6)
                )
                // Assign before starting: a failure to initialize/start must
                // release this already-allocated microphone resource.
                recorder = mic
                check(mic.state == AudioRecord.STATE_INITIALIZED)
                mic.startRecording()
                check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                running = true
                worker = Thread({ readLoop(mic) }, "jarvis-persistent-voice-pcm").apply {
                    isDaemon = true
                    start()
                }
                true
            } catch (t: Throwable) {
                running = false
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recorder = null
                false
            }
        }
    }

    /** Returns an owned descriptor for one recognizer turn; only one pipe at a time. */
    fun openTurn(): ParcelFileDescriptor? = synchronized(lock) {
        if (!running) return@synchronized null
        pipe?.abort()
        pipe = null
        activity.reset()
        return@synchronized runCatching {
            val descriptors = ParcelFileDescriptor.createPipe()
            val sender = PipeWriter(ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]))
            pipe = sender
            sender.start()
            descriptors[0]
        }.getOrNull()
    }

    /** Finish a manually ended utterance after queued audio reaches its recognizer. */
    fun finishTurn() = synchronized(lock) {
        pipe?.finish()
        pipe = null
        activity.reset()
    }

    /** Aborts a cancelled/stale recognizer feed; microphone ownership remains. */
    fun closeTurn() = synchronized(lock) {
        pipe?.abort()
        pipe = null
        activity.reset()
    }

    private fun readLoop(mic: AudioRecord) {
        val samples = ShortArray(FRAMES)
        try {
            while (running) {
                val read = mic.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (!running) break
                if (read < 0) throw IOException("AudioRecord read returned $read")
                if (read == 0) continue
                val activePipe = synchronized(lock) { pipe }
                if (activePipe != null) {
                    val pcm16 = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val value = samples[i].toInt()
                        pcm16[i * 2] = (value and 0xff).toByte()
                        pcm16[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
                    }
                    activePipe.feed(pcm16)
                    val event = synchronized(lock) {
                        if (pipe === activePipe) activity.accept(samples, read)
                        else PcmSpeechActivityGate.Event.NONE
                    }
                    if (event == PcmSpeechActivityGate.Event.ENDED) {
                        activePipe.finish()
                        synchronized(lock) { if (pipe === activePipe) pipe = null }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) onFailure("Persistent PCM audio ended: ${t.javaClass.simpleName}")
        } finally {
            // stop() owns releasing the AudioRecord and pipe.
        }
    }

    fun stop() {
        val previous: AudioRecord?
        synchronized(lock) {
            running = false
            pipe?.abort()
            pipe = null
            previous = recorder
            recorder = null
            activity.reset()
        }
        runCatching { previous?.stop() }
        worker?.interrupt()
        runCatching { previous?.release() }
        worker = null
    }
}
