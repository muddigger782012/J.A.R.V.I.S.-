package com.assistant.core.workflow

import com.assistant.core.models.Task
import com.assistant.core.models.Workflow
import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.LearnableDeviceAction
import java.util.UUID

class WorkflowEngine(
    approvedRoutine: (String) -> ApprovedRoutineRegistry.Routine? = { null },
    executeRoutineStep: (RoutineWorkflowCheckpoint, RoutineWorkflowStep) -> RoutineStepOutcome = { _, _ ->
        RoutineStepOutcome.NotApplied("No routine dispatcher is configured.")
    },
    resolvePreferred: (String) -> LearnableDeviceAction? = { null },
    canLaunchApp: (String) -> Boolean = { false },
    store: RoutineWorkflowStore = InMemoryRoutineWorkflowStore(),
    maxStepAttempts: Int = 2
) {
    private val routines = ApprovedRoutineExecutor(
        approvedRoutine, executeRoutineStep, resolvePreferred, canLaunchApp, store, maxStepAttempts
    )

    /** Lookup a saved routine and freeze its reviewed targets, without running anything. */
    fun planApprovedRoutine(phrase: String, requestKey: String): RoutineWorkflowCheckpoint = routines.plan(phrase, requestKey)

    /** Use existing saved-routine invocation approval; never bypass its confirmation flow. */
    fun approveRoutine(id: String, reviewedSteps: List<RoutineWorkflowStep>): RoutineWorkflowCheckpoint = routines.approve(id, reviewedSteps)

    fun runApprovedRoutine(id: String): RoutineWorkflowCheckpoint = routines.run(id)

    fun resumeApprovedRoutine(id: String): RoutineWorkflowCheckpoint = routines.resume(id)

    fun cancelApprovedRoutine(id: String): RoutineWorkflowCheckpoint = routines.cancel(id)

    fun routineCheckpoint(id: String): RoutineWorkflowCheckpoint = routines.checkpoint(id)

    fun routineCheckpoints(): List<RoutineWorkflowCheckpoint> = routines.checkpoints()

    fun recordRoutineOutcome(id: String, stepIndex: Int, observation: RoutineOutcomeObservation, evidence: String): RoutineWorkflowCheckpoint =
        routines.recordOutcome(id, stepIndex, observation, evidence)

    fun createWorkflow(name: String, triggerType: String): Workflow {
        return Workflow(
            id = UUID.randomUUID().toString(),
            name = name,
            triggerType = triggerType,
            createdAt = System.currentTimeMillis()
        )
    }

    fun createTask(title: String, status: String = "PENDING"): Task {
        return Task(
            id = UUID.randomUUID().toString(),
            title = title,
            status = status,
            createdAt = System.currentTimeMillis()
        )
    }
}
