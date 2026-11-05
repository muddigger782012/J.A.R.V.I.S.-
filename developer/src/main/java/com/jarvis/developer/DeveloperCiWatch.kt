package com.jarvis.developer

/**
 * Follows only the already-approved task commit's CI identity, using bounded GET reads.
 * No scheduler, new task, provider request, retry, approval, or GitHub write is created.
 * An interrupted Activity discards the result instead of claiming a passing build.
 */
internal object DeveloperCiWatch {
    // About twelve minutes of bounded read-only polling. Developer debug
    // compile/test workflows can exceed the former three-minute budget.
    // Activity shutdown still aborts; persistence/recovery remains separate.
    const val MAX_READS = 49
    const val POLL_DELAY_MILLIS = 15_000L

    fun follow(
        initial: PatchPublisher.CiStatus,
        poll: () -> PatchPublisher.CiStatus,
        active: () -> Boolean,
        pause: (Long) -> Unit = Thread::sleep,
        onPending: (PatchPublisher.CiStatus, Int) -> Unit = { _, _ -> }
    ): PatchPublisher.CiStatus? {
        var status = initial
        for (read in 1..MAX_READS) {
            if (!active()) return null
            if (status.state == PatchPublisher.CiState.PASSED ||
                status.state == PatchPublisher.CiState.FAILED) return status
            if (read == MAX_READS) return status
            onPending(status, read)
            pause(POLL_DELAY_MILLIS)
            if (!active()) return null
            // The caller uses PatchPublisher's exact-branch, exact-commit CI reader.
            // Network errors propagate to the caller's unverified-result path.
            status = poll()
        }
        return null
    }
}
