package com.assistant.core.models

data class Workflow(
    val id: String,
    val name: String,
    val triggerType: String,
    val createdAt: Long
)
