package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperPlanContinuationTest {
    private val path = "developer/src/main/java/com/jarvis/developer/Latency.kt"
    private fun plan(): TaskPlanner.Plan = TaskPlanner.Plan(
        id = "plan-1", task = "Fix Developer latency", baseCommitSha = "a".repeat(40),
        analysisId = "b".repeat(64), scope = listOf(path),
        steps = listOf(
            TaskPlanner.Step("analyze", "Confirm analysis", "Review immutable source", emptyList(),
                emptyList()),
            TaskPlanner.Step("implement-1", "Repair latency", "Edit only the scoped source",
                listOf(path), listOf("analyze")),
            TaskPlanner.Step("validate", "Validate", "Check exact CI", emptyList(), listOf("implement-1")),
            TaskPlanner.Step("review", "Review", "Inspect evidence", emptyList(), listOf("validate"))
        ),
        decisions = listOf(TaskPlanner.Decision(1, null, "Created read-only plan", 1000)),
        revision = 0, paused = false, updatedAtMillis = 1000
    )

    @Test fun incompleteAnalysisCannotBeSilentlyMarkedDone() {
        val suggestion = DeveloperPlanContinuation.suggest(listOf(plan()))
        assertNull(suggestion.packet)
        assertTrue(suggestion.message.contains("Confirm analysis"))
        assertTrue(suggestion.message.contains("No step was marked DONE"))
    }

    @Test fun nextImplementationStageRetainsExactScopeAndDoesNotMutatePlan() {
        val initial = plan()
        val ready = TaskPlanner.transition(initial, "analyze", TaskPlanner.Status.DONE,
            "Owner reviewed source analysis", 1001)
        val suggestion = DeveloperPlanContinuation.suggest(listOf(ready))
        val packet = requireNotNull(suggestion.packet)
        assertEquals(listOf(path), packet.paths)
        assertEquals(initial.baseCommitSha, packet.base)
        assertTrue(packet.task.contains("Immutable base: " + initial.baseCommitSha))
        assertTrue(packet.id.startsWith(initial.id))
        assertTrue(suggestion.message.contains("fresh pinned-source diff"))
        assertEquals(TaskPlanner.Status.PENDING, ready.steps[1].status)
        assertEquals(suggestion, DeveloperPlanContinuation.suggest(listOf(ready)))
    }


    @Test fun previouslyOfferedSourcePacketIsRejectedByNewPlanRevisionOrPause() {
        val analyzed = TaskPlanner.transition(plan(), "analyze", TaskPlanner.Status.DONE,
            "Owner reviewed source analysis", 1001)
        val offered = requireNotNull(DeveloperPlanContinuation.suggest(listOf(analyzed)).packet)
        val edited = TaskPlanner.editStep(analyzed, "implement-1", "Updated scope rationale",
            "Read fresh callers before editing", listOf("analyze"), 1002)
        val fresh = requireNotNull(DeveloperPlanContinuation.suggest(listOf(edited)).packet)
        assertNotEquals("The originally displayed revision cannot authorize the new preview",
            offered, fresh)
        assertNotEquals(offered.id, fresh.id)
        assertEquals(analyzed.baseCommitSha, fresh.base)
        val paused = TaskPlanner.pause(analyzed, true, 1002)
        assertNull(DeveloperPlanContinuation.suggest(listOf(paused)).packet)
    }

    @Test fun multiStepPlanPreparesOneCumulativeReviewedSourceChange() {
        val second = "developer/src/main/java/com/jarvis/developer/Network.kt"
        val baseline = plan()
        val combined = baseline.copy(scope = listOf(path, second), steps = listOf(
            baseline.steps[0],
            baseline.steps[1],
            TaskPlanner.Step("implement-2", "Repair network", "Edit network source",
                listOf(second), listOf("implement-1")),
            TaskPlanner.Step("validate", "Validate", "Build entire source together",
                emptyList(), listOf("implement-1", "implement-2")),
            TaskPlanner.Step("review", "Review", "Inspect source and CI",
                emptyList(), listOf("validate"))
        ))
        TaskPlanner.validate(combined)
        val analyzed = TaskPlanner.transition(combined, "analyze", TaskPlanner.Status.DONE,
            "Owner reviewed complete scope", 1001)
        val suggestion = DeveloperPlanContinuation.suggest(listOf(analyzed))
        val packet = requireNotNull(suggestion.packet)
        assertEquals(listOf(path, second).sorted(), packet.paths)
        assertTrue(packet.task.contains("ONE cumulative source proposal"))
        assertTrue(packet.task.contains("implement-2"))
        assertTrue(suggestion.message.contains("cumulative"))
        assertEquals(TaskPlanner.Status.PENDING, analyzed.steps[1].status)
        assertEquals(TaskPlanner.Status.PENDING, analyzed.steps[2].status)
        assertEquals(suggestion, DeveloperPlanContinuation.suggest(listOf(analyzed)))
        // A manually reported partial implementation cannot be mistaken for
        // the exact, whole-scope reviewed/CI-verified commit.
        val partial = TaskPlanner.transition(analyzed, "implement-1", TaskPlanner.Status.DONE,
            "Owner-reported partial source change (not CI proof)", 1002)
        val blocked = DeveloperPlanContinuation.suggest(listOf(partial))
        assertNull(blocked.packet)
        assertTrue(blocked.message.contains("partial source checkpoints"))
    }

    @Test fun pausedAndInterruptedPlansNeverAutomaticallyContinue() {
        val ready = TaskPlanner.transition(plan(), "analyze", TaskPlanner.Status.DONE, "Analysis checked", 1001)
        val paused = TaskPlanner.pause(ready, true, 1002)
        assertNull(DeveloperPlanContinuation.suggest(listOf(paused)).packet)
        assertTrue(DeveloperPlanContinuation.suggest(listOf(paused)).message.contains("paused"))
        val running = TaskPlanner.transition(ready, "implement-1", TaskPlanner.Status.RUNNING, "", 1002)
        assertNull(DeveloperPlanContinuation.suggest(listOf(running)).packet)
        assertTrue(DeveloperPlanContinuation.suggest(listOf(running)).message.contains("RUNNING"))
    }

    @Test fun emptyOrMalformedPlansFailClosed() {
        assertNull(DeveloperPlanContinuation.suggest(emptyList()).packet)
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperPlanContinuation.suggest(listOf(plan().copy(baseCommitSha = "invalid")))
        }
    }

    @Test fun commandDoesNotTreatNaturalLanguageAsAnImplicitInstruction() {
        assertTrue(DeveloperPlanContinueChatCommand.isRequest("Continue Developer plan"))
        assertTrue(DeveloperPlanContinueChatCommand.isRequest("Prepare next Developer step!"))
        assertFalse(DeveloperPlanContinueChatCommand.isRequest("Continue developer plan and publish"))
        assertFalse(DeveloperPlanContinueChatCommand.isRequest("Continue Personal Jarvis plan"))
        assertFalse(DeveloperPlanContinueChatCommand.isRequest("Can you describe my plan?"))
    }
}
