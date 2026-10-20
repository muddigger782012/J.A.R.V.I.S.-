package com.assistant.core.workflow

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.TaskHistoryCommands
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutineHistoryTest {
    private fun completed(index: Int) = RoutineWorkflowCheckpoint("run-$index", setOf("turn-$index"), "focus time",
        listOf(RoutineStepCheckpoint(RoutineWorkflowStep(0, "volume_down"), RoutineStepStatus.VERIFIED, 1)),
        RoutineWorkflowStatus.VERIFIED, approved = true)
    private class Access(override val store: RoutineWorkflowStore) : RoutineWorkflowAccess {
        var busy = false
        override fun <T : Any> tryExclusive(block: () -> T): T? = if (busy) null else block()
        override fun cancellationRequested(id: String) = false
        override fun requestCancellation(id: String) = false
    }
    private fun coordinator(access: Access, calls: MutableList<String> = mutableListOf()) = RoutineExecutionCoordinator(access,
        { ApprovedRoutineRegistry.Routine("focus time", listOf("volume_down")) },
        { _, step -> calls.add(step.actionId); RoutineStepOutcome.Verified })

    @Test fun fullLegacyJournalCanBeReviewedAndCompactedWithoutReplayingRemovedInvocations() {
        var raw: String? = null
        fun store() = JsonRoutineWorkflowStore({ raw }, { raw = it; true })
        repeat(RoutineWorkflowLimits.MAX_RETAINED_WORKFLOWS) { assertTrue(store().save(completed(it))) }
        assertTrue(raw!!.startsWith("[")) // Installed legacy shape remains readable.
        val calls = mutableListOf<String>()
        val access = Access(store())
        val first = coordinator(access, calls)
        try { first.invoke("focus time", "new"); fail("Full history must refuse") } catch (_: IllegalStateException) { }
        assertTrue(first.invokePrepared("new goal", "prepared") { fail("Full history cannot consume pending approval"); false }.message.contains("pending goal approval was not consumed"))
        val review = first.reviewHistory().review!!
        assertEquals(99, review.removable.size)
        assertEquals(99, first.clearHistory(review).cleared)
        assertTrue(raw!!.startsWith("{"))
        assertEquals(listOf(completed(99)), store().load())
        assertTrue(store().isRetiredRequest("turn-0"))
        assertEquals(198, store().retiredFingerprintCount())
        assertFalse(store().save(completed(0))) // An obsolete writer cannot resurrect it.
        try { coordinator(Access(store()), calls).invoke("focus time", "turn-0"); fail("Archived keys must refuse") } catch (_: IllegalStateException) { }
        assertTrue(calls.isEmpty())
        assertEquals(RoutineWorkflowStatus.VERIFIED, coordinator(Access(store()), calls).invoke("focus time", "fresh").checkpoint!!.status)
        assertEquals(listOf("volume_down"), calls)
        assertTrue(store().isRetiredRequest("turn-0"))
    }

    @Test fun cancellationCannotMakeUncertainEffectsEligibleAndLatestStatusAlwaysRemains() {
        val uncertain = completed(1).copy(status = RoutineWorkflowStatus.CANCELLED, cancellationRequested = true,
            steps = completed(1).steps.map { it.copy(status = RoutineStepStatus.AMBIGUOUS) })
        val cancelled = completed(2).copy(status = RoutineWorkflowStatus.CANCELLED, cancellationRequested = true,
            steps = completed(2).steps.map { it.copy(status = RoutineStepStatus.PENDING, attempts = 0) })
        val failed = completed(3).copy(status = RoutineWorkflowStatus.FAILED,
            steps = completed(3).steps.map { it.copy(status = RoutineStepStatus.FAILED) })
        val entries = listOf(completed(0), uncertain, cancelled, failed, completed(4))
        assertEquals(listOf(completed(0), cancelled), RoutineHistory.candidates(entries))
        val store = InMemoryRoutineWorkflowStore()
        entries.forEach { store.save(it) }
        assertFalse(store.archiveCompleted(listOf(uncertain)))
        val access = Access(store)
        val review = coordinator(access).reviewHistory().review!!
        assertEquals(2, coordinator(access).clearHistory(review).cleared)
        assertEquals(listOf(uncertain, failed, completed(4)), store.load())
    }

    @Test fun staleReviewBusyRunnerAndFailedCommitCannotRemoveOrDispatchAnything() {
        var raw: String? = null
        var failWrite = false
        val store = JsonRoutineWorkflowStore({ raw }, { if (failWrite) false else { raw = it; true } })
        store.save(completed(0)); store.save(completed(1))
        val access = Access(store)
        val calls = mutableListOf<String>()
        val controller = coordinator(access, calls)
        val review = controller.reviewHistory().review!!
        access.busy = true
        assertNull(controller.reviewHistory().review)
        assertEquals(0, controller.clearHistory(review).cleared)
        access.busy = false
        store.save(completed(2))
        assertEquals(0, controller.clearHistory(review).cleared)
        val changedReview = controller.reviewHistory().review!!
        val before = raw
        failWrite = true
        assertEquals(0, controller.clearHistory(changedReview).cleared)
        assertEquals(before, raw)
        assertEquals(3, store.load().size)
        assertEquals(0, store.retiredFingerprintCount())
        assertTrue(calls.isEmpty())
    }

    @Test fun cleanupReviewExpiresAndCannotBeRestoredByAnotherConversation() {
        val store = InMemoryRoutineWorkflowStore()
        store.save(completed(0)); store.save(completed(1))
        val access = Access(store)
        var time = 1_000L
        fun commands() = TaskHistoryCommands({ coordinator(access) }) { time }
        val first = commands()
        assertTrue(first.reply(TaskHistoryCommands.Command.REVIEW).contains("1 older completed"))
        assertTrue(commands().reply(TaskHistoryCommands.Command.CLEAR).contains("before clearing"))
        time += 120_001
        assertTrue(first.reply(TaskHistoryCommands.Command.CLEAR).contains("before clearing"))
        first.reply(TaskHistoryCommands.Command.REVIEW)
        first.discardReview()
        assertTrue(first.reply(TaskHistoryCommands.Command.CLEAR).contains("before clearing"))
        first.reply(TaskHistoryCommands.Command.REVIEW)
        assertTrue(first.reply(TaskHistoryCommands.Command.CLEAR).contains("Cleared 1"))
        assertEquals(1, store.load().size)
    }

    @Test fun boundedSafetyRecordsFailClosedAndMalformedArchivesCannotEraseDeduplication() {
        var raw: String? = null
        val store = JsonRoutineWorkflowStore({ raw }, { raw = it; true })
        store.save(completed(0)); store.save(completed(1))
        val legacy = JSONArray(raw)
        val retired = JSONArray()
        repeat(RoutineWorkflowLimits.MAX_RETIRED_FINGERPRINTS) { retired.put("%064x".format(it)) }
        raw = JSONObject().put("version", 2).put("entries", legacy).put("retired", retired).toString()
        val before = raw
        assertFalse(store.archiveCompleted(listOf(completed(0))))
        assertEquals(before, raw)
        for (bad in listOf("not-a-hash", "0".repeat(64))) {
            val fingerprints = JSONArray().put(bad).put(bad)
            raw = JSONObject().put("version", 2).put("entries", legacy).put("retired", fingerprints).toString()
            try { store.load(); fail("Malformed archives must fail closed") } catch (_: IllegalArgumentException) { }
            assertFalse(store.save(completed(2)))
        }
    }
}
