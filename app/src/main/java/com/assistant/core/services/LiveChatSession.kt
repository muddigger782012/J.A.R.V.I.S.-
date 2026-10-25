package com.assistant.core.services

/** Turn tokens prevent old recognizer/network callbacks from reviving an ended conversation. */
class LiveChatSession(private val maxSilentTurns: Int = Int.MAX_VALUE,
    private var idleTimeoutMillis: Long = 300_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Phase { OFF, WAITING, LISTENING, THINKING, SPEAKING }
    var phase = Phase.OFF
        private set
    var token = 0L
        private set
    private var silentTurns = 0
    private var lastActivity = now()
    val active get() = phase != Phase.OFF
    fun configureIdleTimeout(millis: Long) { idleTimeoutMillis = millis.coerceIn(60_000, 1_800_000) }
    fun interrupt(): Boolean {
        if (!active) return false
        token++; silentTurns = 0; lastActivity = now(); phase = Phase.WAITING
        return true
    }
    fun start() { token++; silentTurns = 0; lastActivity = now(); phase = Phase.WAITING }
    fun stop() { token++; phase = Phase.OFF }
    fun listen(): Long? {
        if (phase != Phase.WAITING) return null
        if (expired()) { stop(); return null }
        token++; phase = Phase.LISTENING
        return token
    }
    fun transcript(turn: Long): Boolean {
        if (turn != token || phase != Phase.LISTENING) return false
        silentTurns = 0; lastActivity = now(); phase = Phase.THINKING
        return true
    }
    fun response(turn: Long): Boolean {
        if (turn != token || phase != Phase.THINKING) return false
        phase = Phase.SPEAKING
        return true
    }
    fun speechFinished(turn: Long = token): Boolean {
        if (turn != token || phase != Phase.SPEAKING) return false
        lastActivity = now()
        phase = Phase.WAITING
        return true
    }
    fun silence(turn: Long): Boolean {
        if (turn != token || phase != Phase.LISTENING) return false
        if (++silentTurns >= maxSilentTurns || expired()) { stop(); return false }
        phase = Phase.WAITING
        return true
    }
    fun recover(turn: Long): Boolean {
        if (turn != token || phase !in listOf(Phase.LISTENING, Phase.THINKING)) return false
        if (expired()) { stop(); return false }
        token++ // Reject an assistant response from the failed/timed-out turn.
        phase = Phase.WAITING
        return true
    }
    fun expired() = now() - lastActivity >= idleTimeoutMillis
}
