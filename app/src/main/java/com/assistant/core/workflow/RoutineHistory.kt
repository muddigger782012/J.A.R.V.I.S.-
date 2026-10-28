package com.assistant.core.workflow

import java.security.MessageDigest

/** Cleanup is explicit, keeps the latest status, and cannot release an uncertain effect. */
object RoutineHistory {
    data class Review(val entries: List<RoutineWorkflowCheckpoint>, val retiredFingerprints: Int) {
        val removable: List<RoutineWorkflowCheckpoint> get() = candidates(entries)
    }

    fun candidates(entries: List<RoutineWorkflowCheckpoint>): List<RoutineWorkflowCheckpoint> = entries.dropLast(1).filter { entry ->
        val settled = setOf(RoutineStepStatus.VERIFIED, RoutineStepStatus.USER_CONFIRMED)
        when (entry.status) {
            RoutineWorkflowStatus.VERIFIED, RoutineWorkflowStatus.USER_CONFIRMED -> entry.steps.all { it.status in settled }
            RoutineWorkflowStatus.CANCELLED -> entry.steps.all { it.status in settled || it.status in setOf(RoutineStepStatus.PENDING, RoutineStepStatus.FAILED) }
            else -> false
        }
    }

    internal fun requestFingerprint(key: String) = fingerprint("request:$key")
    internal fun fingerprints(entry: RoutineWorkflowCheckpoint): Set<String> =
        entry.requestKeys.map(::requestFingerprint).toSet() + fingerprint("workflow:${entry.id}")

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
