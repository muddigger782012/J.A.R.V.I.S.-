package com.assistant.core.services

import com.assistant.core.models.Task

class SchedulerService {

    private val taskStore = mutableListOf<Task>()

    fun addTask(task: Task) {
        taskStore.add(task)
    }

    fun getTasks(): List<Task> = taskStore.toList()
}
