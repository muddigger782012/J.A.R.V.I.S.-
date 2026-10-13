package com.assistant.core.services

/** A locally resolved app edit is proposal data, never a launch instruction. */
object PendingAppCorrection {
    private val pattern = Regex("(?i)^(?:actually[, :]? )?(?:no[, :]? )?(?:use|open) (.{1,100}) instead$")
    private fun match(text: String) = pattern.matchEntire(text.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " "))
    fun isRequest(text: String): Boolean = match(text) != null
    fun action(text: String, resolveApp: (String) -> Pair<String, String>?): LearnableDeviceAction? {
        val label = match(text)?.groupValues?.get(1) ?: return null
        return ExplicitCommandTeaching.resolveDevice("open $label", resolveApp)
    }
}
