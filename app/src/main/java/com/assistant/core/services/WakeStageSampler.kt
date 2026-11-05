package com.assistant.core.services

/**
 * The native KWS decoder can emit dozens of stage changes per second.
 * Persisting each one synchronously stalls microphone consumption and can
 * make "Hey Jarvis" miss while Android is screen-off or CPU constrained.
 *
 * Keep infrequent breadcrumbs for diagnostics; never suppress a failure or
 * a non-decoder lifecycle state.
 */
internal class WakeStageSampler(private val intervalMillis: Long = 5_000L) {
    private var lastDecoderWrite: Long? = null

    @Synchronized fun shouldWrite(stage: String, elapsedMillis: Long): Boolean {
        val hotPath = stage.startsWith("sherpa-worker-") ||
            stage.startsWith("sherpa-before-decode-") ||
            stage.startsWith("sherpa-after-decode-")
        if (!hotPath || "failed" in stage || "timeout" in stage) return true
        val last = lastDecoderWrite
        if (last != null && elapsedMillis >= last && elapsedMillis - last < intervalMillis) return false
        lastDecoderWrite = elapsedMillis
        return true
    }
}
