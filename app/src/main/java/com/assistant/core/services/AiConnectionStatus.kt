package com.assistant.core.services

import java.util.Locale

/** Host facts only. Configuration and Android network validation are not provider health checks. */
object AiConnectionStatus {
    enum class Network { UNKNOWN, NONE, UNVALIDATED, VALIDATED }
    data class Snapshot(val network: Network, val geminiConfigured: Boolean, val gatewayConfigured: Boolean)

    fun isQuestion(input: String): Boolean {
        val text = input.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:hey jarvis|jarvis)[,:]?\\s+"), "")
            .removePrefix("please ")
        return text in setOf(
            "are you connected to gemini", "are you connected to google gemini", "are you using gemini", "do you use gemini",
            "is gemini connected", "is gemini working", "gemini connection status", "check gemini connection",
            "are you connected to ai", "are you connected to an ai", "is ai connected", "is your ai connected",
            "is ai working", "ai connection status", "check ai connection", "what ai are you connected to",
            "are you connected to internet", "are you connected to the internet", "are you online", "are you offline",
            "do you have internet access", "do you have an internet connection", "internet connection status",
            "check internet connection"
        )
    }

    fun render(snapshot: Snapshot): String = buildString {
        appendLine("J.A.R.V.I.S. connection status (local check):")
        appendLine(when (snapshot.network) {
            Network.VALIDATED -> "Android reports a network with validated internet access. This does not verify Gemini or the gateway."
            Network.UNVALIDATED -> "Android has not validated internet access on the current network. A sign-in page or network restriction may be involved."
            Network.NONE -> "Android reports no active internet-capable network. Local commands remain available."
            Network.UNKNOWN -> "I couldn't read Android's current network status. Provider connectivity is unverified."
        })
        appendLine(when {
            snapshot.geminiConfigured -> "Gemini is configured and is the selected provider for unresolved chat questions."
            snapshot.gatewayConfigured -> "The optional gateway is configured and is the selected provider for unresolved chat questions. Direct Gemini is not configured; I cannot identify the gateway's underlying model from this check."
            else -> "No online AI provider is configured. Add your Gemini key or optional gateway in Voice Settings → Weather and AI."
        })
        if (snapshot.geminiConfigured && snapshot.gatewayConfigured)
            appendLine("The optional gateway is also configured. Existing chat routing uses Gemini while its key is configured.")
        append("This status reply is generated locally without contacting a provider. A configured key is not proof of a working connection. Ask a normal question to try online AI; if Gemini fails, ask 'explain the last Gemini error' for the recorded details.")
    }
}
