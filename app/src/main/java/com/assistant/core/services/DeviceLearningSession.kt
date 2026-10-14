package com.assistant.core.services

import android.content.Context
import org.json.JSONObject

/** Approval and outcome are separate. A launched intent is not a verified result. */
class DeviceLearningSession internal constructor(
    private val read: () -> String?, private val write: (String?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(ConversationPendingStore(context.applicationContext.filesDir, "device"))
    private constructor(store: ConversationPendingStore) : this(store::read, store::write)

    data class Pending(val phrase: String, val action: LearnableDeviceAction, val learnable: Boolean, val awaitingOutcome: Boolean = false, val contextual: ContextualAction? = null, val replayed: Boolean = false, val explicitTeaching: Boolean = false, val requestId: String? = null) {
        fun shouldLearn(automaticEnabled: Boolean) = learnable && (explicitTeaching || automaticEnabled)
    }
    data class Resolution(val pending: Pending, val accepted: Boolean, val needsClarification: Boolean = false, val reminder: Boolean = false, val correction: Correction = Correction.NONE)
    enum class Correction { NONE, UPDATED, INVALID_DURATION, INVALID_APP, TARGET_LOCKED, ALREADY_REQUESTED, SAVE_FAILED }
    enum class RequestResult { FAILED, REQUESTED_WITHOUT_TRACKING, AWAITING_OUTCOME }

    /** Used for local/preferred/learned actions as well as AI-interpreted ones. */
    fun requestAndTrack(phrase: String, action: LearnableDeviceAction, replayed: Boolean = false, contextual: ContextualAction? = null, request: () -> Boolean): RequestResult {
        if (contextual?.isValid() == false || !action.isValid() || !runCatching(request).getOrDefault(false)) return RequestResult.FAILED
        return if (propose(Pending(phrase, action, learnable = false, awaitingOutcome = true, contextual = contextual, replayed = replayed))) RequestResult.AWAITING_OUTCOME
            else RequestResult.REQUESTED_WITHOUT_TRACKING
    }

    fun propose(pending: Pending): Boolean = synchronized(lock) {
        if (pending.requestId != null && (!CapabilityRequestStore.validId(pending.requestId) || pending.contextual != null || pending.replayed)) return@synchronized false
        if (pending.explicitTeaching && (!pending.learnable || pending.replayed || pending.contextual != null)) return@synchronized false
        if (pending.replayed && (pending.learnable || !pending.awaitingOutcome)) return@synchronized false
        if (pending.contextual?.isValid() == false || !pending.action.isValid() || pending.phrase.isBlank() || pending.phrase.length > 240 ||
            ConfirmationWords.isConfirmation(pending.phrase) || isOutcome(pending.phrase) || isReminder(pending.phrase) || PendingTimerCorrection.isRequest(pending.phrase) || PendingAppCorrection.isRequest(pending.phrase)) return@synchronized false
        write(JSONObject().put("phrase", pending.phrase).put("capability", pending.action.capabilityId)
            .put("parameters", JSONObject(pending.action.parameters)).put("learnable", pending.learnable)
            .put("contextCapability", pending.contextual?.capabilityId)
            .put("contextParameters", pending.contextual?.let { JSONObject(it.parameters) })
            .put("requestId", pending.requestId).put("explicitTeaching", pending.explicitTeaching).put("replayed", pending.replayed).put("awaitingOutcome", pending.awaitingOutcome).put("created", now()).toString())
    }

    fun consume(input: String, resolveApp: (String) -> Pair<String, String>? = { null }): Resolution? = synchronized(lock) {
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
            val requestId = if (json.has("requestId")) json.getString("requestId").takeIf { CapabilityRequestStore.validId(it) && !replayed && contextual == null } ?: return@runCatching null else null
            Pending(json.getString("phrase"), action, json.optBoolean("learnable"), json.optBoolean("awaitingOutcome"), contextual, replayed, explicit, requestId)
        }.getOrNull()
        val timerEdit = PendingTimerCorrection.isRequest(input)
        val appEdit = PendingAppCorrection.isRequest(input)
        if (pending != null && (timerEdit || appEdit)) {
            val targetKind = if (timerEdit) "timer" else "open_app"
            if (pending.awaitingOutcome || pending.action.capabilityId != targetKind || pending.contextual != null || pending.replayed)
                return@synchronized Resolution(pending, false, needsClarification = true, reminder = true,
                    correction = if (pending.awaitingOutcome) Correction.ALREADY_REQUESTED else Correction.TARGET_LOCKED)
            val action = if (timerEdit) PendingTimerCorrection.action(input) else PendingAppCorrection.action(input, resolveApp)
            if (action == null)
                return@synchronized Resolution(pending, false, needsClarification = true, reminder = true,
                    correction = if (timerEdit) Correction.INVALID_DURATION else Correction.INVALID_APP)
            // Explicit aliases retain consent. Ordinary requests cannot teach their
            // original duration/app wording as a different action after an edit.
            val revised = pending.copy(action = action, learnable = pending.explicitTeaching)
            val json = JSONObject(raw).put("parameters", JSONObject(revised.action.parameters)).put("learnable", revised.learnable)
            val saved = write(json.toString())
            return@synchronized Resolution(if (saved) revised else pending, false, needsClarification = true, reminder = true,
                correction = if (saved) Correction.UPDATED else Correction.SAVE_FAILED)
        }
        // Narrow follow-ups retain the original expiry and never authorize an action.
        if (pending != null && isReminder(input))
            return@synchronized Resolution(pending, false, needsClarification = true, reminder = true)
        if (pending?.awaitingOutcome == true && isInspection(input)) return@synchronized null
        // A bare approval cannot verify an already-requested app or timer.
        if (pending?.awaitingOutcome == true && ConfirmationWords.approves(input))
            return@synchronized Resolution(pending, false, needsClarification = true, reminder = true)
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
        private val lock = ConversationIdentityStore.processLock
        fun isOutcome(text: String): Boolean = OutcomeFeedback.parse(text) != null
        private fun followupKey(text: String) = text.lowercase(java.util.Locale.ROOT).trim()
            .trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        fun isReminder(text: String): Boolean = followupKey(text) in setOf(
            "what am i confirming", "what am i approving", "which action am i confirming",
            "which action", "what should i check", "what was the last request"
        )
        private fun isInspection(text: String): Boolean = GeminiFailureMemory.isQuestion(text) || LearnedCommandReviewCommands.page(text) != null || CapabilityDiscovery.isHelp(text) || CapabilityDiscovery.page(text) != null || CapabilityDiscovery.review(text) != null ||
            followupKey(text) in setOf("show assistant memory", "assistant memory", "what do you remember about my apps and timers", "learning status", "show learning status", "how many commands have you learned")
    }
}

