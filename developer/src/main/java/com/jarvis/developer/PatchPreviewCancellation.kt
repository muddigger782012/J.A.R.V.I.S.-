package com.jarvis.developer

import java.io.Closeable
import kotlin.concurrent.thread

/** One in-memory source/proposal preview or GET-only diagnostic read. No write authority.
 * Signal immediately; resource cleanup must not block Android's main thread.
 */
internal class PatchPreviewCancellation(
    private val dispatchCleanup: (() -> Unit) -> Unit = { cleanup ->
        thread(name = "DeveloperPreviewCleanup", isDaemon = true) { cleanup() }
        Unit
    }
) {
    private val lock = Any()
    private val stops = LinkedHashMap<Any, () -> Unit>()
    @Volatile var isCancelled = false
        private set

    fun check() { check(!isCancelled) { "Source preview cancelled" } }

    fun registerStop(stop: () -> Unit): Closeable {
        val id = Any()
        val stopNow = synchronized(lock) {
            if (isCancelled) true else { stops[id] = stop; false }
        }
        if (stopNow) dispatch(stop)
        return Closeable { synchronized(lock) { stops.remove(id) }; Unit }
    }

    fun cancel() {
        val pending = synchronized(lock) {
            if (isCancelled) return
            isCancelled = true
            stops.values.toList().also { stops.clear() }
        }
        pending.forEach(::dispatch)
    }

    private fun dispatch(stop: () -> Unit) {
        // Failed cleanup cannot restore a cancelled token. Existing network budgets remain.
        runCatching { dispatchCleanup { runCatching(stop); Unit } }
    }
}
