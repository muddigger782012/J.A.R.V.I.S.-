package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.workflow.*

/** On-demand synthetic logic checks. Every dispatcher is a counter; no host tools or user state. */
object LocalFoundationChecks {
    const val REVISION = 2
    enum class Case(val label: String, val expected: String) {
        CORRECTIONS("goal corrections", "A duration correction preserves volume and generates a new review; appended commands are rejected."),
        APPROVAL("approval guard", "Planning and execution before approval dispatch nothing; only exact reviewed steps can be approved."),
        RECOVERY("interrupted recovery", "An interrupted action remains ambiguous after reconstruction and cancellation, without another dispatch."),
        RETRIES("bounded retry", "A known not-applied failure can retry only within the attempt limit."),
        HISTORY("history duplicate guard", "Cleanup keeps the latest status and rejects archived invocation keys after reconstruction."),
        CONTACTS("contact selection logic", "Exact names take precedence, distinct numbers need selection, and malformed review controls stay local."),
        RESEARCH("research fallback logic", "A failed synthetic search method falls back to safe source URLs; malformed research controls stay local.")
    }
    enum class Outcome { PASSED, FAILED, ERROR }
    data class Result(val case: Case, val outcome: Outcome)
    data class Run(val build: Int, val buildName: String, val time: Long, val revision: Int, val results: List<Result>)
    private class CheckFailed : RuntimeException()
    private fun verify(condition: Boolean) { if (!condition) throw CheckFailed() }
    private fun rejected(block: () -> Unit): Boolean = try { block(); false } catch (_: IllegalArgumentException) { true } catch (_: IllegalStateException) { true }

    fun run(): List<Result> = run(emptyMap())

    /** Test seams can demonstrate that failed assertions and exceptions do not become passes. */
    internal fun run(overrides: Map<Case, () -> Boolean>): List<Result> = Case.values().map { case ->
        val outcome = try {
            val override = overrides[case]
            if (override != null) verify(override()) else when (case) {
                Case.CORRECTIONS -> corrections()
                Case.APPROVAL -> approval()
                Case.RECOVERY -> recovery()
                Case.RETRIES -> retries()
                Case.HISTORY -> history()
                Case.CONTACTS -> contacts()
                Case.RESEARCH -> research()
            }
            Outcome.PASSED
        } catch (_: CheckFailed) { Outcome.FAILED }
          catch (_: Exception) { Outcome.ERROR }
        Result(case, outcome)
    }

    private fun corrections() {
        val plan = GoalPlanning.plan(GoalPlanning.parse("help me focus for 25 minutes at 20 percent volume")!!,
            { null }, { null }, { false })!!
        val changed = GoalPlanning.revise(plan, GoalPlanning.edit("actually, make that ten minutes")!!)!!
        verify(changed.key != plan.key && changed.actions == listOf("volume:20", "timer:600"))
        verify(GoalPlanning.revise(changed, GoalPlanning.edit("make that ten minutes then reboot")!!) == null)
        verify(GoalPlanning.edit("what would make that easier?") == null)
    }

    private fun contacts() {
        val rows = listOf(ContactCallWorkflow.Recipient("1", "Alex", "2025550100"),
            ContactCallWorkflow.Recipient("2", "Alex", "2025550101"), ContactCallWorkflow.Recipient("3", "Alex Smith", "2025550102"))
        verify(ContactCallWorkflow.matches("Alex", rows).size == 2)
        verify(ContactCallWorkflow.phone("Alex 2") == null)
        verify(ContactCallCommands.parse("choose contact 2 then reboot")?.command == ContactCallCommands.Command.INVALID)
    }

    private fun research() {
        var attempts = 0
        val router = SearchRouter(listOf(SearchRouter.Provider("synthetic failure") { attempts++; error("synthetic") },
            SearchRouter.Provider("synthetic fallback") { attempts++; listOf(SearchRouter.Result("Source", "Synthetic excerpt", "https://example.com/source"),
                SearchRouter.Result("Invalid", "Synthetic", "javascript:invalid")) }))
        val result = router.search("synthetic topic")
        verify(attempts == 2 && result.items.size == 1 && result.provider == "synthetic fallback")
        verify(ResearchBriefCommands.parse("research status then reboot")?.command == ResearchBriefCommands.Command.INVALID)
    }

