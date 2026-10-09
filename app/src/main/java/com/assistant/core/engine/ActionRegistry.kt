package com.assistant.core.engine

import com.assistant.core.models.ActionRequest
import java.util.UUID

class ActionRegistry {
    fun defaultRequestForIntent(actionType: String): ActionRequest? = when (actionType) {
        CREATE_PROJECT -> createProjectRequest("assistant_demo")
        RUN_SHELL -> runShellRequest("id", confirmed = false)
        SHOW_STATUS -> showStatusRequest()
        REBOOT_DEVICE -> rebootDeviceRequest(confirmed = false)
        else -> null
    }

    fun createProjectRequest(name: String) = ActionRequest(UUID.randomUUID().toString(), CREATE_PROJECT, mapOf("name" to name), CAP_STANDARD, 1)
    fun runShellRequest(command: String, confirmed: Boolean) = ActionRequest(UUID.randomUUID().toString(), RUN_SHELL, mapOf("command" to command, "confirmed" to confirmed), CAP_SHIZUKU, 2)
    fun showStatusRequest() = ActionRequest(UUID.randomUUID().toString(), SHOW_STATUS, emptyMap(), CAP_STANDARD, 0)
    fun rebootDeviceRequest(confirmed: Boolean) = ActionRequest(UUID.randomUUID().toString(), REBOOT_DEVICE, mapOf("confirmed" to confirmed), CAP_DHIZUKU, 3)

    companion object {
        const val UNKNOWN = "UNKNOWN"; const val CREATE_PROJECT = "CREATE_PROJECT"; const val WRITE_FILE = "WRITE_FILE"
        const val SHOW_STATUS = "SHOW_STATUS"; const val RUN_SHELL = "RUN_SHELL"; const val REBOOT_DEVICE = "REBOOT_DEVICE"
        const val CAP_STANDARD = "STANDARD"; const val CAP_SPECIAL_ACCESS = "SPECIAL_ACCESS"; const val CAP_SHIZUKU = "SHIZUKU"; const val CAP_DHIZUKU = "DHIZUKU"
    }
}
