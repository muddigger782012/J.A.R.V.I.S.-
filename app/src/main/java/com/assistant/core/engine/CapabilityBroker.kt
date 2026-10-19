package com.assistant.core.engine

import com.assistant.core.adapters.DhizukuAdapter
import com.assistant.core.adapters.ShizukuAdapter
import com.assistant.core.adapters.SpecialAccessAdapter
import com.assistant.core.adapters.StandardAdapter
import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState

class CapabilityBroker internal constructor(
    private val executeRoute: (CapabilityRouting.Route, ActionRequest, CapabilityState) -> ActionResult
) {
    constructor(
        standardAdapter: StandardAdapter,
        shizukuAdapter: ShizukuAdapter,
        dhizukuAdapter: DhizukuAdapter,
        specialAccessAdapter: SpecialAccessAdapter
    ) : this({ route, request, state ->
        when (route) {
            CapabilityRouting.Route.STANDARD -> standardAdapter.execute(request, state)
            CapabilityRouting.Route.SHIZUKU -> shizukuAdapter.execute(request, state)
            CapabilityRouting.Route.DHIZUKU -> dhizukuAdapter.execute(request, state)
            CapabilityRouting.Route.SPECIAL_ACCESS -> specialAccessAdapter.execute(request)
        }
    })

    fun execute(actionRequest: ActionRequest, capabilityState: CapabilityState): ActionResult {
        // Keep the same policy boundary even for callers that use ActionExecutor directly.
        val policy = PolicyGate().evaluate(actionRequest, capabilityState)
        if (!policy.allowed) {
            return ActionResult(actionRequest.id, false, "POLICY", policy.reason)
        }

        return when (val decision = CapabilityRouting.select(actionRequest, capabilityState)) {
            // Selection happens before any execution. An adapter failure or exception is
            // final: ActionResult does not establish whether an action was already applied.
            is CapabilityRouting.Decision.Execute -> executeRoute(decision.route, actionRequest, capabilityState)
            is CapabilityRouting.Decision.Unavailable -> failure(actionRequest, decision.reason)
        }
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
