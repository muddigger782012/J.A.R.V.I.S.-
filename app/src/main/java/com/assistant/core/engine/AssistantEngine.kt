package com.assistant.core.engine

import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import com.assistant.core.services.AuditService
import com.assistant.core.storage.ActionRepository
import java.util.UUID

class AssistantEngine(
    private val intentClassifier: IntentClassifier,
    private val actionRegistry: ActionRegistry,
    private val policyGate: PolicyGate,
    private val actionExecutor: ActionExecutor,
    private val actionRepository: ActionRepository,
    private val auditService: AuditService,
    private val capabilityProvider: () -> CapabilityState
) {

    fun handleUserCommand(rawCommand: String): ActionResult {
        val actionType = intentClassifier.classify(rawCommand)
        val actionRequest = actionRegistry.defaultRequestForIntent(actionType)
            ?: return ActionResult(
                id = UUID.randomUUID().toString(),
                success = false,
                adapterUsed = "ENGINE",
                message = "Unsupported command: $rawCommand"
            )
        return executeAction(actionRequest)
    }

    fun executeAction(actionRequest: ActionRequest): ActionResult {
        actionRepository.insert(actionRequest)
        val capabilityState = capabilityProvider()
        val policyDecision = policyGate.evaluate(actionRequest, capabilityState)
        if (!policyDecision.allowed) {
            val blocked = ActionResult(
                id = actionRequest.id,
                success = false,
                adapterUsed = "POLICY",
                message = policyDecision.reason
            )
            auditService.logActionResult(actionRequest, blocked)
            return blocked
        }

        val result = actionExecutor.execute(actionRequest, capabilityState)
        auditService.logActionResult(actionRequest, result)
        return result
    }
}
