package com.assistant.core.adapters

import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult

class SpecialAccessAdapter {

    fun execute(actionRequest: ActionRequest): ActionResult {
        return ActionResult(
            id = actionRequest.id,
            success = false,
            adapterUsed = "SPECIAL_ACCESS",
            message = "Special access adapter is a placeholder in this MVP."
        )
    }
}
