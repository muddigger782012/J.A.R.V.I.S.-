package com.assistant.core.engine

import com.assistant.core.adapters.DhizukuAdapter
import com.assistant.core.adapters.ShizukuAdapter
import com.assistant.core.adapters.SpecialAccessAdapter
import com.assistant.core.adapters.StandardAdapter
import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState

class CapabilityBroker(
    private val standardAdapter: StandardAdapter,
    private val shizukuAdapter: ShizukuAdapter,
    private val dhizukuAdapter: DhizukuAdapter,
    private val specialAccessAdapter: SpecialAccessAdapter
) {

    fun execute(actionRequest: ActionRequest, capabilityState: CapabilityState): ActionResult {
        return when (actionRequest.requiredCapability.uppercase()) {
            ActionRegistry.CAP_STANDARD -> standardAdapter.execute(actionRequest, capabilityState)
            ActionRegistry.CAP_SPECIAL_ACCESS -> {
                if (capabilityState.specialAccess) {
                    specialAccessAdapter.execute(actionRequest)
                } else {
                    failure(actionRequest, "Special access capability unavailable.")
                }
            }
            ActionRegistry.CAP_SHIZUKU -> {
                if (capabilityState.shizuku) {
                    shizukuAdapter.execute(actionRequest, capabilityState)
                } else {
                    fallbackOrFail(actionRequest, capabilityState, "Shizuku capability unavailable.")
                }
            }
            ActionRegistry.CAP_DHIZUKU -> {
                if (capabilityState.dhizuku || capabilityState.deviceOwner) {
                    dhizukuAdapter.execute(actionRequest, capabilityState)
                } else {
                    fallbackOrFail(actionRequest, capabilityState, "Dhizuku capability unavailable.")
                }
            }
            else -> failure(actionRequest, "Unknown capability: ${actionRequest.requiredCapability}")
        }
    }

    private fun fallbackOrFail(
        actionRequest: ActionRequest,
        capabilityState: CapabilityState,
        reason: String
    ): ActionResult {
        if (actionRequest.allowFallback) {
            return standardAdapter.execute(actionRequest, capabilityState)
        }
        return failure(actionRequest, reason)
    }

    private fun failure(actionRequest: ActionRequest, message: String): ActionResult {
        return ActionResult(
            id = actionRequest.id,
            success = false,
            adapterUsed = "BROKER",
            message = message
        )
    }
}
