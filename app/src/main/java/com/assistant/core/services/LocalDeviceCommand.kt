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

    private fun normalized(input: String): String = input.lowercase(Locale.ROOT)
            .trim()
            .replace(Regex("[?.!,]+$"), "")
            .trim().replace(Regex("\\s+"), " ")
            .replace(Regex("^hey jarvis[, ]+"), "")
            .replace(Regex("^jarvis[, ]+"), "")
            .trim()

    internal fun volumeRequest(input: String): String = normalized(input)
            .replace(Regex("^(?:(?:can|could|would) you (?:please )?|please )"), "")
            .replace(Regex(" please$"), "")

    fun parse(input: String): Action? {
        val phrase = normalized(input)
        val request = volumeRequest(input)
        val media = "(?:my |the )?(?:(?:(?:media|music) )?(?:volume|sound)|music)"
        val smallStep = "(?: (?:a little|a bit|one step))?"
        return when {
            phrase in setOf("update app", "update jarvis", "upgrade app", "upgrade jarvis", "check for jarvis update") -> Action.UPDATE_APP
            ExactVolumeLearning.parse(input) != null -> null // Absolute targets belong to the verified level route.
            request.matches(Regex("^(?:volume up|turn (?:the )?volume up|increase (?:the )?volume|(?:boost|raise) (?:the )?(?:sound|volume))$")) -> Action.VOLUME_UP
            request.matches(Regex("^(?:volume down|turn (?:the )?volume down|decrease (?:the )?volume|lower (?:the )?(?:sound|volume))$")) -> Action.VOLUME_DOWN
            request.matches(Regex("^(?:(?:turn|bring) $media up$smallStep|(?:raise|increase|boost) $media$smallStep|make (?:my |the )?(?:music|media volume|music volume|volume|sound) (?:a little |a bit )?louder)$")) -> Action.VOLUME_UP
            request.matches(Regex("^(?:(?:turn|bring) $media down$smallStep|(?:lower|decrease|reduce) $media$smallStep|make (?:my |the )?(?:music|media volume|music volume|volume|sound) (?:a little |a bit )?quieter)$")) -> Action.VOLUME_DOWN
            else -> null
        }
    }
}
