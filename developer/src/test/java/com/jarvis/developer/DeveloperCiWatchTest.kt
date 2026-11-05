package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperCiWatchTest {
    private fun status(state: PatchPublisher.CiState) = PatchPublisher.CiStatus(state)

    @Test fun queuedAndRunningResolveWithoutAnotherOwnerCommand() {
        val observations = ArrayDeque(listOf(
            status(PatchPublisher.CiState.RUNNING),
            status(PatchPublisher.CiState.PASSED)
        ))
        val seen = mutableListOf<Pair<PatchPublisher.CiState, Int>>()
        var waits = 0
        val result = DeveloperCiWatch.follow(status(PatchPublisher.CiState.QUEUED),
            poll = { observations.removeFirst() },
            active = { true },
            pause = { ms -> assertEquals(15_000L, ms); waits++ },
            onPending = { current, read -> seen += current.state to read })
        assertEquals(PatchPublisher.CiState.PASSED, result?.state)
        assertEquals(2, waits)
        assertEquals(listOf(PatchPublisher.CiState.QUEUED to 1,
            PatchPublisher.CiState.RUNNING to 2), seen)
    }

    @Test fun alreadyTerminalDoesNotPollOrSleep() {
        for (state in listOf(PatchPublisher.CiState.PASSED, PatchPublisher.CiState.FAILED)) {
            val result = DeveloperCiWatch.follow(status(state),
                poll = { error("unexpected network read") },
                active = { true },
                pause = { error("unexpected delay") })
            assertEquals(state, result?.state)
        }
    }

    @Test fun missingBuildExhaustsBudgetWithoutInventingSuccess() {
        var reads = 0
        var waits = 0
        val result = DeveloperCiWatch.follow(status(PatchPublisher.CiState.MISSING),
            poll = { reads++; status(PatchPublisher.CiState.MISSING) },
            active = { true },
            pause = { waits++ })
        assertEquals(PatchPublisher.CiState.MISSING, result?.state)
        assertEquals(DeveloperCiWatch.MAX_READS - 1, reads)
        assertEquals(DeveloperCiWatch.MAX_READS - 1, waits)
    }

    @Test fun activityCancellationDiscardsPendingOutcomeAndFutureReads() {
        var alive = true
        var reads = 0
        val result = DeveloperCiWatch.follow(status(PatchPublisher.CiState.QUEUED),
            poll = { reads++; status(PatchPublisher.CiState.PASSED) },
            active = { alive },
            pause = { alive = false })
        assertNull(result)
        assertEquals(0, reads)
    }

    @Test(expected = IllegalStateException::class)
    fun failedNetworkReadCannotBecomeVerifiedCi() {
        DeveloperCiWatch.follow(status(PatchPublisher.CiState.RUNNING),
            poll = { throw IllegalStateException("network unavailable") },
            active = { true },
            pause = { })
    }
}
