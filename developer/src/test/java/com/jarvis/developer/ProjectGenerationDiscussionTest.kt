package com.jarvis.developer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectGenerationDiscussionTest {
    private fun empty(project: String = "project-a", target: Int = 0) = ProjectGenerationDiscussion.empty(project, target)

    @Test fun storesOnlyBoundedCompletedTurnsAndSearchesOwnerRequestsInsteadOfAiSuggestions() {
        var history = empty()
        repeat(6) { index -> history = history.append("Owner task $index", "AI suggestion $index", emptyList()) }
        val items = history.data().getJSONArray("turns")
        assertEquals(3, items.length()); assertEquals("Owner task 3", items.getJSONObject(0).getString("ownerRequest"))
        val lookup = history.lookupTask("What about tests?")
        assertTrue(lookup.startsWith("What about tests?")); assertTrue(lookup.contains("Owner task 5"))
        assertFalse(lookup.contains("AI suggestion")); assertFalse(lookup.contains("Owner task 2"))
        assertTrue(history.lookupTask("x".repeat(4_000)).length <= 4_000)
    }

    @Test fun redactsWholeValuesAndConfiguredCredentialsBeforeTruncationAndAgainOnRead() {
        val configured = "fixture-current-key-42"
        val github = "ghp_" + "a".repeat(24)
        val history = empty().append("Inspect $configured and $github", "password = \"sensitive-fixture-value\"\n$configured", listOf(configured))
        val text = history.data().toString()
        assertFalse(text.contains(configured)); assertFalse(text.contains(github)); assertFalse(text.contains("sensitive-fixture-value"))
        assertTrue(text.contains("REDACTED"))
        val newerSecret = "earlier-opaque-value"
        val stored = empty().append("Earlier request", "Reply $newerSecret", emptyList()).data()
        val reloaded = ProjectGenerationDiscussion.read("project-a", 0, stored, listOf(newerSecret))
        assertFalse(reloaded.data().toString().contains(newerSecret))
    }

    @Test fun enforcesEscapedJsonAndUnicodeBudgetsWithoutSplittingCharacters() {
        var history = empty()
        repeat(8) { history = history.append("\\\"\n".repeat(1_000), "😀\\\"\n".repeat(1_000), emptyList()) }
        val encoded = history.data().toString()
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= ProjectGenerationDiscussion.MAX_BYTES)
        assertTrue(history.size in 1..ProjectGenerationDiscussion.MAX_TURNS)
        val reloaded = ProjectGenerationDiscussion.read("project-a", 0, JSONObject(encoded), emptyList())
        assertEquals(history.size, reloaded.size)
        assertFalse(encoded.contains("\ud83d\"")); assertFalse(encoded.contains("\ud83d\\"))
        val invalid = empty().append("Request \ud800", "Reply \udc00", emptyList()).data().toString()
        assertTrue(invalid.contains("\ufffd"))
    }

    @Test fun malformedMismatchedAndLegacyRecordsDoNotBecomeConversationContext() {
        val valid = empty().append("Owner task", "Reply", emptyList()).data()
        val records = listOf(null, JSONObject(), JSONObject(valid.toString()).put("project", "project-b"),
            JSONObject(valid.toString()).put("target", 1), JSONObject(valid.toString()).put("schemaVersion", "1"),
            JSONObject(valid.toString()).put("approval", true), JSONObject(valid.toString()).apply {
                getJSONArray("turns").getJSONObject(0).put("assistantReply", 4)
            }, JSONObject(valid.toString()).apply {
                getJSONArray("turns").getJSONObject(0).put("ownerRequest", "x".repeat(801))
            })
        records.forEach { assertEquals(0, ProjectGenerationDiscussion.read("project-a", 0, it, emptyList()).size) }
        assertEquals(0, ProjectGenerationDiscussion.read("project-b", 0, valid, emptyList()).size)
    }

    @Test fun discussionNeverCreatesPatchOrExecutionAuthority() {
        val encoded = empty().append("Prepare a repair", "Review the proposed change", emptyList()).data()
        assertEquals(setOf("schemaVersion", "project", "target", "turns"), encoded.keys().asSequence().toSet())
        assertFalse(PatchEngine.allowedPath("developer/src/main/java/com/jarvis/developer/ProjectGenerationDiscussion.kt"))
        assertFalse(PatchEngine.allowedPath("developer/src/main/java/com/jarvis/developer/ProjectGenerationStore.kt"))
    }
}
