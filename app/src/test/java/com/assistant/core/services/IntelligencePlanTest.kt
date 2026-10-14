package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IntelligencePlanTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun raw(revision: Int = 1) = JSONObject().put("schema", 1).put("revision", revision)
        .put("updated", "2026-10-13").put("throughVersion", "3.5.394").put("note", "Engineering estimate")
        .put("milestone", "Phone checks are distinct")
        .put("overall", 100) // Must never be trusted as the score.
        .put("workstreams", JSONArray().apply { IntelligencePlan.ids.forEach { id -> put(JSONObject()
            .put("id", id).put("title", id).put("percent", 75).put("status", "Implemented")
            .put("implemented", "Bounded handlers").put("remaining", "More work")) } })

    @Test fun validatesSchemaAndDerivesEstimateInsteadOfTrustingRemoteOverall() {
        assertEquals(75, IntelligencePlan.parse(raw().toString())!!.overall)
        assertNull(IntelligencePlan.parse(raw().put("schema", 2).toString()))
        assertNull(IntelligencePlan.parse(raw().put("revision", 1.5).toString()))
        val bad = raw(); bad.getJSONArray("workstreams").getJSONObject(0).put("percent", 101)
        assertNull(IntelligencePlan.parse(bad.toString()))
        bad.getJSONArray("workstreams").getJSONObject(0).put("percent", 75.5)
        assertNull(IntelligencePlan.parse(bad.toString()))
        assertNull(IntelligencePlan.parse(raw().put("note", "x".repeat(65537)).toString()))
        assertNull(IntelligencePlan.parse(raw().put("updated", "bad-date").toString()))
    }
    @Test fun newerCachedPlanSurvivesRestartAndOlderInvalidUpdatesCannotReplaceIt() {
        val bundled = raw().toString()
        fun repository() = IntelligencePlanRepository(temporary.root) { bundled }
        assertEquals(1L, repository().snapshot().revision)
        assertTrue(repository().accept(raw(2).toString()).contains("Updated"))
        assertEquals(2L, repository().snapshot().revision)
        assertTrue(repository().accept(bundled).contains("older"))
        assertTrue(repository().accept("broken").contains("invalid"))
        assertEquals(2L, repository().snapshot().revision)
        File(temporary.root, "intelligence-plan-cache.json").writeText("broken")
        assertEquals(1L, repository().snapshot().revision)
    }
    @Test fun planDiscussionFollowsSelectedWorkstreamAndStopsAtTopicOrChatBoundary() {
        val chat = PlanConversation { IntelligencePlan.parse(raw().toString())!! }
        assertNull(chat.reply("What's left?"))
        assertTrue(chat.reply("Show intelligence plan progress")!!.contains("75%"))
        assertTrue(chat.reply("What about the capability builder?")!!.contains("builder"))
        assertTrue(chat.reply("How far along is that?")!!.contains("75%"))
        assertTrue(chat.reply("What's left?")!!.contains("More work"))
        assertNull(chat.reply("What's the weather?")); assertNull(chat.reply("What's left?"))
        chat.restore(listOf("Show intelligence plan progress" to "plan", "What about the capability builder?" to "builder"))
        assertTrue(chat.reply("What's left?")!!.contains("More work"))
        assertNull(chat.reply("teach intelligence plan means reboot"))
        chat.reset(); assertNull(chat.reply("Tell me more"))
    }
}
