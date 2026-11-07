package com.assistant.core

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.PersonalJarvisBridgeProtocol
import com.assistant.core.services.PersonalJarvisBridgeService
import com.assistant.core.services.PersonalJarvisBridgeToken
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the actual localhost HTTP listener; never invokes a cloud model or privileged actions. */
@RunWith(AndroidJUnit4::class)
class PersonalJarvisBridgeWireTest {
    private fun await(message: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 8000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue(message, condition())
    }

    private fun request(path: String, token: String? = null, postBody: String? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:8765$path").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.instanceFollowRedirects = false
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (postBody != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = postBody.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    @Test fun streamingRequestSendsSseAndCompletesWithoutAnyConfiguredModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("jarvis_cloud_ai", android.content.Context.MODE_PRIVATE)
        val changed = listOf("primary_provider", "provider_fallback", "lfm3_enabled")
        val originals = changed.associateWith { prefs.all[it] }
        // Force a safely unconfigured local model: a test request must never
        // reach a cloud provider, execute a device action, or need an API key.
        assertTrue(prefs.edit().putString("primary_provider", "lfm3")
            .putBoolean("provider_fallback", false)
            .putBoolean("lfm3_enabled", false).commit())
        val token = PersonalJarvisBridgeToken.getOrCreate(context)
        try {
            ActivityScenario.launch(PersonalJarvisBridgeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    ContextCompat.startForegroundService(activity,
                        Intent(activity, PersonalJarvisBridgeService::class.java)
                            .setAction(PersonalJarvisBridgeService.ACTION_START))
                }
                try {
                    await("Bridge should open local listener") { PersonalJarvisBridgeService.listening }
                    // Android must finish acknowledging startForeground() before this short
                    // test can shut down the service; immediate stop races FGS promotion.
                    Thread.sleep(1500)
                    assertTrue("The listener must stay up after foreground promotion", PersonalJarvisBridgeService.listening)
                    val body = JSONObject().put("model", PersonalJarvisBridgeProtocol.MODEL)
                        .put("stream", true).put("messages", org.json.JSONArray()
                            .put(JSONObject().put("role", "user")
                                .put("content", "Connection check; no model must be contacted."))).toString()
                    val result = request("/v1/chat/completions", token, body)
                    assertEquals(200, result.first)
                    assertTrue(result.second.contains("data: "))
                    assertTrue(result.second.contains("Personal Jarvis could not reach its selected AI provider"))
                    assertTrue(result.second.contains("data: [DONE]"))
                } finally {
                    context.stopService(Intent(context, PersonalJarvisBridgeService::class.java))
                    await("Bridge must stop after streaming test") { !PersonalJarvisBridgeService.listening }
                }
            }
        } finally {
            val edit = prefs.edit()
            originals.forEach { (key, value) ->
                when (value) {
                    null -> edit.remove(key)
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                }
            }
            assertTrue(edit.commit())
        }
    }

    @Test fun loopbackListenerRequiresTokenAndRejectsInvalidModelBeforeAiRouting() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val token = PersonalJarvisBridgeToken.getOrCreate(target)
        ActivityScenario.launch(PersonalJarvisBridgeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity,
                    Intent(activity, PersonalJarvisBridgeService::class.java)
                        .setAction(PersonalJarvisBridgeService.ACTION_START))
            }
            try {
                await("Personal bridge must accept loopback connections") { PersonalJarvisBridgeService.listening }
                Thread.sleep(1500)
                assertTrue("The listener must remain up after foreground promotion", PersonalJarvisBridgeService.listening)
                val missing = request("/v1/models")
                assertEquals(401, missing.first)
                assertEquals("unauthorized", JSONObject(missing.second).getJSONObject("error").getString("code"))
                val valid = request("/v1/models", token)
                assertEquals(200, valid.first)
                assertEquals(PersonalJarvisBridgeProtocol.MODEL,
                    JSONObject(valid.second).getJSONArray("data").getJSONObject(0).getString("id"))
                val wrong = request("/v1/chat/completions", token,
                    JSONObject().put("model", "not-personal-jarvis")
                        .put("messages", org.json.JSONArray().put(JSONObject()
                            .put("role", "user").put("content", "No cloud call should happen."))).toString())
                assertEquals(400, wrong.first)
                assertEquals("invalid_request",
                    JSONObject(wrong.second).getJSONObject("error").getString("code"))
            } finally {
                target.stopService(Intent(target, PersonalJarvisBridgeService::class.java))
                await("Stopping bridge must close loopback listener") { !PersonalJarvisBridgeService.listening }
            }
        }
    }
}
