package com.assistant.core.services

/** Offline teaching composes one existing bounded handler, never executable code. */
object ExplicitCommandTeaching {
    data class Request(val phrase: String, val target: String)
    fun parse(input: String): Request? {
        val match = Regex("(?i)^teach (?:jarvis )?(?:that )?(.{3,140}?) (?:means|is the same as) (.+)$")
            .matchEntire(input.trim().replace(Regex("\\s+"), " ")) ?: return null
        val phrase = match.groupValues[1].trim().removeSurrounding("\"")
        return Request(phrase, match.groupValues[2].trim().trimEnd('.', '?', '!')).takeIf { RoutineProposal.isUsableName(it.phrase) }
    }
    fun isRequest(input: String) = input.trim().startsWith("teach ", ignoreCase = true)

    fun resolveDevice(target: String, resolveApp: (String) -> Pair<String, String>?): LearnableDeviceAction? {
        val timer = Regex("(?i)^(?:set|start) (?:a )?timer (?:for )?([1-9][0-9]{0,5}) (seconds?|minutes?|hours?)$").matchEntire(target)
        if (timer != null) {
            val multiplier = when { timer.groupValues[2].startsWith("hour", true) -> 3600L
                timer.groupValues[2].startsWith("minute", true) -> 60L; else -> 1L }
            val seconds = timer.groupValues[1].toLong() * multiplier
            return LearnableDeviceAction("timer", mapOf("seconds" to seconds.toString())).takeIf { it.isValid() }
        }
        val app = Regex("(?i)^(?:open|launch) (.{1,100})$").matchEntire(target)?.groupValues?.get(1) ?: return null
        val resolved = runCatching { resolveApp(app) }.getOrNull() ?: return null
        if (!resolved.second.equals(app, ignoreCase = true)) return null
        return LearnableDeviceAction("open_app", mapOf("package" to resolved.first, "label" to resolved.second)).takeIf { it.isValid() }
    }
}
