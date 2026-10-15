package com.assistant.core

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.learning.ValidatedCommandMemory
import com.assistant.core.services.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual shared text/voice routing, Android connectivity and encrypted device preferences. */
@RunWith(AndroidJUnit4::class)
class AiConnectionStatusTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalPreferences: Map<String, Map<String, *>>

    @Before fun prepareDisposableFixture() {
        assertTrue("Run only on the disposable updater regression variants",
            BuildConfig.BUILD_TYPE in setOf("updaterCheck", "updaterBetaCheck"))
        originalPreferences = preferenceNames.associateWith { prefs(it).all.toMap() }
        listOf("jarvis_validated_commands", "jarvis_device_learning_session", "jarvis_assistant_memory")
            .forEach { assertTrue(prefs(it).edit().clear().commit()) }
        assertTrue(prefs("voice_settings").edit().putBoolean("auto_start_voice", false)
            .putBoolean("auto_start_foreground_service", false).commit())
        assertTrue(prefs("main_ui").edit().putBoolean("prompted_mic_permission", true).commit())
        assertTrue(prefs("jarvis_beta_introduction").edit().putBoolean("deferred", true).commit())
        awaitRequestedNetworkState()
        networkAttempts.set(0)
    }

    @After fun restoreFixturePreferences() {
        if (::originalPreferences.isInitialized) {
            originalPreferences.forEach { (name, values) ->
                val editor = prefs(name).edit().clear()
                values.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                assertTrue("Could not restore fixture preferences", editor.commit())
            }
        }
        assertEquals("Connection inspection must not attempt HTTP(S)", 0, networkAttempts.get())
    }

    @Test fun screenshotQuestionsUseBothSharedRoutesWithoutChangingConfiguration() {
        configureBothProviders()
        assertTrue(GeminiFailureMemory(context).record(503, null))
        val before = configuration()
        val failure = prefs("jarvis_gemini_failure").all.toMap()
        withAssistant { service ->
            screenshotQuestions.forEach { question ->
                assertLocalStatus(service.handleUserInput(question), "Gemini is configured and is the selected provider")
                assertLocalStatus(asyncReply(service, question), "Gemini is configured and is the selected provider")
                assertTrue("The gateway must remain configured alongside Gemini",
                    service.handleUserInput(question).text.contains("optional gateway is also configured"))
                assertConfigurationUnchanged(before)
                assertTrue("Status must not replace recorded Gemini failure evidence",
                    failure == prefs("jarvis_gemini_failure").all)
            }
        }
    }

    @Test fun clearingUserKeyRetainsEachVariantsExistingFallbackAndGatewayPriority() {
        configureBothProviders()
        assertTrue("A saved user key must keep precedence over the compiled fallback",
            ApiKeyStore(context).read() == USER_KEY)
        ApiKeyStore(context).save("")
        assertTrue("Clearing a user key must retain this variant's compiled fallback",
            ApiKeyStore(context).read() == BuildConfig.BETA_GEMINI_API_KEY)
        val before = configuration()
        val selected = if (BuildConfig.BETA_GEMINI_API_KEY.isNotBlank())
            "Gemini is configured and is the selected provider" else
            "optional gateway is configured and is the selected provider"
        withAssistant { service ->
            screenshotQuestions.forEach { question ->
                assertLocalStatus(asyncReply(service, question), selected)
                assertConfigurationUnchanged(before)
            }
            assertTrue(prefs("jarvis_cloud_ai").edit().remove("gateway_token").commit())
            val withoutGateway = configuration()
            val remaining = if (BuildConfig.BETA_GEMINI_API_KEY.isNotBlank())
                "Gemini is configured and is the selected provider" else "No online AI provider is configured"
            assertLocalStatus(service.handleUserInput("AI connection status"), remaining)
            assertConfigurationUnchanged(withoutGateway)
        }
    }

    @Test fun statusPreservesPendingLearnedActionUntilUserReportsItsOutcome() {
        configureBothProviders()
        val action = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
        val alias = "connection fixture browser alias"
        val commands = ValidatedCommandMemory(context)
        assertTrue(commands.recordSuccess(alias, action.capabilityId, LearnableDeviceAction.capabilities, action.parameters))
        val previous = LearnableDeviceAction("timer", mapOf("seconds" to "59"))
        assertTrue(AssistantMemory(context).recordConfirmed(previous))
        // Seed an already-requested replay; the test never launches an app or timer.
        assertTrue(DeviceLearningSession(context).propose(DeviceLearningSession.Pending(
            alias, action, learnable = false, awaitingOutcome = true, replayed = true)))
        val pending = prefs("jarvis_device_learning_session").getString("pending", null)
        val saved = prefs("jarvis_validated_commands").all.toMap()
        val before = configuration()
        withAssistant { service ->
            screenshotQuestions.forEach { question ->
                assertLocalStatus(service.handleUserInput(question), "Gemini is configured")
                assertLocalStatus(asyncReply(service, question), "Gemini is configured")
                assertEquals("Status must preserve the same pending target and expiry", pending,
                    prefs("jarvis_device_learning_session").getString("pending", null))
                assertTrue("Status must neither learn nor pause an alias", saved == prefs("jarvis_validated_commands").all)
                assertConfigurationUnchanged(before)
            }
            val outcome = service.handleUserInput("it failed")
            assertTrue("The original learned replay must still receive the outcome", commands.isPaused(alias))
            assertNull(prefs("jarvis_device_learning_session").getString("pending", null))
            assertEquals(previous, AssistantMemory(context).mostRecentConfirmed())
            assertNull(outcome.actionResult)
        }
    }

    @Test fun legacyStatusAliasesCannotExecuteAndNewAliasesAreRejected() {
        configureBothProviders()
        val entries = JSONArray()
        screenshotQuestions.forEach { question ->
            entries.put(JSONObject().put("phrase", question).put("capabilityId", "volume_down")
                .put("parameters", JSONObject()).put("successes", 1).put("failures", 0))
        }
        assertTrue(prefs("jarvis_validated_commands").edit().putString("entries", entries.toString()).commit())
        val commands = ValidatedCommandMemory(context)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val saved = prefs("jarvis_validated_commands").all.toMap()
        withAssistant { service ->
            screenshotQuestions.forEach { question ->
                assertFalse("A diagnostic question must not become a learned action",
                    commands.recordSuccess(question, "volume_down", VerifiedVolumeLearning.capabilities))
                assertLocalStatus(service.handleUserInput(question), "Gemini is configured")
                assertLocalStatus(asyncReply(service, question), "Gemini is configured")
                assertEquals("A legacy status alias must not change device volume", volume,
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                assertTrue("Inspection must not rewrite legacy entries", saved == prefs("jarvis_validated_commands").all)
                assertNull(prefs("jarvis_device_learning_session").getString("pending", null))
            }
        }
    }

    private fun configureBothProviders() {
        ApiKeyStore(context).save(USER_KEY)
        ApiKeyStore(context, "tavily").save("connection-fixture-search-not-a-provider-key")
        assertTrue(prefs("jarvis_cloud_ai").edit()
            .putString("endpoint", "https://127.0.0.1:9/connection-fixture")
            .putString("gateway_token", "connection-fixture-not-a-provider-token")
            .putString("gemini_model", "gemini-connection-fixture")
            .putString("weather_latitude", "39.0").putString("weather_longitude", "-77.0")
            .putBoolean("automatic_command_learning", true).commit())
    }

    private fun configuration() = listOf("jarvis_cloud_ai", "jarvis_api_keys").associateWith { prefs(it).all.toMap() }
    private fun assertConfigurationUnchanged(before: Map<String, Map<String, *>>) {
        // Boolean comparison prevents an assertion failure from printing credentials.
        assertTrue("Status must preserve keys, encrypted values, endpoint, token, model and other AI settings",
            before == configuration())
        assertEquals("Connection status must not attempt a provider request", 0, networkAttempts.get())
    }

    private fun assertLocalStatus(reply: HybridAssistantReply, selected: String) {
        assertTrue("Expected the reserved local diagnostic route",
            reply.text.startsWith("J.A.R.V.I.S. connection status (local check):"))
        assertTrue("Expected the existing configured-provider priority", reply.text.contains(selected))
        assertTrue("Configuration must not be presented as live provider verification",
            reply.text.contains("A configured key is not proof of a working connection"))
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        // Validation can finish between rendering and this assertion. Both online
        // states remain valid evidence; neither establishes provider health.
        assertTrue("The local reply must reflect the actual Android network state",
            if (hasInternet) reply.text.contains("Android reports a network with validated internet access") ||
                reply.text.contains("Android has not validated internet access on the current network")
            else reply.text.contains("Android reports no active internet-capable network"))
        assertNull("Status must not execute an assistant action", reply.actionResult)
        assertEquals("Status must not attempt HTTP(S)", 0, networkAttempts.get())
    }

    private fun withAssistant(block: (HybridAssistantService) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val service = AtomicReference<HybridAssistantService>()
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("hybridAssistantService")
                field.isAccessible = true
                service.set(field.get(activity) as HybridAssistantService)
            }
            block(service.get())
        }
    }

    private fun asyncReply(service: HybridAssistantService, question: String): HybridAssistantReply {
        val completed = CountDownLatch(1)
        val reply = AtomicReference<HybridAssistantReply>()
        val callbacks = AtomicInteger()
        service.handleUserInputAsync(question) { reply.set(it); callbacks.incrementAndGet(); completed.countDown() }
        assertTrue("The shared asynchronous route did not complete", completed.await(10, TimeUnit.SECONDS))
        assertEquals("A status request must complete once", 1, callbacks.get())
        return reply.get()
    }

    private fun awaitRequestedNetworkState() {
        val expected = InstrumentationRegistry.getArguments().getString("connectionNetwork") ?: return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun available(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (available() != (expected == "available") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertEquals("The emulator did not reach the requested network condition", expected == "available", available())
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    companion object {
        private const val USER_KEY = "connection-fixture-user-owned-not-a-provider-key"
        private val screenshotQuestions = listOf("Are you connected to Gemini", "Are you connected to AI", "Are you connected to internet")
        private val networkAttempts = AtomicInteger()
        private val preferenceNames = listOf("jarvis_cloud_ai", "jarvis_api_keys", "jarvis_gemini_failure",
            "jarvis_validated_commands", "jarvis_device_learning_session", "jarvis_assistant_memory",
            "jarvis_intelligence_results", "voice_settings", "main_ui", "jarvis_beta_introduction", "jarvis_unified_chat")

        @JvmStatic @BeforeClass fun blockExternalHttpBeforeLaunchingApp() {
            // Dedicated instrumentation invocation: fail before any HTTP(S) socket can open,
            // including Gemini's fixed production URL. Never use a real provider credential.
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol !in setOf("http", "https")) null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection = blocked()
                    override fun openConnection(url: URL, proxy: Proxy): URLConnection = blocked()
                    private fun blocked(): URLConnection {
                        networkAttempts.incrementAndGet()
                        throw IOException("HTTP(S) blocked by the connection-status regression fixture")
                    }
                }
            }
            // Verify that both protocol handlers are intercepted before testing routing.
            for (protocol in listOf("http", "https")) {
                try { URL("$protocol://127.0.0.1:9/guard-check").openConnection(); fail("HTTP guard is missing") }
                catch (_: IOException) { }
            }
            assertEquals(2, networkAttempts.get())
            networkAttempts.set(0)
        }
    }
}
