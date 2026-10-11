package com.assistant.core.services

/** Turn tokens prevent old recognizer/network callbacks from reviving an ended conversation. */
class LiveChatSession {
    enum class Phase { OFF, WAITING, LISTENING, THINKING, SPEAKING }
    var phase = Phase.OFF
        private set
    var token = 0L
        private set
    private var silentTurns = 0
    val active get() = phase != Phase.OFF
    fun start() { token++; silentTurns = 0; phase = Phase.WAITING }
    fun stop() { token++; phase = Phase.OFF }
    fun listen(): Long? {
        if (phase != Phase.WAITING) return null
        token++; phase = Phase.LISTENING
        return token
    }
    fun transcript(turn: Long): Boolean {
        if (turn != token || phase != Phase.LISTENING) return false
        silentTurns = 0; phase = Phase.THINKING
        return true
    }
    fun response(turn: Long): Boolean {
        if (turn != token || phase != Phase.THINKING) return false
        phase = Phase.SPEAKING
        return true
    }
    fun speechFinished(): Boolean {
        if (phase != Phase.SPEAKING) return false
        phase = Phase.WAITING
        return true
    }
    fun silence(turn: Long): Boolean {
        if (turn != token || phase != Phase.LISTENING) return false
        if (++silentTurns >= 3) { stop(); return false }
        phase = Phase.WAITING
        return true
    }
}
