package com.assistant.core.services

import java.net.URI

/** Search and consent stay embedded; source pages are deliberately opened externally. */
object SearchBrowserNavigation {
    fun canStart(url: String): Boolean = runCatching {
        val uri = URI(url)
        ChatSearchResults.safeUrl(url) && uri.host == "www.google.com" && uri.path == "/search"
    }.getOrDefault(false)
    fun staysEmbedded(url: String): Boolean = runCatching {
        ChatSearchResults.safeUrl(url) && URI(url).host in setOf("www.google.com", "google.com", "consent.google.com")
    }.getOrDefault(false)
}
