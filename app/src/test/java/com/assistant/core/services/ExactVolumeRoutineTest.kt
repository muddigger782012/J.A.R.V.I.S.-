package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import org.junit.Assert.*
import org.junit.Test

class ExactVolumeRoutineTest {
    private fun lesson(steps: String) = FallbackIntentLesson("CREATE_ROUTINE", .95,
        mapOf("phrase" to "low browse", "steps" to steps), learnable = true)

    @Test fun nearestSupportedLevelHandlesLimitsWithoutOverflow() {
        assertEquals(4, VerifiedMediaVolumeLevel.targetIndex(25, 15))
        assertEquals(8, VerifiedMediaVolumeLevel.targetIndex(50, 15))
        assertEquals(0, VerifiedMediaVolumeLevel.targetIndex(0, 15))
        assertEquals(15, VerifiedMediaVolumeLevel.targetIndex(100, 15))
        assertEquals(Int.MAX_VALUE, VerifiedMediaVolumeLevel.targetIndex(100, Int.MAX_VALUE))
        for (percent in listOf(-1, 101)) assertNull(VerifiedMediaVolumeLevel.targetIndex(percent, 15))
        assertNull(VerifiedMediaVolumeLevel.targetIndex(25, 0))
    }

    @Test fun readbackAndAlreadySatisfiedLevelAreVerifiedButBlockedWritesAreNot() {
        var level = 10
        var writes = 0
        assertTrue(VerifiedMediaVolumeLevel.execute(25, 15, { level }, { level = it; writes++ }))
        assertEquals(4, level)
        assertTrue(VerifiedMediaVolumeLevel.execute(25, 15, { level }, { error("Already at target; no write needed") }))
        assertEquals(1, writes)
        assertFalse(VerifiedMediaVolumeLevel.execute(50, 15, { level }, { }))
        assertFalse(VerifiedMediaVolumeLevel.execute(50, 15, { level }, { throw SecurityException("Blocked") }))
        assertFalse(VerifiedMediaVolumeLevel.execute(101, 15, { error("Invalid percent must not read") }, { error("Invalid percent must not write") }))
    }

    @Test fun approvalPersistsBoundPercentageAndAppWithoutExecutingThem() {
        val proposal = RoutineProposal.fromAiLesson(lesson("volume:25,open:Chrome")) { name ->
            if (name == "Chrome") "com.android.chrome" to "Chrome" else null
        }!!
        var pending: String? = null
        var stored = "[]"
        val approval = RoutineConfirmationStore({ pending }, { pending = it; true })
        val registry = ApprovedRoutineRegistry({ stored }, { stored = it; true })
        assertTrue(approval.propose(proposal))
        assertNull(registry.find("low browse"))
        assertTrue(registry.saveApproved(approval.consume("confirm routine")!!.routine))
        assertEquals(proposal, ApprovedRoutineRegistry({ stored }, { error("Read only") }).find("low browse"))
        assertTrue(RoutineProposal.describe("volume:25").contains("nearest supported level"))
        for (invalid in listOf("volume:-1", "volume:101", "volume:025", "volume:25.5", "volume:25%", "volume:quiet",
            "volume:25,volume_down", "volume_up,volume:25", "volume:25,volume:50", "timer:300,volume:25"))
            assertNull(RoutineProposal.fromLesson(lesson(invalid)))
        assertNotNull(RoutineProposal.fromLesson(lesson("volume:0,timer:300")))
        assertNotNull(RoutineProposal.fromLesson(lesson("volume:100")))
    }

    @Test fun failedVolumeVerificationStopsAppAndMissingAppPreflightStopsVolume() {
        val proposal = RoutineProposal.fromAiLesson(lesson("volume:25,open:Chrome")) { "com.android.chrome" to "Chrome" }!!
        val memory = AssistantMemory({ "{}" }, { error("Running must not approve preferences") })
        var volumeCalls = 0
        var appCalls = 0
        val failed = RoutineExecution.runWithPreferred(proposal.actions, memory, { true }) { step, target ->
            if (RoutineSteps.volumePercent(step) != null) {
                volumeCalls++
                if (VerifiedMediaVolumeLevel.execute(25, 15, { 10 }, { })) RoutineExecution.Status.VERIFIED else RoutineExecution.Status.FAILED
            } else { appCalls++; assertNotNull(target); RoutineExecution.Status.AWAITING_OUTCOME }
        }
        assertEquals(listOf("volume:25" to RoutineExecution.Status.FAILED), failed.steps)
        assertEquals(1, volumeCalls)
        assertEquals(0, appCalls)
        val blocked = RoutineExecution.runWithPreferred(proposal.actions, memory, { false }) { _, _ ->
            error("No side effects before target availability is established")
        }
        assertTrue(blocked.unavailableApp)
    }
}
