package com.assistant.core.services

import java.util.Locale

/** Deterministic routing for explicit connectivity requests, before AI fallback. */
internal object ProviderDiagnosticIntent {
    enum class Kind { INTERNET, SEARCH, GEMINI, AI }
    fun classify(input: String): Kind? {
        val phrase = input.trim().lowercase(Locale.ROOT)
            .trimEnd('.', '?', '!')
            .replace(Regex("\\s+"), " ")
        return when (phrase) {
            "test internet connection", "test internet connections",
            "check internet connection", "check internet connections",
            "test network connection", "test network connections" -> Kind.INTERNET
            "test search connection", "test search connections",
            "check search connection", "check search connections",
            "test search connectivity" -> Kind.SEARCH
            "test gemini connection", "test gemini connections",
            "check gemini connection", "test gemini's availability",
            "check gemini availability", "is gemini available" -> Kind.GEMINI
            "test ai", "test ai is functional", "test ai connection",
            "test ai connectivity" -> Kind.AI
            else -> null
        }
    }
}
