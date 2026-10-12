package com.assistant.core.services

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NeuralVoiceCatalogTest {
    @Test fun exactVersionSpeakerIdsAreDistinctAndLegacySelectionMigratesToGeorge() {
        assertEquals(9, NeuralVoiceCatalog.resolve("kokoro-v019:george").speaker)
        assertEquals(10, NeuralVoiceCatalog.resolve("kokoro-v019:lewis").speaker)
        assertEquals(6, NeuralVoiceCatalog.resolve("kokoro-v019:michael").speaker)
        assertEquals(3, NeuralVoiceCatalog.voices.map { it.speaker }.toSet().size)
        assertEquals("kokoro-v019:george", NeuralVoiceCatalog.resolve("en-gb-x-local").id)
        val selected = AssistantVoiceSelection(originalVoice = "kokoro-v019:lewis", femaleVoice = "saved-female")
        assertEquals(selected, AssistantVoiceSelection.fromJson(selected.toJson()))
        assertEquals("saved-female", selected.voiceName(AssistantVoiceProfile.FEMALE))
    }
    @Test fun archiveExtractionCannotEscapeOrUseAnotherRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "neural-voice-policy-test")
        assertEquals(File(root, "model.onnx").canonicalFile,
            NeuralVoiceCatalog.safeDestination(root, "kokoro-en-v0_19/model.onnx"))
        for (path in listOf("/tmp/model", "kokoro-en-v0_19/../../model", "another-model/model", "kokoro-en-v0_19/..", "kokoro-en-v0_19\\model")) {
            assertTrue(path, runCatching { NeuralVoiceCatalog.safeDestination(root, path) }.isFailure)
        }
    }
}
