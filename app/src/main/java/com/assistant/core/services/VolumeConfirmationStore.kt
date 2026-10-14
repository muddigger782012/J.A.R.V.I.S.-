package com.assistant.core.services

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object ConfirmationWords {
    private fun normalized(text: String) = text.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
    fun approves(text: String): Boolean = normalized(text) in setOf("yes", "yes please", "yes fine", "yes do it", "do it", "sure", "okay", "ok", "confirm volume")
    fun cancels(text: String): Boolean = normalized(text) in setOf("no", "no thanks", "cancel", "never mind", "nevermind", "cancel volume")
    fun isConfirmation(text: String): Boolean = approves(text) || cancels(text)
}

/** Shared by chat/voice instances; approvals are consumed once and expire. */
class VolumeConfirmationStore internal constructor(
    private val read: () -> String?,
    private val write: (String?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        read = { context.applicationContext.getSharedPreferences("jarvis_volume_confirmation", Context.MODE_PRIVATE).getString("pending", null) },
        write = { value ->
            val editor = context.applicationContext.getSharedPreferences("jarvis_volume_confirmation", Context.MODE_PRIVATE).edit()
            if (value == null) editor.remove("pending") else editor.putString("pending", value)
            editor.commit()
        }
    )

    data class Pending(val phrase: String, val lesson: FallbackIntentLesson, val explicitTeaching: Boolean = false, val requestId: String? = null)
    data class Resolution(val pending: Pending, val approved: Boolean)

    fun propose(phrase: String, lesson: FallbackIntentLesson, explicitTeaching: Boolean = false, requestId: String? = null): Boolean = synchronized(lock) {
        if (requestId != null && (!CapabilityRequestStore.validId(requestId) || ExactVolumeLearning.fromLesson(lesson) == null)) return@synchronized false
        if (explicitTeaching && (ExactVolumeLearning.fromLesson(lesson) == null || !lesson.canTeachLocally())) return@synchronized false
        if (phrase.isBlank() || phrase.length > 240 || ConfirmationWords.isConfirmation(phrase) || (VerifiedVolumeLearning.capability(lesson) == null && ExactVolumeLearning.fromLesson(lesson) == null)) return@synchronized false
        write(JSONObject().put("phrase", phrase).put("explicitTeaching", explicitTeaching).put("created", now()).put("requestId", requestId).put("lesson", JSONObject()
            .put("intent", lesson.intent).put("confidence", lesson.confidence)
            .put("entities", JSONObject(lesson.entities)).put("learnable", lesson.learnable)).toString())
    }

    fun consume(input: String): Resolution? = synchronized(lock) {
        val raw = read() ?: return@synchronized null
        // An unrelated next turn invalidates the proposal as well.
        if (!write(null)) return@synchronized null
        val pending = runCatching {
            val json = JSONObject(raw)
            val age = now() - json.getLong("created")
            if (age !in 0..120_000) return@runCatching null
            val lesson = FallbackIntentLesson.fromJson(json.getJSONObject("lesson").toString()) ?: return@runCatching null
            if ((VerifiedVolumeLearning.capability(lesson) == null && ExactVolumeLearning.fromLesson(lesson) == null)) return@runCatching null
            val explicit = json.optBoolean("explicitTeaching")
            if (explicit && (ExactVolumeLearning.fromLesson(lesson) == null || !lesson.canTeachLocally())) return@runCatching null
            val requestId = if (json.has("requestId")) json.getString("requestId") else null
            if (requestId != null && (!CapabilityRequestStore.validId(requestId) || ExactVolumeLearning.fromLesson(lesson) == null)) return@runCatching null
            Pending(json.getString("phrase"), lesson, explicit, requestId)
        }.getOrNull() ?: return@synchronized null
        if (!ConfirmationWords.isConfirmation(input)) return@synchronized null
        Resolution(pending, ConfirmationWords.approves(input))
    }

    companion object { private val lock = Any() }
}
