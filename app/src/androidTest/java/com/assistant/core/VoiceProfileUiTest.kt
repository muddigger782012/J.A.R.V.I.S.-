package com.assistant.core

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.VoiceProfileStore
import com.assistant.core.services.VoiceRecognitionHints
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class VoiceProfileUiTest {
    @Test fun profileUsesEveryThemeAndOnlySuppliesApprovedVocabulary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appearance = context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE)
        val previous = appearance.getString("theme", "default")
        val store = VoiceProfileStore(context.filesDir)
        val language = Locale.getDefault().toLanguageTag()
        try {
            store.clear()
            assertFalse(VoiceRecognitionHints.apply(context, Intent()))
            assertTrue(store.approve("Troy capability request number one", "Try capability request number one", language, "device", false))
            val hinted = Intent()
            assertTrue(VoiceRecognitionHints.apply(context, hinted))
            assertEquals(listOf("Try capability request number one"), hinted.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS))
            assertFalse(VoiceRecognitionHints.apply(context, Intent(), false))
            for (theme in listOf("default", "stark", "cybertron", "default_alternative", "stark_alternative", "cybertron_alternative", "aurora", "sunset", "glacier", "emerald", "royal", "hacker")) {
                appearance.edit().putString("theme", theme).commit()
                ActivityScenario.launch<VoiceProfileActivity>(Intent(context, VoiceProfileActivity::class.java)).use { scenario ->
                    scenario.onActivity { activity ->
                        assertFalse(activity.isFinishing)
                        assertEquals(View.GONE, activity.findViewById<View>(R.id.voiceProfileReview).visibility)
                        assertEquals("Hey Jarvis", activity.findViewById<EditText>(R.id.voiceProfilePhrase).text.toString())
                        assertTrue(activity.findViewById<TextView>(R.id.voiceProfileScore).text.contains("0/1 matched"))
                        activity.findViewById<Button>(R.id.voiceProfileManage).performClick()
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.voiceProfileEntries).visibility)
                        activity.findViewById<Button>(R.id.voiceProfileEntryRemove).performClick()
                        assertTrue(store.entries().isEmpty())
                        assertTrue(activity.findViewById<TextView>(R.id.voiceProfileScore).text.contains("No approved practice"))
                        assertTrue(store.approve("Troy capability request number one", "Try capability request number one", language, "device", false))
                    }
                }
            }
            assertTrue(store.clear())
            assertFalse(VoiceRecognitionHints.apply(context, Intent()))
        } finally { store.clear(); appearance.edit().putString("theme", previous).commit() }
    }
}
