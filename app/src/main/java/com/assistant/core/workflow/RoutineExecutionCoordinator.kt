package com.assistant.core.workflow

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.LearnableDeviceAction

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
    private val canLaunchApp: (String) -> Boolean = { false }
) {
    data class Result(val checkpoint: RoutineWorkflowCheckpoint?, val busy: Boolean = false, val message: String = "")

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

    fun resume(): Result = exclusive { engine ->
        val saved = current(engine.routineCheckpoints()) ?: return@exclusive Result(null)
        // READY can survive a crash between saved approval and first dispatch. This explicit
        // resume is fresh authorization; constructing the coordinator never starts it.
        Result(if (saved.status == RoutineWorkflowStatus.READY) engine.runApprovedRoutine(saved.id)
            else engine.resumeApprovedRoutine(saved.id))
    }

    fun cancel(): Result {
        return access.tryExclusive {
            val engine = engine()
            val saved = current(engine.routineCheckpoints()) ?: return@tryExclusive Result(null)
            Result(engine.cancelApprovedRoutine(saved.id))
        } ?: run {
            val saved = current(access.store.load()) ?: return Result(null, busy = true)
            if (saved.status in terminal) return Result(saved, busy = true)
            check(access.requestCancellation(saved.id)) { "The cancellation request could not be saved." }
            Result(saved.copy(cancellationRequested = true, status = RoutineWorkflowStatus.CANCELLING), busy = true,
                message = "Cancellation was saved. It stops subsequent steps after the in-flight action returns.")
        }
    }

    /** Explicit checked-result report, never an approval or an instruction to resume. */
    fun report(observation: RoutineOutcomeObservation): Result = exclusive { engine ->
        report(engine, current(engine.routineCheckpoints()), observation)
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
            "User reported the current routine result. This is not device verification or proof that an update was installed."))
    }

    private fun exclusive(block: (WorkflowEngine) -> Result): Result =
        access.tryExclusive { block(engine()) } ?: run {
            val saved = current(access.store.load())
            Result(saved, busy = true, message = "Another assistant is processing this routine. No additional actions were started; review its status before retrying.")
        }

    private fun engine(): WorkflowEngine {
        lateinit var engine: WorkflowEngine
        engine = WorkflowEngine(approvedRoutine = approvedRoutine, resolvePreferred = resolvePreferred,
            canLaunchApp = canLaunchApp, store = access.store, executeRoutineStep = { checkpoint, step ->
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
