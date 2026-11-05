package com.assistant.core.services

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * An immediate, non-TTS acknowledgment for a successfully detected wake word.
 * Never gate command capture on voice-model initialization or audio callbacks.
 */
internal object WakeAcknowledgement {
    const val DURATION_MS = 120
    const val CAPTURE_DELAY_MS = 180L

    fun beep(context: Context, onFailure: (String) -> Unit = {}) {
        // This is intentionally a small Android system tone rather than an
        // offline/online TTS invocation. Honor the phone's media volume.
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 82) }.getOrElse {
            onFailure("Wake cue unavailable (${it.javaClass.simpleName}); listening anyway.")
            return
        }
        try {
            if (!tone.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)) {
                onFailure("Wake cue could not start; listening anyway.")
            }
        } catch (error: Exception) {
            onFailure("Wake cue failed (${error.javaClass.simpleName}); listening anyway.")
        } finally {
            // Release only after the short sound has finished. Do not delay
            // recognition waiting for this cleanup callback.
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, DURATION_MS + 60L)
        }
    }
}
