package com.assistant.core.workflow

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.LearnableDeviceAction
import com.assistant.core.services.GoalPlanning

/**
 * The execution lease spans planning, write-ahead checkpoints and dispatch. A new engine is
 * constructed only while holding it: another live runner's RUNNING state is never recovered
 * as an interruption. Status and cancellation remain available through a separate short I/O lock.
 */
interface RoutineWorkflowAccess {
    val store: RoutineWorkflowStore
    fun <T : Any> tryExclusive(block: () -> T): T?
    fun cancellationRequested(id: String): Boolean
    fun requestCancellation(id: String): Boolean
}

class RoutineExecutionCoordinator(
    private val access: RoutineWorkflowAccess,
    private val approvedRoutine: (String) -> ApprovedRoutineRegistry.Routine?,
    private val execute: (RoutineWorkflowCheckpoint, RoutineWorkflowStep) -> RoutineStepOutcome,
    private val resolvePreferred: (String) -> LearnableDeviceAction? = { null },
    private val canLaunchApp: (String) -> Boolean = { false },
    private val observe: (String, RoutineWorkflowObservation) -> Unit = { _, _ -> }
) {
    data class Result(val checkpoint: RoutineWorkflowCheckpoint?, val busy: Boolean = false, val message: String = "")

    /**
     * A freshly reviewed host plan, not a learned alias. Confirming its exact pending definition
     * must atomically consume that review and expose it to approvedRoutine before planning.
     * The callback only updates local approval state; it must not dispatch an action.
     */
    fun invokePrepared(phrase: String, requestKey: String, confirmDefinition: () -> Boolean): Result = exclusive { engine ->
        val previous = current(engine.routineCheckpoints())
        if (previous != null && blocks(previous)) {
            Result(previous, message = "Resolve this execution before approving another goal. The new goal has not run.")
        } else if (engine.routineCheckpoints().size >= RoutineWorkflowLimits.MAX_RETAINED_WORKFLOWS) {
            Result(previous, message = "Task history is full. Say 'review task history' to review completed records for cleanup. The pending goal approval was not consumed and no new steps ran.")
        } else {
            check(confirmDefinition()) { "The reviewed goal changed, expired or could not be saved." }
            val plan = engine.planApprovedRoutine(phrase, requestKey)
            engine.approveRoutine(plan.id, plan.steps.map { it.step })
            Result(engine.runApprovedRoutine(plan.id))
        }
    }

    /** Explicit reapproval after interruption between goal definition and approval checkpoint. */
    fun approvePrepared(id: String, reviewedSteps: List<RoutineWorkflowStep>): Result = exclusive { engine ->
        val saved = current(engine.routineCheckpoints())
        require(saved?.id == id && !saved.cancellationRequested && (saved.status == RoutineWorkflowStatus.AWAITING_APPROVAL ||
            (saved.status == RoutineWorkflowStatus.FAILED && !saved.approved && saved.steps.all { it.attempts == 0 }))) {
            "The goal review is no longer current."
        }
        engine.approveRoutine(id, reviewedSteps)
        Result(engine.runApprovedRoutine(id))
    }

    fun invoke(phrase: String, requestKey: String): Result = exclusive { engine ->
        val current = current(engine.routineCheckpoints())
        if (current != null && blocks(current) && !current.phrase.equals(phrase.trim(), ignoreCase = true)) {
            Result(current, message = if (current.steps.any { it.status in unresolved })
                "Check and report this routine's result before starting a different routine. Cancellation cannot clear an uncertain effect."
                else "Resolve or cancel this execution before starting a different routine.")
        } else {
            val plan = engine.planApprovedRoutine(phrase, requestKey)
            if (plan.status == RoutineWorkflowStatus.AWAITING_APPROVAL) {
                // The explicit invocation of this saved, reviewed routine is its authorization.
                engine.approveRoutine(plan.id, plan.steps.map { it.step })
            }
            Result(engine.runApprovedRoutine(plan.id))
        }
    }

    /** Recovery only inspects checkpoints. Reading status never starts pending actions. */
    fun status(): Result = exclusive { engine -> Result(current(engine.routineCheckpoints())) }

    data class HistoryResult(val review: RoutineHistory.Review? = null, val cleared: Int = 0, val message: String = "")

    fun reviewHistory(): HistoryResult = access.tryExclusive {
        HistoryResult(RoutineHistory.Review(engine().routineCheckpoints(), access.store.retiredFingerprintCount()))
    } ?: HistoryResult(message = "Another assistant is processing a task. Review task history after it returns; no history was changed.")

    /** A full fresh snapshot must equal the review; this never dispatches or cancels actions. */
    fun clearHistory(reviewed: RoutineHistory.Review): HistoryResult = access.tryExclusive {
        val fresh = RoutineHistory.Review(engine().routineCheckpoints(), access.store.retiredFingerprintCount())
        when {
            fresh != reviewed -> HistoryResult(message = "Task history changed. Say 'review task history' before clearing it; nothing was removed.")
            fresh.removable.isEmpty() -> HistoryResult(message = "No completed history is eligible for cleanup. The latest task and unresolved tasks are retained.")
            !access.store.archiveCompleted(fresh.removable) -> HistoryResult(message = "History cleanup could not be saved, or its duplicate-prevention storage is full. Existing task records remain protected; no actions were run.")
            else -> HistoryResult(cleared = fresh.removable.size,
                message = "Cleared ${fresh.removable.size} completed task records. The latest status, unfinished or uncertain tasks, and duplicate-prevention fingerprints remain. No device action was run or undone.")
        }
    } ?: HistoryResult(message = "Another assistant is processing a task. No history was changed; review again after it returns.")

    fun resume(expectedId: String? = null): Result = exclusive { engine ->
        val saved = current(engine.routineCheckpoints()) ?: return@exclusive Result(null)
        if (expectedId != null && expectedId != saved.id) return@exclusive Result(saved, message = "The tracked execution changed. Review its status before controlling it.")
        // READY can survive a crash between saved approval and first dispatch. This explicit
        // resume is fresh authorization; constructing the coordinator never starts it.
        Result(if (saved.status == RoutineWorkflowStatus.READY) engine.runApprovedRoutine(saved.id)
            else engine.resumeApprovedRoutine(saved.id))
    }

    fun cancel(expectedId: String? = null): Result {
        return access.tryExclusive {
            val engine = engine()
            val saved = current(engine.routineCheckpoints()) ?: return@tryExclusive Result(null)
            if (expectedId != null && expectedId != saved.id) return@tryExclusive Result(saved, message = "The tracked execution changed. No cancellation was applied.")
            Result(engine.cancelApprovedRoutine(saved.id))
        } ?: run {
            val saved = current(access.store.load()) ?: return Result(null, busy = true)
            if (expectedId != null && expectedId != saved.id) return Result(saved, busy = true, message = "The tracked execution changed. No cancellation was applied.")
            if (saved.status in terminal) return Result(saved, busy = true)
            check(access.requestCancellation(saved.id)) { "The cancellation request could not be saved." }
            Result(saved.copy(cancellationRequested = true, status = RoutineWorkflowStatus.CANCELLING), busy = true,
                message = "Cancellation was saved. It stops subsequent steps after the in-flight action returns.")
        }
    }

    /** Explicit checked-result report, never an approval or an instruction to resume. */
    fun report(observation: RoutineOutcomeObservation, expectedId: String? = null): Result = exclusive { engine ->
        val saved = current(engine.routineCheckpoints())
        if (expectedId != null && saved?.id != expectedId) Result(saved, message = "The tracked execution changed. No outcome was changed.")
        else report(engine, saved, observation)
    }

    /** A generic device follow-up may update only the exact invocation from this session. */
    fun reportFor(id: String, phrase: String, target: LearnableDeviceAction, observation: RoutineOutcomeObservation): Result = exclusive { engine ->
        val saved = current(engine.routineCheckpoints())
        if (saved?.id != id || saved.phrase != phrase || saved.steps.none { it.step.target == target && it.status in unresolved }) {
            Result(saved, message = "This result does not match the tracked routine invocation.")
        } else report(engine, saved, observation)
    }

    private fun report(engine: WorkflowEngine, saved: RoutineWorkflowCheckpoint?, observation: RoutineOutcomeObservation): Result {
        if (saved == null) return Result(null)
        val step = saved.steps.lastOrNull { it.status in unresolved && it.status != RoutineStepStatus.RUNNING }
            ?: return Result(saved, message = "No requested or uncertain routine step is awaiting a result report. No outcome was changed.")
        return Result(engine.recordRoutineOutcome(saved.id, step.step.index, observation,
            if (GoalPlanning.isKey(saved.phrase)) "User reported the current goal step's result. This is owner feedback, not device verification of the whole objective."
            else "User reported the current routine result. This is not device verification or proof that an update was installed."))
    }

    private fun exclusive(block: (WorkflowEngine) -> Result): Result =
        access.tryExclusive { block(engine()) } ?: run {
            val saved = current(access.store.load())
            Result(saved, busy = true, message = "Another assistant is processing this routine. No additional actions were started; review its status before retrying.")
        }

    private fun engine(): WorkflowEngine {
        lateinit var engine: WorkflowEngine
        engine = WorkflowEngine(approvedRoutine = approvedRoutine, resolvePreferred = resolvePreferred,
            canLaunchApp = canLaunchApp, store = access.store, observeRoutine = observe, executeRoutineStep = { checkpoint, step ->
                if (access.cancellationRequested(checkpoint.id)) {
                    engine.cancelApprovedRoutine(checkpoint.id)
                    RoutineStepOutcome.NotApplied("Cancellation was requested before dispatch.")
                } else {
                    val outcome = execute(checkpoint, step)
                    if (access.cancellationRequested(checkpoint.id)) engine.cancelApprovedRoutine(checkpoint.id)
                    outcome
                }
            })
        // A process may have died after persisting cancellation but before reaching a boundary.
        current(engine.routineCheckpoints())?.let {
            if (access.cancellationRequested(it.id)) engine.cancelApprovedRoutine(it.id)
        }
        return engine
    }

    private fun current(entries: List<RoutineWorkflowCheckpoint>): RoutineWorkflowCheckpoint? =
        entries.lastOrNull(::blocks) ?: entries.lastOrNull()

    private fun blocks(entry: RoutineWorkflowCheckpoint) = entry.status !in terminal || entry.steps.any { it.status in unresolved }

    private companion object {
        val terminal = setOf(RoutineWorkflowStatus.CANCELLED, RoutineWorkflowStatus.VERIFIED, RoutineWorkflowStatus.USER_CONFIRMED)
        val unresolved = setOf(RoutineStepStatus.RUNNING, RoutineStepStatus.REQUESTED, RoutineStepStatus.AWAITING_OUTCOME,
            RoutineStepStatus.REQUESTED_WITHOUT_TRACKING, RoutineStepStatus.AMBIGUOUS)
    }
}
