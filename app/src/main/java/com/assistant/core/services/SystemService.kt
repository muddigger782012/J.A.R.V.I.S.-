package com.assistant.core.services

import android.content.Context
import com.assistant.core.models.CapabilityState

class SystemService(private val context: Context) {

    fun buildStatusSummary(capabilityState: CapabilityState): String {
        return buildString {
            appendLine("J.A.R.V.I.S. status")
            appendLine("filesDir: ${context.filesDir.absolutePath}")
            appendLine("standard: ${capabilityState.standard}")
            appendLine("specialAccess: ${capabilityState.specialAccess}")
            appendLine("shizuku: ${capabilityState.shizuku}")
            appendLine("dhizuku: ${capabilityState.dhizuku}")
            appendLine("deviceOwner: ${capabilityState.deviceOwner}")
            appendLine("oemPrivileged: ${capabilityState.oemPrivileged}")
            appendLine("platformSigned: ${capabilityState.platformSigned}")
            append("deviceOwnerActive: ${capabilityState.deviceOwner}")
        }
    }
}
