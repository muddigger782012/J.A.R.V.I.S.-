package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ComposableGoalPlanningTest {
    private val music = LearnableDeviceAction("open_app", mapOf("package" to "com.example.music", "label" to "Test Music"))
    private val maps = LearnableDeviceAction("open_app", mapOf("package" to "com.example.maps", "label" to "Test Maps"))
    private fun plan(text: String) = GoalPlanning.parse(text)?.let { request -> GoalPlanning.plan(request,
        { if (it == "music") music else if (it == "maps") maps else null },
        { label -> listOf(music, maps).singleOrNull { it.parameters.getValue("label").equals(label, true) }?.let { it.parameters.getValue("package") to it.parameters.getValue("label") } }, { true }) }

    @Test fun explicitCompositionUsesSpokenValuesFrozenAppsAndAnOptionalFinalTimer() {
        val goal = plan("Please plan a task to set media volume to twenty percent then open Test Music then start a timer for ten minutes")!!
        assertEquals(GoalPlanning.Kind.COMPOSED, goal.kind)
        assertEquals("volume:20", goal.actions[0])
        assertEquals(music, RoutineSteps.appAction(goal.actions[1]))
        assertEquals("timer:600", goal.actions.last())
        assertTrue(GoalPlanning.review(goal).contains("Each app or timer request pauses"))
        assertEquals(listOf(RoutineSteps.appStep(maps)), plan("prepare a task to open Test Maps")!!.actions)
        assertEquals(listOf("volume:0"), plan("plan a task to lower media volume to zero percent")!!.actions)
    }

    @Test fun drivePreparationFreezesBothPreferredAppsWithoutAssumingPlaybackOrNavigation() {
        val goal = plan("help me get ready to drive with music and Maps at thirty percent volume")!!
        assertEquals(GoalPlanning.Kind.DRIVE, goal.kind)
        assertEquals(listOf("volume:30", RoutineSteps.appStep(music), RoutineSteps.appStep(maps)), goal.actions)
        assertTrue(GoalPlanning.review(goal).contains("No playback or navigation completion is verified"))
        assertEquals("volume:50", plan("prepare my drive")!!.actions.first())
        val request = GoalPlanning.parse("prepare my drive")!!
        assertNull(GoalPlanning.plan(request, { music }, { null }, { true })) // One app cannot silently fill both roles.
        assertNull(GoalPlanning.plan(request, { if (it == "music") music else null }, { null }, { true }))
        assertNull(GoalPlanning.plan(request, { if (it == "music") music else maps }, { null }, { it != "com.example.maps" }))
    }

    @Test fun unsupportedClausesAndMalformedControlsNeverProducePartialPlansOrLearnedAliases() {
        for (text in listOf("plan a task", "plan a task to set media volume to twenty percent then call Alex",
            "plan a task to open Test Music then reboot", "plan a task to start a timer for zero minutes",
            "plan a task to open Test Music then open Test Maps then set volume to ten percent",
            "plan a task to open Test Music then open Test Maps then start a timer for ten minutes then call Alex",
            "plan a task to set volume to 101 percent", "plan a task to open Test Music then start a timer for 25 hours",
            "prepare my drive then send a message", "prepare my drive at 101 percent volume")) {
            assertNotNull(text, GoalPlanning.parse(text))
            assertNull(text, plan(text))
            assertTrue(text, LearnedCommandReviewCommands.isReserved(text))
        }
        for (text in listOf("approve goal plan then search the service manual", "the goal step worked then open Test Maps")) {
            assertNull(GoalPlanning.command(text)); assertTrue(GoalPlanning.isManagementInput(text))
            assertTrue(LearnedCommandReviewCommands.isReserved(text))
        }
        for (word in listOf("yes", "please", "go ahead", "please do", "no")) assertTrue(GoalPlanning.isReviewReply(word))
        assertFalse(GoalPlanning.isReviewReply("go ahead and open settings"))
        for (text in listOf("What would you plan a task to do?", "Don't prepare my drive", "Tell me about Maps"))
            assertNull(text, GoalPlanning.parse(text))
    }

    @Test fun appResolutionIsExactAndDuplicatePackagesAndUnboundActionsAreRejected() {
        val request = GoalPlanning.parse("plan a task to open Test Music then open Test Maps")!!
        assertNull(GoalPlanning.plan(request, { null }, { "com.example.music" to "Other" }, { true }))
        assertNull(GoalPlanning.plan(request, { null }, { label -> "com.example.same" to label }, { true }))
        assertNull(GoalPlanning.plan(request, { null }, { null }, { true }))
        for (steps in listOf(listOf("app_update"), listOf("preferred:maps"), listOf("volume_down"),
            listOf("timer:300", RoutineSteps.appStep(maps)!!), listOf("volume:20", "volume:30"), emptyList()))
            assertFalse(steps.toString(), ComposableGoalPlanning.validActions(steps))
        val multiApp = listOf(RoutineSteps.appStep(music)!!, RoutineSteps.appStep(maps)!!)
        assertTrue(ComposableGoalPlanning.validActions(multiApp))
        assertFalse(RoutineSteps.isValid(multiApp)) // Existing AI/saved routine validation stays unchanged.
    }

    @Test fun correctionsReplaceOnlyExistingFieldsAndInvalidateEarlierApproval() {
        val original = plan("plan a task to set volume to twenty percent then open Test Music then start a timer for ten minutes")!!
        val duration = GoalPlanning.revise(original, GoalPlanning.edit("make that five minutes")!!)!!
        assertEquals(listOf(original.actions[0], original.actions[1], "timer:300"), duration.actions)
        assertNotEquals(original.key, duration.key)
        val volume = GoalPlanning.revise(duration, GoalPlanning.edit("change goal volume to thirty percent")!!)!!
        assertEquals(listOf("volume:30", original.actions[1], "timer:300"), volume.actions)
        assertNotEquals(duration.key, volume.key)
        assertNull(GoalPlanning.revise(plan("plan a task to open Test Music")!!, GoalPlanning.edit("change goal volume to ten percent")!!))
        assertNull(GoalPlanning.revise(plan("prepare my drive")!!, GoalPlanning.edit("make that ten minutes")!!))
    }

    @Test fun oldInstalledGoalStateAndNewVariableLengthPlansSurviveReconstructionWithoutReplacingCurrentApproval() {
        val directory = Files.createTempDirectory("composed-goal-state").toFile()
        fun store() = GoalPlanStore(PersonalStateFile(directory, "goal-plans.json")) { 1_000L }
        try {
            val old = plan("help me focus for ten minutes")!!
            assertTrue(store().propose(old)); assertTrue(store().approve(old))
            assertEquals(old, store().current())
            for (text in listOf("plan a task to open Test Music", "prepare my drive")) {
                val proposed = plan(text)!!
                assertTrue(store().propose(proposed)); assertEquals(proposed, store().pending())
                assertEquals(old, store().current())
                assertFalse(store().approve(old))
                assertTrue(store().discardPending())
            }
            assertEquals(old.actions, store().find(old.key)!!.actions)
        } finally { directory.deleteRecursively() }
    }
}
