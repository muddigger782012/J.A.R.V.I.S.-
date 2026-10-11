package com.assistant.core.services

/** Pure parser for routine proposals; does not persist or execute anything. */
object RoutineCommandParser {
    data class Proposal(val phrase: String, val actions: List<String>)

    fun parse(input: String): Proposal? {
        val match = Regex(
            "(?i)^teach (?:jarvis )?(?:that )?(.{3,120}?) (?:means|is the same as) (.+)$"
        ).matchEntire(input.trim()) ?: return null
        val steps = match.groupValues[2].split(Regex("(?i)\\s+and\\s+"))
        if (steps.size !in 2..5) return null
        val actions = steps.map { step ->
            when (LocalDeviceCommand.parse(step.trim().trimEnd('.', '!', '?'))) {
                LocalDeviceCommand.Action.UPDATE_APP -> "app_update"
                LocalDeviceCommand.Action.VOLUME_UP -> "volume_up"
                LocalDeviceCommand.Action.VOLUME_DOWN -> "volume_down"
                null -> return null
            }
        }
        if (actions.distinct().size != actions.size) return null
        return Proposal(match.groupValues[1].trim(), actions)
    }
}
