package com.assistant.core.services

import android.content.Context

/** Uses the same user-configured AI connections as ordinary fallback chat. */
class SearchAiClient(context: Context) {
    private val direct = DirectOnlineClient(context)
    private val gateway = FallbackAiClient(context)

    fun answer(query: String, items: List<SearchRouter.Result>): String? {
        if (!SearchAiContext.canAnswer(items)) return null
        val evidence = SearchAiContext.evidence(query, items)
        if (runCatching { direct.hasGeminiKey() }.getOrDefault(false)) {
            direct.searchAnswer(evidence)?.let { return it.take(12000) }
        }
        if (!gateway.isConfigured()) return null
        return gateway.ask(SearchAiContext.INSTRUCTIONS + "\nSearch evidence (JSON):\n" + evidence,
            learnIntent = false)?.reply?.take(12000)
    }
}
