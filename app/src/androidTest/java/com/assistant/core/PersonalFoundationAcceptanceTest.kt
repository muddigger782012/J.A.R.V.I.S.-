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

/** Disposable storage and a counting executor: these checks never perform phone actions. */
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

    private fun assistant(): HybridAssistantService {
        val capabilities = CapabilityState(true, false, false, false, false, false, false)
        val registry = ActionRegistry()
        val broker = CapabilityBroker { _, request, _ ->
            executions++
            ActionResult(request.id, false, "TEST", "No device effect is permitted in this test.")
        }
        val engine = AssistantEngine(IntentClassifier(), registry, PolicyGate(), ActionExecutor(broker),
            ActionRepository(database), AuditService(AuditRepository(database)), { capabilities })
        return HybridAssistantService(engine, registry, SystemService(context), { capabilities })
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "foundation_test_${UUID.randomUUID()}_"
        private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        private val preferences = mutableSetOf<String>()
        private val databases = mutableSetOf<String>()
        var activityRequests = 0
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
            error("Device activity dispatch is disabled in foundation acceptance checks.")
        }
        fun cleanup() {
            preferences.forEach { baseContext.deleteSharedPreferences(it) }
            databases.forEach { baseContext.deleteDatabase(it) }
            directory.deleteRecursively()
        }
    }
}
