package com.assistant.core

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.ApiKeyStore
import com.assistant.core.services.ApkUpdateService
import com.assistant.core.services.LiveChatStore
import com.assistant.core.services.UpdateChannel
import com.assistant.core.services.VoicePreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only the disposable updaterCheck/updaterBetaCheck fixtures may run these destructive seed steps. */
@RunWith(AndroidJUnit4::class)
class UpdaterDataRetentionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = "updater-fixture:${context.packageName}"
    private val userKey get() = "fixture-user-owned-key:${context.packageName}"
    private val otherKey get() = "fixture-search-key:${context.packageName}"
    private val beta get() = context.packageName == UpdateChannel.BETA.applicationId

    private fun requireFixture() {
        assertTrue("Use only the disposable updater regression variants",
            BuildConfig.BUILD_TYPE in setOf("updaterCheck", "updaterBetaCheck"))
        assertEquals(if (beta) UpdateChannel.BETA.applicationId else UpdateChannel.JARVIS.applicationId, context.packageName)
        // Boolean comparisons prevent a failing assertion from printing a compiled API credential.
        assertTrue("Main API configuration must remain empty; Beta fixture injection must be present",
            if (beta) BuildConfig.BETA_GEMINI_API_KEY == BETA_FIXTURE_VALUE else BuildConfig.BETA_GEMINI_API_KEY.isEmpty())
    }

    @Test fun seedOlderInstallation() {
        requireFixture()
        assertEquals(108L, ApkUpdateService.installedIdentity(context).versionCode)
        val keyStore = ApiKeyStore(context)
        keyStore.save("")
        assertTrue("The existing Gemini fallback differs from this variant's compiled configuration",
            keyStore.read() == BuildConfig.BETA_GEMINI_API_KEY)
        assertTrue("Non-Gemini integrations must not acquire the Beta Gemini fallback",
            ApiKeyStore(context, "tavily").read().isEmpty())
        keyStore.save(userKey)
        ApiKeyStore(context, "tavily").save(otherKey)
        assertTrue("User-owned Gemini configuration must take precedence over the compiled Beta fallback",
            keyStore.read() == userKey)
        assertTrue("User key must be encrypted at rest",
            context.getSharedPreferences("jarvis_api_keys", Context.MODE_PRIVATE).getString("gemini", "").orEmpty().let {
                it.isNotEmpty() && !it.contains(userKey)
            })
        context.getSharedPreferences("updater_regression", Context.MODE_PRIVATE).edit()
            .putString("marker", marker).putLong("seedVersion", 108L).commit()
        context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE).edit()
            .putString("theme", if (beta) "cybertron" else "stark")
            .putInt("font_percent", if (beta) 135 else 120).commit()
        VoicePreferences(context).let { preferences ->
            preferences.save(preferences.load().copy(
                wakeWord = if (beta) "hey beta jarvis" else "hey main jarvis",
                preferOfflineCommandRecognition = true,
                autoStartVoice = false,
                autoStartForegroundService = false
            ))
        }
        LiveChatStore.currentConversation(context, marker)
        LiveChatStore.append(context, marker, "Retain this simulated upgrade conversation", marker)
        // Flush apply() writes before the harness replaces the APK/process.
        assertTrue(context.getSharedPreferences("jarvis_api_keys", Context.MODE_PRIVATE).edit().commit())
        assertTrue(context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE).edit().commit())
        assertRetained()
    }

    @Test fun verifyRetainedAfterUpgrade() {
        requireFixture()
        assertEquals(109L, ApkUpdateService.installedIdentity(context).versionCode)
        assertRetained()
    }

    @Test fun verifyOlderInstallationStillRetained() {
        requireFixture()
        assertEquals(108L, ApkUpdateService.installedIdentity(context).versionCode)
        assertRetained()
    }

    private fun assertRetained() {
        val seeded = context.getSharedPreferences("updater_regression", Context.MODE_PRIVATE)
        assertEquals(marker, seeded.getString("marker", null))
        assertEquals(108L, seeded.getLong("seedVersion", -1))
        assertEquals(if (beta) "cybertron" else "stark", ThemePreferences.selected(context))
        assertEquals(if (beta) 135 else 120, FontPreferences.percent(context))
        assertEquals(if (beta) "hey beta jarvis" else "hey main jarvis", VoicePreferences(context).load().wakeWord)
        assertTrue(VoicePreferences(context).load().preferOfflineCommandRecognition)
        assertEquals(marker, LiveChatStore.currentConversation(context))
        val history = LiveChatStore.messages(context)
        assertEquals(1, history.length())
        assertEquals(marker, history.getJSONObject(0).getString("conversation"))
        assertTrue("The encrypted user Gemini key and Android Keystore entry must survive a same-signer upgrade",
            ApiKeyStore(context).read() == userKey)
        assertTrue("The independent user search integration must survive the upgrade",
            ApiKeyStore(context, "tavily").read() == otherKey)
    }

    companion object {
        private const val BETA_FIXTURE_VALUE = "jarvis-updater-fixture-not-a-provider-key"
    }
}
