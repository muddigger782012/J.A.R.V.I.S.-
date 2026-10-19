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
import java.io.File
import java.util.UUID
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
