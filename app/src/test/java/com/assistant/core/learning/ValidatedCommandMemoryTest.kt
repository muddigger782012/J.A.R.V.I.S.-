package com.assistant.core.learning

import com.assistant.core.services.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ValidatedCommandMemoryTest {
    private val preference = mapOf("slot" to "browser", "package" to "com.android.chrome", "label" to "Chrome")
    private val original = "Chrome should be my go to browser"
    private val hyphenated = "Chrome should be my go-to browser"

    @Test fun oldSavedPreferenceMatchesWordHyphensOfflineAfterRecreationWithoutRelearning() {
        var saved = JSONArray().put(JSONObject().put("phrase", original.lowercase()).put("capabilityId", "set_preference")
            .put("parameters", JSONObject(preference)).put("successes", 1)).toString()
        val before = saved
        fun memory() = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        for (phrase in listOf(original, hyphenated, "Chrome should be my go‐to browser", "Chrome should be my go‑to browser", "  CHROME should be my go-to browser!  ")) {
            val learned = memory().match(phrase, LearnablePreference.capabilities)!!
            assertEquals(preference, learned.parameters)
            assertEquals("set_preference", learned.capabilityId)
        }
        assertEquals(before, saved) // Reads do not rewrite the user's existing entries.
        val learned = memory().match(hyphenated, LearnablePreference.capabilities)!!
        val bound = LearnablePreference.fromParameters(learned.parameters)!!
        var pending: String? = null
        var savedPreference: LearnableDeviceAction? = null
        val confirmations = PreferenceConfirmationStore({ pending }, { pending = it; true })
        assertTrue(confirmations.propose(bound.slot, bound.action))
        assertNull(savedPreference)
        val approved = confirmations.consume("yes")!!
        val outcome = PreferenceLearning.commit(approved, true, { true }, { _, action -> savedPreference = action; true }, { savedPreference }, { _, _ -> fail("offline replay must not create a new lesson"); false })
        assertEquals(PreferenceLearning.Outcome(true, false), outcome)
        assertEquals(bound.action, savedPreference)
    }

    @Test fun reverseDirectionSharesOneMeaningAndConflictingVariantCannotReplaceIt() {
        var saved = "[]"
        val memory = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        assertTrue(memory.recordSuccess(hyphenated, "set_preference", LearnablePreference.capabilities, preference))
        assertEquals(preference, memory.match(original, LearnablePreference.capabilities)?.parameters)
        assertTrue(memory.recordSuccess(original, "set_preference", LearnablePreference.capabilities, preference))
        assertEquals(1, memory.learnedPhraseCount())
        assertFalse(memory.recordSuccess(hyphenated, "set_preference", LearnablePreference.capabilities, preference + ("slot" to "maps")))
        assertFalse(memory.recordSuccess(original, "open_app", LearnableDeviceAction.capabilities, preference - "slot"))
        assertEquals(preference, memory.match(hyphenated, LearnablePreference.capabilities)?.parameters)
        assertTrue(memory.forget(hyphenated))
        assertNull(memory.match(original, LearnablePreference.capabilities))
        assertEquals(0, memory.learnedPhraseCount())
    }

    @Test fun legacyConflictingVariantsAreAmbiguousAcrossCapabilityAllowlists() {
        val entries = JSONArray()
        entries.put(JSONObject().put("phrase", original).put("capabilityId", "set_preference").put("parameters", JSONObject(preference)).put("successes", 100))
        entries.put(JSONObject().put("phrase", hyphenated).put("capabilityId", "open_app").put("parameters", JSONObject(preference - "slot")).put("successes", 1))
        var saved = entries.toString()
        val memory = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        assertNull(memory.match(original, LearnablePreference.capabilities))
        assertNull(memory.match(hyphenated, LearnableDeviceAction.capabilities))
        assertNull(memory.match(original, LearnablePreference.capabilities + LearnableDeviceAction.capabilities))
        assertFalse(memory.recordSuccess(original, "set_preference", LearnablePreference.capabilities, preference))
        assertTrue(memory.forget(original))
        assertEquals(0, memory.learnedPhraseCount())
    }

    @Test fun legacySameCapabilityButDifferentParametersCannotSelectMostFrequentTarget() {
        val entries = JSONArray()
        for ((phrase, slot) in listOf(original to "browser", hyphenated to "maps")) {
            entries.put(JSONObject().put("phrase", phrase).put("capabilityId", "set_preference").put("parameters", JSONObject(preference + ("slot" to slot))).put("successes", if (slot == "browser") 100 else 1))
        }
        val memory = ValidatedCommandMemory({ entries.toString() }, { true })
        assertNull(memory.match(original, LearnablePreference.capabilities))
    }

    @Test fun numericSignsStandaloneDashesNegationsAndAppTargetChangesRemainDistinct() {
        var saved = "[]"
        val memory = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        assertTrue(memory.recordSuccess("set -5", "volume_down", VerifiedVolumeLearning.capabilities))
        assertNull(memory.match("set 5", VerifiedVolumeLearning.capabilities))
        assertTrue(memory.recordSuccess("volume - down", "volume_down", VerifiedVolumeLearning.capabilities))
        assertNull(memory.match("volume down", VerifiedVolumeLearning.capabilities))
        assertTrue(memory.recordSuccess(original, "set_preference", LearnablePreference.capabilities, preference))
        assertNull(memory.match("don't " + hyphenated, LearnablePreference.capabilities))
        assertNull(memory.match(hyphenated.replace("Chrome", "Firefox"), LearnablePreference.capabilities))
        assertNull(memory.match("yes", LearnablePreference.capabilities))
        assertNull(memory.match("it worked", LearnablePreference.capabilities))
    }
}
