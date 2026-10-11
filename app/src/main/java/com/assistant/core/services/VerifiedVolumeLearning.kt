package com.assistant.core.services

/** AI may select only an existing media-volume capability, never supply code. */
object VerifiedVolumeLearning {
    val capabilities = setOf("volume_up", "volume_down")

    fun capability(lesson: FallbackIntentLesson): String? {
        if (!FallbackLessonProtocol.accepts(lesson) || lesson.confidence < FallbackIntentLesson.TEACH_THRESHOLD ||
            lesson.intent != "DEVICE_SETTING" || lesson.entities.keys != setOf("setting", "direction") ||
            lesson.entities["setting"] != "media_volume") return null
        return when (lesson.entities["direction"]) {
            "up" -> "volume_up"
            "down" -> "volume_down"
            else -> null
        }
    }

    data class Result(val executed: Boolean, val learned: Boolean)

    fun execute(
        utterance: String,
        lesson: FallbackIntentLesson,
        learningEnabled: Boolean,
        adjust: (String) -> Boolean,
        record: (String, String) -> Boolean
    ): Result? {
        val capability = capability(lesson) ?: return null
        val executed = runCatching { adjust(capability) }.getOrDefault(false)
        val learned = executed && learningEnabled && lesson.canTeachLocally() &&
            runCatching { record(utterance, capability) }.getOrDefault(false)
        return Result(executed, learned)
    }
}

/** A successful API call alone is not proof that Android changed the volume. */
object VolumeChangeVerification {
    fun changed(before: Int, after: Int, increase: Boolean): Boolean =
        if (increase) after > before else after < before
}
