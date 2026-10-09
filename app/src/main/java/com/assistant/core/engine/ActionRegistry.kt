package com.assistant.core.engine

import com.assistant.core.models.ActionRequest
import java.util.UUID

class ActionRegistry {

    fun defaultRequestForIntent(actionType: String): ActionRequest? {
        return when (actionType) {
            CREATE_PROJECT -> createProjectRequest("assistant_demo")
            RUN_SHELL -> runShellRequest("id", confirmed = true)
            SHOW_STATUS -> showStatusRequest()
            REBOOT_DEVICE -> rebootDeviceRequest(confirmed = true)
            else -> null
        }
    }

    fun createProjectRequest(name: String): ActionRequest {
        return ActionRequest(
            id = UUID.randomUUID().toString(),
            actionType = CREATE_PROJECT,
            parameters = mapOf("name" to name),
            requiredCapability = CAP_STANDARD,
            riskLevel = 1
        )
    }

    fun runShellRequest(command: String, confirmed: Boolean): ActionRequest {
        return ActionRequest(
            id = UUID.randomUUID().toString(),
            actionType = RUN_SHELL,
            parameters = mapOf("command" to command, "confirmed" to confirmed),
            requiredCapability = CAP_SHIZUKU,
            riskLevel = 2
        )
    }

    fun showStatusRequest(): ActionRequest {
        return ActionRequest(
            id = UUID.randomUUID().toString(),
            actionType = SHOW_STATUS,
            parameters = emptyMap(),
            requiredCapability = CAP_STANDARD,
            riskLevel = 0
        )
    }

    fun rebootDeviceRequest(confirmed: Boolean): ActionRequest {
        return ActionRequest(
            id = UUID.randomUUID().toString(),
            actionType = REBOOT_DEVICE,
            parameters = mapOf("confirmed" to confirmed),
            requiredCapability = CAP_DHIZUKU,
            riskLevel = 3
        )
    }

    companion object {
        const val UNKNOWN = "UNKNOWN"
        const val CREATE_PROJECT = "CREATE_PROJECT"
        const val WRITE_FILE = "WRITE_FILE"
        const val SHOW_STATUS = "SHOW_STATUS"
        const val RUN_SHELL = "RUN_SHELL"
        const val REBOOT_DEVICE = "REBOOT_DEVICE"

        const val CAP_STANDARD = "STANDARD"
        const val CAP_SPECIAL_ACCESS = "SPECIAL_ACCESS"
        const val CAP_SHIZUKU = "SHIZUKU"
        const val CAP_DHIZUKU = "DHIZUKU"
    }
}
