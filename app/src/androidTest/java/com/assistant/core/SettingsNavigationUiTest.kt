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
    private fun selectAppearanceTab(activity: AppearanceSettingsActivity, index: Int) {
        // Click the real tab View. Material's optimized host APIs are not a
        // separately compiled instrumentation contract.
        val tabs = activity.findViewById<android.view.ViewGroup>(R.id.appearanceTabs)
        assertTrue((tabs.getChildAt(0) as android.view.ViewGroup).getChildAt(index).performClick())
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue(message, condition())
    }

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
                    selectAppearanceTab(activity, 1)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    color = activity.findViewById(R.id.appearanceFontColor)
                    color.setText("#8DEFFF")
                    activity.findViewById<android.widget.SeekBar>(R.id.appearanceFontSlider).progress = 12
                    val scroll = activity.findViewById<ScrollView>(R.id.appearanceScroll)
                    scroll.scrollTo(0, 120); fontsY = scroll.scrollY
                    assertTrue(fontsY > 0)
                    selectAppearanceTab(activity, 0)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(themesY, activity.findViewById<ScrollView>(R.id.appearanceScroll).scrollY)
                    selectAppearanceTab(activity, 1)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(color, activity.findViewById(R.id.appearanceFontColor))
                    assertEquals("#8DEFFF", color.text.toString())
                    assertEquals(12, activity.findViewById<android.widget.SeekBar>(R.id.appearanceFontSlider).progress)
                    assertEquals(fontsY, activity.findViewById<ScrollView>(R.id.appearanceScroll).scrollY)
                    selectAppearanceTab(activity, 0)
                }
                instrumentation.waitForIdleSync()
                var beforeTheme: AppearanceSettingsActivity? = null
                scenario.onActivity { beforeTheme = it; it.findViewById<android.view.View>(R.id.btnThemeStark).performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertNotSame("Theme selection must actually recreate the page", beforeTheme, activity)
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

    @Test fun closeNestedSettingsReturnsToTheExistingChatAndRetainsItsDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settingsMonitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
        val appearanceMonitor = instrumentation.addMonitor(AppearanceSettingsActivity::class.java.name, null, false)
        var settings: android.app.Activity? = null
        var appearance: android.app.Activity? = null
        var main: MainActivity? = null
        val prefs = instrumentation.targetContext.getSharedPreferences("main_ui", Context.MODE_PRIVATE)
        val prompted = prefs.all["prompted_mic_permission"]
        try {
            // This navigation fixture needs no microphone access. Restore the
            // first-run prompt flag afterwards; no permission is granted.
            prefs.edit().putBoolean("prompted_mic_permission", true).commit()
            // Close deliberately delivers a new Intent. ActivityScenario tracks
            // the launch Intent and cannot track cleanup after setIntent changes it.
            main = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val originalMain = main!!
            run {
                lateinit var originalInput: android.widget.EditText
                instrumentation.runOnMainSync {
                    originalInput = originalMain.findViewById(R.id.etCommandInput)
                    originalInput.setText("Keep this unsent draft")
                    originalMain.startActivity(android.content.Intent(originalMain, SettingsActivity::class.java))
                }
                settings = instrumentation.waitForMonitorWithTimeout(settingsMonitor, 5000)
                assertNotNull("Settings did not open", settings)
                instrumentation.runOnMainSync {
                    settings!!.startActivity(android.content.Intent(settings, AppearanceSettingsActivity::class.java))
                }
                appearance = instrumentation.waitForMonitorWithTimeout(appearanceMonitor, 5000)
                assertNotNull("Nested Appearance did not open", appearance)
                instrumentation.runOnMainSync {
                    appearance!!.findViewById<android.view.View>(R.id.menuPageClose).performClick()
                }
                awaitCondition("Close must resume the existing Chat") {
                    var resumed = false
                    instrumentation.runOnMainSync {
                        resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getLifecycleStageOf(originalMain) == androidx.test.runner.lifecycle.Stage.RESUMED
                    }
                    resumed
                }
                instrumentation.runOnMainSync {
                    assertFalse("Close must reuse the existing Chat", originalMain.isFinishing || originalMain.isDestroyed)
                    assertSame(originalInput, originalMain.findViewById(R.id.etCommandInput))
                    assertEquals("Keep this unsent draft", originalInput.text.toString())
                    assertEquals(android.view.View.VISIBLE, originalMain.findViewById<android.view.View>(R.id.tabAssistantSection).visibility)
                    assertEquals(android.view.View.GONE, originalMain.findViewById<android.view.View>(R.id.menuPageNavigation).visibility)
                }
            }
        } finally {
            instrumentation.removeMonitor(settingsMonitor); instrumentation.removeMonitor(appearanceMonitor)
            instrumentation.runOnMainSync { appearance?.finish(); settings?.finish(); main?.finish() }
            instrumentation.waitForIdleSync()
            prefs.edit().apply {
                if (prompted is Boolean) putBoolean("prompted_mic_permission", prompted) else remove("prompted_mic_permission")
            }.commit()
        }
    }
}
