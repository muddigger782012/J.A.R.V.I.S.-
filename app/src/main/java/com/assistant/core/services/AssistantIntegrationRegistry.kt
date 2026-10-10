package com.assistant.core.services

/**
 * Optional integration boundaries inspired by open voice assistant ecosystems.
 *
 * Google Assistant proprietary code is not bundled. Google App Actions are an
 * Android discovery/invocation surface, not a substitute for JARVIS routing.
 * Home Assistant Assist is a user-configured external endpoint; OpenVoiceOS
 * skills require an explicit compatible bridge rather than running Python in
 * the Android process.
 */
object AssistantIntegrationRegistry {
    enum class Kind {
        ANDROID_ASSISTANT, GOOGLE_APP_ACTIONS, HOME_ASSISTANT_ASSIST,
        OPEN_VOICE_OS, LOCAL_WAKE_WORD, LOCAL_SPEECH_TO_TEXT,
        LOCAL_TEXT_TO_SPEECH
    }

    data class Integration(
        val kind: Kind,
        val displayName: String,
        val requiresConfiguration: Boolean,
        val enabledByDefault: Boolean,
        val description: String
    )

    val available: List<Integration> = listOf(
        Integration(Kind.ANDROID_ASSISTANT, "Android default assistant",
            false, true, "Uses Android VoiceInteractionService and existing JARVIS core."),
        Integration(Kind.GOOGLE_APP_ACTIONS, "Google App Actions",
            true, false, "Optional Google-triggered app shortcuts; does not embed Google Assistant."),
        Integration(Kind.HOME_ASSISTANT_ASSIST, "Home Assistant Assist",
            true, false, "Optional authenticated Assist WebSocket pipeline for smart-home intents."),
        Integration(Kind.OPEN_VOICE_OS, "OpenVoiceOS skills",
            true, false, "Optional bridge to compatible open-source skills."),
        Integration(Kind.LOCAL_WAKE_WORD, "Offline wake-word detection",
            true, false, "Dedicated keyword model and engine required; no repeated SpeechRecognizer polling."),
        Integration(Kind.LOCAL_SPEECH_TO_TEXT, "Local speech recognition",
            true, false, "Pluggable on-device transcription engine."),
        Integration(Kind.LOCAL_TEXT_TO_SPEECH, "Local speech synthesis",
            false, true, "Android TextToSpeech implementation.")
    )

    /** Remote integrations must be opt-in and never receive credentials in logs. */
    interface IntentProvider {
        val kind: Kind
        fun isConfigured(): Boolean
        suspend fun interpret(text: String): ProviderResponse?
    }

    data class ProviderResponse(
        val speech: String,
        val structuredIntent: String? = null,
        val parameters: Map<String, String> = emptyMap()
    )

    /** An external provider may propose actions, never execute them directly. */
    interface ActionProposalSink {
        fun submitForPolicyValidation(response: ProviderResponse): Boolean
    }
}
