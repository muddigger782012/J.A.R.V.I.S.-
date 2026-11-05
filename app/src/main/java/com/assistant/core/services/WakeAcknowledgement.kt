package com.assistant.core.services

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler

/** An immediate, local wake acknowledgment that never depends on AI or TTS. */
internal object WakeAcknowledgement {
    const val TONE_MILLIS = 110L
    const val CAPTURE_DELAY_MILLIS = 180L

    fun beepThenCapture(context: Context, handler: Handler, capture: () -> Unit) {
        // This callback normally arrives on the native wake worker. Tone and
        // command-capture sequencing are deliberately serialized on main.
        handler.post {
            val tone = runCatching {
                ToneGenerator(AudioManager.STREAM_MUSIC, 80).also {
                    it.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MILLIS.toInt())
                }
            }.getOrNull()
            handler.postDelayed({
                runCatching { tone?.release() }
                capture()
            }, if (tone != null) CAPTURE_DELAY_MILLIS else 0L)
        }
    }
}
