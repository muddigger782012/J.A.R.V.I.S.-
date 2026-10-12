package com.assistant.core.services

import android.content.Context
import org.json.JSONObject

/** Approval and outcome are separate. A launched intent is not a verified result. */
class DeviceLearningSession internal constructor(
    private val read: () -> String?, private val write: (String?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_device_learning_session", Context.MODE_PRIVATE).getString("pending", null) },
        { value ->
            val editor = context.applicationContext.getSharedPreferences("jarvis_device_learning_session", Context.MODE_PRIVATE).edit()
            if (value == null) editor.remove("pending") else editor.putString("pending", value)
            editor.commit()
        }
    )
    data class Pending(val phrase: String, val action: LearnableDeviceAction, val learnable: Boolean, val awaitingOutcome: Boolean = false, val contextual: ContextualAction? = null, val replayed: Boolean = false, val explicitTeaching: Boolean = false) {
        fun shouldLearn(automaticEnabled: Boolean) = learnable && (explicitTeaching || automaticEnabled)
    }
    data class Resolution(val pending: Pending, val accepted: Boolean, val needsClarification: Boolean = false)
    enum class RequestResult { FAILED, REQUESTED_WITHOUT_TRACKING, AWAITING_OUTCOME }

    /** Used for local/preferred/learned actions as well as AI-interpreted ones. */
    fun requestAndTrack(phrase: String, action: LearnableDeviceAction, replayed: Boolean = false, contextual: ContextualAction? = null, request: () -> Boolean): RequestResult {
        if (contextual?.isValid() == false || !action.isValid() || !runCatching(request).getOrDefault(false)) return RequestResult.FAILED
        return if (propose(Pending(phrase, action, learnable = false, awaitingOutcome = true, contextual = contextual, replayed = replayed))) RequestResult.AWAITING_OUTCOME
            else RequestResult.REQUESTED_WITHOUT_TRACKING
    }

    fun propose(pending: Pending): Boolean = synchronized(lock) {
        if (pending.explicitTeaching && (!pending.learnable || pending.replayed || pending.contextual != null)) return@synchronized false
        if (pending.replayed && (pending.learnable || !pending.awaitingOutcome)) return@synchronized false
        if (pending.contextual?.isValid() == false || !pending.action.isValid() || pending.phrase.isBlank() || pending.phrase.length > 240 ||
            ConfirmationWords.isConfirmation(pending.phrase) || isOutcome(pending.phrase)) return@synchronized false
        write(JSONObject().put("phrase", pending.phrase).put("capability", pending.action.capabilityId)
            .put("parameters", JSONObject(pending.action.parameters)).put("learnable", pending.learnable)
            .put("contextCapability", pending.contextual?.capabilityId)
            .put("contextParameters", pending.contextual?.let { JSONObject(it.parameters) })
            .put("explicitTeaching", pending.explicitTeaching).put("replayed", pending.replayed).put("awaitingOutcome", pending.awaitingOutcome).put("created", now()).toString())
    }

    fun consume(input: String): Resolution? = synchronized(lock) {
        val raw = read() ?: return@synchronized null
        val pending = runCatching {
            val json = JSONObject(raw)
            if (now() - json.getLong("created") !in 0..300_000) return@runCatching null
            val parameters = json.getJSONObject("parameters")
            val action = LearnableDeviceAction(json.getString("capability"), parameters.keys().asSequence().associateWith { parameters.getString(it) })
            if (!action.isValid()) return@runCatching null
            val contextual = if (json.has("contextCapability")) {
                val params = json.getJSONObject("contextParameters")
                ContextualAction(json.getString("contextCapability"), params.keys().asSequence().associateWith { params.getString(it) })
                    .takeIf { it.isValid() } ?: return@runCatching null
            } else null
            val replayed = json.optBoolean("replayed")
            if (replayed && (json.optBoolean("learnable") || !json.optBoolean("awaitingOutcome"))) return@runCatching null
            val explicit = json.optBoolean("explicitTeaching")
            if (explicit && (!json.optBoolean("learnable") || replayed || contextual != null)) return@runCatching null
            Pending(json.getString("phrase"), action, json.optBoolean("learnable"), json.optBoolean("awaitingOutcome"), contextual, replayed, explicit)
        }.getOrNull()
        // An unclear outcome retains the same pending target and original expiry.
        // It must not become either success or failure, including for learned replays.
        if (pending?.awaitingOutcome == true && OutcomeFeedback.parse(input) == OutcomeFeedback.Result.UNCLEAR)
            return@synchronized Resolution(pending, false, needsClarification = true)
        if (!write(null) || pending == null) return@synchronized null
        if (pending.awaitingOutcome) {
            val result = OutcomeFeedback.parse(input) ?: return@synchronized null
            Resolution(pending, result == OutcomeFeedback.Result.SUCCESS)
        } else if (ConfirmationWords.isConfirmation(input)) Resolution(pending, ConfirmationWords.approves(input)) else null
    }

    companion object {
        private val lock = Any()
        fun isOutcome(text: String): Boolean = OutcomeFeedback.parse(text) != null
    }
}
