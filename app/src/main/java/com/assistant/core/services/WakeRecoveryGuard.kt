package com.assistant.core.services

/**
 * Native decoder cleanup cannot be forced safely across a JNI call.
 * Recovery waits for the old microphone worker to finish before replacing it,
 * even when that worker previously reported a decoder stall.
 */
internal object WakeRecoveryGuard {
    enum class Decision { RESTART, WAIT, FAIL }
    fun decision(workerAlive: Boolean, pollCount: Int): Decision = when {
        !workerAlive -> Decision.RESTART
        pollCount >= 30 -> Decision.FAIL
        else -> Decision.WAIT
    }
}
