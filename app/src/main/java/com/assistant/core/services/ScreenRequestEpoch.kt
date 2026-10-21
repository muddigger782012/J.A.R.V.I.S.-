package com.assistant.core.services

import java.util.concurrent.atomic.AtomicLong

/** Cancellation is visible to queued and executing screen requests without waiting for the UI looper. */
internal class ScreenRequestEpoch {
    data class Token internal constructor(internal val value: Long)
    private val epoch = AtomicLong()
    fun token() = Token(epoch.get())
    fun current() = epoch.get()
    fun valid(token: Token) = token.value == epoch.get()
    fun cancel() { epoch.incrementAndGet() }
    // An old timeout cannot invalidate work started after a separate cancellation.
    fun timeout(token: Token) { epoch.compareAndSet(token.value, token.value + 1) }
}
