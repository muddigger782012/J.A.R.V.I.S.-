package com.assistant.core.workflow

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.LearnableDeviceAction
import com.assistant.core.services.RoutineSteps
import com.assistant.core.services.GoalPlanning
import com.assistant.core.services.ComposableGoalPlanning
import java.util.UUID

/**
 * Executes only registry-approved, bounded routines. Planning and approval have no effects.
 * The injected dispatcher must return promptly and must not internally retry launches.
 * Cancellation is cooperative: it stops subsequent dispatch, not an Android action already
 * in flight. All external effects run outside the state lock so cancellation/status work.
 */
internal class ApprovedRoutineExecutor(
    private val approvedRoutine: (String) -> ApprovedRoutineRegistry.Routine?,
    private val execute: (RoutineWorkflowCheckpoint, RoutineWorkflowStep) -> RoutineStepOutcome,
    private val resolvePreferred: (String) -> LearnableDeviceAction?,
    private val canLaunchApp: (String) -> Boolean,
    private val store: RoutineWorkflowStore,
    private val maxStepAttempts: Int
) {
    private val lock = Any()
    private val entries = linkedMapOf<String, RoutineWorkflowCheckpoint>()
    private val running = mutableSetOf<String>()

    init {
        require(maxStepAttempts in 1..3) { "Step attempts must be bounded to 1..3." }
        // A load failure must fail closed; silently starting with empty state could relaunch.
        for (saved in store.load()) {
            require(saved.id !in entries && saved.requestKeys.isNotEmpty() && saved.requestKeys.none { it.isBlank() })
            require(entries.values.none { it.requestKeys.intersect(saved.requestKeys).isNotEmpty() })
            require(validSteps(saved.phrase, saved.steps.map { it.step }) && saved.steps.all { it.attempts in 0..3 })
            val recovered = when {
                saved.steps.any { it.status == RoutineStepStatus.RUNNING } -> saved.copy(
                    steps = saved.steps.map { if (it.status == RoutineStepStatus.RUNNING)
                        it.copy(status = RoutineStepStatus.AMBIGUOUS, detail = "Execution was interrupted; do not repeat without outcome evidence.") else it },
                    status = if (saved.cancellationRequested) RoutineWorkflowStatus.CANCELLED else RoutineWorkflowStatus.AMBIGUOUS,
                    detail = "An in-flight action may have applied before interruption."
                )
                saved.status in setOf(RoutineWorkflowStatus.RUNNING, RoutineWorkflowStatus.CANCELLING) -> saved.copy(
                    status = if (saved.cancellationRequested) RoutineWorkflowStatus.CANCELLED else RoutineWorkflowStatus.PAUSED
                )
                else -> saved
            }.detached()
            entries[saved.id] = recovered
            if (recovered != saved) runCatching { store.save(recovered.detached()) }
        }
    }

    fun plan(phrase: String, requestKey: String): RoutineWorkflowCheckpoint = synchronized(lock) {
        require(requestKey.isNotBlank() && requestKey.length <= 200) { "A stable invocation key is required." }
        check(!store.isRetiredRequest(requestKey)) { "This invocation was already archived. No action was repeated; use a fresh explicit request." }
        entries.values.firstOrNull { requestKey in it.requestKeys }?.let {
            require(it.phrase.equals(phrase.trim().replace(Regex("\\s+"), " "), ignoreCase = true)) {
                "An invocation key cannot be reused for a different routine."
            }
            return@synchronized it.detached()
        }
        val routine = approvedRoutine(phrase) ?: throw IllegalArgumentException("No approved routine was found.")
        require(validActions(routine.phrase, routine.actions)) { "Unsupported routine steps." }
        entries.values.firstOrNull { it.phrase == routine.phrase && blocksNewInvocation(it) }?.let {
            check(it.requestKeys.size < RoutineWorkflowLimits.MAX_REQUEST_KEYS_PER_WORKFLOW) {
                "The workflow invocation-key limit was reached. Inspect the existing workflow; no actions were run."
            }
            val aliased = it.copy(requestKeys = it.requestKeys + requestKey)
            check(save(aliased)) { "The duplicate invocation checkpoint could not be saved." }
            return@synchronized aliased.detached()
        }
        check(entries.size < RoutineWorkflowLimits.MAX_RETAINED_WORKFLOWS) {
            "The workflow journal is full. Say 'review task history' to review completed entries for cleanup. Existing status, cancellation and safe resume remain available; no actions were run."
        }
        val steps = routine.actions.mapIndexed { index, action ->
            val slot = RoutineSteps.preferredSlot(action)
            val target = when {
                slot != null -> resolvePreferred(slot)?.takeIf { it.isValid() && it.capabilityId == "open_app" }
                    ?: throw IllegalArgumentException("The approved preferred app is unavailable.")
                else -> RoutineSteps.appAction(action) ?: RoutineSteps.timerAction(action)
            }
            RoutineStepCheckpoint(RoutineWorkflowStep(index, action, target?.let { it.copy(parameters = it.parameters.toMap()) }))
        }
        require(targetsAvailable(steps)) { "The approved app is not launchable. No steps were run." }
        // A second routine name must not bypass an unresolved timer/app/updater launch.
        require(entries.values.none { previous -> previous.steps.any { old ->
            old.status in unresolved && steps.any { effectKey(old.step) == effectKey(it.step) }
        } }) { "An equivalent action has an unresolved outcome. Inspect that workflow first." }
        val checkpoint = RoutineWorkflowCheckpoint(UUID.randomUUID().toString(), setOf(requestKey), routine.phrase, steps)
        check(save(checkpoint)) { "The workflow plan could not be saved. No steps were run." }
        checkpoint.detached()
    }

    /**
     * Call only for an explicit invocation of the saved, reviewed routine (or an existing
     * confirmation). This does not replace RoutineConfirmationStore or authorize installs.
     * The exact displayed plan, including frozen preferred-app binding, must match.
     */
    fun approve(id: String, reviewedSteps: List<RoutineWorkflowStep>): RoutineWorkflowCheckpoint = synchronized(lock) {
        val current = get(id)
        val retryApproval = current.status == RoutineWorkflowStatus.FAILED && !current.approved &&
            !current.cancellationRequested && current.steps.all { it.attempts == 0 }
        if (current.status != RoutineWorkflowStatus.AWAITING_APPROVAL && !retryApproval) return@synchronized current.detached()
        require(current.steps.map { it.step } == reviewedSteps) { "Approval must match the exact reviewed plan." }
        if (!stillApproved(current)) return@synchronized failBeforeDispatch(current, "The saved routine changed or was removed.")
        val approved = current.copy(approved = true, status = RoutineWorkflowStatus.READY)
        if (!save(approved)) return@synchronized failBeforeDispatch(current, "Approval checkpoint could not be saved.")
        approved.detached()
    }

    fun checkpoint(id: String): RoutineWorkflowCheckpoint = synchronized(lock) { get(id).detached() }

    fun checkpoints(): List<RoutineWorkflowCheckpoint> = synchronized(lock) { entries.values.map { it.detached() } }

    fun run(id: String): RoutineWorkflowCheckpoint = drive(id, resume = false)

    /** No automatic retry. Only pending steps or positively not-applied failures can resume. */
    fun resume(id: String): RoutineWorkflowCheckpoint = drive(id, resume = true)

    private fun drive(id: String, resume: Boolean): RoutineWorkflowCheckpoint {
        synchronized(lock) {
            val current = get(id)
            if (id in running || current.cancellationRequested || !current.approved) return current.detached()
            val allowed = if (resume) setOf(RoutineWorkflowStatus.FAILED, RoutineWorkflowStatus.PAUSED)
                else setOf(RoutineWorkflowStatus.READY)
            if (current.status !in allowed) return current.detached()
            val next = current.steps.firstOrNull { it.status !in finished } ?: return finish(current)
            if (next.status !in setOf(RoutineStepStatus.PENDING, RoutineStepStatus.FAILED) || next.attempts >= maxStepAttempts)
                return current.detached()
            if (!stillApproved(current)) return failBeforeDispatch(current, "The saved routine changed or was removed.")
            if (!targetsAvailable(current.steps)) return failBeforeDispatch(current, "The bound app is unavailable. No further steps were run.")
            if (conflictsWithUnresolved(current)) return failBeforeDispatch(current, "Another workflow has an unresolved effect. No further steps were run.")
            running.add(id)
        }
        try {
            while (true) {
                val next = synchronized(lock) {
                    val current = get(id)
                    if (current.cancellationRequested) {
                        val cancelled = current.copy(status = RoutineWorkflowStatus.CANCELLED)
                        save(cancelled)
                        return cancelled.detached()
                    }
                    val candidate = current.steps.firstOrNull { it.status !in finished }
                        ?: return finish(current)
                    if (candidate.status !in setOf(RoutineStepStatus.PENDING, RoutineStepStatus.FAILED) || candidate.attempts >= maxStepAttempts)
                        return current.detached()
                    if (!stillApproved(current)) return failBeforeDispatch(current, "The saved routine changed or was removed.")
                    if (conflictsWithUnresolved(current)) return failBeforeDispatch(current, "Another workflow has an unresolved effect. No further steps were run.")
                    val started = candidate.copy(status = RoutineStepStatus.RUNNING, attempts = candidate.attempts + 1, detail = "")
                    val dispatching = current.withStep(started).copy(status = RoutineWorkflowStatus.RUNNING, detail = "")
                    // Write ahead. A restarted engine sees RUNNING as ambiguous, never retryable.
                    if (!save(dispatching)) {
                        val notRun = current.withStep(candidate.copy(status = RoutineStepStatus.FAILED,
                            detail = "No action was dispatched because its checkpoint could not be saved."))
                        return failBeforeDispatch(notRun, "Execution checkpoint could not be saved.")
                    }
                    started
                }
                val outcome = try {
                    // A cancellation arriving after this dispatch boundary cannot undo this action.
                    execute(checkpoint(id), next.step.copy(target = next.step.target?.let { it.copy(parameters = it.parameters.toMap()) }))
                } catch (_: Exception) {
                    RoutineStepOutcome.Ambiguous("The dispatcher was interrupted or failed; the effect may already have applied.")
                }
                synchronized(lock) {
                    val current = get(id)
                    val result = applyOutcome(next, outcome)
                    val updated = current.withStep(result)
                    val status = when {
                        current.cancellationRequested -> RoutineWorkflowStatus.CANCELLED
                        result.status == RoutineStepStatus.FAILED -> RoutineWorkflowStatus.FAILED
                        result.status == RoutineStepStatus.AMBIGUOUS -> RoutineWorkflowStatus.AMBIGUOUS
                        result.status in requested -> RoutineWorkflowStatus.AWAITING_OUTCOME
                        else -> RoutineWorkflowStatus.RUNNING
                    }
                    val settled = updated.copy(status = status, detail = result.detail)
                    if (!save(settled)) {
                        val uncertain = settled.withStep(result.copy(status = RoutineStepStatus.AMBIGUOUS,
                            detail = "The action returned, but its outcome checkpoint could not be saved. Do not repeat it."))
                            .copy(status = if (current.cancellationRequested) RoutineWorkflowStatus.CANCELLED else RoutineWorkflowStatus.AMBIGUOUS)
                        entries[id] = uncertain
                        return uncertain.detached()
                    }
                    if (status != RoutineWorkflowStatus.RUNNING) return settled.detached()
                }
            }
        } finally {
            synchronized(lock) { running.remove(id) }
        }
    }

    fun cancel(id: String): RoutineWorkflowCheckpoint = synchronized(lock) {
        val current = get(id)
        if (current.status in terminal || current.cancellationRequested) return@synchronized current.detached()
        val cancelled = current.copy(cancellationRequested = true,
            status = if (id in running && current.steps.any { it.status == RoutineStepStatus.RUNNING })
                RoutineWorkflowStatus.CANCELLING else RoutineWorkflowStatus.CANCELLED,
            detail = "Cancellation stops subsequent steps; already dispatched actions are not undone.")
        if (!save(cancelled)) entries[id] = cancelled.copy(detail = "Cancelled locally; cancellation checkpoint could not be saved.")
        get(id).detached()
    }

    /**
     * This records evidence, never dispatches or retries. A failed outcome after a request
     * stays ambiguous: an unsuccessful result is not proof that no side effect happened.
     * Confirmation cannot revive a cancelled workflow; remaining steps stay cancelled.
     */
    fun recordOutcome(id: String, stepIndex: Int, observation: RoutineOutcomeObservation, evidence: String): RoutineWorkflowCheckpoint = synchronized(lock) {
        require(evidence.isNotBlank()) { "Outcome evidence is required." }
        val current = get(id)
        if (id in running) return@synchronized current.detached()
        val step = current.steps.getOrNull(stepIndex) ?: throw IllegalArgumentException("Unknown workflow step.")
        if (step.status !in requested && step.status != RoutineStepStatus.AMBIGUOUS) return@synchronized current.detached()
        val observed = step.copy(status = when (observation) {
            RoutineOutcomeObservation.DEVICE_VERIFIED -> RoutineStepStatus.VERIFIED
            RoutineOutcomeObservation.USER_CONFIRMED -> RoutineStepStatus.USER_CONFIRMED
            RoutineOutcomeObservation.FAILED -> RoutineStepStatus.AMBIGUOUS
        }, detail = evidence)
        val updated = current.withStep(observed)
        val settled = updated.copy(status = when {
            current.cancellationRequested -> RoutineWorkflowStatus.CANCELLED
            observation == RoutineOutcomeObservation.FAILED -> RoutineWorkflowStatus.AMBIGUOUS
            updated.steps.all { it.status in finished } -> completedStatus(updated)
            else -> RoutineWorkflowStatus.PAUSED
        }, detail = evidence)
        if (!save(settled)) {
            entries[id] = current.copy(detail = "Outcome evidence could not be saved; no further actions are permitted.")
            return@synchronized get(id).detached()
        }
        settled.detached()
    }

    private fun get(id: String) = entries[id] ?: throw IllegalArgumentException("Unknown workflow.")

    private fun save(next: RoutineWorkflowCheckpoint): Boolean {
        entries[next.id] = next.detached()
        return runCatching { store.save(next.detached()) }.getOrDefault(false)
    }

    private fun failBeforeDispatch(current: RoutineWorkflowCheckpoint, reason: String): RoutineWorkflowCheckpoint {
        val failed = current.copy(status = RoutineWorkflowStatus.FAILED, detail = reason)
        save(failed)
        return failed.detached()
    }

    private fun finish(current: RoutineWorkflowCheckpoint): RoutineWorkflowCheckpoint {
        val done = current.copy(status = completedStatus(current), detail = "All steps have outcome evidence.")
        if (!save(done)) {
            val paused = current.copy(status = RoutineWorkflowStatus.PAUSED, detail = "Final checkpoint could not be saved; effects will not be repeated.")
            entries[current.id] = paused
            return paused.detached()
        }
        return done.detached()
    }

    private fun completedStatus(current: RoutineWorkflowCheckpoint) =
        if (current.steps.any { it.status == RoutineStepStatus.USER_CONFIRMED }) RoutineWorkflowStatus.USER_CONFIRMED
        else RoutineWorkflowStatus.VERIFIED

    private fun stillApproved(current: RoutineWorkflowCheckpoint): Boolean = runCatching {
        approvedRoutine(current.phrase)?.actions == current.steps.map { it.step.actionId }
    }.getOrDefault(false)

    private fun targetsAvailable(steps: List<RoutineStepCheckpoint>): Boolean = steps.all { state ->
        val target = state.step.target
        target?.capabilityId != "open_app" || runCatching { canLaunchApp(target.parameters.getValue("package")) }.getOrDefault(false)
    }

    private fun validActions(phrase: String, actions: List<String>) = if (GoalPlanning.isKey(phrase))
        ComposableGoalPlanning.validActions(actions) else RoutineSteps.isValid(actions)

    private fun validSteps(phrase: String, steps: List<RoutineWorkflowStep>): Boolean =
        validActions(phrase, steps.map { it.actionId }) && steps.withIndex().all { (index, step) ->
            step.index == index && when {
                RoutineSteps.preferredSlot(step.actionId) != null -> step.target?.isValid() == true && step.target.capabilityId == "open_app"
                else -> step.target == (RoutineSteps.appAction(step.actionId) ?: RoutineSteps.timerAction(step.actionId))
            }
        }

    private fun blocksNewInvocation(current: RoutineWorkflowCheckpoint): Boolean =
        current.status !in terminal || current.steps.any { it.status in unresolved }

    private fun conflictsWithUnresolved(current: RoutineWorkflowCheckpoint): Boolean {
        val remaining = current.steps.filter { it.status !in finished }.map { effectKey(it.step) }.toSet()
        return entries.values.any { other -> other.id != current.id &&
            other.steps.any { it.status in unresolved && effectKey(it.step) in remaining } }
    }

    private fun effectKey(step: RoutineWorkflowStep): String = when (step.target?.capabilityId) {
        "open_app" -> "app:${step.target.parameters["package"]}"
        "timer" -> "timer:${step.target.parameters["seconds"]}"
        else -> step.actionId
    }

    private fun applyOutcome(step: RoutineStepCheckpoint, outcome: RoutineStepOutcome): RoutineStepCheckpoint = step.copy(
        status = when (outcome) {
            // startActivity success only proves a request. Even a mistaken dispatcher must
            // not promote launching an app/timer/updater into completing its actual task.
            RoutineStepOutcome.Verified -> if (step.step.target != null || step.step.actionId == "app_update")
                RoutineStepStatus.REQUESTED else RoutineStepStatus.VERIFIED
            RoutineStepOutcome.Requested -> RoutineStepStatus.REQUESTED
            RoutineStepOutcome.AwaitingOutcome -> RoutineStepStatus.AWAITING_OUTCOME
            RoutineStepOutcome.RequestedWithoutTracking -> RoutineStepStatus.REQUESTED_WITHOUT_TRACKING
            is RoutineStepOutcome.NotApplied -> RoutineStepStatus.FAILED
            is RoutineStepOutcome.Ambiguous -> RoutineStepStatus.AMBIGUOUS
        },
        detail = when (outcome) {
            is RoutineStepOutcome.NotApplied -> outcome.reason
            is RoutineStepOutcome.Ambiguous -> outcome.reason
            RoutineStepOutcome.Verified -> if (step.step.target != null || step.step.actionId == "app_update")
                "Launch requested; task completion has not been verified." else "Device effect verified."
            else -> "Action requested; completion requires outcome evidence."
        }
    )

    private fun RoutineWorkflowCheckpoint.withStep(step: RoutineStepCheckpoint) = copy(
        steps = steps.map { if (it.step.index == step.step.index) step else it }
    )

    companion object {
        private val finished = setOf(RoutineStepStatus.VERIFIED, RoutineStepStatus.USER_CONFIRMED)
        private val requested = setOf(RoutineStepStatus.REQUESTED, RoutineStepStatus.AWAITING_OUTCOME, RoutineStepStatus.REQUESTED_WITHOUT_TRACKING)
        private val unresolved = requested + setOf(RoutineStepStatus.RUNNING, RoutineStepStatus.AMBIGUOUS)
        private val terminal = setOf(RoutineWorkflowStatus.VERIFIED, RoutineWorkflowStatus.USER_CONFIRMED, RoutineWorkflowStatus.CANCELLED)
    }
}
