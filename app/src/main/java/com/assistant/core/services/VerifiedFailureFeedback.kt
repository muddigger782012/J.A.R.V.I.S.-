package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory

/** Failure feedback belongs to a tracked replay, never a bare report or cancelled proposal. */
object VerifiedFailureFeedback {
    fun pause(resolution: DeviceLearningSession.Resolution, memory: ValidatedCommandMemory): Boolean {
        val pending = resolution.pending
        if (resolution.accepted || !pending.awaitingOutcome || !pending.replayed || pending.learnable ||
            !pending.action.isValid() || pending.contextual?.isValid() == false) return false
        return memory.recordFailure(pending.phrase, pending.contextual?.capabilityId ?: pending.action.capabilityId,
            pending.contextual?.parameters ?: pending.action.parameters)
    }
}
