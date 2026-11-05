package com.assistant.core.services

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * A single short, non-speech confirmation of a *detected* wake word.
 * Never used on SpeechRecognizer start, Live Chat listening, or retry loops.
 */
internal object WakeAcknowledgement {
    private val gate = WakeAcknowledgementGate()
    private val main = Handler(Looper.getMainLooper())

    fun play(): Boolean {
        if (!gate.shouldPlay(SystemClock.elapsedRealtime())) return false
        return runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            try {
                if (!tone.startTone(ToneGenerator.TONE_PROP_BEEP, 110)) {
                    tone.release()
                    return false
                }
                main.postDelayed({ runCatching { tone.release() } }, 200L)
                true
            } catch (failure: Exception) {
                tone.release()
                throw failure
            }
        }.getOrDefault(false)
    }
}

/** Dedupes repeated decoder callbacks without suppressing a later deliberate wake. */
internal class WakeAcknowledgementGate(private val minimumIntervalMillis: Long = 1500L) {
    private var lastAckAt = Long.MIN_VALUE
    @Synchronized fun shouldPlay(nowMillis: Long): Boolean {
        if (lastAckAt != Long.MIN_VALUE && nowMillis >= lastAckAt &&
            nowMillis - lastAckAt < minimumIntervalMillis) return false
        lastAckAt = nowMillis
        return true
    }
}
