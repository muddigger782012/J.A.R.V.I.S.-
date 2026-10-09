package com.assistant.core.services

import com.assistant.core.models.CapabilityState

class PrivilegeCatalogService {

    fun buildPrivilegeOverview(capabilityState: CapabilityState, dhizukuStatus: DhizukuStatus): String {
        val lines = mutableListOf<String>()

        lines += "Privilege Center: Dhizuku + Device Owner"
        lines += "-----------------------------------------"
        lines += "Dhizuku installed: ${dhizukuStatus.appInstalled}"
        lines += "Dhizuku initialized: ${dhizukuStatus.initialized}"
        lines += "Dhizuku permission granted: ${dhizukuStatus.permissionGranted}"
        lines += "Dhizuku version: ${dhizukuStatus.versionName ?: "N/A"} (${dhizukuStatus.versionCode ?: "N/A"})"
        lines += "Dhizuku owner package: ${dhizukuStatus.ownerPackageName ?: "N/A"}"
        lines += "Dhizuku owner component: ${dhizukuStatus.ownerComponentName ?: "N/A"}"
        lines += "Delegated scopes: ${if (dhizukuStatus.delegatedScopes.isEmpty()) "none" else dhizukuStatus.delegatedScopes.joinToString()}"
        lines += "Device owner (this app): ${capabilityState.deviceOwner}"
        dhizukuStatus.errorMessage?.let { lines += "Dhizuku note: $it" }
        lines += ""
        lines += "Available privileged functions/options:"

        val canUseDhizuku = dhizukuStatus.initialized && dhizukuStatus.permissionGranted
        val canUseDeviceOwnerDirectly = capabilityState.deviceOwner
        val hasPrivilegedPath = canUseDhizuku || canUseDeviceOwnerDirectly

        fun mark(name: String, available: Boolean, details: String) {
            val symbol = if (available) "[AVAILABLE]" else "[LOCKED]"
            lines += "$symbol $name"
            lines += "  - $details"
        }

        mark(
            name = "Request/refresh Dhizuku permission",
            available = dhizukuStatus.initialized,
            details = "Grants app access to Dhizuku-shared owner privileges."
        )
        mark(
            name = "Query Dhizuku owner and delegated scopes",
            available = dhizukuStatus.initialized,
            details = "Read owner package/component and current delegated scopes."
        )
        mark(
            name = "Set delegated scopes via Dhizuku",
            available = canUseDhizuku,
            details = "Manage DevicePolicy delegated scopes for this app."
        )
        mark(
            name = "Bind/start/stop Dhizuku user service",
            available = canUseDhizuku,
            details = "Run service operations in Dhizuku user context."
        )
        mark(
            name = "Spawn privileged process through Dhizuku",
            available = canUseDhizuku,
            details = "Create remote privileged process via Dhizuku API."
        )
        mark(
            name = "Remote privileged binder transact",
            available = canUseDhizuku,
            details = "Use binder wrapper for privileged binder transactions."
        )
        mark(
            name = "Reboot device",
            available = hasPrivilegedPath,
            details = "Possible via DeviceOwner directly or Dhizuku owner path."
        )
        mark(
            name = "Lock device now",
            available = hasPrivilegedPath,
            details = "DevicePolicy lockNow capability."
        )
        mark(
            name = "Set camera disabled",
            available = hasPrivilegedPath,
            details = "DevicePolicy camera restriction control."
        )
        mark(
            name = "Set screen capture disabled",
            available = hasPrivilegedPath,
            details = "DevicePolicy screen capture restriction."
        )
        mark(
            name = "Manage user restrictions",
            available = hasPrivilegedPath,
            details = "Add/clear UserManager restrictions."
        )
        mark(
            name = "Set keyguard/status-bar policy",
            available = hasPrivilegedPath,
            details = "DevicePolicy UI/security policy controls."
        )
        mark(
            name = "Set secure/global settings policy",
            available = hasPrivilegedPath,
            details = "Policy-managed setting controls where permitted."
        )
        mark(
            name = "Package policy (e.g., uninstall block)",
            available = hasPrivilegedPath,
            details = "DevicePolicy package restriction controls."
        )
        mark(
            name = "Factory reset / wipe data",
            available = hasPrivilegedPath,
            details = "High-risk device wipe (should require confirmation workflow)."
        )

        lines += ""
        lines += "Activation guidance:"
        lines += "1) Install and open Dhizuku app."
        lines += "2) Activate Dhizuku as owner/delegated manager per Dhizuku docs."
        lines += "3) Tap 'Request Dhizuku Permission' in this app."
        lines += "4) Tap 'Refresh Privilege Status' to reload capabilities."

        return lines.joinToString(separator = "\n")
    }
}
