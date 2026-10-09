package com.assistant.core.models

data class Task(
    val id: String,
    val title: String,
    val status: String,
    val createdAt: Long
)
