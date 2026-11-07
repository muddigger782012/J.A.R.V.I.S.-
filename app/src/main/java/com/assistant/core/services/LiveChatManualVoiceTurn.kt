package com.assistant.core.services

/**
 * Tracks a one-shot explicit microphone tap separately from ambient room monitoring.
 *
 * A manual tap authorizes *conversation* for that captured turn, not a privileged
 * Android device action. If the room transcript lacks a direct address, the
 * foreground service must send it only through the text-only ambient responder.
 *
 * Token binding ensures old recognizer callbacks never consume a later tap.
 */
internal class LiveChatManualVoiceTurn {
    private var nextTurnRequested = false
    private var armedTurn: Long? = null

    /** The user tapped the microphone to explicitly restart recognition. */
    fun requestNext() {
        nextTurnRequested = true
        armedTurn = null
    }

    /** The user tapped to finish the actual ongoing speech capture. */
    fun requestCurrent(token: Long) {
        nextTurnRequested = false
        armedTurn = token
    }

    /** Bind a pending manual request to the next newly-created recognizer turn. */
    fun started(token: Long) {
        armedTurn = if (nextTurnRequested) token else null
        nextTurnRequested = false
    }

    /** Only a matching final transcript may use the explicit-conversation override. */
    fun consume(token: Long): Boolean {
        val allowed = armedTurn == token
        if (allowed) armedTurn = null
        return allowed
    }

    fun cancelled(token: Long) {
        if (armedTurn == token) armedTurn = null
    }

    fun reset() {
        nextTurnRequested = false
        armedTurn = null
    }
}
