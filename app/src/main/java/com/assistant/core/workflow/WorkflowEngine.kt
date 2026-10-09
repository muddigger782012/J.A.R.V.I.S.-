package com.assistant.core.workflow

import com.assistant.core.models.Task
import com.assistant.core.models.Workflow
import java.util.UUID

class WorkflowEngine {

    fun createWorkflow(name: String, triggerType: String): Workflow {
        return Workflow(
            id = UUID.randomUUID().toString(),
            name = name,
            triggerType = triggerType,
            createdAt = System.currentTimeMillis()
        )
    }

    fun createTask(title: String, status: String = "PENDING"): Task {
        return Task(
            id = UUID.randomUUID().toString(),
            title = title,
            status = status,
            createdAt = System.currentTimeMillis()
        )
    }
}
