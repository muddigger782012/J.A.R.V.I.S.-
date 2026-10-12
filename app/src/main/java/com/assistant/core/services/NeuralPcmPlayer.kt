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

/** Route native speech through media volume with universally supported PCM16. */
class NeuralPcmPlayer(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var track: AudioTrack? = null
    fun play(clip: NeuralSpeechCache.Clip, active: () -> Boolean, firstQueued: () -> Unit) {
        val samples = NeuralPcm.pcm16(clip.samples)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focusLost = AtomicBoolean(false)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) focusLost.set(true) }, Handler(Looper.getMainLooper())).build()
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Media audio focus was unavailable" }
        var output: AudioTrack? = null
        try {
            if (!active()) return
            val minimum = AudioTrack.getMinBufferSize(clip.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "PCM16 audio format is unavailable" }
            val player = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(clip.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(8192, minimum)).setTransferMode(AudioTrack.MODE_STREAM).build()
            output = player; track = player
            check(player.state == AudioTrack.STATE_INITIALIZED) { "Audio track could not initialize" }
            player.setVolume(1f)
            if (!active()) return
            player.play()
            var offset = 0
            while (offset < samples.size && active()) {
                check(!focusLost.get()) { "Media audio focus was lost" }
                val count = player.write(samples, offset, minOf(2048, samples.size - offset), AudioTrack.WRITE_BLOCKING)
                check(count > 0) { "PCM16 playback failed: $count" }
                if (offset == 0) firstQueued()
                offset += count
            }
            val deadline = SystemClock.elapsedRealtime() + samples.size * 1000L / clip.sampleRate + 5000L
            while (active() && (player.playbackHeadPosition.toLong() and 0xffffffffL) < samples.size) {
                check(!focusLost.get()) { "Media audio focus was lost" }
                check(SystemClock.elapsedRealtime() < deadline) { "Audio playback did not complete" }
                Thread.sleep(15)
            }
        } finally {
            if (track === output) track = null
            output?.let { runCatching { it.stop() }; it.release() }
            manager.abandonAudioFocusRequest(request)
        }
    }
    fun stop() { track?.let { runCatching { it.pause(); it.flush() } } }
}
