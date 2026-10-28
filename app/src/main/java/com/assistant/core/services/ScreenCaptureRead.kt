package com.assistant.core.services

/** A disappearing node invalidates the entire read. Only a fresh read may be retried. */
internal object ScreenCaptureRead {
    class Changed : ScreenReadFailure(ScreenReadFailure.Reason.CHANGED)

    fun <T> read(allowed: () -> Boolean, capture: () -> T): T {
        check(allowed())
        return try { capture() } catch (_: Changed) {
            check(allowed())
            capture() // One complete recapture; never reuse a partial tree or retry an action.
        }
    }
}
