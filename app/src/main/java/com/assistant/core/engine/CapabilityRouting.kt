package com.assistant.core.engine

import com.assistant.core.models.ActionRequest
import com.assistant.core.models.CapabilityState
import java.util.Locale

/** Pre-execution routing only; never infers retry safety from an adapter's result text. */
internal object CapabilityRouting {
    enum class Route { STANDARD, SPECIAL_ACCESS, SHIZUKU, DHIZUKU }

    sealed class Decision {
        data class Execute(val route: Route) : Decision()
        data class Unavailable(val reason: String) : Decision()
    }

    // Only actions already implemented by StandardAdapter may fall back to it.
    private val standardActions = setOf(
        ActionRegistry.CREATE_PROJECT,
        ActionRegistry.WRITE_FILE,
        ActionRegistry.SHOW_STATUS,
        ActionRegistry.READ_SCREEN,
        ActionRegistry.ACCESSIBILITY_BACK,
        ActionRegistry.ACCESSIBILITY_HOME,
        ActionRegistry.ACCESSIBILITY_RECENTS
    )

    fun select(request: ActionRequest, state: CapabilityState): Decision {
        val preferred = Route.values().firstOrNull {
            it.name == request.requiredCapability.uppercase(Locale.ROOT)
        } ?: return Decision.Unavailable(
            "Unknown capability: ${request.requiredCapability}. Choose STANDARD, SPECIAL_ACCESS, SHIZUKU, or DHIZUKU."
        )

        val reason = when {
            !supports(preferred, request.actionType) ->
                if (preferred == Route.SPECIAL_ACCESS) "Special access has no implemented actions."
                else "${preferred.name} does not support ${request.actionType}."
            else -> unavailableReason(preferred, state)
        } ?: return Decision.Execute(preferred)

        if (request.allowFallback && preferred != Route.STANDARD && request.actionType in standardActions) {
            val standardUnavailable = unavailableReason(Route.STANDARD, state)
            return if (standardUnavailable == null) Decision.Execute(Route.STANDARD)
            else Decision.Unavailable("$reason $standardUnavailable")
        }

        val fallback = if (!request.allowFallback) "Fallback is disabled for this request."
        else "No supported alternative route is available for ${request.actionType}."
        val advice = if (supports(preferred, request.actionType)) "" else " ${guidance(request.actionType)}"
        return Decision.Unavailable("$reason $fallback$advice")
    }

    private fun supports(route: Route, action: String): Boolean = when (route) {
        Route.STANDARD -> action in standardActions
        Route.SHIZUKU -> action == ActionRegistry.RUN_SHELL
        Route.DHIZUKU -> action == ActionRegistry.REBOOT_DEVICE
        Route.SPECIAL_ACCESS -> false // The current adapter is a placeholder, not an execution route.
    }

    private fun unavailableReason(route: Route, state: CapabilityState): String? = when (route) {
        Route.STANDARD -> if (state.standard) null
            else "Standard capability unavailable. Refresh device capabilities before trying again."
        Route.SHIZUKU -> if (state.shizuku) null
            else "Shizuku capability unavailable. Open Shizuku, start its service, and grant this app permission in the Shizuku tab."
        Route.DHIZUKU -> if (state.dhizuku || state.deviceOwner) null
            else "Dhizuku capability unavailable. Start Dhizuku and request permission, or configure this app as device owner."
        Route.SPECIAL_ACCESS -> "Special access has no implemented actions."
    }

    private fun guidance(action: String): String = when (action) {
        ActionRegistry.READ_SCREEN, ActionRegistry.ACCESSIBILITY_BACK,
        ActionRegistry.ACCESSIBILITY_HOME, ActionRegistry.ACCESSIBILITY_RECENTS ->
            "Use STANDARD and enable J.A.R.V.I.S. Accessibility in Android Accessibility settings."
        in standardActions -> "Use STANDARD for this action."
        ActionRegistry.RUN_SHELL -> "RUN_SHELL requires SHIZUKU. Start its service and grant this app permission in the Shizuku tab."
        ActionRegistry.REBOOT_DEVICE -> "REBOOT_DEVICE requires DHIZUKU with permission or device-owner access, and explicit confirmation."
        else -> "Choose an action implemented by an adapter."
    }
}
