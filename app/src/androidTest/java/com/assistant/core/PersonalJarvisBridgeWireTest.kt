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
