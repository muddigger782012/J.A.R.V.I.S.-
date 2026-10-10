package com.assistant.core.services

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.assistant.core.MainActivity

class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}

/**
 * System-assistant invocation entry point. Android owns the voice-interaction
 * lifecycle; J.A.R.V.I.S. routes the visible interaction into the same unified
 * Assistant workspace rather than maintaining a second assistant UI.
 */
class JarvisVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_ASSISTANT_INVOCATION, true)
        }
        runCatching { context.startActivity(launch) }
    }

    companion object {
        const val EXTRA_ASSISTANT_INVOCATION = "jarvis_assistant_invocation"
    }
}
