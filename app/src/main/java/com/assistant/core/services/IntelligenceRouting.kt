package com.assistant.core.services

/** Local handlers run before this router. Providers interpret; their text never executes a tool. */
object IntelligenceRouting {
    enum class Provider(val key: String, val label: String) { GEMINI("gemini", "Gemini"), GATEWAY("gateway", "your conversation gateway") }
    data class Routed(val answer: FallbackAiResult, val provider: Provider?, val attempted: List<Provider>)
    fun selected(value: String?) = Provider.values().firstOrNull { it.key == value } ?: Provider.GEMINI
    fun ask(primary: Provider, fallback: Boolean, configured: Set<Provider>, current: () -> Boolean = { true },
            request: (Provider) -> FallbackAiResult?): Routed {
        val order = if (fallback) listOf(primary) + Provider.values().filter { it != primary } else listOf(primary)
        val attempted = mutableListOf<Provider>()
        var failure: FallbackAiResult? = null
        for (provider in order) {
            if (!current()) return Routed(FallbackAiResult("Request cancelled.", null, providerFailed = true), null, attempted)
            if (provider !in configured) continue
            attempted.add(provider)
            val answer = runCatching { request(provider) }.getOrNull()
            if (!current()) return Routed(FallbackAiResult("Request cancelled.", null, providerFailed = true), null, attempted)
            if (answer != null && !answer.providerFailed && answer.reply.isNotBlank()) {
                val reply = if (provider != primary) "${primary.label.replaceFirstChar(Char::uppercaseChar)} was unavailable; ${provider.label} answered. ${answer.reply}" else answer.reply
                return Routed(answer.copy(reply = reply), provider, attempted)
            }
            failure = answer?.copy(lessonJson = null, providerFailed = true)
        }
        return Routed(failure ?: FallbackAiResult("The selected AI connection is unavailable. Supported local commands remain available; review Weather and AI settings or try another configured provider.", null, providerFailed = true), null, attempted)
    }
    fun isStatus(text: String) = ScreenSemantics.clean(text).trimEnd('.', '?', '!') in setOf(
        "ai routing status", "intelligence routing status", "which ai provider is selected")
    fun status(primary: Provider, fallback: Boolean, gemini: Boolean, gateway: Boolean, model: String): String =
        "Supported device commands, approved routines, memory and screen tasks are processed locally and do not require an AI key. Android permissions and an offline-capable speech recognizer are still needed for their respective functions. " +
        "Complex conversation uses ${primary.label} first. Gemini ${if (gemini) "is configured" else "needs an API key"}; its selected model is ${model.take(100)}. " +
        "The conversation gateway ${if (gateway) "is configured" else "needs an HTTPS endpoint and token"}; its model follows your server configuration. " +
        "Automatic provider fallback is ${if (fallback) "enabled" else "disabled"}. Configuration is not proof of current provider reachability."
}

/** A cancelled or superseded asynchronous reply cannot revive prompts, learning or conversation memory. */
class AssistantResponseTurns {
    private val lock = Any()
    private var sequence = 0L
    private val bound = ThreadLocal<Long>()
    fun begin(): Long = synchronized(lock) { ++sequence }
    fun current(token: Long): Boolean = synchronized(lock) { sequence == token }
    fun current(): Boolean = bound.get()?.let(::current) ?: true
    fun cancel(clear: () -> Unit = {}) = synchronized(lock) { ++sequence; clear() }
    fun <T> inTurn(token: Long, block: () -> T): T {
        val previous = bound.get(); bound.set(token)
        return try { block() } finally { if (previous == null) bound.remove() else bound.set(previous) }
    }
    fun <T> mutate(block: () -> T): T? = synchronized(lock) { if (current()) block() else null }
}

/** Each recognizer instance owns one capture; terminal callbacks can be delivered only once. */
class RecognitionCaptureGate {
    private var sequence = 0L
    private var active = false
    @Synchronized fun begin(): Long { active = true; return ++sequence }
    @Synchronized fun valid(token: Long) = active && sequence == token
    @Synchronized fun finish(token: Long): Boolean {
        if (!valid(token)) return false
        active = false; return true
    }
    @Synchronized fun cancel() { sequence++; active = false }
}

object VoiceScreenApprovalScope {
    fun bound(text: String): Boolean = ScreenControls.parse(text)?.kind == ScreenControls.Kind.CONFIRM ||
        ScreenTaskCommands.parse(text, active = true)?.kind in setOf(ScreenTaskCommands.Kind.CONFIRM_STEP, ScreenTaskCommands.Kind.CONTINUE)
    fun valid(text: String, captured: String, current: String) = !bound(text) || captured == current
}
