package com.assistant.core.adapters

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.assistant.core.engine.ActionRegistry
import com.assistant.core.models.ActionRequest
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import com.assistant.core.services.DhizukuService
import com.rosan.dhizuku.api.Dhizuku

class DhizukuAdapter(
    private val context: Context,
    private val dhizukuService: DhizukuService = DhizukuService()
) {

    fun isAvailable(capabilityState: CapabilityState): Boolean = capabilityState.dhizuku

    fun execute(actionRequest: ActionRequest, capabilityState: CapabilityState): ActionResult {
        val dhizukuStatus = dhizukuService.getStatus(context)
        if (!isAvailable(capabilityState) && !dhizukuStatus.initialized) {
            return ActionResult(
                id = actionRequest.id,
                success = false,
                adapterUsed = "DHIZUKU",
                message = dhizukuStatus.errorMessage
                    ?: "Dhizuku unavailable. Install/launch Dhizuku and refresh privileges."
            )
        }

        return when (actionRequest.actionType) {
            ActionRegistry.REBOOT_DEVICE -> rebootDevice(actionRequest, capabilityState, dhizukuStatus)
            else -> ActionResult(
                id = actionRequest.id,
                success = false,
                adapterUsed = "DHIZUKU",
                message = "Unsupported Dhizuku action: ${actionRequest.actionType}"
            )
        }
    }

    private fun rebootDevice(
        actionRequest: ActionRequest,
        capabilityState: CapabilityState,
        dhizukuStatus: com.assistant.core.services.DhizukuStatus
    ): ActionResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return ActionResult(
                id = actionRequest.id,
                success = false,
                adapterUsed = "DHIZUKU",
                message = "DevicePolicyManager unavailable on this device."
            )

        return try {
            when {
                capabilityState.deviceOwner -> {
                    // If this app is device owner directly, reboot using own admin component.
                    val selfComponent = Dhizuku.getOwnerComponent(dpm)
                    if (selfComponent == null) {
                        ActionResult(
                            id = actionRequest.id,
                            success = false,
                            adapterUsed = "DHIZUKU",
                            message = "Device-owner component not found for reboot."
                        )
                    } else {
                        dpm.reboot(selfComponent)
                        ActionResult(
                            id = actionRequest.id,
                            success = true,
                            adapterUsed = "DHIZUKU",
                            message = "Reboot requested using direct device-owner path."
                        )
                    }
                }
                dhizukuStatus.initialized && dhizukuStatus.permissionGranted -> {
                    val ownerComponent = Dhizuku.getOwnerComponent(context)
                    if (ownerComponent == null) {
                        ActionResult(
                            id = actionRequest.id,
                            success = false,
                            adapterUsed = "DHIZUKU",
                            message = "Dhizuku owner component unavailable."
                        )
                    } else {
                        dpm.reboot(ownerComponent)
                        ActionResult(
                            id = actionRequest.id,
                            success = true,
                            adapterUsed = "DHIZUKU",
                            message = "Reboot requested through Dhizuku owner path."
                        )
                    }
                }
                else -> ActionResult(
                    id = actionRequest.id,
                    success = false,
                    adapterUsed = "DHIZUKU",
                    message = "Dhizuku permission not granted. Use 'Request Dhizuku Permission' first."
                )
            }
        } catch (error: SecurityException) {
            ActionResult(
                id = actionRequest.id,
                success = false,
                adapterUsed = "DHIZUKU",
                message = "Reboot blocked by system policy: ${error.message ?: "security exception"}"
            )
        } catch (error: Throwable) {
            ActionResult(
                id = actionRequest.id,
                success = false,
                adapterUsed = "DHIZUKU",
                message = "Dhizuku reboot failed: ${error.message ?: "unknown error"}"
            )
        }
    }
}
