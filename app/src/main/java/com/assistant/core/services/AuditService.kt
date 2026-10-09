package com.assistant.core.services

import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult
import com.assistant.core.storage.AuditLogEntry
import com.assistant.core.storage.AuditRepository

class AuditService(private val auditRepository: AuditRepository) {

    fun logActionResult(actionRequest: ActionRequest, actionResult: ActionResult) {
        val status = if (actionResult.success) "SUCCESS" else "FAIL"
        auditRepository.insert(
            actionId = actionRequest.id,
            adapterUsed = actionResult.adapterUsed,
            status = status,
            message = actionResult.message
        )
    }

    fun getRecentEntries(limit: Int = 20): List<AuditLogEntry> {
        return auditRepository.getRecent(limit)
    }
}
