package com.assistant.core.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Read-only: no probe, prompt, credential value, or settings changes leave the device. */
object AiConnectionStatusReader {
    fun read(context: Context): AiConnectionStatus.Snapshot {
        val network = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = manager.activeNetwork
            val capabilities = manager.getNetworkCapabilities(active)
            when {
                active == null -> AiConnectionStatus.Network.NONE
                capabilities == null -> AiConnectionStatus.Network.UNKNOWN
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> AiConnectionStatus.Network.NONE
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> AiConnectionStatus.Network.VALIDATED
                else -> AiConnectionStatus.Network.UNVALIDATED
            }
        }.getOrDefault(AiConnectionStatus.Network.UNKNOWN)
        return AiConnectionStatus.Snapshot(network,
            geminiConfigured = runCatching { DirectOnlineClient(context).hasGeminiKey() }.getOrDefault(false),
            gatewayConfigured = FallbackAiClient(context).isConfigured())
    }
}
