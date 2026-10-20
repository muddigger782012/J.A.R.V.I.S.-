package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GoalPlanningTest {
    private val music = LearnableDeviceAction("open_app", mapOf("package" to "com.example.music", "label" to "Test Music"))
    private val maps = LearnableDeviceAction("open_app", mapOf("package" to "com.example.maps", "label" to "Test Maps"))
    private fun plan(text: String) = GoalPlanning.plan(GoalPlanning.parse(text)!!,
        { if (it == "music") music else maps }, { name -> if (name.equals("test music", true)) "com.example.music" to "Test Music" else null }, { true })

    @Test fun threeGoalTemplatesComposeReviewedBoundedStepsWithoutExecutingAnything() {
        val focus = plan("Help me focus for twenty five minutes at 20 percent volume!")!!
        assertEquals(GoalPlanning.Kind.FOCUS, focus.kind)
        assertEquals(listOf("volume:20", "timer:1500"), focus.actions)
        assertEquals(music, RoutineSteps.appAction(plan("Prepare a music session")!!.actions.last()))
        assertEquals(maps, RoutineSteps.appAction(plan("Prepare a navigation session at 40 percent volume")!!.actions.last()))
        assertTrue(GoalPlanning.valid(focus))
        assertTrue(GoalPlanning.review(focus).contains("nothing has run"))
        assertTrue(GoalPlanning.review(focus).contains("approve goal plan"))
    }

    @Test fun defaultsAreVisibleAndMissingDurationsRequireClarification() {
        val request = GoalPlanning.parse("help me focus")!!
        assertNull(request.seconds)
        assertEquals(25, request.volume)
        assertNull(GoalPlanning.plan(request, { music }, { null }, { true }))
        val music = plan("prepare a music session")!!
        assertEquals("volume:35", music.actions.first())
        assertTrue(GoalPlanning.review(music).contains("35%"))
    }

    @Test fun invalidOrAppendedInstructionsCannotBecomePartialExecutablePlans() {
        for (text in listOf("help me focus for zero minutes", "help me focus for 25 minutes then reboot my phone",
            "help me focus for 25 hours", "help me focus for five minutes at 101 percent volume")) {
            val request = GoalPlanning.parse(text)
            assertTrue(text, request == null || GoalPlanning.plan(request, { music }, { null }, { true }) == null)
        }
        for (text in listOf("what happens when you start a focus session?", "don't help me focus", "tell me about music sessions",
            "help me focus on my writing", "approve goal plan then reboot", "yes")) {
            assertNull(text, GoalPlanning.parse(text))
            assertNull(text, GoalPlanning.command(text))
        }
    }

    @Test fun appTargetsAreFrozenAndUnavailableOrAmbiguousTargetsDoNotProduceAPlan() {
        val request = GoalPlanning.parse("prepare a music session using Test Music")!!
        val frozen = GoalPlanning.plan(request, { maps }, { "com.example.music" to "Test Music" }, { true })!!
        assertEquals(music, RoutineSteps.appAction(frozen.actions.last()))
        assertNull(GoalPlanning.plan(request, { music }, { null }, { true }))
        assertNull(GoalPlanning.plan(request, { music }, { "com.example.other" to "Other" }, { true }))
        assertNull(GoalPlanning.plan(request, { music }, { "com.example.music" to "Test Music" }, { false }))
        assertNull(GoalPlanning.plan(GoalPlanning.parse("prepare navigation")!!, { null }, { null }, { true }))
    }

    @Test fun pendingReviewIsSharedExpiresAndCanOnlyBeConsumedOnceForItsExactPlan() {
        val directory = Files.createTempDirectory("goal-state").toFile()
        var time = 1_000L
        fun store() = GoalPlanStore(PersonalStateFile(directory, "goal-plans.json")) { time }
        try {
            val first = plan("help me focus for 25 minutes")!!
            assertTrue(store().propose(first))
            assertEquals(first, store().pending())
            assertNull(store().find(first.key))
            val replacement = plan("help me focus for 10 minutes")!!
            assertTrue(store().propose(replacement))
            assertFalse(store().approve(first))
            assertTrue(store().approve(replacement))
            assertFalse(store().approve(replacement))
            assertNull(store().pending())
            assertEquals(replacement.actions, store().find(replacement.key)!!.actions)
            assertTrue(store().propose(first))
            time += 120_001
            assertNull(store().pending())
            assertFalse(store().approve(first))
            assertEquals(replacement, store().current())
        } finally { directory.deleteRecursively() }
    }

    @Test fun clarificationAndResetNeverRemoveTheApprovedDefinition() {
        val directory = Files.createTempDirectory("goal-draft").toFile()
        try {
            val store = GoalPlanStore(PersonalStateFile(directory, "goal-plans.json")) { 1_000L }
            val current = plan("help me focus for 5 minutes")!!
            assertTrue(store.propose(current)); assertTrue(store.approve(current))
            assertTrue(store.askDuration(GoalPlanning.parse("help me focus at 10 percent volume")!!))
            assertEquals(10, GoalPlanStore(PersonalStateFile(directory, "goal-plans.json")) { 1_001L }.durationDraft()!!.volume)
            assertTrue(store.discardPending())
            assertNull(store.durationDraft())
            assertEquals(current, store.current())
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptOrOversizedStateCannotBeOverwrittenByAProposal() {
        val directory = Files.createTempDirectory("goal-corrupt").toFile()
        try {
            val file = java.io.File(directory, "goal-plans.json")
            val store = GoalPlanStore(PersonalStateFile(directory, file.name))
            for (invalid in listOf("invalid", "{\"version\":999}", " ".repeat(256_001))) {
                file.writeText(invalid)
                try { store.propose(plan("help me focus for 5 minutes")!!); fail("Must preserve corruption") } catch (_: Exception) { }
                assertEquals(invalid, file.readText())
            }
        } finally { directory.deleteRecursively() }
    }
}
