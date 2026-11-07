package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalLfm3ClientTest {
    @Test fun onlyListedUncensoredModelsCanBeSelected() {
        val models = LocalLfm3Models.variants
        assertEquals(2, models.size)
        assertTrue(models.all { it.repository.contains("Uncensored") })
        assertEquals("atomic", LocalLfm3Models.selected(null).key)
        assertEquals("extreme", LocalLfm3Models.selected("extreme").key)
        assertEquals("atomic", LocalLfm3Models.selected("unknown").key)
        assertNotEquals(models[0].alias, models[1].alias)
    }

    @Test fun localPortIsStrictlyBoundedAndInvalidSettingsCannotRedirectToRemoteHost() {
        for (invalid in listOf(null, "", "0", "80", "65536", "127.0.0.1:8080", "https://test", "-1"))
            assertNull(LocalLfm3Models.port(invalid))
        assertEquals(8080, LocalLfm3Models.port("8080"))
        assertEquals(65535, LocalLfm3Models.port("65535"))
    }

    @Test fun modelInventoryRequiresTheExactLoadedAlias() {
        val inventory = """{"data":[{"id":"jarvis-lfm3-atomic"},{"id":"another-model"}]}"""
        assertTrue(LocalLfm3Models.modelAvailable(inventory, "jarvis-lfm3-atomic"))
        assertFalse(LocalLfm3Models.modelAvailable(inventory, "jarvis-lfm3-extreme"))
        assertFalse(LocalLfm3Models.modelAvailable("{}", "jarvis-lfm3-atomic"))
    }

    @Test fun requestsAreTextOnlyWithoutToolsLessonsOrRemoteCredentials() {
        val model = LocalLfm3Models.variants.first()
        val request = JSONObject(LocalLfm3Models.request(model, "hello", listOf("prior question" to "prior answer")))
        assertEquals(model.alias, request.getString("model"))
        assertFalse(request.getBoolean("stream"))
        assertFalse(request.has("tools"))
        assertFalse(request.has("api_key"))
        assertFalse(request.has("lesson"))
        assertFalse(request.has("location"))
        val messages = request.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(3).getString("role"))
        assertEquals("hello", messages.getJSONObject(3).getString("content"))
    }

    @Test fun onlyChatMessageTextIsAcceptedNeverToolCallsOrUntrustedLessons() {
        assertEquals("Done", LocalLfm3Models.response(
            """{"choices":[{"message":{"role":"assistant","content":" Done "}}]}"""))
        assertNull(LocalLfm3Models.response("""{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"reboot"}}]}}]}"""))
        assertNull(LocalLfm3Models.response("""{"lesson":{"action":"shell"},"reply":"I did it"}"""))
    }

    @Test fun primaryLfm3FallsBackToConfiguredCloudWithoutRunningTools() {
        val result = IntelligenceRouting.ask(IntelligenceRouting.Provider.LOCAL_LFM3, true,
            setOf(IntelligenceRouting.Provider.LOCAL_LFM3, IntelligenceRouting.Provider.GEMINI)) { provider ->
            if (provider == IntelligenceRouting.Provider.LOCAL_LFM3)
                FallbackAiResult("Local runtime offline", "bad lesson", providerFailed = true)
            else FallbackAiResult("Gemini answer", null)
        }
        assertEquals(IntelligenceRouting.Provider.GEMINI, result.provider)
        assertEquals(listOf(IntelligenceRouting.Provider.LOCAL_LFM3, IntelligenceRouting.Provider.GEMINI), result.attempted)
        assertNull(result.answer.lessonJson)
    }
}