    private fun approval() {
        var calls = 0
        val routine = ApprovedRoutineRegistry.Routine("synthetic check", listOf("volume_down", "volume_up"))
        val engine = WorkflowEngine(approvedRoutine = { routine }, executeRoutineStep = { _, _ -> calls++; RoutineStepOutcome.Verified })
        val plan = engine.planApprovedRoutine(routine.phrase, "synthetic-turn")
        engine.runApprovedRoutine(plan.id)
        verify(calls == 0 && engine.routineCheckpoint(plan.id).status == RoutineWorkflowStatus.AWAITING_APPROVAL)
        verify(rejected { engine.approveRoutine(plan.id, plan.steps.map { it.step }.reversed()) })
        verify(calls == 0)
        engine.approveRoutine(plan.id, plan.steps.map { it.step })
        verify(engine.runApprovedRoutine(plan.id).status == RoutineWorkflowStatus.VERIFIED && calls == 2)
    }

    private fun recovery() {
        var calls = 0
        val store = InMemoryRoutineWorkflowStore()
        val routine = ApprovedRoutineRegistry.Routine("synthetic check", listOf("volume_down", "volume_up"))
        fun engine() = WorkflowEngine(approvedRoutine = { routine }, store = store,
            executeRoutineStep = { _, _ -> calls++; RoutineStepOutcome.Verified })
        val first = engine()
        val plan = first.planApprovedRoutine(routine.phrase, "synthetic-turn")
        val ready = first.approveRoutine(plan.id, plan.steps.map { it.step })
        verify(store.save(ready.copy(status = RoutineWorkflowStatus.RUNNING, steps = ready.steps.mapIndexed { index, state ->
            if (index == 0) state.copy(status = RoutineStepStatus.RUNNING, attempts = 1) else state
        })))
        val restored = engine()
        verify(restored.routineCheckpoint(plan.id).status == RoutineWorkflowStatus.AMBIGUOUS)
        restored.runApprovedRoutine(plan.id); restored.resumeApprovedRoutine(plan.id)
        val cancelled = restored.cancelApprovedRoutine(plan.id)
        restored.resumeApprovedRoutine(plan.id)
        verify(calls == 0 && cancelled.steps.first().status == RoutineStepStatus.AMBIGUOUS)
    }

    private fun retries() {
        var calls = 0
        val routine = ApprovedRoutineRegistry.Routine("synthetic check", listOf("volume_down"))
        val engine = WorkflowEngine(approvedRoutine = { routine }, executeRoutineStep = { _, _ ->
            calls++; RoutineStepOutcome.NotApplied("Synthetic non-application; no device dispatcher exists.")
        })
        val plan = engine.planApprovedRoutine(routine.phrase, "synthetic-turn")
        engine.approveRoutine(plan.id, plan.steps.map { it.step })
        engine.runApprovedRoutine(plan.id)
        verify(calls == 1)
        engine.resumeApprovedRoutine(plan.id)
        engine.resumeApprovedRoutine(plan.id)
        verify(calls == 2 && engine.routineCheckpoint(plan.id).steps.single().attempts == 2)
    }

    private fun history() {
        var raw: String? = null
        var calls = 0
        val routine = ApprovedRoutineRegistry.Routine("synthetic check", listOf("volume_down"))
        fun store() = JsonRoutineWorkflowStore({ raw }, { raw = it; true })
        fun engine() = WorkflowEngine(approvedRoutine = { routine }, store = store(),
            executeRoutineStep = { _, _ -> calls++; RoutineStepOutcome.Verified })
        fun complete(key: String): RoutineWorkflowCheckpoint {
            val runner = engine()
            val plan = runner.planApprovedRoutine(routine.phrase, key)
            runner.approveRoutine(plan.id, plan.steps.map { it.step })
            return runner.runApprovedRoutine(plan.id)
        }
        val old = complete("synthetic-old")
        val latest = complete("synthetic-latest")
        verify(store().archiveCompleted(listOf(old)))
        verify(store().load() == listOf(latest))
        verify(rejected { engine().planApprovedRoutine(routine.phrase, "synthetic-old") })
        verify(calls == 2)
        complete("synthetic-fresh")
        verify(calls == 3 && store().isRetiredRequest("synthetic-old"))
    }
}
