package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import java.util.Locale

/** AI can compose existing handlers, never supply code, permissions or arbitrary arguments. */
object RoutineProposal {
    /** Resolve exact user-requested labels locally. Never accept an AI-supplied binding. */
    fun fromAiLesson(lesson: FallbackIntentLesson, resolveApp: (String) -> Pair<String, String>?): ApprovedRoutineRegistry.Routine? {
        if (!lesson.canTeachLocally() || lesson.intent != "CREATE_ROUTINE" || lesson.entities.keys != setOf("phrase", "steps")) return null
        val drafts = lesson.entities.getValue("steps").split(',').map { it.trim() }
        if (drafts.size !in 1..3 || drafts.any { it.startsWith("app:") }) return null
        val steps = drafts.map { step ->
            if (!step.startsWith("open:")) step else {
                val label = step.removePrefix("open:").trim().takeIf { it.isNotBlank() && it.length <= 100 } ?: return null
                val resolved = runCatching { resolveApp(label) }.getOrNull() ?: return null
                if (!resolved.second.equals(label, ignoreCase = true)) return null
                RoutineSteps.appStep(LearnableDeviceAction("open_app", mapOf("package" to resolved.first, "label" to resolved.second))) ?: return null
            }
        }
        return fromLesson(lesson.copy(entities = lesson.entities + ("steps" to steps.joinToString(","))))
    }
    fun isCreationRequest(text: String): Boolean = Regex(
        "(?i)^(?:(?:please|can you|could you) )?(?:create|make|build|design|add) (?:a |an )?(?:new )?(?:routine|shortcut|capability)\\b.*$"
    ).matches(text.trim())

    fun fromLesson(lesson: FallbackIntentLesson): ApprovedRoutineRegistry.Routine? {
        if (!lesson.canTeachLocally() || lesson.intent != "CREATE_ROUTINE" || lesson.entities.keys != setOf("phrase", "steps")) return null
        val phrase = lesson.entities.getValue("phrase").lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        val steps = lesson.entities.getValue("steps").split(',').map { it.trim() }
        if (!isUsableName(phrase) || !RoutineSteps.isValid(steps)) return null
        return ApprovedRoutineRegistry.Routine(phrase, steps)
    }

    fun isUsableName(phrase: String): Boolean {
        if (phrase.length !in 3..120 || !Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} '_-]*$").matches(phrase)) return false
        if (ConfirmationWords.isConfirmation(phrase) || DeviceLearningSession.isOutcome(phrase) ||
            LocalDeviceCommand.parse(phrase) != null || LearnedCommandReviewCommands.isReserved(phrase) ||
            AssistantMemoryCommands.preferredSlot(phrase) != null || AssistantMemoryCommands.repeatCapability(phrase) != null ||
            AssistantMemoryCommands.preference(phrase) != null || AssistantMemoryCommands.forgetKey(phrase) != null) return false
        // Routine labels are names, not requests that belong to another local handler.
        val first = phrase.lowercase(Locale.ROOT).substringBefore(' ')
        return first !in setOf("open", "launch", "start", "set", "use", "remember", "create", "teach", "confirm", "cancel",
            "forget", "delete", "reset", "clear", "list", "show", "call", "text", "send", "pay", "purchase", "reboot",
            "search", "find", "navigate", "play", "stop", "pause", "repeat", "do", "what", "how", "when", "where", "who")
    }

    fun describe(action: String): String = when (action) {
        "volume_up" -> "raise media volume one step"
        "volume_down" -> "lower media volume one step"
        "app_update" -> "open the J.A.R.V.I.S. updater (Android installation approval still required)"
        else -> RoutineSteps.preferredSlot(action)?.let { "open the current saved preferred $it app" }
            ?: RoutineSteps.volumePercent(action)?.let { "set media volume to $it% (nearest supported level)" }
            ?: RoutineSteps.appAction(action)?.description() ?: RoutineSteps.timerAction(action)?.description() ?: "unsupported step"
    }
}
