package com.assistant.core.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous PCM playback for an entire Kokoro reply.
 *
 * Each previous chunk independently opened/closed AudioTrack and requested/
 * abandoned audio focus. That inserts route transitions and silence between
 * sentences even when the next audio chunk is already available.
 */
class NeuralPcmPlayer(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    @Volatile private var current: Session? = null

    inner class Session internal constructor() {
        private val cancelled = AtomicBoolean(false)
        private val focusLost = AtomicBoolean(false)
        private val focusPaused = AtomicBoolean(false)
        @Volatile private var track: AudioTrack? = null
        private var request: AudioFocusRequest? = null
        private val timeline = PcmPlaybackTimeline()

        /** Called sequentially on the dedicated playback executor. */
        fun enqueue(clip: NeuralSpeechCache.Clip, active: () -> Boolean, firstQueued: () -> Unit) {
            if (cancelled.get() || !active()) return
            val pcm = NeuralPcm.pcm16(clip.samples)
            val player = track ?: initialize(clip.sampleRate)
            check(timeline.sampleRate == 0 || timeline.sampleRate == clip.sampleRate) { "Speech sample rate changed during a reply" }
            var offset = 0
            while (offset < pcm.size && active() && !cancelled.get()) {
                awaitFocus(active)
                val written = player.write(pcm, offset, minOf(2048, pcm.size - offset), AudioTrack.WRITE_BLOCKING)
                check(written > 0) { "PCM playback write failed: $written" }
                if (timeline.framesQueued == 0L) firstQueued()
                offset += written
                timeline.accept(clip.sampleRate, written)
            }
        }

        private fun initialize(sampleRate: Int): AudioTrack {
            check(!cancelled.get()) { "Speech session was cancelled" }
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ change ->
                    when (change) {
                        // A short alarm or incoming notification is not permission
                        // to discard the rest of the spoken answer.
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            focusPaused.set(true)
                            runCatching { track?.pause() }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            focusPaused.set(false)
                            if (!cancelled.get()) runCatching { track?.play() }
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> focusLost.set(true)
                        // LOSS_TRANSIENT_CAN_DUCK intentionally keeps speech active.
                    }
                }, Handler(Looper.getMainLooper())).build()
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Media audio focus was unavailable"
            }
            request = focus
            val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "PCM16 audio format is unavailable" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(8192, minimum))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack could not initialize" }
            output.setVolume(1f)
            if (cancelled.get()) return output
            output.play()
            return output
        }

        private fun awaitFocus(active: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (focusPaused.get() && active() && !cancelled.get()) {
                check(SystemClock.elapsedRealtime() < deadline) {
                    "Speech audio focus did not return after a temporary interruption"
                }
                Thread.sleep(25L)
            }
            check(!focusLost.get()) { "Speech audio focus was permanently lost" }
        }

        /** Finish only after the last queued PCM frame has actually played. */
        fun drain(active: () -> Boolean) {
            val output = track ?: return
            if (timeline.framesQueued == 0L) return
            val deadline = SystemClock.elapsedRealtime() +
                (timeline.framesQueued * 1000L / timeline.sampleRate + 10_000L).coerceAtMost(300_000L)
            while (active() && !cancelled.get() &&
                !timeline.isDrained(output.playbackHeadPosition.toLong() and 0xffffffffL)) {
                awaitFocus(active)
                check(SystemClock.elapsedRealtime() < deadline) { "Audio playback did not complete" }
                Thread.sleep(15L)
            }
        }

        /** Android output underruns are useful evidence of gaps between synthesized chunks. */
        fun underrunCount(): Int = runCatching { track?.underrunCount ?: -1 }.getOrDefault(-1)

        /** May be called from the main thread to unblock a cancelled utterance. */
        fun cancel() {
            cancelled.set(true)
            track?.let { runCatching { it.pause(); it.flush() } }
        }

        /** Called by the playback executor after all queued operations finish. */
        fun close() {
            cancelled.set(true)
            val output = track
            track = null
            output?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            request?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
            request = null
            if (current === this) current = null
        }
    }

    fun newSession(): Session = Session().also {
        current?.cancel()
        current = it
    }

    fun stop() {
        current?.cancel()
    }
}
