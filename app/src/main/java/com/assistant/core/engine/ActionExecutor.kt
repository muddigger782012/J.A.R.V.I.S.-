package com.assistant.core.engine

import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState

class ActionExecutor(private val capabilityBroker: CapabilityBroker) {

    fun execute(actionRequest: ActionRequest, capabilityState: CapabilityState): ActionResult {
        return capabilityBroker.execute(actionRequest, capabilityState)
    }
}
