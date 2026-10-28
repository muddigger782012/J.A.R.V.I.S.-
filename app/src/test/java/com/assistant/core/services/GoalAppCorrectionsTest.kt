package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import com.assistant.core.workflow.*
import java.nio.file.Files
import java.util.UUID

class GoalAppCorrectionsTest {
    private val music = LearnableDeviceAction("open_app", mapOf("package" to "com.example.music", "label" to "Test Music"))
    private val maps = LearnableDeviceAction("open_app", mapOf("package" to "com.example.maps", "label" to "Test Maps"))
    private val browser = LearnableDeviceAction("open_app", mapOf("package" to "com.example.browser", "label" to "Test Browser"))
    private fun app(action: LearnableDeviceAction) = RoutineSteps.appStep(action)!!
    private fun plan(kind: GoalPlanning.Kind = GoalPlanning.Kind.COMPOSED, vararg actions: String) =
        GoalPlanning.Plan("goal plan ${UUID.randomUUID()}", kind, actions.toList())
    private fun revise(plan: GoalPlanning.Plan, text: String, launch: (String) -> Boolean = { true }) =
        GoalAppCorrections.revise(plan, GoalAppCorrections.explicit(text) ?: GoalAppCorrections.contextual(text)!!,
            { when (it) { "music" -> music; "maps" -> maps; else -> browser } },
            { name -> listOf(music, maps, browser).firstOrNull { it.parameters.getValue("label").equals(name, true) }
                ?.let { it.parameters.getValue("package") to it.parameters.getValue("label") } }, launch)

    @Test fun explicitAppCorrectionsStayInTheLocalGoalControlPath() {
        assertTrue(GoalPlanning.isManagementInput("change goal app to Test Maps"))
        assertTrue(LearnedCommandReviewCommands.isReserved("change goal step two to open Test Maps"))
    }

    @Test fun singleAppCorrectionsFreezeOneReplacementAndPreserveVolumeAndTimer() {
        val original = plan(GoalPlanning.Kind.COMPOSED, "volume:20", app(music), "timer:600")
        val changed = revise(original, "Actually, use my maps app instead!")!!
        assertEquals(listOf("volume:20", app(maps), "timer:600"), changed.actions)
        assertNotEquals(original.key, changed.key)
        assertEquals(original.kind, changed.kind)
        assertTrue(GoalPlanning.valid(changed))
        val exact = revise(changed, "change goal app to Test Browser")!!
        assertEquals(browser, RoutineSteps.appAction(exact.actions[1]))
        assertTrue(GoalPlanning.review(exact).contains("step 2: open Test Browser"))
    }

    @Test fun numberedEditsSupportComposedAndDriveGoalsWithoutGuessingAmongApps() {
        for (kind in listOf(GoalPlanning.Kind.COMPOSED, GoalPlanning.Kind.DRIVE)) {
            val original = plan(kind, "volume:40", app(music), app(maps))
            assertNull(revise(original, "use my browser app instead"))
            for (number in listOf("3", "three", "third")) {
                val changed = revise(original, "change the goal step $number to open Test Browser")!!
                assertEquals(listOf("volume:40", app(music), app(browser)), changed.actions)
            }
            assertNull(revise(original, "change goal step one to open Test Browser"))
            assertNull(revise(original, "change goal step four to open Test Browser"))
        }
        val timer = plan(GoalPlanning.Kind.COMPOSED, app(music), "timer:600")
        assertNull(revise(timer, "change goal step two to open Test Browser"))
    }

    @Test fun musicAndNavigationTemplatesAcceptAReplacementWithoutChangingTheirOtherSteps() {
        for (kind in listOf(GoalPlanning.Kind.MUSIC, GoalPlanning.Kind.NAVIGATION)) {
            val original = plan(kind, "volume:35", app(music))
            val changed = revise(original, "open Test Browser instead")!!
            assertEquals(kind, changed.kind)
            assertEquals(listOf("volume:35", app(browser)), changed.actions)
        }
        assertNull(revise(plan(GoalPlanning.Kind.FOCUS, "volume:20", "timer:600"), "change goal app to Test Browser"))
    }

    @Test fun unavailableAmbiguousOrFuzzyTargetsCannotReplaceAReviewedApp() {
        val original = plan(GoalPlanning.Kind.COMPOSED, app(music))
        assertNull(revise(original, "change goal app to Test Browser") { false })
        assertNull(revise(original, "change goal app to Browser"))
        val edit = GoalAppCorrections.explicit("change goal app to Test Browser")!!
        assertNull(GoalAppCorrections.revise(original, edit, { null }, { null }, { true }))
        assertNull(GoalAppCorrections.revise(original, edit, { null }, { "com.example.other" to "Other" }, { true }))
        assertNull(GoalAppCorrections.revise(original, edit, { null }, { error("Lookup failed") }, { true }))
        assertNull(GoalAppCorrections.revise(original, GoalAppCorrections.contextual("use my maps app instead")!!,
            { LearnableDeviceAction("timer", mapOf("seconds" to "60")) }, { null }, { true }))
    }

