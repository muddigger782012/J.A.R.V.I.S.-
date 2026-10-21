package com.assistant.core

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.engine.*
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import com.assistant.core.services.*
import com.assistant.core.storage.ActionRepository
import com.assistant.core.storage.AuditRepository
import com.assistant.core.storage.Database
import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.workflow.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable storage and counting execution. The adapter case posts synthetic notifications and
 * cancels its own alarms; no real contact, message, user journal or external target is used. */
@RunWith(AndroidJUnit4::class)
class PersonalFoundationAcceptanceTest {
    private lateinit var context: IsolatedContext
    private lateinit var database: Database
    private var executions = 0

    @Before fun prepare() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        database = Database(context)
    }

    @After fun cleanup() {
        database.close()
        context.cleanup()
    }

    @Test fun unfamiliarObjectivesAndCommandQuestionsStayConversational() {
        val service = assistant()
        for (input in listOf("Help me prepare for tomorrow", "What happens when you reboot?",
            "How do I create a project?", "What does HTTP status mean?", "Tell me about time travel",
            "How do I schedule an appointment?", "What does read my screen mean?")) {
            assertTrue(input, service.handleUserInput(input).needsInterpretation)
        }
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
        assertTrue(service.getConversationSnapshot().isEmpty())
    }

    @Test fun actualAndroidStorageRestoresDialogueAndClearDoesNotRepopulateIt() {
        val service = assistant()
        service.handleUserInput("hello")
        service.handleUserInput("thanks")
        val restored = assistant()
        assertTrue(restored.getConversationSnapshot().contains("You: hello"))
        assertTrue(restored.handleUserInput("clear conversation memory").text.startsWith("Cleared"))
        assertTrue(restored.getConversationSnapshot().isEmpty())
        assertTrue(service.getConversationSnapshot().isEmpty())
        assertTrue(assistant().getConversationSnapshot().isEmpty())
    }

    @Test fun correctedDialogueSurvivesButRestoredApprovalCannotAuthorizeAnAction() {
        val memory = ConversationMemory(context)
        assertTrue(memory.record("Use the first project", "Which project did you mean?"))
        assertTrue(memory.record("Correction, use the second project", "The second project is the current topic."))
        assertTrue(memory.record("reboot my phone", "Confirm the reboot?"))
        val restored = assistant()
        val history = restored.getConversationSnapshot()
        assertTrue(history.indexOf("Use the first project") < history.indexOf("Correction, use the second project"))
        assertTrue(restored.handleUserInput("yes").needsInterpretation)
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun improvementAndWorkflowReviewAreLocalAndDoNotPolluteMemory() {
        val service = assistant()
        for (input in listOf("improvement status", "review improvement proposals", "routine execution status", "show conversation memory")) {
            assertFalse(input, service.handleUserInput(input).needsInterpretation)
        }
        assertEquals(0, executions)
        assertTrue(service.getConversationSnapshot().isEmpty())
    }

    @Test fun interruptedRoutineRestoresThroughTheActualServiceWithoutReplayingAnAction() {
        val access = LocalRoutineWorkflowAccess(context)
        val engine = WorkflowEngine(approvedRoutine = { ApprovedRoutineRegistry.Routine("focus time", listOf("timer:300")) }, store = access.store)
        val plan = engine.planApprovedRoutine("focus time", "first-turn")
        val ready = engine.approveRoutine(plan.id, plan.steps.map { it.step })
        assertTrue(access.store.save(ready.copy(status = RoutineWorkflowStatus.RUNNING,
            steps = ready.steps.map { it.copy(status = RoutineStepStatus.RUNNING, attempts = 1) })))
        val service = assistant()
        assertTrue(service.handleUserInput("routine execution status").text.contains("effect uncertain"))
        service.handleUserInput("resume routine execution")
        service.handleUserInput("focus time")
        assertEquals(0, context.activityRequests)
        assertEquals(0, executions)
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, LocalRoutineWorkflowAccess(context).store.load().single().status)
        assertTrue(assistant().handleUserInput("the routine worked").text.contains("you confirmed the result"))
        assertEquals(RoutineWorkflowStatus.USER_CONFIRMED, LocalRoutineWorkflowAccess(context).store.load().single().status)
        assertTrue(service.getConversationSnapshot().isEmpty())
        // Existing corrupt state is preserved, never replaced with an empty/repeatable journal.
        val journal = File(context.noBackupFilesDir, "routine-execution/checkpoints.json")
        journal.writeText("invalid checkpoint")
        assertTrue(assistant().handleUserInput("routine execution status").text.contains("could not be accessed safely"))
        assertEquals("invalid checkpoint", journal.readText())
    }

    @Test fun separateAndroidStorageInstancesCanCancelAnInFlightRoutine() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<RoutineExecutionCoordinator.Result>()
        val failure = AtomicReference<Throwable>()
        var calls = 0
        fun coordinator() = RoutineExecutionCoordinator(LocalRoutineWorkflowAccess(context),
            { ApprovedRoutineRegistry.Routine("focus time", listOf("volume:25", "app_update")) },
            { _, _ -> calls++; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); RoutineStepOutcome.Verified })
        val worker = Thread { try { result.set(coordinator().invoke("focus time", "voice-turn")) } catch (e: Throwable) { failure.set(e) } }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val chat = coordinator()
            assertTrue(chat.status().busy)
            assertEquals(RoutineStepStatus.RUNNING, chat.status().checkpoint!!.steps.first().status)
            assertTrue(chat.invoke("focus time", "chat-turn").busy)
            assertEquals(RoutineWorkflowStatus.CANCELLING, chat.cancel().checkpoint!!.status)
        } finally { release.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(1, calls)
        assertEquals(RoutineWorkflowStatus.CANCELLED, result.get().checkpoint!!.status)
        assertEquals(RoutineWorkflowStatus.CANCELLED, coordinator().resume().checkpoint!!.status)
        assertEquals(1, calls)
        val next = coordinator().invoke("focus time", "next-turn").checkpoint!!
        val storage = LocalRoutineWorkflowAccess(context)
        assertTrue(storage.requestCancellation(next.id))
        assertTrue(storage.requestCancellation(result.get().checkpoint!!.id))
        assertTrue(storage.cancellationRequested(next.id))
    }

    @Test fun goalClarificationReviewAndBareYesDoNotDispatchAndFailedReplacementCannotReviveAnOldPlan() {
        val service = assistant()
        assertTrue(service.handleUserInput("help me focus").text.contains("How long"))
        val review = assistant().handleUserInput("twenty five minutes").text
        assertTrue(review.contains("approve goal plan"))
        assertTrue(review.contains("25%"))
        assertTrue(service.getConversationSnapshot().contains("twenty five minutes"))
        assertTrue(service.getConversationSnapshot().contains("approve goal plan"))
        val answered = CountDownLatch(1)
        val answer = AtomicReference<HybridAssistantReply>()
        service.handleUserInputAsync("yes") { answer.set(it); answered.countDown() }
        assertTrue(answered.await(3, TimeUnit.SECONDS))
        assertFalse(answer.get().needsInterpretation)
        assertTrue(answer.get().text.contains("approve goal plan"))
        assertNotNull(GoalPlanStore(context).pending())
        service.handleUserInput("cancel goal plan")
        assertTrue(assistant().handleUserInput("approve goal plan").text.contains("No current goal plan"))
        service.handleUserInput("help me focus for five minutes")
        service.handleUserInput("prepare a music session using JarvisMissingTestApp")
        assertTrue(assistant().handleUserInput("approve goal plan").text.contains("No current goal plan"))
        assertNull(GoalPlanStore(context).pending())
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun optInEvaluationAndManualBaselineComparisonSurviveReconstructionWithoutCollectingDialogue() {
        val service = assistant()
        assertTrue(service.handleUserInput("review improvement evaluation").text.contains("recording: off"))
        service.handleUserInput("enable improvement evaluation")
        service.handleUserInput("record improvement feedback for correction handling as passed")
        service.handleUserInput("save improvement baseline")
        val restored = assistant()
        restored.handleUserInput("record improvement feedback for correction handling as failed")
        assertTrue(restored.handleUserInput("compare improvement evaluation").text.contains("reported regression"))
        assertTrue(restored.handleUserInput("prepare improvement proposal for correction handling").text.contains("Developer change brief"))
        restored.handleUserInput("clear improvement evaluation")
        assertFalse(ImprovementEvaluation(context).snapshot().enabled)
        assertTrue(ImprovementEvaluation(context).snapshot().samples.isEmpty())
        assertTrue(service.getConversationSnapshot().isEmpty())
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun approvalFromAnotherAssistantCannotRunAChangedOrUnreviewedGoal() {
        val voice = assistant()
        voice.handleUserInput("help me focus for 25 minutes")
        val chat = assistant()
        chat.handleUserInput("help me focus for five minutes")
        val current = GoalPlanStore(context).pending()!!
        assertEquals("timer:300", current.actions.last())
        assertTrue(voice.handleUserInput("approve goal plan").text.contains("current goal plan"))
        assertEquals(current, GoalPlanStore(context).pending())
        assertTrue(assistant().handleUserInput("approve goal plan").text.contains("before approving again"))
        assertEquals(current, GoalPlanStore(context).pending())
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun completedHistoryCleanupRequiresFreshLocalReviewAndRetainsUncertainActionsAndDedupe() {
        val access = LocalRoutineWorkflowAccess(context)
        fun checkpoint(index: Int) = RoutineWorkflowCheckpoint("history-$index", setOf("history-turn-$index"), "focus time",
            listOf(RoutineStepCheckpoint(RoutineWorkflowStep(0, "volume_down"), RoutineStepStatus.VERIFIED, 1)),
            RoutineWorkflowStatus.VERIFIED, approved = true)
        access.store.save(checkpoint(0))
        val uncertain = checkpoint(1).copy(status = RoutineWorkflowStatus.CANCELLED, cancellationRequested = true,
            steps = checkpoint(1).steps.map { it.copy(status = RoutineStepStatus.AMBIGUOUS) })
        access.store.save(uncertain)
        access.store.save(checkpoint(2))
        val chat = assistant()
        assertTrue(chat.handleUserInput("review task history").text.contains("1 older completed"))
        assertTrue(assistant().handleUserInput("clear reviewed task history").text.contains("before clearing"))
        chat.handleUserInput("review improvement evaluation")
        assertTrue(chat.handleUserInput("clear reviewed task history").text.contains("before clearing"))
        assertFalse(chat.handleUserInput("clear reviewed task history then reboot").needsInterpretation)
        assertEquals(3, access.store.load().size)
        chat.handleUserInput("review task history")
        assertTrue(chat.handleUserInput("clear reviewed task history").text.contains("Cleared 1"))
        val restored = LocalRoutineWorkflowAccess(context).store
        assertEquals(listOf(uncertain, checkpoint(2)), restored.load())
        assertTrue(restored.isRetiredRequest("history-turn-0"))
        assertFalse(restored.save(checkpoint(0)))
        assertTrue(chat.getConversationSnapshot().isEmpty())
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun savedImprovementBriefsSurviveCorrectionAndDismissalWithoutLosingExistingSuggestions() {
        val chat = assistant()
        chat.handleUserInput("enable improvement evaluation")
        chat.handleUserInput("record improvement feedback for update status as failed")
        assertTrue(chat.handleUserInput("prepare improvement proposal for update status").text.contains("saved locally"))
        val restored = assistant()
        assertTrue(restored.handleUserInput("review improvement proposals").text.contains("Saved local improvement proposals"))
        restored.handleUserInput("record improvement feedback for update status as passed")
        val brief = restored.handleUserInput("review improvement proposal for update status").text
        assertTrue(brief.contains("owner reported failed"))
        assertTrue(brief.contains("newer report supersedes this source (passed)"))
        restored.handleUserInput("dismiss improvement proposal for update status")
        assertEquals(ImprovementEvaluation.ProposalStatus.DISMISSED, ImprovementEvaluation(context).snapshot().proposals.single().status)
        restored.handleUserInput("clear improvement proposals")
        val evaluation = ImprovementEvaluation(context).snapshot()
        assertTrue(evaluation.proposals.isEmpty())
        assertTrue(evaluation.enabled)
        assertEquals(ImprovementEvaluation.Outcome.PASSED, evaluation.samples.single().outcome)
        assertTrue(chat.getConversationSnapshot().isEmpty())
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun goalCorrectionsRequireTheRevisedReviewAndInvalidCorrectionCannotReviveTheOldPlan() {
        val voice = assistant()
        voice.handleUserInput("help me focus for 25 minutes at 20 percent volume")
        val original = GoalPlanStore(context).pending()!!
        val chat = assistant()
        assertTrue(chat.handleUserInput("actually, make that ten minutes").text.contains("replaces the earlier pending review"))
        val duration = GoalPlanStore(context).pending()!!
        assertEquals(listOf("volume:20", "timer:600"), duration.actions)
        assertNotEquals(original.key, duration.key)
        chat.handleUserInput("change goal volume to zero percent")
        val revised = GoalPlanStore(context).pending()!!
        assertEquals(listOf("volume:0", "timer:600"), revised.actions)
        assertFalse(GoalPlanStore(context).approve(original))
        assertTrue(voice.handleUserInput("approve goal plan").text.contains("before approving again"))
        assertEquals(revised, GoalPlanStore(context).pending())
        chat.handleUserInput("make that ten minutes then reboot")
        assertNull(GoalPlanStore(context).pending())
        assertTrue(voice.handleUserInput("approve goal plan").text.contains("No current goal plan"))
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun localSelfChecksUseIsolatedStateAndKeepManualEvidenceAndRealDeviceActionsSeparate() {
        val chat = assistant()
        assertTrue(chat.handleUserInput("run local foundation checks").text.contains("No checks were run"))
        chat.handleUserInput("enable improvement evaluation")
        chat.handleUserInput("record improvement feedback for routine recovery as unclear")
        val before = ImprovementEvaluation(context).snapshot()
        val result = chat.handleUserInput("run local foundation checks")
        assertFalse(result.needsInterpretation)
        assertTrue(result.text.contains("counter dispatchers"))
        val saved = ImprovementEvaluation(context).snapshot()
        assertTrue(saved.localRun!!.results.all { it.outcome == LocalFoundationChecks.Outcome.PASSED })
        assertEquals(before.samples, saved.samples)
        assertTrue(LocalRoutineWorkflowAccess(context).store.load().isEmpty())
        chat.handleUserInput("save local check baseline")
        val restored = assistant()
        assertTrue(restored.handleUserInput("compare local foundation checks").text.contains("passed -> passed"))
        restored.handleUserInput("disable improvement evaluation")
        assertTrue(restored.handleUserInput("run local foundation checks").text.contains("No checks were run"))
        assertFalse(restored.handleUserInput("run local foundation checks then reboot").needsInterpretation)
        val localReply = AtomicReference<HybridAssistantReply>()
        restored.handleUserInputAsync("run local foundation checks then find a service manual") { localReply.set(it) }
        assertNotNull(localReply.get())
        assertFalse(localReply.get().needsInterpretation)
        restored.handleUserInput("clear local foundation checks")
        assertNull(ImprovementEvaluation(context).snapshot().localRun)
        assertEquals(before.samples, ImprovementEvaluation(context).snapshot().samples)
        assertTrue(chat.getConversationSnapshot().isEmpty())
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun reminderReviewAndBareConfirmationCannotRegisterOrReuseAnUnseenReminderPlan() {
        val voice = assistant()
        assertTrue(voice.handleUserInput("prepare a reminder in ten minutes to Open Maps then call Mom").text.contains("Review local reminder"))
        val manager = PersonalReminderRuntime.manager(context)
        val original = manager.pending()!!
        assertTrue(manager.records().isEmpty())
        assertTrue(voice.handleUserInput("yes").text.contains("bare confirmation"))
        val chat = assistant()
        chat.handleUserInput("prepare a reminder in five minutes to Check the door")
        val current = manager.pending()!!
        assertNotEquals(original.id, current.id)
        assertTrue(voice.handleUserInput("approve reminder").text.contains("before approving again"))
        assertEquals(current, manager.pending())
        assertTrue(manager.records().isEmpty())
        assertFalse(voice.handleUserInput("approve reminder then reboot").needsInterpretation)
        chat.handleUserInput("cancel reminder plan")
        assertNull(manager.pending())
        val response = AtomicReference<HybridAssistantReply>()
        chat.handleUserInputAsync("remind me in ten minutes then find a service manual to reboot") { response.set(it) }
        assertNotNull(response.get())
        assertFalse(response.get().needsInterpretation)
        assertTrue(manager.records().isEmpty())
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun androidReminderAdaptersRegisterCancelAndReadBackSyntheticNotificationsWithIsolatedState() {
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val platform = PersonalReminderRuntime.platform(context)
        val managerNotifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val ids = mutableListOf<String>()
        var time = System.currentTimeMillis()
        fun manager() = PersonalReminders(PersonalStateFile(context, "adapter-reminders.json"), platform) { time }
        try {
            val info = context.packageManager.getReceiverInfo(android.content.ComponentName(context, PersonalReminderReceiver::class.java), 0)
            assertFalse(info.exported)
            assertTrue(platform.notificationsAvailable())
            val direct = assistant().handleUserInput("remind me in ten minutes to Synthetic direct reminder")
            assertTrue(direct.text.contains("Registered reminder"))
            val directManager = PersonalReminderRuntime.manager(context)
            val directRecord = directManager.records().single()
            ids.add(directRecord.plan.id)
            directManager.cancel(directRecord.number)
            val cancelledPlan = manager().prepare("Synthetic cancelled reminder", 600)
            ids.add(cancelledPlan.id)
            val first = manager().approve(cancelledPlan).record!!
            assertEquals(PersonalReminders.Status.SCHEDULED, first.status)
            manager().restore()
            assertEquals(PersonalReminders.Status.CANCELLED, manager().cancel(first.number).record!!.status)
            time = cancelledPlan.dueAt + 1
            manager().deliver(cancelledPlan.id)
            assertFalse(managerNotifications.activeNotifications.any { it.tag == cancelledPlan.id })
            val postedPlan = manager().prepare("Synthetic foundation notification", 600)
            ids.add(postedPlan.id)
            assertEquals(PersonalReminders.Status.SCHEDULED, manager().approve(postedPlan).record!!.status)
            time = postedPlan.dueAt + 1
            assertEquals(PersonalReminders.Status.POSTED, manager().deliver(postedPlan.id).record!!.status)
            assertTrue(managerNotifications.activeNotifications.any { it.tag == postedPlan.id })
            manager().deliver(postedPlan.id)
            assertEquals(1, managerNotifications.activeNotifications.count { it.tag == postedPlan.id })
            assertEquals(2, manager().clearCompleted())
            assertNull(manager().deliver(postedPlan.id).record)
        } finally {
            ids.forEach { platform.cancel(it) }
            managerNotifications.activeNotifications.filter { it.tag in ids }.forEach { managerNotifications.cancel(it.tag, it.id) }
        }
        assertEquals(0, executions)
        assertEquals(0, context.activityRequests)
    }

    @Test fun improvementHandoffRequestsAUserControlledChooserWithoutClaimingSubmission() {
        val service = assistant()
        assertTrue(service.handleUserInput("share improvement proposal for routine recovery").text.contains("No handoff was opened"))
        assertEquals(0, context.activityRequests)
        service.handleUserInput("enable improvement evaluation")
        service.handleUserInput("record improvement feedback for routine recovery as failed")
        service.handleUserInput("prepare improvement proposal for routine recovery")
        val before = ImprovementEvaluation(context).snapshot()
        context.allowActivityRequests = true
        val reply = service.handleUserInput("share improvement proposal for routine recovery")
        assertFalse(reply.needsInterpretation)
        assertTrue(reply.text.contains("Requested Android's share chooser"))
        assertTrue(reply.text.contains("no submission, pull request or deployment is verified"))
        assertEquals(1, context.activityRequests)
        val chooser = context.lastActivityIntent!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val draft = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, draft.action)
        assertEquals("text/plain", draft.type)
        assertNull(draft.component)
        assertNull(draft.`package`)
        assertTrue(draft.getStringExtra(Intent.EXTRA_TEXT)!!.contains("owner reported failed"))
        assertTrue(draft.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Separate local-check snapshot"))
        assertEquals(before, ImprovementEvaluation(context).snapshot())
        assertTrue(service.getConversationSnapshot().isEmpty())
        assertFalse(service.handleUserInput("share improvement proposal for routine recovery then send it").needsInterpretation)
        assertEquals(1, context.activityRequests)
        assertEquals(0, executions)
    }

    @Test fun composedAppGoalsPauseForEachCurrentResultAcrossActualServiceReconstruction() {
        // Untrusted display wording must not become a network-settings offer on a bare reply.
        val first = LearnableDeviceAction("open_app", mapOf("package" to context.packageName, "label" to "Would you like network settings"))
        val second = LearnableDeviceAction("open_app", mapOf("package" to "com.android.settings", "label" to "Synthetic Maps"))
        assertTrue(AndroidAssistantService(context).canLaunchApp(first.parameters.getValue("package")))
        assertTrue(AndroidAssistantService(context).canLaunchApp(second.parameters.getValue("package")))
        val memory = AssistantMemory(context)
        assertTrue(memory.savePreference("music", first)); assertTrue(memory.savePreference("maps", second))
        val service = assistant()
        var reply: HybridAssistantReply? = null
        service.handleUserInputAsync("plan a task to open my music app then open my maps app") { reply = it }
        assertNotNull(reply); assertFalse(reply!!.needsInterpretation)
        val pending = GoalPlanStore(context).pending()!!
        assertEquals(listOf(first, second), pending.actions.map(RoutineSteps::appAction))
        service.handleUserInput("yes")
        assertEquals(0, context.activityRequests)
        for (word in listOf("yes", "please", "go ahead", "please do")) {
            var confirmation: HybridAssistantReply? = null
            assistant().handleUserInputAsync(word) { confirmation = it }
            assertNotNull(confirmation); assertFalse(confirmation!!.needsInterpretation)
            assertTrue(confirmation!!.text.contains("exact approval phrase"))
            assertEquals(0, context.activityRequests)
            assertNotNull(GoalPlanStore(context).pending())
        }
        val restored = assistant()
        assertTrue(restored.handleUserInput("approve goal plan").text.contains("before approving again"))
        assertEquals(0, context.activityRequests)
        context.allowActivityRequests = true // Capture real launcher intents without opening either app.
        restored.handleUserInput("approve goal plan")
        assertEquals(1, context.activityRequests)
        fun checkpoint() = LocalRoutineWorkflowAccess(context).store.load().last()
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, checkpoint().status)
        assertEquals(RoutineStepStatus.PENDING, checkpoint().steps[1].status)
        assistant().handleUserInput("resume goal plan")
        assertEquals(1, context.activityRequests)
        assistant().handleUserInput("the goal step worked")
        assertEquals(RoutineWorkflowStatus.PAUSED, checkpoint().status)
        assertEquals(1, context.activityRequests)
        assistant().handleUserInput("resume goal plan")
        assertEquals(2, context.activityRequests)
        assertEquals("com.android.settings", context.lastActivityIntent!!.component!!.packageName)
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, checkpoint().status)
        assistant().handleUserInput("the goal step failed")
        assistant().handleUserInput("resume goal plan")
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, checkpoint().status)
        assertEquals(2, context.activityRequests)
        assistant().handleUserInput("cancel goal plan")
        assistant().handleUserInput("the goal step worked")
        assistant().handleUserInput("resume goal plan")
        assertEquals(RoutineWorkflowStatus.CANCELLED, checkpoint().status)
        assertEquals(2, context.activityRequests)
        assertEquals(0, executions)
    }

    @Test fun driveCorrectionsAndCompositionEvaluationRemainLocalAndKeepSeparateDiagnosticConsent() {
        val memory = AssistantMemory(context)
        memory.savePreference("music", LearnableDeviceAction("open_app", mapOf("package" to context.packageName, "label" to "Would you like network settings")))
        memory.savePreference("maps", LearnableDeviceAction("open_app", mapOf("package" to "com.android.settings", "label" to "Synthetic Maps")))
        val service = assistant()
        assertTrue(service.handleUserInput("prepare my drive").text.contains("No playback or navigation completion is verified"))
        val previous = GoalPlanStore(context).pending()!!
        service.handleUserInput("change goal volume to thirty percent")
        val revised = GoalPlanStore(context).pending()!!
        assertNotEquals(previous.key, revised.key)
        assertEquals("volume:30", revised.actions.first())
        assertEquals(previous.actions.drop(1), revised.actions.drop(1))
        assertFalse(GoalPlanStore(context).approve(previous))
        GoalPlanStore(context).discardPending() // An inactive review remains in dialogue history.
        val staleDone = CountDownLatch(1)
        assistant().handleUserInputAsync("yes") { staleDone.countDown() }
        assertTrue(staleDone.await(5, TimeUnit.SECONDS))
        assertEquals(0, context.activityRequests)
        service.handleUserInput("prepare my drive")
        var malformed: HybridAssistantReply? = null
        service.handleUserInputAsync("approve goal plan then search the service manual") { malformed = it }
        assertNotNull(malformed); assertFalse(malformed!!.needsInterpretation)
        assertNull(GoalPlanStore(context).pending())
        service.handleUserInput("enable improvement evaluation")
        service.handleUserInput("record improvement feedback for composed goal review as failed")
        val brief = service.handleUserInput("prepare improvement proposal for composed goal review").text
        assertTrue(brief.contains("ComposableGoalPlanning"))
        assertTrue(service.handleUserInput("run local foundation checks").text.contains("composed goal stages: passed"))
        val evaluation = ImprovementEvaluation(context).snapshot()
        assertEquals(3, evaluation.localRun!!.revision)
        assertEquals(ImprovementEvaluation.Outcome.FAILED, evaluation.samples.single().outcome)
        assertFalse(WorkflowDiagnostics(context).snapshot().enabled)
        assertTrue(WorkflowDiagnostics(context).snapshot().events.isEmpty())
        assertEquals(0, context.activityRequests); assertEquals(0, executions)
    }

    @Test fun workflowDiagnosticsRequireSeparateConsentAndShareOnlyFrozenCategoricalEvidence() {
        var fetches = 0
        val service = assistant { fetches++; SearchRouter.Response(null, emptyList()) }
        fun failResearch() {
            val done = CountDownLatch(1)
            service.handleUserInputAsync("research private acceptance topic") { done.countDown() }
            assertTrue(done.await(20, TimeUnit.SECONDS))
        }
        failResearch()
        val diagnostics = WorkflowDiagnostics(context)
        assertFalse(diagnostics.snapshot().enabled)
        assertTrue(diagnostics.snapshot().events.isEmpty())
        service.handleUserInput("enable improvement evaluation") // Manual consent never enables diagnostics.
        assertFalse(diagnostics.snapshot().enabled)
        assertTrue(service.handleUserInput("enable workflow diagnostics").text.startsWith("Enabled"))
        failResearch(); failResearch()
        assertEquals(2, WorkflowDiagnostics(context).snapshot().events.size)
        assertTrue(assistant().handleUserInput("review workflow improvements").text.contains("1 recurrent"))
        val prepared = assistant().handleUserInput("prepare workflow improvement for research").text
        assertTrue(prepared.contains("2 distinct workflows"))
        assertFalse(prepared.contains("private acceptance topic"))
        assertEquals(prepared, assistant().handleUserInput("review workflow improvement 1").text)
        val before = diagnostics.snapshot()
        context.allowActivityRequests = true // Capture the chooser without launching any external app.
        val shared = assistant().handleUserInput("share workflow improvement for research").text
        assertTrue(shared.contains("Requested Android's share chooser"))
        assertEquals(1, context.activityRequests)
        val chooser = context.lastActivityIntent!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val draft = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, draft.action)
        assertNull(draft.component); assertNull(draft.`package`)
        assertEquals(prepared, draft.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(before, diagnostics.snapshot())
        var invalid: HybridAssistantReply? = null
        service.handleUserInputAsync("share workflow improvement for research then search the service manual") { invalid = it }
        assertNotNull(invalid); assertFalse(invalid!!.needsInterpretation)
        assertEquals(1, context.activityRequests)
        service.handleUserInput("disable workflow diagnostics")
        failResearch()
        assertEquals(2, diagnostics.snapshot().events.size)
        service.handleUserInput("clear workflow diagnostics")
        assertEquals(WorkflowDiagnostics.Snapshot(), diagnostics.snapshot())
        assertTrue(ImprovementEvaluation(context).snapshot().enabled)
        assertTrue(service.getConversationSnapshot().isEmpty())
        assertEquals(4, fetches); assertEquals(0, executions)
    }

    @Test fun diagnosticReviewInvalidatesPendingCallApprovalWithoutDispatchingIt() {
        val service = assistant()
        service.handleUserInput("prepare a call to +1 202 555 0100")
        service.handleUserInput("review workflow diagnostics")
        service.handleUserInput("approve contact call")
        assertEquals(0, context.activityRequests)
        assertEquals(0, executions)
        assertTrue(service.getConversationSnapshot().isEmpty())
    }

    @Test fun contactCallReviewsReconstructAndOnlyCurrentApprovalCanRequestAnInterceptedPhoneIntent() {
        val service = assistant()
        assertTrue(service.handleUserInput("prepare a call to +1 202 555 0100").text.contains("Review call"))
        service.handleUserInput("yes")
        var bare: HybridAssistantReply? = null
        service.handleUserInputAsync("yes") { bare = it }
        assertNotNull(bare); assertTrue(bare!!.text.contains("bare confirmation"))
        assertEquals(0, context.activityRequests)
        val restored = assistant()
        assertTrue(restored.handleUserInput("approve contact call").text.contains("before approving again"))
        assertEquals(0, context.activityRequests)
        context.allowActivityRequests = true // Captures the intent; never delegates to Android or places a call.
        assertTrue(restored.handleUserInput("approve contact call").text.contains("Requested"))
        assertEquals(1, context.activityRequests)
        assertTrue(context.lastActivityIntent!!.action in setOf(Intent.ACTION_CALL, Intent.ACTION_DIAL))
        assertEquals("tel", context.lastActivityIntent!!.data!!.scheme)
        restored.handleUserInput("approve contact call")
        var malformed: HybridAssistantReply? = null
        restored.handleUserInputAsync("approve contact call then search the service manual") { malformed = it }
        assertNotNull(malformed); assertFalse(malformed!!.needsInterpretation)
        assertEquals(1, context.activityRequests)
        assertTrue(restored.getConversationSnapshot().isEmpty())
        assertEquals(0, executions)
    }

    @Test fun syntheticResearchSavesLinkedExcerptsAndActualServiceRestoresThemOfflineWithoutAnotherFetch() {
        var fetches = 0
        val service = assistant { fetches++; SearchRouter.Response("synthetic", listOf(SearchRouter.Result("Synthetic guide", "An isolated source excerpt.", "https://example.com/guide"))) }
        val done = CountDownLatch(1); val result = AtomicReference<HybridAssistantReply>()
        service.handleUserInputAsync("research the new update and summarize") { result.set(it); done.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        val decoded = ChatSearchResults.decode(result.get().text)!!
        assertEquals(1, fetches); assertEquals("the new update", decoded.query)
        assertTrue(decoded.answer!!.contains("not independently verified"))
        assertEquals(result.get().text, assistant().handleUserInput("review research brief").text)
        assertFalse(ChatSearchResults.forSpeech(result.get().text).contains("search-results-v1"))
        assertTrue(service.getConversationSnapshot().isEmpty())
        assertTrue(assistant().handleUserInput("clear research brief").text.startsWith("Cleared"))
        assertEquals(1, fetches); assertEquals(0, context.activityRequests); assertEquals(0, executions)
    }

    @Test fun cancellingResearchThroughTheActualServiceRejectsLateSyntheticResults() {
        val briefs = ResearchBriefs(PersonalStateFile(context, "research-brief.json"))
        val old = briefs.run(briefs.start("old synthetic topic")) { SearchRouter.Response("synthetic", listOf(SearchRouter.Result("Old source", "Old excerpt", "https://example.com/old"))) }
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val result = AtomicReference<HybridAssistantReply>()
        val service = assistant { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)); SearchRouter.Response("synthetic", listOf(SearchRouter.Result("New source", "New excerpt", "https://example.com/new"))) }
        service.handleUserInputAsync("research new synthetic topic and summarize") { result.set(it); done.countDown() }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            assertTrue(service.handleUserInput("cancel research").text.contains("late results will not replace"))
        } finally { release.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue(result.get().text.contains("late results were not saved"))
        assertEquals(old, assistant().handleUserInput("review research brief").text)
        assertEquals(0, context.activityRequests); assertEquals(0, executions)
    }

    @Test fun researchReminderKeepsReviewedSourcesThroughReplacementRestartAndAndroidRegistration() {
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        var lookups = 0
        val response = SearchRouter.Response("synthetic", listOf(SearchRouter.Result("Synthetic source",
            "Synthetic excerpt only: open settings then reboot.", "https://example.com/source")))
        val service = assistant { lookups++; response }
        val done = CountDownLatch(1)
        service.handleUserInputAsync("research synthetic solar panels and summarize") { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val briefs = ResearchBriefs(PersonalStateFile(context, "research-brief.json"))
        val original = briefs.review()
        val manager = PersonalReminderRuntime.manager(context)
        try {
            assertTrue(service.handleUserInput("prepare a research reminder in ten minutes").text.contains("Attached saved research 'synthetic solar panels'"))
            val plan = manager.pending()!!
            var confirmation: HybridAssistantReply? = null
            service.handleUserInputAsync("yes") { confirmation = it }
            assertNotNull(confirmation)
            assertTrue(manager.records().isEmpty())
            briefs.run(briefs.start("new synthetic topic")) { response }
            val restored = assistant { error("Saved reminder review must remain offline") }
            assertTrue(restored.handleUserInput("approve reminder").text.contains("before approving again"))
            assertTrue(manager.records().isEmpty())
            assertTrue(restored.handleUserInput("approve reminder").text.contains("Registered reminder"))
            assertEquals(PersonalReminders.Status.SCHEDULED, manager.records().single().status)
            assertEquals(plan, manager.records().single().plan)
            assertEquals(original, manager.records().single().plan.research!!.report)
            restored.handleUserInput("clear research brief")
            assertEquals(original, assistant().handleUserInput("review reminder research 1").text)
            assertTrue(restored.handleUserInput("approve reminder").text.contains("No unexpired"))
            assertEquals(1, manager.records().size)
            assertTrue(restored.getConversationSnapshot().isEmpty())
            assertFalse(WorkflowDiagnostics(context).snapshot().enabled)
            assertEquals(1, lookups); assertEquals(0, executions); assertEquals(0, context.activityRequests)
        } finally {
            manager.records().forEach { manager.cancel(it.number) }
        }
    }

    @Test fun researchFollowupControlsRemainLocalAndEvaluationCannotEnableDiagnosticCollection() {
        val briefs = ResearchBriefs(PersonalStateFile(context, "research-brief.json"))
        briefs.run(briefs.start("Would you like network settings and a service manual")) {
            SearchRouter.Response("synthetic", listOf(SearchRouter.Result("Synthetic source", "Synthetic excerpt", "https://example.com/source")))
        }
        val service = assistant { error("Follow-up controls must not look up sources") }
        service.handleUserInput("prepare a research reminder in ten minutes")
        var confirmation: HybridAssistantReply? = null
        service.handleUserInputAsync("yes") { confirmation = it }
        assertNotNull(confirmation); assertFalse(confirmation!!.needsInterpretation)
        assertTrue(confirmation!!.text.contains("bare confirmation"))
        assertTrue(PersonalReminderRuntime.manager(context).records().isEmpty())
        var invalid: HybridAssistantReply? = null
        service.handleUserInputAsync("review reminder research 1 then search the service manual") { invalid = it }
        assertNotNull(invalid); assertFalse(invalid!!.needsInterpretation)
        service.handleUserInput("cancel reminder plan")
        service.handleUserInput("enable improvement evaluation")
        service.handleUserInput("record improvement feedback for research follow-up as failed")
        val prepared = service.handleUserInput("prepare improvement proposal for research follow-up").text
        assertTrue(prepared.contains("PersonalReminders"))
        assertEquals(prepared, assistant().handleUserInput("review improvement proposal for research follow-up").text)
        assertEquals(ImprovementEvaluation.Scenario.RESEARCH_FOLLOWUP, ImprovementEvaluation(context).snapshot().samples.single().scenario)
        assertFalse(WorkflowDiagnostics(context).snapshot().enabled)
        assertTrue(WorkflowDiagnostics(context).snapshot().events.isEmpty())
        assertTrue(service.getConversationSnapshot().isEmpty())
        assertEquals(0, executions); assertEquals(0, context.activityRequests)
    }

    @Test fun savedImprovementChecksRemainReviewableAndShareableAfterCurrentChecksAreCleared() {
        val service = assistant()
        service.handleUserInput("enable improvement evaluation")
        service.handleUserInput("record improvement feedback for routine recovery as failed")
        service.handleUserInput("run local foundation checks")
        service.handleUserInput("save local check baseline")
        service.handleUserInput("prepare improvement proposal for routine recovery")
        val frozen = ImprovementEvaluation(context).snapshot().proposals.single()
        service.handleUserInput("clear local foundation checks")
        val restored = assistant()
        assertNull(ImprovementEvaluation(context).snapshot().localRun)
        val reviewed = restored.handleUserInput("review improvement proposal number one").text
        assertTrue(reviewed.contains("counter dispatchers"))
        assertTrue(reviewed.contains("passed -> passed; unchanged"))
        context.allowActivityRequests = true
        assertTrue(restored.handleUserInput("share improvement proposal 1").text.contains("Requested Android's share chooser"))
        @Suppress("DEPRECATION") val share = context.lastActivityIntent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, share.action)
        assertNull(share.component); assertNull(share.`package`)
        assertTrue(share.getStringExtra(Intent.EXTRA_TEXT)!!.contains(reviewed))
        assertEquals(frozen, ImprovementEvaluation(context).snapshot().proposals.single())
        restored.handleUserInput("refresh improvement proposal for routine recovery")
        val updated = ImprovementEvaluation(context).snapshot().proposals
        assertEquals(listOf(1, 2), updated.map { it.number })
        assertEquals(frozen, updated.first())
        assertNull(updated.last().evidence!!.localRun)
        restored.handleUserInput("dismiss improvement proposal 1")
        assertTrue(restored.handleUserInput("share improvement proposal 1").text.contains("No handoff was opened"))
        assertEquals(1, context.activityRequests)
        assertFalse(WorkflowDiagnostics(context).snapshot().enabled)
        assertTrue(restored.getConversationSnapshot().isEmpty())
        assertEquals(0, executions)
    }

    @Test fun proposalArchiveNumbersAndMalformedRefreshStaySafeAcrossActualServiceReconstruction() {
        val service = assistant()
        service.handleUserInput("enable improvement evaluation")
        service.handleUserInput("record improvement feedback for research follow-up as failed")
        service.handleUserInput("prepare improvement proposal for research follow-up")
        service.handleUserInput("clear improvement proposals")
        service.handleUserInput("prepare improvement proposal for research follow-up")
        assertEquals(2, ImprovementEvaluation(context).snapshot().proposals.single().number)
        val restored = assistant()
        assertTrue(restored.handleUserInput("review improvement proposal 1").text.contains("No matching"))
        assertTrue(restored.handleUserInput("share improvement proposal 1").text.contains("No handoff was opened"))
        val before = ImprovementEvaluation(context).snapshot()
        var invalid: HybridAssistantReply? = null
        restored.handleUserInputAsync("refresh improvement proposal for research follow-up then search the service manual") { invalid = it }
        assertNotNull(invalid); assertFalse(invalid!!.needsInterpretation)
        assertEquals(before, ImprovementEvaluation(context).snapshot())
        assertTrue(restored.handleUserInput("review improvement proposals").text.contains("Proposal 2"))
        assertEquals(0, context.activityRequests); assertEquals(0, executions)
        assertTrue(restored.getConversationSnapshot().isEmpty())
    }

    private fun assistant(researchFetch: ((String) -> SearchRouter.Response)? = null): HybridAssistantService {
        val capabilities = CapabilityState(true, false, false, false, false, false, false)
        val registry = ActionRegistry()
        val broker = CapabilityBroker { _, request, _ ->
            executions++
            ActionResult(request.id, false, "TEST", "No device effect is permitted in this test.")
        }
        val engine = AssistantEngine(IntentClassifier(), registry, PolicyGate(), ActionExecutor(broker),
            ActionRepository(database), AuditService(AuditRepository(database)), { capabilities })
        return HybridAssistantService(engine, registry, SystemService(context), { capabilities }, researchFetch)
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "foundation_test_${UUID.randomUUID()}_"
        private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        private val preferences = mutableSetOf<String>()
        private val databases = mutableSetOf<String>()
        var activityRequests = 0
        var allowActivityRequests = false
        var lastActivityIntent: Intent? = null
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            preferences.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }
        override fun getDatabasePath(name: String): File = baseContext.getDatabasePath(prefix + name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
            databases.add(prefix + name)
            return baseContext.openOrCreateDatabase(prefix + name, mode, factory)
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
            databases.add(prefix + name)
            return baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
        }
        override fun startActivity(intent: Intent) {
            activityRequests++
            lastActivityIntent = intent
            if (allowActivityRequests) return
            error("Device activity dispatch is disabled in foundation acceptance checks.")
        }
        fun cleanup() {
            preferences.forEach { baseContext.deleteSharedPreferences(it) }
            databases.forEach { baseContext.deleteDatabase(it) }
            directory.deleteRecursively()
        }
    }
}
