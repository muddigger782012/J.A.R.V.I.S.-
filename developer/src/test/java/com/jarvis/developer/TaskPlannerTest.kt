package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class TaskPlannerTest {
    private val a = "developer/src/main/java/com/jarvis/developer/ScreenState.kt"
    private val b = "developer/src/main/java/com/jarvis/developer/ScreenRows.kt"
    private val c = "developer/src/test/java/com/jarvis/developer/ScreenStateTest.kt"
    private val readonly = "app/src/main/java/com/assistant/SettingsActivity.kt"
    private val commit = "1".repeat(40)

    private fun file(path: String, deps: List<String> = emptyList(), status: String = "resolved") = RepositoryAnalysis.PlannedFile(
        RepositoryAnalysis.SourceFile(path, "2".repeat(40), 0), listOf("Untrusted source reason"), "Never copy proposed code", "Never copy source impact",
        emptyList(), if (deps.isEmpty()) emptyList() else listOf(RepositoryAnalysis.Dependency("Untrusted reference", "import", deps, status)),
        listOf(RepositoryAnalysis.Citation(path, "2".repeat(40), commit, 1, 1, "DO NOT PERSIST SOURCE EXCERPT")))

    private fun analysis(files: List<RepositoryAnalysis.PlannedFile> = listOf(file(a), file(b, listOf(a)))) =
        RepositoryAnalysis.AnalysisPlan("Improve scoped screen state", commit, files, emptyList(), files.map { it.path }, files.size)

    private fun plan(files: List<RepositoryAnalysis.PlannedFile> = listOf(file(a), file(b, listOf(a))), scope: List<String> = listOf(a, b)) =
        TaskPlanner.create("screen-task", analysis(files), scope, 10)

    private fun done(plan: TaskPlanner.Plan, id: String): TaskPlanner.Plan =
        TaskPlanner.transition(plan, id, TaskPlanner.Status.DONE, "User reports reviewed $id; no independent CI proof", plan.updatedAtMillis + 1)

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected bounded validation failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun decomposesResolvedDependenciesWithImmutableSourceReferences() {
        val plan = plan()
        assertEquals(commit, plan.baseCommitSha)
        assertEquals(analysis().analysisId, plan.analysisId)
        assertEquals(listOf("analyze", "implement-1", "implement-2", "validate", "review"), plan.steps.map { it.id })
        val prerequisite = plan.steps.single { a in it.paths }
        val dependent = plan.steps.single { b in it.paths }
        assertTrue(prerequisite.id in dependent.dependsOn)
        assertTrue(plan.steps.indexOf(prerequisite) < plan.steps.indexOf(dependent))
        assertEquals("analyze", TaskPlanner.next(plan)?.id)
        assertEquals(0L, plan.revision)
        assertEquals(1, plan.decisions.single().sequence)
        val persisted = plan.toString()
        assertFalse(persisted.contains("DO NOT PERSIST SOURCE EXCERPT"))
        assertFalse(persisted.contains("Never copy proposed code"))
        assertFalse(persisted.contains("Untrusted reference"))
    }

    @Test fun sourceCyclesBecomeOneBoundedImplementationStep() {
        val files = listOf(file(a, listOf(b)), file(b, listOf(a)), file(c, listOf(a)))
        val plan = plan(files, listOf(c, b, a))
        val group = plan.steps.single { a in it.paths }
        assertEquals(setOf(a, b), group.paths.toSet())
        assertTrue(group.detail.contains("dependency cycle"))
        assertTrue(group.id in plan.steps.single { c in it.paths }.dependsOn)
        TaskPlanner.validate(plan)
        assertEquals(plan, this.plan(files.reversed(), listOf(a, c, b)))
    }

    @Test fun excludesReadonlyAndUnresolvedEdgesFromEditableGraph() {
        val plan = plan(listOf(file(a, listOf(readonly)), file(b, listOf(a), "ambiguous")))
        assertEquals(setOf(a, b), plan.steps.flatMap { it.paths }.toSet())
        assertFalse(readonly in plan.scope)
        assertTrue(plan.steps.single { a in it.paths }.detail.contains("read-only context"))
        assertTrue(plan.steps.single { b in it.paths }.detail.contains("uncertain lexical dependencies"))
        assertEquals(listOf("analyze"), plan.steps.single { b in it.paths }.dependsOn)
        rejects { this.plan(scope = listOf(readonly)) }
    }

    @Test fun explicitFilesAbsentFromAnalysisRequireInspection() {
        val plan = plan(listOf(file(a)), listOf(a, b))
        assertTrue(plan.steps.single { b in it.paths }.detail.contains("not selected by analysis"))
        assertEquals(setOf(a, b), plan.scope.toSet())
    }

    @Test fun cannotStartOrCompleteBeforeDependenciesAndOnlyOneStepRuns() {
        var plan = plan(listOf(file(a), file(b)))
        rejects { TaskPlanner.transition(plan, "implement-1", TaskPlanner.Status.RUNNING, "", 11) }
        rejects { done(plan, "implement-1") }
        rejects { TaskPlanner.transition(plan, "analyze", TaskPlanner.Status.DONE, "", 11) }
        plan = done(plan, "analyze")
        plan = TaskPlanner.transition(plan, "implement-1", TaskPlanner.Status.RUNNING, "", 12)
        assertEquals("implement-1", TaskPlanner.next(plan)?.id)
        rejects { TaskPlanner.transition(plan, "implement-2", TaskPlanner.Status.RUNNING, "", 13) }
        plan = done(plan, "implement-1")
        assertEquals("implement-2", TaskPlanner.next(plan)?.id)
    }

    @Test fun completesAllStepsWithoutInferringBuildSuccess() {
        var plan = plan()
        while (TaskPlanner.next(plan) != null) plan = done(plan, TaskPlanner.next(plan)!!.id)
        assertTrue(plan.steps.all { it.status == TaskPlanner.Status.DONE })
        assertNull(TaskPlanner.next(plan))
        assertTrue(plan.steps.single { it.id == "validate" }.detail.contains("does not prove"))
        assertTrue(plan.steps.all { it.checkpoint.contains("no independent CI proof") })
    }

    @Test fun pauseResumeAndRecoveryNeverRestartInterruptedWork() {
        var plan = done(plan(), "analyze")
        plan = TaskPlanner.transition(plan, "implement-1", TaskPlanner.Status.RUNNING, "", 12)
        val checkpoint = TaskPlanner.pause(plan, true, 13)
        assertNull(TaskPlanner.next(checkpoint))
        rejects { TaskPlanner.transition(checkpoint, "implement-1", TaskPlanner.Status.RUNNING, "", 14) }
        val recovered = TaskPlanner.recover(checkpoint, 14)
        assertTrue(recovered.paused)
        assertEquals(TaskPlanner.Status.BLOCKED, recovered.steps.single { it.id == "implement-1" }.status)
        assertEquals(checkpoint.scope, recovered.scope)
        assertEquals(checkpoint.decisions, recovered.decisions.take(checkpoint.decisions.size))
        assertTrue(recovered.decisions.last().message.contains("uncertain"))
        val resumed = TaskPlanner.pause(recovered, false, 15)
        assertNull(TaskPlanner.next(resumed))
        val unblocked = TaskPlanner.transition(resumed, "implement-1", TaskPlanner.Status.PENDING, "", 16)
        assertEquals("implement-1", TaskPlanner.next(unblocked)?.id)
    }

    @Test fun editingResetsSelectedAndAllTransitiveDependents() {
        var plan = plan()
        while (TaskPlanner.next(plan) != null) plan = done(plan, TaskPlanner.next(plan)!!.id)
        val target = plan.steps.single { a in it.paths }
        val edited = TaskPlanner.editStep(plan, target.id, "Review changed acceptance", "New bounded requirement", target.dependsOn, plan.updatedAtMillis + 1)
        assertEquals(TaskPlanner.Status.DONE, edited.steps.first().status)
        assertTrue(edited.steps.drop(1).all { it.status == TaskPlanner.Status.PENDING && it.checkpoint.isEmpty() })
        assertEquals("Review changed acceptance", edited.steps.single { it.id == target.id }.title)
        assertEquals(plan.scope, edited.scope)
        assertEquals(plan.revision + 1, edited.revision)
        assertTrue(plan.steps.all { it.status == TaskPlanner.Status.DONE })
        val originalReport = plan.steps.single { it.id == target.id }.checkpoint
        assertTrue(edited.decisions.any { originalReport in it.message })
        val recovered = TaskPlanner.recover(edited, edited.updatedAtMillis + 1)
        assertTrue(recovered.decisions.any { originalReport in it.message })
    }

    @Test fun revertingCompletionInvalidatesDependentCheckpoints() {
        var plan = plan()
        while (TaskPlanner.next(plan) != null) plan = done(plan, TaskPlanner.next(plan)!!.id)
        val changed = TaskPlanner.transition(plan, "analyze", TaskPlanner.Status.BLOCKED, "", plan.updatedAtMillis + 1)
        assertEquals(TaskPlanner.Status.BLOCKED, changed.steps.first().status)
        assertTrue(changed.steps.drop(1).all { it.status == TaskPlanner.Status.PENDING && it.checkpoint.isEmpty() })
    }

    @Test fun blockingReasonsRemainInHistoryAfterRetryAndRecovery() {
        val started = TaskPlanner.transition(done(plan(), "analyze"), "implement-1", TaskPlanner.Status.RUNNING, "Starting a bounded review", 12)
        val blocked = TaskPlanner.transition(started, "implement-1", TaskPlanner.Status.BLOCKED, "Dependency contract needs owner clarification", 13)
        assertEquals("", blocked.steps.single { it.id == "implement-1" }.checkpoint)
        assertTrue(blocked.decisions.last().message.contains("Dependency contract needs owner clarification"))
        val retry = TaskPlanner.transition(blocked, "implement-1", TaskPlanner.Status.PENDING, "Retry after clarification", 14)
        val recovered = TaskPlanner.recover(retry, 15)
        assertTrue(recovered.decisions.any { it.message.contains("Starting a bounded review") })
        assertTrue(recovered.decisions.any { it.message.contains("Dependency contract needs owner clarification") })
        assertTrue(recovered.decisions.any { it.message.contains("Retry after clarification") })
        rejects { TaskPlanner.transition(recovered, "implement-1", TaskPlanner.Status.BLOCKED, "x".repeat(4_001), 16) }
    }

    @Test fun dependencyEditsRejectCyclesMissingDuplicatesAndForwardReferences() {
        val plan = plan()
        for (dependencies in listOf(listOf("review"), listOf("absent"), listOf("analyze", "analyze"), listOf("implement-1"))) {
            rejects { TaskPlanner.editStep(plan, "implement-1", "Edited", "Edited detail", dependencies, 11) }
        }
        rejects { TaskPlanner.editStep(plan, "absent", "Edited", "Edited detail", emptyList(), 11) }
    }

    @Test fun independentStepsCanMoveButDependencyOrderCannotBeBroken() {
        val plan = plan(listOf(file(a), file(b)))
        val moved = TaskPlanner.move(plan, "implement-2", -1, 11)
        assertEquals(listOf("analyze", "implement-2", "implement-1", "validate", "review"), moved.steps.map { it.id })
        assertEquals(plan.scope, moved.scope)
        rejects { TaskPlanner.move(plan, "analyze", 1, 11) }
        rejects { TaskPlanner.move(plan, "review", -1, 11) }
        rejects { TaskPlanner.move(plan, "analyze", -1, 11) }
        rejects { TaskPlanner.move(plan, "implement-2", Int.MIN_VALUE, 11) }
    }

    @Test fun boundsScopeMetadataAndPersistedGraph() {
        rejects { this.plan(scope = emptyList()) }
        rejects { this.plan(scope = listOf(a, a)) }
        rejects { this.plan(scope = (1..9).map { a.replace("ScreenState.kt", "Screen$it.kt") }) }
        rejects { TaskPlanner.create("bad/id", analysis(), listOf(a), 10) }
        rejects { TaskPlanner.create("x".repeat(49), analysis(), listOf(a), 10) }
        val plan = plan()
        rejects { TaskPlanner.validate(plan.copy(scope = listOf(readonly))) }
        rejects { TaskPlanner.validate(plan.copy(steps = plan.steps + plan.steps.first())) }
        rejects { TaskPlanner.validate(plan.copy(steps = plan.steps.map { it.copy(paths = emptyList()) })) }
        rejects { TaskPlanner.validate(plan.copy(baseCommitSha = "main")) }
        rejects { TaskPlanner.validate(plan.copy(analysisId = "unverified")) }
        rejects { TaskPlanner.validate(plan.copy(revision = 20)) }
        rejects { TaskPlanner.note(plan, "x".repeat(4_001), 11) }
        rejects { TaskPlanner.create("task", analysis().copy(task = "x".repeat(4_001)), listOf(a), 10) }
    }

    @Test fun decisionHistoryHasNoSilentTruncationAndClockCannotGoBackwards() {
        var plan = plan()
        for (index in 2..TaskPlanner.MAX_DECISIONS) plan = TaskPlanner.note(plan, "Decision $index", plan.updatedAtMillis + 1)
        assertEquals(TaskPlanner.MAX_DECISIONS, plan.decisions.size)
        assertEquals((1..TaskPlanner.MAX_DECISIONS).toList(), plan.decisions.map { it.sequence })
        rejects { TaskPlanner.note(plan, "One too many", plan.updatedAtMillis + 1) }
        rejects { TaskPlanner.pause(this.plan(), true, 9) }
        rejects { TaskPlanner.validate(plan.copy(decisions = plan.decisions.mapIndexed { i, d -> if (i == 1) d.copy(atMillis = 0) else d })) }
        rejects { TaskPlanner.validate(plan.copy(decisions = plan.decisions.mapIndexed { i, d -> if (i == 1) d.copy(sequence = 1) else d })) }
        rejects { TaskPlanner.validate(plan.copy(decisions = plan.decisions.mapIndexed { i, d -> if (i == 1) d.copy(stepId = "unknown") else d })) }
    }

    @Test fun rejectsRecognizedSecretsAndControlsAcrossMetadata() {
        val token = "ghp_" + "A".repeat(24)
        val samples = listOf(token, "api_key=abcdefghijklmnop", "password = \"avoid-saving-this\"", "-----BEGIN RSA PRIVATE KEY-----", "Authorization: Bearer abcdefghijklmnop", "bad\u0000control", "bad\u007fcontrol", "bad\u0085control", "bad\u202eoverride", "bad\ud800surrogate")
        val plan = plan()
        for (secret in samples) {
            rejects { TaskPlanner.note(plan, secret, 11) }
            rejects { TaskPlanner.editStep(plan, "analyze", secret, "Safe detail", emptyList(), 11) }
            rejects { TaskPlanner.editStep(plan, "analyze", "Safe title", secret, emptyList(), 11) }
            rejects { TaskPlanner.transition(plan, "analyze", TaskPlanner.Status.DONE, secret, 11) }
            rejects { TaskPlanner.create("task", analysis().copy(task = secret), listOf(a), 10) }
        }
        rejects { TaskPlanner.create(token, analysis(), listOf(a), 10) }
        rejects { this.plan(scope = listOf(a.replace("ScreenState", token))) }
    }

    @Test fun snapshotsCallerListsAndExposesUnmodifiableNestedLists() {
        val paths = mutableListOf(a, b)
        val files = mutableListOf(file(a), file(b, listOf(a)))
        val plan = TaskPlanner.create("task", analysis(files), paths, 10)
        paths.clear(); files.clear()
        assertEquals(setOf(a, b), plan.scope.toSet())
        val deps = mutableListOf("analyze")
        val edited = TaskPlanner.editStep(plan, "implement-1", "Editable title", "Editable detail", deps, 11)
        deps.clear()
        assertEquals(listOf("analyze"), edited.steps.single { it.id == "implement-1" }.dependsOn)
        fun immutable(block: () -> Unit) { try { block(); fail("List must be unmodifiable") } catch (_: UnsupportedOperationException) { } }
        immutable { (edited.scope as MutableList<String>).clear() }
        immutable { (edited.steps as MutableList<TaskPlanner.Step>).clear() }
        immutable { (edited.steps.single { it.id == "implement-1" }.paths as MutableList<String>).clear() }
        immutable { (edited.steps.single { it.id == "implement-1" }.dependsOn as MutableList<String>).clear() }
        immutable { (edited.decisions as MutableList<TaskPlanner.Decision>).clear() }
        immutable { (TaskPlanner.next(edited)!!.dependsOn as MutableList<String>).clear() }
        TaskPlanner.validate(edited)
    }
}
