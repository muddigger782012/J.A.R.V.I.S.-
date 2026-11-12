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

    fun <T> read(allowed: () -> Boolean, capture: () -> T): T {
        check(allowed())
        return try { capture() } catch (_: Changed) {
            check(allowed())
            capture() // One complete recapture; never reuse a partial tree or retry an action.
        }
    }
}
