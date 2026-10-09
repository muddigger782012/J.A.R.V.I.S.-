package com.assistant.core.services

import android.service.voice.VoiceInteractionService

/**
 * Android assistant-role entry point. When the user selects J.A.R.V.I.S. as the
 * device assistant, Android can keep this lightweight service available as the
 * system assistant integration point while the central assistant owns logic.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}
