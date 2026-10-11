package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class PreferenceLearningTest {
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
    private val phrase = "Chrome should be my go-to browser"
    private fun lesson(entities: Map<String, String> = mapOf("slot" to "browser", "app_name" to "Chrome"), confidence: Double = .95) =
        FallbackIntentLesson("SET_APP_PREFERENCE", confidence, entities, learnable = true)

    @Test fun approvedVerifiedSaveLearnsOriginalPhraseAndOfflineReplayStillRequiresApproval() {
        var state = "{}"
        var aliases = "[]"
        var pending: String? = null
        fun memory() = AssistantMemory({ state }, { state = it; true })
        fun commands() = ValidatedCommandMemory({ aliases }, { aliases = it ?: "[]"; true })
        fun confirmations() = PreferenceConfirmationStore({ pending }, { pending = it; true })
        val preference = LearnablePreference.fromLesson(lesson()) { "com.android.chrome" to "Chrome" }!!
        assertTrue(confirmations().propose(preference.slot, preference.action, phrase))
        assertNull(memory().preferred("browser"))
        assertEquals(0, commands().learnedPhraseCount())
        val approved = confirmations().consume("yes")!!
        val outcome = PreferenceLearning.commit(approved, true, { true }, memory()::savePreference, memory()::preferred,
            { original, pref -> commands().recordSuccess(original, LearnablePreference.CAPABILITY, LearnablePreference.capabilities, pref.parameters()) })
        assertEquals(PreferenceLearning.Outcome(true, true), outcome)
        assertEquals(chrome, memory().preferred("browser"))
        assertNull(commands().match("yes", LearnablePreference.capabilities))
        assertNull(confirmations().consume("yes"))
        val learned = commands().match(phrase, LearnablePreference.capabilities)!!
        val offline = LearnablePreference.fromParameters(learned.parameters)!!
        memory().forget("preferred_browser")
        assertTrue(confirmations().propose(offline.slot, offline.action))
        assertNull(memory().preferred("browser"))
        val declined = confirmations().consume("no")!!
        assertEquals(PreferenceLearning.Outcome(false, false), PreferenceLearning.commit(declined, true, { true }, memory()::savePreference, memory()::preferred, { _, _ -> fail("cancel must not train"); false }))
        assertNull(memory().preferred("browser"))
        assertTrue(confirmations().propose(offline.slot, offline.action))
        val replay = PreferenceLearning.commit(confirmations().consume("yes")!!, true, { true }, memory()::savePreference, memory()::preferred, { _, _ -> fail("replay has no new lesson"); false })
        assertEquals(PreferenceLearning.Outcome(true, false), replay)
        assertEquals(chrome, memory().preferred("browser"))
    }

    @Test fun noLearningAfterMissingAppFailedWriteFailedReadbackOrUnapprovedRequest() {
        val approved = PreferenceConfirmationStore.Pending("browser", chrome, true, phrase)
        var saves = 0
        var records = 0
        fun run(pending: PreferenceConfirmationStore.Pending = approved, installed: Boolean = true, write: Boolean = true, stored: LearnableDeviceAction? = chrome) =
            PreferenceLearning.commit(pending, true, { installed }, { _, _ -> saves++; write }, { stored }, { _, _ -> records++; true })
        assertFalse(run(installed = false).saved)
        assertEquals(0, saves)
        assertFalse(run(write = false).saved)
        assertFalse(run(stored = null).saved)
        assertFalse(run(pending = approved.copy(approved = false)).saved)
        assertEquals(0, records)
        assertEquals(PreferenceLearning.Outcome(true, false), PreferenceLearning.commit(approved, false, { true }, { _, _ -> true }, { chrome }, { _, _ -> fail("learning disabled"); false }))
        assertEquals(PreferenceLearning.Outcome(true, false), PreferenceLearning.commit(approved, true, { true }, { _, _ -> true }, { chrome }, { _, _ -> false }))
    }

    @Test fun strictSchemasRejectInventedPackagesUnknownSlotsMissingTargetsAndLowConfidence() {
        var resolutions = 0
        fun resolve(name: String): Pair<String, String>? { resolutions++; return if (name == "Chrome") "com.android.chrome" to "Chrome" else null }
        assertNull(LearnablePreference.fromLesson(lesson(confidence = .89), ::resolve))
        assertNull(LearnablePreference.fromLesson(lesson(mapOf("slot" to "browser")), ::resolve))
        assertNull(LearnablePreference.fromLesson(lesson(mapOf("slot" to "email", "app_name" to "Chrome")), ::resolve))
        assertNull(LearnablePreference.fromLesson(lesson(mapOf("slot" to "browser", "app_name" to "Chrome", "package" to "injected.app")), ::resolve))
        assertEquals(0, resolutions)
        assertNull(LearnablePreference.fromLesson(lesson(mapOf("slot" to "browser", "app_name" to "Unknown")), ::resolve))
        assertNull(LearnablePreference.fromParameters(mapOf("slot" to "browser", "package" to "bad;command", "label" to "Chrome")))
        assertNull(LearnablePreference.fromParameters(mapOf("slot" to "browser", "package" to "com.android.chrome", "label" to "Chrome", "extra" to "value")))
        assertFalse(LearningAdmissionPolicy.canRecordWithoutActionResult(lesson()))
    }

    @Test fun unrelatedOutcomeExpiredCancelledAndStorageFailureCannotApprovePreference() {
        var pending: String? = null
        var now = 1000L
        fun session() = PreferenceConfirmationStore({ pending }, { pending = it; true }, { now })
        assertTrue(session().propose("browser", chrome, phrase))
        assertNull(session().consume("it worked"))
        assertNull(session().consume("yes"))
        assertTrue(session().propose("browser", chrome, phrase))
        assertNull(session().consume("what time is it"))
        assertNull(session().consume("yes"))
        assertTrue(session().propose("browser", chrome, phrase))
        now += 120001L
        assertNull(session().consume("yes"))
        for (invalid in listOf("yes", "it worked", "", "x".repeat(241))) assertFalse(session().propose("browser", chrome, invalid))
        assertFalse(session().propose("clear", learningPhrase = phrase))
        assertFalse(PreferenceConfirmationStore({ null }, { false }).propose("browser", chrome, phrase))
    }

    @Test fun phraseConflictsDoNotReplaceExistingOfflineMeaning() {
        var aliases = "[]"
        val commands = ValidatedCommandMemory({ aliases }, { aliases = it ?: "[]"; true })
        assertTrue(commands.recordSuccess(phrase, "open_app", LearnableDeviceAction.capabilities, chrome.parameters))
        val pending = PreferenceConfirmationStore.Pending("browser", chrome, true, phrase)
        val outcome = PreferenceLearning.commit(pending, true, { true }, { _, _ -> true }, { chrome },
            { original, pref -> commands.recordSuccess(original, LearnablePreference.CAPABILITY, LearnablePreference.capabilities, pref.parameters()) })
        assertEquals(PreferenceLearning.Outcome(true, false), outcome)
        assertEquals("open_app", commands.match(phrase, LearnableDeviceAction.capabilities)?.capabilityId)
    }
}
