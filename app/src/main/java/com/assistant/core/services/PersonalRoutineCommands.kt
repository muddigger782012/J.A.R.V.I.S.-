package com.assistant.core.services

import java.util.Locale

/** Commands refer only to this service's current reviewed routine, never to dialogue history. */
object PersonalRoutineCommands {
    enum class Command { STATUS, RESUME, CANCEL }
    fun parse(text: String): Command? = when (text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!')
        .trim().replace(Regex("\\s+"), " ")) {
        "routine execution status" -> Command.STATUS
        "resume routine execution" -> Command.RESUME
        "cancel routine execution" -> Command.CANCEL
        else -> null
    }
}
