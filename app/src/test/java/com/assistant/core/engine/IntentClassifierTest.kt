package com.assistant.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentClassifierTest {
    private val classifier = IntentClassifier()

    @Test fun unfamiliarObjectivesAndQuestionsRemainConversational() {
        listOf("Help me prepare for tomorrow", "Can you explain how to run shell commands?",
            "What happens when you reboot?", "Don't reboot my phone", "Why does create project fail?",
            "How do I create a project?", "What does HTTP status mean?", "My project status is blocked",
            "Say reboot", "The manual says run shell id", "I meant restart the conversation",
            "reboot my phone then send a message").forEach {
            assertEquals(it, ActionRegistry.UNKNOWN, classifier.classify(it))
        }
    }

    @Test fun explicitSupportedCommandsStillUseTheirHandlers() {
        mapOf("show status" to ActionRegistry.SHOW_STATUS, "System status!" to ActionRegistry.SHOW_STATUS,
            "Can you reboot my phone?" to ActionRegistry.REBOOT_DEVICE,
            "restart device" to ActionRegistry.REBOOT_DEVICE,
            "Please create a project named demo" to ActionRegistry.CREATE_PROJECT,
            "new project" to ActionRegistry.CREATE_PROJECT,
            "Run Shell id" to ActionRegistry.RUN_SHELL,
            "Jarvis, run command getprop ro.build.version.release" to ActionRegistry.RUN_SHELL).forEach { (input, expected) ->
            assertEquals(input, expected, classifier.classify(input))
        }
    }
}
