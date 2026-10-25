package com.assistant.core.services

/**
 * Approval policy for J.A.R.V.I.S. development jobs.
 * An approval mode never elevates Android privileges or bypasses platform consent.
 * Commercial distribution must set allowOwnerDevelopment=false at its policy boundary.
 */
enum class DevelopmentApprovalMode(val wireValue: String) {
    SUPERVISED("supervised"),
    AUTHORIZED_AUTONOMOUS("authorized_autonomous"),
    OWNER_DEVELOPMENT("owner_development");

    companion object {
        fun fromWire(value: String): DevelopmentApprovalMode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

class DevelopmentApprovalPolicy(private val allowOwnerDevelopment: Boolean) {
    val availableModes: List<DevelopmentApprovalMode>
        get() = DevelopmentApprovalMode.entries.filter {
            allowOwnerDevelopment || it != DevelopmentApprovalMode.OWNER_DEVELOPMENT
        }

    fun accepts(mode: DevelopmentApprovalMode): Boolean = mode in availableModes

    fun requireAllowed(mode: DevelopmentApprovalMode): DevelopmentApprovalMode {
        require(accepts(mode)) { "Owner development is not available in commercial editions" }
        return mode
    }

    /** The caller must still verify job scope, identity and any required user consent. */
    fun needsPerJobApproval(mode: DevelopmentApprovalMode): Boolean {
        requireAllowed(mode)
        return mode == DevelopmentApprovalMode.SUPERVISED
    }
}