    @Test fun everyAppMustRemainLaunchableAndDuplicatePackagesAreRejectedEvenWithDifferentLabels() {
        val original = plan(GoalPlanning.Kind.COMPOSED, app(music), app(maps))
        assertNull(revise(original, "change goal step two to open Test Music"))
        assertNull(revise(original, "change goal step two to open Test Browser") { it != "com.example.music" })
        val edit = GoalAppCorrections.explicit("change goal step two to open Other Music")!!
        assertNull(GoalAppCorrections.revise(original, edit, { null }, { "com.example.music" to "Other Music" }, { true }))
    }

    @Test fun malformedControlsRemainReservedAndCannotBecomePartialAppOrSearchRequests() {
        val original = plan(GoalPlanning.Kind.COMPOSED, app(music))
        for (text in listOf("change goal app", "change goal app to", "change goal step a to open Test Maps",
            "change goal step 0 to open Test Maps", "change goal step two to reboot", "change goal step",
            "change goal app to Test Maps then search the manual", "change goal app to " + "a".repeat(401),
            "use Test Maps instead then search the service manual")) {
            val edit = GoalAppCorrections.explicit(text) ?: GoalAppCorrections.contextual(text)
            assertNotNull(text, edit)
            assertTrue(text, LearnedCommandReviewCommands.isReserved(text))
            assertNull(text, revise(original, text))
        }
        for (text in listOf("what app should I use instead?", "open Test Maps", "yes", "change goal volume to 20 percent")) {
            assertNull(text, GoalAppCorrections.explicit(text)); assertNull(text, GoalAppCorrections.contextual(text))
        }
    }

    @Test fun correctionsCannotReviveStaleOrExpiredReviewsOrChangeTheApprovedDefinition() {
        val directory = Files.createTempDirectory("goal-app-correction").toFile()
        var time = 1_000L
        fun store() = GoalPlanStore(PersonalStateFile(directory, "goals.json")) { time }
        try {
            val approved = plan(GoalPlanning.Kind.COMPOSED, app(music))
            assertTrue(store().propose(approved)); assertTrue(store().approve(approved))
            val original = plan(GoalPlanning.Kind.COMPOSED, app(maps))
            assertTrue(store().propose(original))
            val revised = revise(original, "use my browser app instead")!!
            assertTrue(store().replacePending(original, revised))
            assertFalse(store().approve(original)); assertFalse(store().replacePending(original, null))
            assertEquals(approved, store().current()); assertEquals(revised, store().pending())
            time += 120_001
            assertFalse(store().approve(revised)); assertFalse(store().replacePending(revised, null))
            assertEquals(approved, store().current())
        } finally { directory.deleteRecursively() }
    }

    @Test fun approvedCorrectionExecutesOnlyItsFrozenTargetAndNeverUsesANewerPreference() {
        val directory = Files.createTempDirectory("goal-app-execution").toFile()
        try {
            val goals = GoalPlanStore(PersonalStateFile(directory, "goals.json")) { 1_000L }
            val original = plan(GoalPlanning.Kind.COMPOSED, app(music), "timer:600")
            val revised = revise(original, "use my browser app instead")!!
            goals.propose(original); goals.replacePending(original, revised)
            assertFalse(goals.approve(original)); assertTrue(goals.approve(revised))
            val dispatched = mutableListOf<LearnableDeviceAction?>()
            val checkpoints = InMemoryRoutineWorkflowStore()
            fun engine() = WorkflowEngine(approvedRoutine = goals::find, store = checkpoints, resolvePreferred = { maps }, canLaunchApp = { true },
                executeRoutineStep = { _: RoutineWorkflowCheckpoint, step: RoutineWorkflowStep -> dispatched.add(step.target); RoutineStepOutcome.Requested })
            val saved = engine().planApprovedRoutine(revised.key, "reviewed correction")
            engine().approveRoutine(saved.id, saved.steps.map { it.step }); engine().runApprovedRoutine(saved.id)
            assertEquals(listOf(browser), dispatched)
            engine().resumeApprovedRoutine(saved.id)
            assertEquals(listOf(browser), dispatched)
            assertEquals(RoutineStepStatus.PENDING, checkpoints.load().single().steps[1].status)
        } finally { directory.deleteRecursively() }
    }

    @Test fun appCorrectionEvaluationIsSeparatelyOptInAndFreezesTheSpecificRegressionFixture() {
        val directory = Files.createTempDirectory("goal-app-evaluation").toFile()
        try {
            val store = ImprovementEvaluation(PersonalStateFile(directory, "evaluation.json"), 1, "3.5.1")
            val scenario = ImprovementEvaluation.Scenario.GOAL_APP_CHANGE
            assertFalse(store.record(scenario, ImprovementEvaluation.Outcome.FAILED))
            assertTrue(store.setEnabled(true)); assertTrue(store.record(scenario, ImprovementEvaluation.Outcome.FAILED))
            val brief = store.reply(ImprovementEvaluation.command("prepare improvement proposal for goal app correction")!!)
            assertTrue(brief.contains("GoalAppCorrections")); assertTrue(brief.contains("owner reported failed"))
            assertEquals(scenario, store.snapshot().proposals.single().source.scenario)
            assertFalse(WorkflowDiagnostics(PersonalStateFile(directory, "diagnostics.json"), 1, "3.5.1").snapshot().enabled)
        } finally { directory.deleteRecursively() }
    }
}
