package com.assistant.core.services

import java.util.Locale

/**
 * Deterministic, side-effect-free routing for approved device commands.
 * Speech, typed chat and wake-word transcripts can share this parser.
 * Unrecognized phrases must not be treated as permission to execute actions.
 */
object LocalDeviceCommand {
    enum class Action { UPDATE_APP, VOLUME_UP, VOLUME_DOWN }

    /** Teaching an existing updater alias must never be mistaken for creating a new action. */
    fun isUpdaterTeaching(input: String): Boolean {
        val phrase = input.lowercase(Locale.ROOT).trim().replace(Regex("[?.!]+$"), "")
            .replace(Regex("\\s+"), " ")
        return phrase.matches(Regex(
            "^teach (?:jarvis )?(?:that )?update (?:the )?app (?:means|is the same as) " +
                "(?:use|run|open|start) the (?:(?:upgrade|update) app function (?:built )?in (?:the )?app|" +
                "built[- ]in app (?:update|upgrade) feature)$"
        ))
    }

    fun parse(input: String): Action? {
        val phrase = input.lowercase(Locale.ROOT)
            .replace(Regex("[?.!,]+$"), "")
            .trim().replace(Regex("\\s+"), " ")
            .replace(Regex("^hey jarvis[, ]+"), "")
            .replace(Regex("^jarvis[, ]+"), "")
            .trim()
        return when {
            phrase in setOf("update app", "update jarvis", "upgrade app", "upgrade jarvis", "check for jarvis update") -> Action.UPDATE_APP
            phrase.matches(Regex("^(?:volume up|turn (?:the )?volume up|increase (?:the )?volume|(?:boost|raise) (?:the )?(?:sound|volume)(?: all the way up)?)$")) -> Action.VOLUME_UP
            phrase.matches(Regex("^(?:volume down|turn (?:the )?volume down|decrease (?:the )?volume|lower (?:the )?(?:sound|volume))$")) -> Action.VOLUME_DOWN
            else -> null
        }
    }
}
