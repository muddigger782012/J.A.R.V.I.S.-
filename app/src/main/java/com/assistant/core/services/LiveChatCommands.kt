package com.assistant.core.services

import java.util.Locale

/** Explicit session controls are handled locally before any assistant/AI routing. */
object LiveChatCommands {
    enum class Control { START, STOP, NONE }
    fun parse(text: String): Control {
        val phrase = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(hey jarvis|jarvis)\\s+"), "")
            .removePrefix("please ").removeSuffix(" please").trim()
        return when (phrase) {
            "start live chat", "begin live chat", "open live chat", "start live conversation", "begin live conversation", "enable live chat" -> Control.START
            "end live chat", "stop live chat", "exit live chat", "stop conversation", "end live conversation", "stop live conversation", "disable live chat" -> Control.STOP
            else -> Control.NONE
        }
    }
}
