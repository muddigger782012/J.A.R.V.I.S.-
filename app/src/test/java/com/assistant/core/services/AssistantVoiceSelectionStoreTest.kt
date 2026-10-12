package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AssistantVoiceSelectionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun anExistingReaderSeesAnotherInstancesSavedProfileAndVoice() {
        val writer = AssistantVoiceSelectionStore(temporary.root)
        val background = AssistantVoiceSelectionStore(temporary.root)
        assertEquals(AssistantVoiceProfile.ORIGINAL, background.load().profile)
        writer.update({ AssistantVoiceSelection() }) { it.copy(profile = AssistantVoiceProfile.FEMALE, femaleVoice = "female-local") }
        assertEquals(AssistantVoiceProfile.FEMALE, background.load().profile)
        writer.update({ AssistantVoiceSelection() }) { it.copy(profile = AssistantVoiceProfile.ORIGINAL, originalVoice = "different-local") }
        assertEquals("different-local", background.load().voiceName(AssistantVoiceProfile.ORIGINAL))
        assertEquals("female-local", background.load().femaleVoice)
    }

    @Test fun legacyProfileAndBothVoiceNamesSurviveMigration() {
        val old = AssistantVoiceSelection(AssistantVoiceProfile.FEMALE, "original", "female")
        val store = AssistantVoiceSelectionStore(temporary.root)
        store.update({ old }) { it.copy(profile = AssistantVoiceProfile.ORIGINAL, originalVoice = "new-original") }
        val restored = AssistantVoiceSelectionStore(temporary.root).load { error("Legacy fallback should not be used after migration") }
        assertEquals(AssistantVoiceSelection(AssistantVoiceProfile.ORIGINAL, "new-original", "female"), restored)
    }

    @Test fun concurrentFemaleCaptureCannotOverwriteASavedOriginalChoice() {
        val first = AssistantVoiceSelectionStore(temporary.root)
        val second = AssistantVoiceSelectionStore(temporary.root)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val original = workers.submit {
                first.update({ AssistantVoiceSelection() }) { it.copy(profile = AssistantVoiceProfile.ORIGINAL, originalVoice = "chosen") }
            }
            val female = workers.submit {
                second.update({ AssistantVoiceSelection() }) { if (it.femaleVoice.isBlank()) it.copy(femaleVoice = "retained") else it }
            }
            original.get(5, TimeUnit.SECONDS); female.get(5, TimeUnit.SECONDS)
            assertEquals(AssistantVoiceSelection(AssistantVoiceProfile.ORIGINAL, "chosen", "retained"), first.load())
        } finally { workers.shutdownNow() }
    }
}
