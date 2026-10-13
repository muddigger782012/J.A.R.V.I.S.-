package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import java.util.Locale

object VoiceRecognitionHints {
    fun apply(context: Context, intent: Intent, enabled: Boolean = true): Boolean {
        if (!enabled || Build.VERSION.SDK_INT < 33) return false
        val hints = VoiceProfileStore(context.filesDir).hints(Locale.getDefault().toLanguageTag())
        if (hints.isEmpty()) return false
        intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
        return true
    }
}
