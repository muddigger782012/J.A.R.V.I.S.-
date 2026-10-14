package com.assistant.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.LiveChatStore
import com.assistant.core.services.VoiceForegroundService
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSwitchUiTest {
    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(edit.commit())
    }
    private fun awaitState(check: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (!check() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertTrue("Timed out waiting for the live service transition", check())
    }

    @Test fun newAndSavedChatsStopLiveTurnsAndDiscardOldPermissionResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = listOf("voice_settings", "main_ui", "jarvis_unified_chat").map {
            context.getSharedPreferences(it, Context.MODE_PRIVATE)
        }
        val beforePrefs = prefs.map { it.all }
        fun chatFile(file: java.io.File) = file.name.startsWith("live-chat-") || file.name.startsWith("chat-context")
        val beforeFiles = context.filesDir.listFiles().orEmpty().filter(::chatFile).associate { it.name to it.readBytes() }
        try {
            // The disposable emulator exercises live-service lifecycle, without a spoken command.
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            prefs[0].edit().putBoolean("auto_start_voice", false).putBoolean("auto_start_foreground_service", false).commit()
            prefs[1].edit().putBoolean("prompted_mic_permission", true).commit()
            val original = "ui-isolation-original"
            val saved = "ui-isolation-saved"
            prefs[2].edit().putString("active_session", original)
                .putString("messages_$original", JSONArray(listOf("You: Original chat", "J.A.R.V.I.S.: Original reply")).toString())
                .putString("messages_$saved", JSONArray(listOf("You: Saved chat", "J.A.R.V.I.S.: Saved reply")).toString()).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.pendingWeatherPermissionCommand = activity.pendingConversationCommand("Show intelligence plan progress")
                    activity.pendingMissedCallCommand = activity.pendingConversationCommand("Show intelligence plan progress")
                    VoiceForegroundService.startLiveChat(activity, original, false)
                }
                awaitState { LiveChatStore.state(context).optBoolean("active") }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<EditText>(R.id.etCommandInput).isEnabled)
                    activity.findViewById<Button>(R.id.btnNewAssistantChat).performClick()
                    assertNotEquals(original, LiveChatStore.currentConversation(activity))
                    assertNull(activity.pendingWeatherPermissionCommand)
                    assertNull(activity.pendingMissedCallCommand)
                    val afterSwitch = activity.findViewById<TextView>(R.id.tvOutputLog).text.toString()
                    activity.completeWeatherPermission(false)
                    activity.completeCallLogPermission(false)
                    assertEquals(afterSwitch, activity.findViewById<TextView>(R.id.tvOutputLog).text.toString())
                    assertTrue(activity.findViewById<EditText>(R.id.etCommandInput).isEnabled)
                    assertEquals(activity.getString(R.string.ui_ready), activity.findViewById<TextView>(R.id.tvAssistantState).text.toString())
                }
                awaitState { !LiveChatStore.state(context).optBoolean("active") }
                scenario.onActivity { activity ->
                    val oldRequest = activity.pendingConversationCommand("Show intelligence plan progress")
                    activity.findViewById<TextView>(R.id.tvAssistantState).text = "Thinking…"
                    // This is the same destination handler used by the saved-history dialog.
                    activity.selectConversation(saved)
                    val output = activity.findViewById<TextView>(R.id.tvOutputLog).text.toString()
                    assertTrue(output.contains("Saved reply")); assertFalse(output.contains("Original reply"))
                    assertEquals(activity.getString(R.string.ui_ready), activity.findViewById<TextView>(R.id.tvAssistantState).text.toString())
                    activity.pendingWeatherPermissionCommand = oldRequest
                    activity.pendingMissedCallCommand = oldRequest
                    activity.completeWeatherPermission(true); activity.completeCallLogPermission(true)
                    assertEquals(output, activity.findViewById<TextView>(R.id.tvOutputLog).text.toString())

                    val oldSavedRequest = activity.pendingConversationCommand("Show intelligence plan progress")
                    activity.selectConversation(saved)
                    assertNotEquals(oldSavedRequest.token.epoch, activity.pendingConversationCommand("Show intelligence plan progress").token.epoch)
                    val reopened = activity.findViewById<TextView>(R.id.tvOutputLog).text.toString()
                    activity.pendingWeatherPermissionCommand = oldSavedRequest
                    activity.pendingMissedCallCommand = oldSavedRequest
                    activity.completeWeatherPermission(true); activity.completeCallLogPermission(true)
                    assertEquals(reopened, activity.findViewById<TextView>(R.id.tvOutputLog).text.toString())
                }
            }
        } finally {
            context.stopService(Intent(context, VoiceForegroundService::class.java))
            prefs.zip(beforePrefs).forEach { (preference, values) -> restore(preference, values) }
            context.filesDir.listFiles().orEmpty().filter(::chatFile).forEach { it.delete() }
            beforeFiles.forEach { (name, bytes) -> java.io.File(context.filesDir, name).writeBytes(bytes) }
        }
    }
}
