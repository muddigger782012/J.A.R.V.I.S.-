package com.assistant.core.models

data class CapabilityState(
    val standard: Boolean,
    val specialAccess: Boolean,
    val shizuku: Boolean,
    val dhizuku: Boolean,
    val deviceOwner: Boolean,
    val oemPrivileged: Boolean,
    val platformSigned: Boolean
)
