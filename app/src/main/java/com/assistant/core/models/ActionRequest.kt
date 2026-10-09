package com.assistant.core.models

data class ActionRequest(
    val id: String,
    val actionType: String,
    val parameters: Map<String, Any?>,
    val requiredCapability: String,
    val riskLevel: Int,
    val allowFallback: Boolean = true
)
