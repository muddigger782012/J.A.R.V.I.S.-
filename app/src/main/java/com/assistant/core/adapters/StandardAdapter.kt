package com.assistant.core.adapters

import com.assistant.core.engine.ActionRegistry
import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import com.assistant.core.models.Project
import com.assistant.core.services.CodingService
import com.assistant.core.services.FileService
import com.assistant.core.services.SystemService
import com.assistant.core.services.JarvisAccessibilityService
import android.accessibilityservice.AccessibilityService
import com.assistant.core.storage.ProjectRepository
import java.io.File
import java.util.UUID

class StandardAdapter(
    private val codingService: CodingService,
    private val fileService: FileService,
    private val systemService: SystemService,
    private val projectRepository: ProjectRepository
) {

    fun execute(actionRequest: ActionRequest, capabilityState: CapabilityState): ActionResult {
        return when (actionRequest.actionType) {
            ActionRegistry.CREATE_PROJECT -> createProject(actionRequest)
            ActionRegistry.WRITE_FILE -> writeFile(actionRequest)
            ActionRegistry.SHOW_STATUS -> showStatus(actionRequest, capabilityState)
            ActionRegistry.READ_SCREEN -> accessibilityResult(actionRequest, "read screen") { it.currentWindowSummary() }
            ActionRegistry.ACCESSIBILITY_BACK -> accessibilityGlobal(actionRequest, AccessibilityService.GLOBAL_ACTION_BACK, "Back")
            ActionRegistry.ACCESSIBILITY_HOME -> accessibilityGlobal(actionRequest, AccessibilityService.GLOBAL_ACTION_HOME, "Home")
            ActionRegistry.ACCESSIBILITY_RECENTS -> accessibilityGlobal(actionRequest, AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents")
            else -> ActionResult(
                id = actionRequest.id,
                success = false,
                adapterUsed = "STANDARD",
                message = "Unsupported action for StandardAdapter: ${actionRequest.actionType}"
            )
        }
    }

    private fun createProject(actionRequest: ActionRequest): ActionResult {
        val projectName = actionRequest.parameters["name"] as? String ?: "assistant_demo"
        val projectRoot = codingService.createProject(projectName)
        projectRepository.insert(
            Project(
                id = UUID.randomUUID().toString(),
                name = projectName,
                rootPath = projectRoot.absolutePath,
                createdAt = System.currentTimeMillis()
            )
        )
        return ActionResult(
            id = actionRequest.id,
            success = true,
            adapterUsed = "STANDARD",
            message = "Project created: $projectName",
            output = projectRoot.absolutePath
        )
    }

    private fun writeFile(actionRequest: ActionRequest): ActionResult {
        val path = actionRequest.parameters["path"] as? String
        val content = actionRequest.parameters["content"] as? String ?: ""
        if (path.isNullOrBlank()) {
            return ActionResult(
                id = actionRequest.id,
                success = false,
                adapterUsed = "STANDARD",
                message = "WRITE_FILE missing parameter: path"
            )
        }

        val target = File(path)
        fileService.writeTextFile(target, content)
        return ActionResult(
            id = actionRequest.id,
            success = true,
            adapterUsed = "STANDARD",
            message = "File written successfully",
            output = target.absolutePath
        )
    }

    private fun accessibilityGlobal(actionRequest: ActionRequest, globalAction: Int, label: String): ActionResult {
        return accessibilityResult(actionRequest, label) { service ->
            if (service.performGlobal(globalAction)) "$label action completed." else "$label action was not accepted by Android."
        }
    }

    private fun accessibilityResult(actionRequest: ActionRequest, label: String, operation: (JarvisAccessibilityService) -> String): ActionResult {
        val service = JarvisAccessibilityService.active()
            ?: return ActionResult(actionRequest.id, false, "ACCESSIBILITY", "J.A.R.V.I.S. Accessibility is not enabled. Enable it in Android Accessibility settings.")
        return runCatching { operation(service) }
            .fold(
                onSuccess = { ActionResult(actionRequest.id, true, "ACCESSIBILITY", "$label completed.", it) },
                onFailure = { ActionResult(actionRequest.id, false, "ACCESSIBILITY", "$label failed: ${it.message ?: "unknown error"}") }
            )
    }

    private fun showStatus(actionRequest: ActionRequest, capabilityState: CapabilityState): ActionResult {
        val status = systemService.buildStatusSummary(capabilityState)
        return ActionResult(
            id = actionRequest.id,
            success = true,
            adapterUsed = "STANDARD",
            message = "Status generated",
            output = status
        )
    }
}
