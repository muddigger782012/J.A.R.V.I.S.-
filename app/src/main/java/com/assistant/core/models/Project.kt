package com.assistant.core.models

data class Project(
    val id: String,
    val name: String,
    val rootPath: String,
    val createdAt: Long
)
