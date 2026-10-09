package com.assistant.core.models

data class ActionResult(
    val id: String,
    val success: Boolean,
    val adapterUsed: String,
    val message: String,
    val output: String? = null
)
