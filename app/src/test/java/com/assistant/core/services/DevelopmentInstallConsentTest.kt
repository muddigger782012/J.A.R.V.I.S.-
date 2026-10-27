package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DevelopmentInstallConsentTest {
    @Test fun speechCapturedBeforeAChangedReviewCannotApproveTheNewOffer() {
        val store = DevelopmentInstallConsent { 1000 }
        store.propose(offer())
        val captured = DevelopmentInstallConsent.voiceScope()
        store.propose(offer().copy(job = "job-two"))
        assertFalse(VoiceDevelopmentApprovalScope.valid("Approve this APK installation", captured, DevelopmentInstallConsent.voiceScope()))
        assertTrue(VoiceDevelopmentApprovalScope.valid("Cancel APK installation", captured, DevelopmentInstallConsent.voiceScope()))
        assertTrue(VoiceDevelopmentApprovalScope.valid("Install the APK Developer built", captured, DevelopmentInstallConsent.voiceScope()))
    }
    @Test fun cancellationAndOtherTurnsInvalidateAnEarlierMicrophoneApprovalScope() {
        val store = DevelopmentInstallConsent { 1000 }
        store.propose(offer()); val first = DevelopmentInstallConsent.voiceScope()
        assertTrue(VoiceDevelopmentApprovalScope.valid("Confirm this APK installation", first, first))
        store.take()
        assertFalse(VoiceDevelopmentApprovalScope.valid("Confirm this APK installation", first, DevelopmentInstallConsent.voiceScope()))
        store.propose(offer()); val second = DevelopmentInstallConsent.voiceScope()
        store.discard()
        assertFalse(VoiceDevelopmentApprovalScope.valid("Confirm this APK installation", second, DevelopmentInstallConsent.voiceScope()))
    }
    private fun offer() = DevelopmentInstallConsent.Offer("job-one", "supervised", 7,
        "00000000-0000-0000-0000-000000000001", 121_000, "com.jarvis.developer", 178, "production")
    private fun reply() = JSONObject().put("id", "job-one").put("mode", "supervised").put("revision", 7)
        .put("state", "READY_FOR_REVIEW").put("verification", JSONObject().put("SIGNED_APK", "PASSED"))
        .put("artifact", JSONObject().put("stale", false).put("packageName", "com.jarvis.developer").put("versionCode", 178).put("signing", "production"))
        .put("artifactReview", JSONObject().put("purpose", "installation").put("consumed", false)
            .put("ticket", offer().ticket).put("expires", 121_000))
    @Test fun decodedPromptNamesTheExactApplicationBuildAndSigningWithoutLeakingATicket() {
        val value = DevelopmentInstallConsent.decode(reply(), "job-one", "supervised")
        assertEquals(offer(), value)
        assertTrue(value.prompt().contains("Jarvis Developer build 178 with production signing"))
        assertTrue(value.prompt().contains("approve this APK installation"))
        assertFalse(value.prompt().contains(value.ticket))
        assertTrue(value.copy(signing = "debug").prompt().contains("debug signing"))
    }
    @Test fun consumingAnOfferForAnyNewInputCannotLeaveItForAnUnrelatedLaterApproval() {
        val store = DevelopmentInstallConsent { 1000 }
        assertTrue(store.propose(offer()))
        assertEquals(offer(), store.take())
        assertNull(store.take())
    }
    @Test fun processRecreationAndExplicitCancellationCannotRestoreConsent() {
        val store = DevelopmentInstallConsent { 1000 }
        store.propose(offer()); store.discard()
        assertNull(store.take())
        store.propose(offer())
        assertNull(DevelopmentInstallConsent { 1000 }.take())
    }
    @Test fun expiryAndClockRollbackInvalidateTheOffer() {
        var time = 1000L
        val store = DevelopmentInstallConsent { time }
        store.propose(offer()); time = 121_000
        assertNull(store.take())
        time = 1000; store.propose(offer()); time = 999
        assertNull(store.take())
        assertFalse(store.propose(offer().copy(expires = 999)))
    }
    @Test fun aNewReviewReplacesConsentAndAnInvalidOfferClearsThePreviousOne() {
        val store = DevelopmentInstallConsent { 1000 }
        store.propose(offer())
        val next = offer().copy(job = "job-two")
        assertTrue(store.propose(next)); assertEquals(next, store.take())
        store.propose(offer()); assertFalse(store.propose(offer().copy(expires = 200_000)))
        assertNull(store.take())
    }
    @Test fun navigationTicketsStaleEvidenceAndAlreadyConsumedReviewsCannotBecomeInstallOffers() {
        val navigation = reply().apply { getJSONObject("artifactReview").put("purpose", "review") }
        val stale = reply().apply { getJSONObject("artifact").put("stale", true) }
        val used = reply().apply { getJSONObject("artifactReview").put("consumed", true) }
        val cancelled = reply().apply { getJSONObject("artifactReview").put("cancelled", true) }
        val unverified = reply().apply { getJSONObject("verification").put("SIGNED_APK", "BLOCKED") }
        for (value in listOf(navigation, stale, used, cancelled, unverified))
            assertThrows(IllegalArgumentException::class.java) { DevelopmentInstallConsent.decode(value, "job-one", "supervised") }
    }
    @Test fun wrongJobsModesAndUnsupportedPackagesCannotBeReviewedAsTheCurrentApk() {
        assertThrows(IllegalArgumentException::class.java) { DevelopmentInstallConsent.decode(reply(), "job-two", "supervised") }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentInstallConsent.decode(reply(), "job-one", "owner_development") }
        val value = reply().apply { getJSONObject("artifact").put("packageName", "unrelated.application") }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentInstallConsent.decode(value, "job-one", "supervised") }
    }
}
