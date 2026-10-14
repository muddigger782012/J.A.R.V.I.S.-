package com.assistant.core

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationUiTest {
    @Test fun settingsRowsWrapAcrossThemesAndVoiceEditorHasNoSiblingHubLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE)
        val before = prefs.all
        try {
            FontPreferences.save(context, 150, "")
            for (theme in listOf("default", "stark", "cybertron", "default_alternative", "stark_alternative", "cybertron_alternative", "aurora", "sunset", "glacier", "emerald", "royal", "hacker")) {
                ThemePreferences.save(context, theme)
                ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
                        val panel = root.getChildAt(0) as LinearLayout
                        val rows = (0 until panel.childCount).map { panel.getChildAt(it) }.filter { it.isClickable }
                        assertEquals(8, rows.size)
                        assertEquals(8, rows.map { it.contentDescription }.distinct().size)
                        assertTrue(rows.all { it.isFocusable && it.minimumHeight >= SettingsMenuViews.dp(activity, 48) })
                        rows.forEach { row ->
                            row.measure(android.view.View.MeasureSpec.makeMeasureSpec(SettingsMenuViews.dp(activity, 240), android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                            assertTrue(row.measuredHeight >= SettingsMenuViews.dp(activity, 48))
                        }
                        assertFalse(activity.isFinishing)
                        rows.first().performClick()
                        assertTrue(activity.isFinishing)
                    }
                }
            }
            ActivityScenario.launch(VoiceSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNull(activity.findViewById<android.view.View>(R.id.btnThemes))
                    assertTrue(activity.findViewById<android.widget.EditText>(R.id.etWakeWord).isEnabled)
                    activity.findViewById<android.view.View>(R.id.btnCloseVoiceSettings).performClick()
                    assertTrue(activity.isFinishing)
                }
            }
        } finally {
            val edit = prefs.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
            } }
            edit.commit()
        }
    }
}
