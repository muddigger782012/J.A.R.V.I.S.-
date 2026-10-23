package com.assistant.core.services

/** Select an available recognizer without turning an explicit on-device request into network capture. */
object SpeechCaptureRouting {
    enum class Engine { ON_DEVICE, SYSTEM }

    fun choose(preferOffline: Boolean, onDeviceOnly: Boolean, deviceAvailable: Boolean, systemAvailable: Boolean,
               requestedEngine: Engine? = null): Engine? = when {
        onDeviceOnly && requestedEngine == Engine.SYSTEM -> null
        onDeviceOnly || requestedEngine == Engine.ON_DEVICE -> if (deviceAvailable) Engine.ON_DEVICE else null
        requestedEngine == Engine.SYSTEM -> if (systemAvailable) Engine.SYSTEM else null
        preferOffline && deviceAvailable -> Engine.ON_DEVICE
        systemAvailable -> Engine.SYSTEM
        else -> null
    }
}

/** Keep provider ordering and pair each nonblank transcript with its original score. */
object SpeechRecognitionCandidates {
    data class Candidate(val transcript: String, val confidence: Float, val confidenceKnown: Boolean)

    fun read(transcripts: List<String>, scores: FloatArray?, limit: Int = 5): List<Candidate> = transcripts
        .mapIndexedNotNull { index, text ->
            val raw = scores?.getOrNull(index)
            text.trim().takeIf(String::isNotBlank)?.let { Candidate(it, confidence(raw), raw != null && raw.isFinite() && raw in 0f..1f) }
        }.distinctBy { it.transcript }.take(limit.coerceIn(0, 5))

    fun confidence(raw: Float?): Float = if (raw == null || !raw.isFinite() || raw !in 0f..1f) 0.5f else raw
}
