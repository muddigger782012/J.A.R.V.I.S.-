package com.jarvis.developer

import java.security.MessageDigest

/** Session-only evidence bound to the exact provider, key and model. Never persists credentials. */
internal class ConnectionVerification {
    enum class State { UNTESTED, TESTING, CONNECTED, FAILED }
    private var identity = ""
    private var generation = 0L
    var onChanged: (() -> Unit)? = null
    var state = State.UNTESTED; private set
    var detail = "Connection has not been tested."; private set

    fun select(provider: String, key: String, model: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val next = digest.digest(listOf(provider, key, model).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (next != identity) {
            identity = next; generation++; state = State.UNTESTED
            detail = if (key.isBlank()) "Connect an AI provider in Settings to get started."
                else "$provider connection has not been tested for these settings."
            onChanged?.invoke()
        }
    }

    fun begin(): Long {
        generation++; state = State.TESTING; detail = "Testing connection…"
        onChanged?.invoke()
        return generation
    }

    fun finish(ticket: Long, success: Boolean, message: String): Boolean {
        if (ticket != generation || state != State.TESTING) return false
        state = if (success) State.CONNECTED else State.FAILED
        detail = message
        onChanged?.invoke()
        return true
    }
}
