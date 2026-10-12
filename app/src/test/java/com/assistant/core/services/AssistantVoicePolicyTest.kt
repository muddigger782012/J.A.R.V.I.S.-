package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class AssistantVoicePolicyTest {
    private fun voice(name: String, country: String = "GB", network: Boolean = false, installed: Boolean = true) =
        AssistantVoicePolicy.Candidate(name, country, 300, 200, networkRequired = network, installed = installed)

    @Test fun newInstallUsesOriginalAndSavedFemaleRemainsFemale() {
        assertEquals(AssistantVoiceProfile.ORIGINAL, AssistantVoiceProfile.fromId(null))
        assertEquals(AssistantVoiceProfile.ORIGINAL, AssistantVoiceProfile.fromId("unknown"))
        assertEquals(AssistantVoiceProfile.FEMALE, AssistantVoiceProfile.fromId("female"))
    }

    @Test fun femaleNameDoesNotCountAsMale() {
        val voices = listOf(voice("gb-female"), voice("gb-male"))
        assertEquals("gb-male", AssistantVoicePolicy.select(voices, AssistantVoiceProfile.ORIGINAL)?.name)
        assertEquals("gb-female", AssistantVoicePolicy.select(voices, AssistantVoiceProfile.FEMALE)?.name)
    }

    @Test fun explicitVoicePreservesExistingVoiceRegardlessOfRanking() {
        val voices = listOf(voice("previous", "US"), voice("gb-male"))
        assertEquals("previous", AssistantVoicePolicy.select(voices, AssistantVoiceProfile.FEMALE, "previous")?.name)
        assertEquals("previous", AssistantVoicePolicy.select(voices, AssistantVoiceProfile.ORIGINAL, "previous")?.name)
    }

    @Test fun unavailableAndNetworkVoicesCannotOverrideOfflineRequirement() {
        val voices = listOf(voice("network", network = true), voice("missing", installed = false), voice("local", "US"))
        assertEquals("local", AssistantVoicePolicy.select(voices, AssistantVoiceProfile.ORIGINAL, "network")?.name)
        assertEquals("local", AssistantVoicePolicy.select(voices, AssistantVoiceProfile.ORIGINAL, "missing")?.name)
        assertNull(AssistantVoicePolicy.select(voices.take(2), AssistantVoiceProfile.ORIGINAL))
        assertNull(AssistantVoicePolicy.select(listOf(voice("other").copy(language = "fr")), AssistantVoiceProfile.ORIGINAL))
    }

    @Test fun unknownGenderNamesDoNotImplyMaleAndBritishAccentHasPriority() {
        val voices = listOf(voice("b-opaque"), voice("a-opaque"), voice("us-male", "US"))
        assertEquals("a-opaque", AssistantVoicePolicy.select(voices, AssistantVoiceProfile.ORIGINAL)?.name)
        assertEquals("a-opaque", AssistantVoicePolicy.select(voices.reversed(), AssistantVoiceProfile.ORIGINAL)?.name)
    }
}
