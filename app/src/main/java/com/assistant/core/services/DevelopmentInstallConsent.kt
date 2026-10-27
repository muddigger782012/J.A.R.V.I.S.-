package com.assistant.core.services

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONObject

/** Deliberate, purpose-specific consent. Never persisted or inferred from an AI reply or generic yes. */
class DevelopmentInstallConsent(private val now: () -> Long = System::currentTimeMillis) {
    data class Offer(val job: String, val mode: String, val revision: Long, val ticket: String,
        val expires: Long, val packageName: String, val versionCode: Long, val signing: String) {
        init {
            Wire.validate(Wire.Request("validate-offer", Wire.ARTIFACT_INSTALL_REVIEW, mode = mode, jobId = job, expectedRevision = revision))
            require(ticket.matches(Regex("[a-f0-9-]{36}")) && expires > 0)
            require(packageName in setOf("com.assistant.core", Wire.DEVELOPER_PACKAGE) && versionCode in 1..2_099_999_999 && signing in setOf("debug", "production"))
        }
        fun prompt(): String {
            val app = if (packageName == "com.assistant.core") "Personal Jarvis" else "Jarvis Developer"
            return "Developer verified $app build $versionCode with $signing signing matching your installed Jarvis identity. " +
                "Approve asking Android to install this exact APK? Say 'approve this APK installation' or 'cancel APK installation'. Android's permission and confirmation remain required."
        }
    }
    private var pending: Pair<Offer, Long>? = null
    @Synchronized fun propose(offer: Offer): Boolean {
        contextSequence.incrementAndGet()
        pending = null
        val time = now()
        if (offer.expires - time !in 1..120_000L) return false
        pending = offer to time
        return true
    }
    /** Every new utterance consumes the old context; an unrelated yes cannot approve it later. */
    @Synchronized fun take(): Offer? {
        contextSequence.incrementAndGet()
        val stored = pending; pending = null
        val time = now()
        return stored?.takeIf { time - it.second in 0..120_000L && time < it.first.expires }?.first
    }
    @Synchronized fun discard() { contextSequence.incrementAndGet(); pending = null }
    companion object {
        private val process = java.util.UUID.randomUUID().toString()
        private val contextSequence = java.util.concurrent.atomic.AtomicLong()
        /** Non-authorizing process-local identity captured when microphone input begins. */
        fun voiceScope(): String = "$process:${contextSequence.get()}"
        fun decode(reply: JSONObject, job: String, mode: String): Offer {
            val delivery = reply.getJSONObject("artifactReview")
            val artifact = reply.getJSONObject("artifact")
            val revision = reply.getLong("revision")
            Wire.validate(Wire.Request("validate-offer", Wire.ARTIFACT_INSTALL_REVIEW, mode = mode, jobId = job, expectedRevision = revision))
            require(reply.getString("id") == job && reply.getString("mode") == mode)
            require(reply.getString("state") == "READY_FOR_REVIEW" && reply.getJSONObject("verification").getString("SIGNED_APK") == "PASSED")
            require(!artifact.getBoolean("stale") && !delivery.getBoolean("consumed") && !delivery.optBoolean("cancelled"))
            require(delivery.getString("purpose") == "installation")
            val ticket = delivery.getString("ticket")
            require(ticket.matches(Regex("[a-f0-9-]{36}")))
            val packageName = artifact.getString("packageName")
            val version = artifact.getLong("versionCode")
            val signing = artifact.getString("signing")
            require(packageName in setOf("com.assistant.core", Wire.DEVELOPER_PACKAGE) && version in 1..2_099_999_999 && signing in setOf("debug", "production"))
            return Offer(job, mode, revision, ticket, delivery.getLong("expires"), packageName, version, signing)
        }
    }
}
