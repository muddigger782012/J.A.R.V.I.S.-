package com.assistant.core.services

import java.util.concurrent.atomic.AtomicReference

/** Cancellation is visible to queued and executing screen requests without waiting for the UI looper. */
internal class ScreenRequestEpoch {
    data class Token internal constructor(internal val value: Long)
    data class State(val value: Long, val preserveTask: Boolean)
    private val epoch = AtomicReference(State(0, false))
    fun token() = Token(epoch.get().value)
    fun current() = epoch.get().value
    fun state() = epoch.get()
    fun valid(token: Token) = token.value == epoch.get().value
    fun cancel(preserveTask: Boolean = false) { epoch.updateAndGet { State(it.value + 1, preserveTask) } }
    // An old timeout cannot invalidate work started after a separate cancellation.
    fun timeout(token: Token) {
        val current = epoch.get()
        if (current.value == token.value) epoch.compareAndSet(current, State(current.value + 1, false))
    }
}
