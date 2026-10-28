package com.assistant.core.services

/** Only fixed reasons cross the screen boundary; exception messages may contain private app data. */
open class ScreenReadFailure(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason { BUSY, CHANGED, DISCONNECTED, CANCELLED, LIMIT, UNAVAILABLE }

    companion object {
        fun guidance(failure: Exception): String = when ((failure as? ScreenReadFailure)?.reason) {
            Reason.BUSY -> "Android did not finish reading this screen within the time limit. Let the screen settle, then ask me to read it again. No automatic action retry was made."
            Reason.CHANGED -> "The screen changed while I was reading it. Let it settle, then request a fresh screen read or control review. No automatic action retry was made."
            Reason.DISCONNECTED -> "Jarvis Accessibility disconnected before the screen could be read. Open Android Accessibility settings and enable Jarvis yourself, return to the intended app, then request a fresh read. No automatic action retry was made."
            Reason.CANCELLED -> "The screen read was cancelled. Request a fresh read when you are ready. No automatic action retry was made."
            Reason.LIMIT -> "This screen exceeds the supported reading limits. Open a smaller section or use the app's own controls, then request a fresh read. No partial screen or automatic action retry was used."
            else -> "Screen access could not be verified. Check the current app and Android Accessibility access before requesting a fresh read. No automatic action retry was made."
        }
    }
}

/** One transient, content-free record. No labels, package names, values, exceptions or node references. */
data class ScreenReadDiagnostics(val reason: ScreenReadFailure.Reason? = null, val protectedScreen: Boolean = false,
                                 val stage: Stage = Stage.PREPARE, val nodes: Int = 0,
                                 val elapsedMillis: Long = 0, val cache: Cache = Cache.UNKNOWN) {
    enum class Stage { PREPARE, ROOT, WINDOW, CONTROLS, VERIFY }
    enum class Cache { ENABLED, DISABLED, UNKNOWN }

    fun report(): String {
        val outcome = when {
            reason != null -> when (reason) {
                ScreenReadFailure.Reason.BUSY -> "time limit reached"
                ScreenReadFailure.Reason.CHANGED -> "screen changed during capture"
                ScreenReadFailure.Reason.DISCONNECTED -> "Accessibility disconnected"
                ScreenReadFailure.Reason.CANCELLED -> "cancelled"
                ScreenReadFailure.Reason.LIMIT -> "supported reading limit reached"
                ScreenReadFailure.Reason.UNAVAILABLE -> "Android could not supply a verified screen"
            }
            protectedScreen -> "protected screen; no content exposed"
            else -> "completed"
        }
        val phase = when (stage) {
            Stage.PREPARE -> "checking access"
            Stage.ROOT -> "getting the screen root"
            Stage.WINDOW -> "checking the app window"
            Stage.CONTROLS -> "reading visible controls"
            Stage.VERIFY -> "checking screen consistency"
        }
        val cacheState = when (cache) { Cache.ENABLED -> "enabled"; Cache.DISABLED -> "disabled"; Cache.UNKNOWN -> "unknown" }
        val elapsed = if (elapsedMillis >= 60_000) "at least 60000" else elapsedMillis.coerceAtLeast(0).toString()
        return "Last screen-read attempt: $outcome. Stage: $phase. " +
            "Examined nodes: ${if (protectedScreen) 0 else nodes.coerceIn(0, 300)}. Time: $elapsed ms. Android cache: $cacheState. " +
            "This describes that attempt, not the current screen. Request a fresh read for current content. " +
            "This diagnostic contains no screen text or user values."
    }
}
