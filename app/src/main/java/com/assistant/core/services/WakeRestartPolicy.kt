package com.assistant.core.services

/**
 * A one-shot microphone action must not accidentally turn a user's explicitly
 * enabled wake standby into a non-sticky Android service.
 *
 * Explicit STOP and live-chat-only sessions still use their own lifecycle.
 */
internal object WakeRestartPolicy {
    fun shouldRemainSticky(wakeRequested: Boolean, explicitlyStopped: Boolean = false) =
        wakeRequested && !explicitlyStopped
}
