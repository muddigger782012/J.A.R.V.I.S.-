package com.assistant.core.services

import java.security.MessageDigest

/** Stable across AI enrichment, isolated across conversations and search categories. */
object SearchCardPresentation {
    fun stateKey(session: String, message: ChatSearchResults.Message): String {
        val identity = listOf(session, message.kind, message.query).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun freshness(retrievedAt: Long, now: Long = System.currentTimeMillis()): String? {
        if (retrievedAt <= 0 || retrievedAt > now) return null
        val minutes = (now - retrievedAt) / 60_000
        return when {
            minutes == 0L -> "Retrieved just now"
            minutes < 60 -> "Retrieved $minutes min ago"
            minutes < 1440 -> "Retrieved ${minutes / 60} hr ago"
            minutes < 2880 -> "Retrieved 1 day ago"
            else -> "Retrieved ${minutes / 1440} days ago"
        }
    }
}
