package com.assistant.core.services

/** A bounded informational topic, never an action or approval parser. */
class PlanConversation(private val plan: () -> IntelligencePlan) {
    private var active = false
    private var selected: String? = null
    fun reset() { active = false; selected = null }
    fun restore(turns: List<Pair<String, String>>) { reset(); turns.takeLast(12).forEach { reply(it.first) } }
    fun reply(input: String): String? {
        val text = input.lowercase(java.util.Locale.ROOT).trim().replace(Regex("^(?:hey\\s+)?jarvis[,:]?\\s*"), "")
            .replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ")
        if (text.matches(Regex(".*\\b(?:teach|forget|delete|reset|run|execute|reboot|open|start|cancel|approve)\\b.*"))) { reset(); return null }
        val explicit = text.contains("intelligence plan") || text in setOf("plan progress", "intelligence progress", "development progress")
        val followup = text in setOf("whats left", "what is left", "what remains", "whats remaining", "what still needs to be done",
            "how far along is that", "how far along are we", "how much is done", "tell me more", "why", "whats next", "what next", "are we done", "is it finished", "continue", "continue with that", "how much is completed", "how much is left") ||
            text.matches(Regex("(?:what about|how far along is|tell me about) (?:the )?(?:router|settings|learning loop|capability builder|builder|dashboard|providers|updates|rollback|voice|speech)"))
        if (!explicit && (!active || !followup)) { reset(); return null }
        val current = plan()
        active = true
        val names = mapOf("router" to "router", "settings" to "settings", "learning loop" to "learning", "capability builder" to "builder", "builder" to "builder",
            "dashboard" to "dashboard", "providers" to "providers", "updates" to "updates", "rollback" to "updates", "voice" to "settings", "speech" to "settings")
        val matched = names.entries.filter { Regex("\\b${it.key}\\b").containsMatchIn(text) }.map { it.value }.distinct()
        if (matched.size == 1) selected = matched.single() else if (explicit) selected = null
        val row = current.workstreams.find { it.id == selected }
        val remaining = text in setOf("whats left", "what is left", "what remains", "whats remaining", "what still needs to be done", "whats next", "what next", "how much is left") || text.contains("remaining")
        val prefix = "Intelligence Plan revision ${current.revision}, ${current.updated}; evidence through ${current.throughVersion}."
        if (row != null) return "$prefix\n${row.title}: approximately ${row.percent}% of planned scope.\n${row.status}\n" +
            if (remaining) "Remaining: ${row.remaining}" else "Implemented: ${row.implemented}\nRemaining: ${row.remaining}\nThese are engineering scope estimates, not an accuracy score."
        if (remaining) return "$prefix\nRemaining work:\n" + current.workstreams.joinToString("\n\n") { "${it.title}: ${it.remaining}" }
        return "$prefix\nApproximately ${current.overall}% of original planned scope.\n" + current.workstreams.joinToString("\n") { "${it.title}: ${it.percent}%" } +
            "\n\n${current.note}\n${current.milestone}\nAsk 'what's left?' or 'what about the capability builder?'."
    }
}
