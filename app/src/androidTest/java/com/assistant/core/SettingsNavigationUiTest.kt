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
                        val root = activity.findViewById<android.view.ViewGroup>(R.id.menuPageContent).getChildAt(0) as ScrollView
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
                        activity.findViewById<android.view.View>(R.id.menuPageBack).performClick()
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

    @Test fun everyUtilityActivityKeepsAPersistentBackAndCloseWithoutReplacingExistingButtons() {
        val pages = listOf(
            SettingsActivity::class.java to R.id.menuPageBack,
            AppearanceSettingsActivity::class.java to R.id.btnCloseAppearances,
            ThemeSettingsActivity::class.java to R.id.btnCloseThemes,
            VoiceSettingsActivity::class.java to R.id.btnCloseVoiceSettings,
            AssistantVoiceSettingsActivity::class.java to R.id.btnCloseAssistantVoice,
            VoiceProfileActivity::class.java to R.id.voiceProfileBack,
            IntelligenceActivity::class.java to R.id.btnCloseIntelligence,
            AssistantConnectionsActivity::class.java to R.id.menuPageBack,
            LiveChatHistoryActivity::class.java to R.id.btnCloseLiveHistory
        )
        for ((page, backId) in pages) {
            ActivityScenario.launch(page).use { scenario ->
                scenario.onActivity { activity ->
                    val navigation = activity.findViewById<android.view.ViewGroup>(R.id.menuPageNavigation)
                    val body = activity.findViewById<android.view.View>(R.id.menuPageContent)
                    val back = activity.findViewById<android.view.View>(backId)
                    val close = activity.findViewById<android.view.View>(R.id.menuPageClose)
                    assertNotNull(page.simpleName, navigation)
                    assertTrue(back.isShown && close.isShown)
                    assertTrue(back.hasOnClickListeners() && close.hasOnClickListeners())
                    assertSame(navigation, back.parent)
                    assertTrue("Navigation must not overlay ${page.simpleName}", navigation.bottom <= body.top)
                    back.performClick()
                    assertTrue(page.simpleName, activity.isFinishing)
                }
            }
        }
    }

    @Test fun appearanceRetainsIndependentTabScrollEditsAndPositionAcrossThemeRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE)
        val before = prefs.all
        try {
            ThemePreferences.save(context, "default")
            ActivityScenario.launch(AppearanceSettingsActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                var themesY = 0
                var fontsY = 0
                lateinit var color: android.widget.EditText
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<ScrollView>(R.id.appearanceScroll)
                    scroll.scrollTo(0, 400); themesY = scroll.scrollY
                    assertTrue(themesY > 0)
                    activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.appearanceTabs).getTabAt(1)!!.select()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    color = activity.findViewById(R.id.appearanceFontColor)
                    color.setText("#8DEFFF")
                    activity.findViewById<android.widget.SeekBar>(R.id.appearanceFontSlider).progress = 12
                    val scroll = activity.findViewById<ScrollView>(R.id.appearanceScroll)
                    scroll.scrollTo(0, 120); fontsY = scroll.scrollY
                    assertTrue(fontsY > 0)
                    activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.appearanceTabs).getTabAt(0)!!.select()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(themesY, activity.findViewById<ScrollView>(R.id.appearanceScroll).scrollY)
                    activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.appearanceTabs).getTabAt(1)!!.select()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(color, activity.findViewById(R.id.appearanceFontColor))
                    assertEquals("#8DEFFF", color.text.toString())
                    assertEquals(12, activity.findViewById<android.widget.SeekBar>(R.id.appearanceFontSlider).progress)
                    assertEquals(fontsY, activity.findViewById<ScrollView>(R.id.appearanceScroll).scrollY)
                    activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.appearanceTabs).getTabAt(0)!!.select()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { it.findViewById<android.view.View>(R.id.btnThemeStark).performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals("stark", ThemePreferences.selected(activity))
                    assertEquals(themesY, activity.findViewById<ScrollView>(R.id.appearanceScroll).scrollY)
                    assertTrue(activity.findViewById<android.view.View>(R.id.menuPageClose).isShown)
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
