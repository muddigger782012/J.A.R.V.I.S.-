package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LearnedCommandReviewTest {
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
    private val firefox = LearnableDeviceAction("open_app", mapOf("package" to "org.mozilla.firefox", "label" to "Firefox"))

    @Test fun inspectionReadsActualSavedMeaningsAndNeverWritesEitherStore() {
        var saved = "[]"
        var context = "{}"
        var writes = 0
        var contextWrites = 0
        val commands = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; writes++; true })
        val memory = AssistantMemory({ context }, { context = it; contextWrites++; true })
        commands.recordSuccess("less racket", "volume_down", VerifiedVolumeLearning.capabilities)
        commands.recordSuccess("give me a break", "timer", LearnableDeviceAction.capabilities, mapOf("seconds" to "300"))
        commands.recordSuccess("Chrome should be my go-to browser", "set_preference", LearnablePreference.capabilities, chrome.parameters + ("slot" to "browser"))
        val examples = listOf(IntentLearningStore.SavedExample("clock please", "GENERAL_QUESTION", "verified_local_time", 2, 0),
            IntentLearningStore.SavedExample("explain engines", "GENERAL_QUESTION", "validated_fallback", 1, 2))
        writes = 0
        val review = LearnedCommandReview.render(1, commands, memory, examples)
        assertTrue(review.contains("3 command entries, 2 intent examples"))
        assertTrue(review.contains("lower media volume one step"))
        assertTrue(review.contains("start a 300-second timer"))
        assertTrue(review.contains("set J.A.R.V.I.S. preferred browser to Chrome; asks approval"))
        assertTrue(review.contains("request the current time"))
        assertTrue(review.contains("reported failures: 2"))
        assertEquals(0, writes)
        assertEquals(0, contextWrites)
        assertNull(memory.preferred("browser"))
    }

    @Test fun contextualReviewUsesCurrentPreferenceAndExplainsMissingMemory() {
        var saved = "[]"
        var context = "{}"
        val commands = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        val memory = AssistantMemory({ context }, { context = it; true })
        commands.recordSuccess("my usual browser", "open_preferred", ContextualAction.capabilities, mapOf("slot" to "browser"))
        assertTrue(LearnedCommandReview.render(1, commands, memory, emptyList()).contains("unavailable until its assistant memory is saved"))
        memory.savePreference("browser", chrome)
        val before = saved
        assertTrue(LearnedCommandReview.render(1, commands, memory, emptyList()).contains("open Chrome"))
        memory.savePreference("browser", firefox)
        val updated = LearnedCommandReview.render(1, commands, memory, emptyList())
        assertTrue(updated.contains("open Firefox"))
        assertFalse(updated.contains("open Chrome"))
        assertEquals(before, saved)
    }

    @Test fun pagesAreBoundedAndInvalidOrMissingPagesAreHandledLocally() {
        var saved = "[]"
        val commands = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        val memory = AssistantMemory({ "{}" }, { fail("review must not write"); false })
        assertTrue(LearnedCommandReview.render(1, commands, memory, emptyList()).startsWith("No saved"))
        for (i in 1..13) commands.recordSuccess("saved phrase $i", "volume_down", VerifiedVolumeLearning.capabilities)
        val first = LearnedCommandReview.render(1, commands, memory, emptyList())
        val second = LearnedCommandReview.render(2, commands, memory, emptyList())
        assertTrue(first.contains("Page 1 of 2"))
        assertTrue(first.contains("show learned commands page 2"))
        assertEquals(12, first.lineSequence().count { Regex("^[0-9]+\\. .*→.*").matches(it) })
        assertEquals(1, second.lineSequence().count { Regex("^[0-9]+\\. .*→.*").matches(it) })
        assertTrue(LearnedCommandReview.render(0, commands, memory, emptyList()).contains("starting at 1"))
        assertTrue(LearnedCommandReview.render(Int.MAX_VALUE, commands, memory, emptyList()).contains("There are 2"))
    }

    @Test fun quotedDeletionUsesExistingHyphenMatchingAndPreservesAssistantPreferences() {
        var saved = "[]"
        var context = "{}"
        val commands = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        val memory = AssistantMemory({ context }, { context = it; true })
        memory.savePreference("browser", chrome)
        commands.recordSuccess("Chrome should be my go to browser", "set_preference", LearnablePreference.capabilities, chrome.parameters + ("slot" to "browser"))
        val phrase = LearnedCommandReviewCommands.forgetPhrase("Forget learned command “Chrome should be my go-to browser”")!!
        assertTrue(commands.forget(phrase))
        assertNull(commands.match(phrase, LearnablePreference.capabilities))
        assertEquals(chrome, memory.preferred("browser"))
        assertTrue(LearnedCommandReview.render(1, commands, memory, emptyList()).startsWith("No saved"))
    }

    @Test fun collisionsInvalidParametersAndReservedLegacyAliasesAreMarkedUnavailable() {
        val entries = JSONArray()
        fun add(phrase: String, capability: String, params: Map<String, String>) {
            entries.put(JSONObject().put("phrase", phrase).put("capabilityId", capability).put("parameters", JSONObject(params)))
        }
        add("go-to browser", "open_app", chrome.parameters)
        add("go to browser", "open_app", firefox.parameters)
        add("bad timer", "timer", mapOf("seconds" to "-1"))
        add("show learned commands", "volume_up", emptyMap())
        add("unknown action", "arbitrary_shell", mapOf("code" to "untrusted"))
        val commands = ValidatedCommandMemory({ entries.toString() }, { fail("review must not write"); false })
        val memory = AssistantMemory({ "{}" }, { false })
        val review = LearnedCommandReview.render(1, commands, memory, emptyList())
        assertEquals(5, review.lineSequence().count { it.contains("→ unavailable:") })
        assertFalse(review.contains("untrusted"))
    }

    @Test fun controlsHaveExplicitGrammarAndCannotBecomeLearnedActions() {
        assertEquals(1, LearnedCommandReviewCommands.page("Show learned commands!"))
        assertEquals(2, LearnedCommandReviewCommands.page("show my learned commands page 2"))
        assertEquals(0, LearnedCommandReviewCommands.page("show learned commands page 999999999999999999"))
        assertNull(LearnedCommandReviewCommands.page("don't show learned commands"))
        assertNull(LearnedCommandReviewCommands.page("show learned commands and open Chrome"))
        assertEquals("my command", LearnedCommandReviewCommands.forgetPhrase("Please remove learned command \"my command\""))
        assertEquals("my command", LearnedCommandReviewCommands.forgetPhrase("Forget learned command “my command”."))
        assertNull(LearnedCommandReviewCommands.forgetPhrase("don't forget learned command my command"))
        val commands = ValidatedCommandMemory({ "[]" }, { fail("reserved control must not be saved"); false })
        assertFalse(commands.recordSuccess("show learned commands", "volume_up", VerifiedVolumeLearning.capabilities))
        assertFalse(commands.recordSuccess("forget learned command my command", "volume_up", VerifiedVolumeLearning.capabilities))
    }
}
