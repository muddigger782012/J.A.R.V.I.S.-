package com.assistant.core

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.RuntimeSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real private-provider round trips; no microphone, network or owner credentials are used. */
@RunWith(AndroidJUnit4::class)
class RuntimeSettingsAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri get() = Uri.parse("content://${context.packageName}.runtime-settings")

    @Test fun providerReadsLatestMainProcessSettingsAndRemainsPrivate() {
        val prefs = context.getSharedPreferences("jarvis_cloud_ai", Context.MODE_PRIVATE)
        val key = "runtime_settings_acceptance_probe"
        assertFalse(prefs.contains(key))
        try {
            assertTrue(prefs.edit().putString(key, "first").commit())
            assertEquals("first", context.contentResolver.call(uri, "snapshot", "jarvis_cloud_ai", null)!!.getString(key))
            assertTrue(prefs.edit().putString(key, "second").commit())
            assertEquals("second", context.contentResolver.call(uri, "snapshot", "jarvis_cloud_ai", null)!!.getString(key))
            assertEquals("second", RuntimeSettings.read(context, "jarvis_cloud_ai").getString(key, null))
            val provider = context.packageManager.resolveContentProvider(uri.authority!!, 0)!!
            assertFalse(provider.exported); assertFalse(provider.grantUriPermissions)
        } finally { prefs.edit().remove(key).commit() }
    }

    @Test fun privateBridgeRejectsUnrelatedPreferencesAndArbitraryWrites() {
        var rejected = false
        try { context.contentResolver.call(uri, "snapshot", "unrelated_owner_data", null) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        rejected = false
        try { context.contentResolver.call(uri, "boolean", "jarvis_cloud_ai", Bundle().apply {
            putString("key", "provider_fallback"); putBoolean("value", false)
        }) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
