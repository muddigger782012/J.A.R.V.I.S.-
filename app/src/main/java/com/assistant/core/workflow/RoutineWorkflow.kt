package com.assistant.core.workflow

import com.assistant.core.services.LearnableDeviceAction
import com.assistant.core.services.RoutineExecution

/** Capacity refuses new work; no checkpoint or invocation key is silently evicted. */
object RoutineWorkflowLimits {
    const val MAX_RETAINED_WORKFLOWS = 100
    const val MAX_REQUEST_KEYS_PER_WORKFLOW = 32
}

/** A host-bound action from the existing RoutineSteps allowlist, never executable text. */
data class RoutineWorkflowStep(
    val index: Int,
    val actionId: String,
    val target: LearnableDeviceAction? = null
)

enum class RoutineWorkflowStatus {
    AWAITING_APPROVAL, READY, RUNNING, PAUSED, AWAITING_OUTCOME,
    FAILED, AMBIGUOUS, CANCELLING, CANCELLED, VERIFIED, USER_CONFIRMED
}

enum class RoutineStepStatus {
    PENDING, RUNNING, VERIFIED, USER_CONFIRMED, REQUESTED, AWAITING_OUTCOME,
    REQUESTED_WITHOUT_TRACKING, FAILED, AMBIGUOUS
}

data class RoutineStepCheckpoint(
    val step: RoutineWorkflowStep,
    val status: RoutineStepStatus = RoutineStepStatus.PENDING,
    val attempts: Int = 0,
    val detail: String = ""
)

/** Immutable snapshot for review, status and an optional durable store. No Android objects. */
data class RoutineWorkflowCheckpoint(
    val id: String,
    val requestKeys: Set<String>,
    val phrase: String,
    val steps: List<RoutineStepCheckpoint>,
    val status: RoutineWorkflowStatus = RoutineWorkflowStatus.AWAITING_APPROVAL,
    val approved: Boolean = false,
    val cancellationRequested: Boolean = false,
    val detail: String = ""
)

/**
 * FAILED means positively known not to have applied. A false verification result or an
 * exception after dispatch is ambiguous, not evidence that repeating an action is safe.
 */
sealed class RoutineStepOutcome {
    object Verified : RoutineStepOutcome()
    object Requested : RoutineStepOutcome()
    object AwaitingOutcome : RoutineStepOutcome()
    object RequestedWithoutTracking : RoutineStepOutcome()
    data class NotApplied(val reason: String) : RoutineStepOutcome()
    data class Ambiguous(val reason: String) : RoutineStepOutcome()

    companion object {
        /** Conservative bridge for the service's current routine-dispatch callback. */
        fun fromRoutineStatus(status: RoutineExecution.Status): RoutineStepOutcome = when (status) {
            RoutineExecution.Status.VERIFIED -> Verified
            RoutineExecution.Status.REQUESTED -> Requested
            RoutineExecution.Status.AWAITING_OUTCOME -> AwaitingOutcome
            RoutineExecution.Status.REQUESTED_WITHOUT_TRACKING -> RequestedWithoutTracking
            RoutineExecution.Status.FAILED -> Ambiguous("The action did not produce a verified outcome.")
        }
    }
}

/** Evidence supplied by an existing device readback or explicit user outcome flow. */
enum class RoutineOutcomeObservation { DEVICE_VERIFIED, USER_CONFIRMED, FAILED }

/**
 * save must atomically retain the entire snapshot before returning true. A durable adapter
 * can survive service/process recreation. Share one WorkflowEngine per store; this contract
 * does not provide coordination between independently running engines or processes.
 */
interface RoutineWorkflowStore {
    fun load(): List<RoutineWorkflowCheckpoint>
    fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean
}

/** Default is process-local. Do not claim restart recovery without a durable adapter. */
class InMemoryRoutineWorkflowStore : RoutineWorkflowStore {
    private val entries = linkedMapOf<String, RoutineWorkflowCheckpoint>()

    @Synchronized override fun load(): List<RoutineWorkflowCheckpoint> = entries.values.map { it.detached() }

    @Synchronized override fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean {
        entries[checkpoint.id] = checkpoint.detached()
        return true
    }
}

/** Keep caller-owned mutable lists/maps out of both live state and stored snapshots. */
internal fun RoutineWorkflowCheckpoint.detached(): RoutineWorkflowCheckpoint = copy(
    requestKeys = requestKeys.toSet(),
    steps = steps.map { state ->
        state.copy(step = state.step.copy(target = state.step.target?.let {
            it.copy(parameters = it.parameters.toMap())
        }))
    }
)
