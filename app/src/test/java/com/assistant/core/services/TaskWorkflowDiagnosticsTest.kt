package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.workflow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TaskWorkflowDiagnosticsTest {
    private class Fixture {
        val directory = Files.createTempDirectory("task-diagnostics").toFile()
        val path = File(directory, "workflow-diagnostics.json")
        val backing = InMemoryRoutineWorkflowStore()
        var writable = true
        val access = object : RoutineWorkflowAccess {
            override val store = object : RoutineWorkflowStore by backing {
                override fun save(checkpoint: RoutineWorkflowCheckpoint) = writable && backing.save(checkpoint)
            }
            override fun <T : Any> tryExclusive(block: () -> T): T = block()
            override fun cancellationRequested(id: String) = false
            override fun requestCancellation(id: String) = false
        }
        var definition: ApprovedRoutineRegistry.Routine? = ApprovedRoutineRegistry.Routine("Private commute", listOf("volume_down", "volume_up"))
        var calls = 0
        var effect: (RoutineWorkflowStep) -> RoutineStepOutcome = { RoutineStepOutcome.Verified }
        fun diagnostics() = WorkflowDiagnostics(PersonalStateFile(directory, path.name), 7, "3.5.7") { 1_000L }
        fun reply(text: String) = diagnostics().reply(WorkflowDiagnostics.command(text)!!)
        fun coordinator(observer: (String, RoutineWorkflowObservation) -> Unit = diagnostics()::observeTask) =
            RoutineExecutionCoordinator(access, { key -> definition?.takeIf { it.phrase == key } },
                { _, step -> calls++; effect(step) }, observe = observer)
        fun invoke(turn: String = "private turn") = coordinator().invoke(definition!!.phrase, turn).checkpoint!!
        fun ready(): RoutineWorkflowCheckpoint {
            val engine = WorkflowEngine(approvedRoutine = { definition }, store = access.store)
            val pending = engine.planApprovedRoutine(definition!!.phrase, "ready turn")
            return engine.approveRoutine(pending.id, pending.steps.map { it.step })
        }
        fun codes() = diagnostics().snapshot().events.map { it.code }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.directory.deleteRecursively() }
    }

    @Test fun enablingDoesNotImportStableHistoryAndStatusAndReviewNeverDispatch() = fixture { f ->
        f.invoke()
        assertFalse(f.path.exists())
        f.reply("enable workflow diagnostics")
        repeat(3) { f.coordinator().status(); f.coordinator().reviewHistory(); f.reply("review workflow diagnostics") }
        assertTrue(f.codes().isEmpty())
        assertEquals(2, f.calls)
        f.invoke("second turn")
        assertEquals(listOf(WorkflowDiagnostics.Code.TASK_DEVICE_VERIFIED), f.codes())
        f.reply("disable workflow diagnostics")
        f.invoke("third turn")
        assertEquals(1, f.codes().size)
        f.reply("clear workflow diagnostics")
        assertEquals(WorkflowDiagnostics.Snapshot(), f.diagnostics().snapshot())
        assertEquals(3, f.backing.load().size)
    }

    @Test fun recurrenceCountsDistinctTasksAndFreezesEvidenceWithoutPrivateRoutineOrFailureText() = fixture { f ->
        f.reply("enable workflow diagnostics")
        f.effect = { RoutineStepOutcome.NotApplied("Private adapter error") }
        val first = f.invoke()
        f.coordinator().resume(); f.coordinator().resume(); f.coordinator().status()
        assertEquals(1, f.codes().size)
        assertTrue(f.reply("prepare workflow improvement for tasks").contains("No repeated"))
        f.coordinator().cancel()
        val second = f.invoke("second private turn")
        assertNotEquals(first.id, second.id)
        val brief = f.reply("prepare workflow improvement for tasks")
        assertTrue(brief.contains("2 distinct workflows"))
        assertTrue(brief.contains("ApprovedRoutineExecutor"))
        assertTrue(brief.contains("not owner feedback, a verified root cause"))
        val frozen = f.diagnostics().snapshot().proposals.single()
        f.coordinator().cancel(); f.effect = { RoutineStepOutcome.Verified }; f.invoke("third private turn")
        assertEquals(frozen, f.diagnostics().snapshot().proposals.single())
        assertEquals(brief, f.reply("review workflow improvement 1"))
        for (privateText in listOf("Private commute", "Private adapter error", "private turn", "timer:60", "volume:20")) {
            assertFalse(privateText, f.path.readText().contains(privateText))
            assertFalse(privateText, brief.contains(privateText))
        }
        assertTrue(f.coordinator().reviewHistory().review!!.entries.isNotEmpty())
    }

    @Test fun ownerFailureAfterARequestRemainsUncertainAndDoesNotBecomeNotAppliedOrWholeTaskVerification() = fixture { f ->
        f.definition = ApprovedRoutineRegistry.Routine("Private commute", listOf("volume:20", "timer:60"))
        f.reply("enable workflow diagnostics")
        f.effect = { if (it.index == 0) RoutineStepOutcome.Verified else RoutineStepOutcome.Requested }
        f.invoke()
        assertEquals(listOf(WorkflowDiagnostics.Code.TASK_REQUESTED), f.codes())
        val failed = f.coordinator().report(RoutineOutcomeObservation.FAILED).checkpoint!!
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, failed.status)
        repeat(3) { f.coordinator().report(RoutineOutcomeObservation.FAILED); f.coordinator().resume() }
        assertEquals(listOf(WorkflowDiagnostics.Code.TASK_REQUESTED, WorkflowDiagnostics.Code.TASK_OWNER_FAILED), f.codes())
        assertEquals(2, f.calls)
        f.coordinator().report(RoutineOutcomeObservation.USER_CONFIRMED)
        assertEquals(RoutineWorkflowStatus.USER_CONFIRMED, f.coordinator().status().checkpoint!!.status)
        assertEquals(WorkflowDiagnostics.Code.TASK_OWNER_CONFIRMED, f.codes().last())
        assertFalse(f.codes().contains(WorkflowDiagnostics.Code.TASK_DEVICE_VERIFIED))
        assertFalse(f.codes().contains(WorkflowDiagnostics.Code.TASK_NOT_APPLIED))
    }

    @Test fun interruptedDispatchProducesOneCategoryAcrossReconstructionWithoutRepeatingEffects() = fixture { f ->
        val saved = f.ready()
        f.backing.save(saved.copy(status = RoutineWorkflowStatus.RUNNING,
            steps = saved.steps.mapIndexed { i, s -> if (i == 0) s.copy(status = RoutineStepStatus.RUNNING, attempts = 1) else s }))
        f.reply("enable workflow diagnostics")
        repeat(3) {
            assertEquals(RoutineWorkflowStatus.AMBIGUOUS, f.coordinator().status().checkpoint!!.status)
            f.coordinator().resume()
        }
        assertEquals(listOf(WorkflowDiagnostics.Code.TASK_OUTCOME_UNKNOWN), f.codes())
        assertEquals(0, f.calls)
        f.coordinator().report(RoutineOutcomeObservation.USER_CONFIRMED)
        assertEquals(RoutineWorkflowStatus.PAUSED, f.coordinator().status().checkpoint!!.status)
        f.coordinator().resume()
        assertEquals(1, f.calls)
        assertFalse(f.codes().contains(WorkflowDiagnostics.Code.TASK_DEVICE_VERIFIED))
    }

    @Test fun betweenStepRecoveryPausesOnceAndExplicitCancellationIsNotReportedAsFailure() = fixture { f ->
        val saved = f.ready()
        f.backing.save(saved.copy(status = RoutineWorkflowStatus.RUNNING,
            steps = saved.steps.mapIndexed { i, s -> if (i == 0) s.copy(status = RoutineStepStatus.VERIFIED, attempts = 1) else s }))
        f.reply("enable workflow diagnostics")
        repeat(3) { assertEquals(RoutineWorkflowStatus.PAUSED, f.coordinator().status().checkpoint!!.status) }
        assertEquals(listOf(WorkflowDiagnostics.Code.TASK_RECOVERY_PAUSED), f.codes())
        f.coordinator().cancel(); f.coordinator().resume()
        assertEquals(0, f.calls)
        assertEquals(1, f.codes().size)
    }

    @Test fun removedApprovalProducesABlockedCheckpointAndFailedWritesDoNotInventSavedEvidence() = fixture { f ->
        f.ready(); f.reply("enable workflow diagnostics")
        f.definition = null
        assertEquals(RoutineWorkflowStatus.FAILED, f.coordinator().resume().checkpoint!!.status)
        assertEquals(listOf(WorkflowDiagnostics.Code.TASK_BLOCKED), f.codes())
        assertEquals(0, f.calls)
    }

    @Test fun unsavedWriteAheadFailureCannotDispatchOrClaimAnObservation() = fixture { f ->
        f.ready(); f.reply("enable workflow diagnostics"); f.writable = false
        f.coordinator().resume()
        assertEquals(0, f.calls)
        assertTrue(f.codes().isEmpty())
    }

    @Test fun outcomeWriteFailureIsOnlyObservedWhenInterruptedCheckpointRecoveryIsSaved() = fixture { f ->
        f.ready(); f.reply("enable workflow diagnostics")
        f.effect = { f.writable = false; RoutineStepOutcome.Verified }
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, f.coordinator().resume().checkpoint!!.status)
        assertTrue(f.codes().isEmpty())
        f.coordinator().status()
        assertTrue(f.codes().isEmpty())
        f.writable = true
        f.coordinator().status(); f.coordinator().resume()
        assertEquals(listOf(WorkflowDiagnostics.Code.TASK_OUTCOME_UNKNOWN), f.codes())
        assertEquals(1, f.calls)
    }

    @Test fun brokenDiagnosticStorageOrObserverCannotChangeExecutionAndOldCategoriesSurviveUpgrade() = fixture { f ->
        f.reply("enable workflow diagnostics")
        f.diagnostics().observe(java.util.UUID.randomUUID().toString(), WorkflowDiagnostics.Code.NO_SOURCES)
        assertEquals(RoutineWorkflowStatus.VERIFIED, f.invoke().status)
        assertEquals(listOf(WorkflowDiagnostics.Code.NO_SOURCES, WorkflowDiagnostics.Code.TASK_DEVICE_VERIFIED), f.codes())
        f.path.writeText("corrupt diagnostics")
        assertEquals(RoutineWorkflowStatus.VERIFIED, f.invoke("second").status)
        assertEquals("corrupt diagnostics", f.path.readText())
        assertEquals(RoutineWorkflowStatus.VERIFIED,
            f.coordinator { _, _ -> error("Observer unavailable") }.invoke("Private commute", "third").checkpoint!!.status)
        assertEquals(6, f.calls)
    }

    @Test fun taskManagementControlsAreExactAndRemainReservedFromLearningAndChainedRequests() {
        for (action in listOf("prepare", "review", "dismiss", "share")) {
            val command = WorkflowDiagnostics.command("$action workflow improvement for tasks")!!
            assertEquals(WorkflowDiagnostics.Family.TASK, command.family)
            assertTrue(LearnedCommandReviewCommands.isReserved("$action workflow improvement for tasks"))
            assertEquals(WorkflowDiagnostics.Action.INVALID,
                WorkflowDiagnostics.command("$action workflow improvement for tasks then reboot")!!.action)
        }
    }

    @Test fun diagnosticsUseTheSavedLaunchCategoryEvenIfADispatcherClaimsTimerVerification() = fixture { f ->
        f.definition = ApprovedRoutineRegistry.Routine("Private commute", listOf("timer:60"))
        f.reply("enable workflow diagnostics")
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, f.invoke().status)
        assertEquals(listOf(WorkflowDiagnostics.Code.TASK_REQUESTED), f.codes())
        f.coordinator().report(RoutineOutcomeObservation.DEVICE_VERIFIED)
        assertEquals(WorkflowDiagnostics.Code.TASK_DEVICE_VERIFIED, f.codes().last())
    }

    @Test fun legacyConsentPreservesOldObservationsWithoutSilentlyExpandingToTaskCheckpoints() = fixture { f ->
        f.reply("enable workflow diagnostics")
        f.diagnostics().observe(java.util.UUID.randomUUID().toString(), WorkflowDiagnostics.Code.NO_SOURCES)
        val legacy = org.json.JSONObject(f.path.readText()).put("version", 1)
        legacy.remove("tasksEnabled")
        f.path.writeText(legacy.toString())
        assertTrue(f.diagnostics().snapshot().enabled)
        assertFalse(f.diagnostics().snapshot().tasksEnabled)
        assertTrue(f.reply("review workflow diagnostics").contains("Earlier consent covers"))
        f.invoke()
        assertEquals(listOf(WorkflowDiagnostics.Code.NO_SOURCES), f.codes())
        f.reply("enable workflow diagnostics")
        f.coordinator().status() // Re-enabling never imports the earlier completed task.
        assertEquals(1, f.codes().size)
        f.invoke("explicitly opted in")
        assertEquals(listOf(WorkflowDiagnostics.Code.NO_SOURCES, WorkflowDiagnostics.Code.TASK_DEVICE_VERIFIED), f.codes())
        f.reply("disable workflow diagnostics")
        assertFalse(f.diagnostics().snapshot().tasksEnabled)
    }
}
