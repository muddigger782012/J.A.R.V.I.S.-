package com.assistant.core.engine

import com.assistant.core.models.ActionRequest
import com.assistant.core.models.CapabilityState

data class PolicyDecision(
    val allowed: Boolean,
    val reason: String
)

class PolicyGate {

    fun evaluate(actionRequest: ActionRequest, capabilityState: CapabilityState): PolicyDecision {
        if (actionRequest.riskLevel >= 2) {
            val confirmed = actionRequest.parameters["confirmed"] as? Boolean ?: false
            if (!confirmed) {
                return PolicyDecision(false, "High-risk action blocked: explicit confirmation required.")
            }
        }

        if (actionRequest.actionType == ActionRegistry.REBOOT_DEVICE) {
            if (!capabilityState.dhizuku && !capabilityState.deviceOwner) {
                return PolicyDecision(false, "REBOOT_DEVICE blocked: Dhizuku or device owner required.")
            }
        }

        return PolicyDecision(true, "Allowed")
    }
}
