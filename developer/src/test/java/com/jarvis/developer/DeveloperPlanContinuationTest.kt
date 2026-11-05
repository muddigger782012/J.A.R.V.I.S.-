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
