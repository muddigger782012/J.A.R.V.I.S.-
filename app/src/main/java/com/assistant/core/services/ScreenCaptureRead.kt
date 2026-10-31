package com.assistant.core.services

/** A disappearing node invalidates the entire read. Only a fresh read may be retried. */
internal object ScreenCaptureRead {
    class Changed : ScreenReadFailure(ScreenReadFailure.Reason.CHANGED)

    /** Fresh Android hierarchy queries need no second Binder read; cached nodes still must refresh. */
    class Freshness(val platformFresh: Boolean) {
        var refreshes: Int = 0
            private set
        fun validate(refresh: () -> Boolean) {
            if (platformFresh) return
            refreshes++
            if (!refresh()) throw Changed()
        }
    }

    /** Only explicit observations may spend remaining request time on a fresh timeout recheck. */
    fun mayRetryTimeout(kind: ScreenControls.Kind?, remainingMillis: Long) =
        kind in setOf(ScreenControls.Kind.READ, ScreenControls.Kind.LIST) && remainingMillis >= 500

    fun <T> read(allowed: () -> Boolean, retryTimeout: () -> Boolean = { false },
                 beforeRetry: (ScreenReadFailure.Reason) -> Unit = {}, capture: () -> T): T {
        check(allowed())
        return try { capture() } catch (failure: ScreenReadFailure) {
            if (failure !is Changed && (failure.reason != ScreenReadFailure.Reason.BUSY || !retryTimeout())) throw failure
            check(allowed())
            beforeRetry(failure.reason)
            capture() // One complete recapture; never reuse a partial tree or retry an action.
        }
    }
}
