package com.assistant.core.services

/**
 * One bounded fallback probe when Personal Live Chat has produced no transcript
 * after two complete on-device listening attempts. Avoids endlessly displaying
 * "Listening" if the device engine never captures actual words. This never
 * directly performs any phone action or bypasses the quiet-chime policy.
 */
internal class LiveChatInputRecovery {
    private var systemProbeAttempted = false

    fun reset() {
        systemProbeAttempted = false
    }

    fun shouldProbeSystem(onDevice: Boolean, consecutiveSilentTurns: Int,
                          pcmStreaming: Boolean, systemAvailable: Boolean): Boolean {
        if (systemProbeAttempted || !onDevice || consecutiveSilentTurns < 2 ||
            pcmStreaming || !systemAvailable) return false
        systemProbeAttempted = true
        return true
    }
}
