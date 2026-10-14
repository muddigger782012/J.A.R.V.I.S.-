package com.assistant.core

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class IntelligenceMenusUiTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun visible(activity: android.app.Activity) = descendants(activity.findViewById(android.R.id.content)).filter { it.isShown }
    private fun row(activity: android.app.Activity, title: String) = visible(activity).first { it.isClickable && it.contentDescription?.toString()?.startsWith(title) == true }
    @Test fun appearanceIntelligenceAndOfflinePlanUseAllThemesAndReturnToTheirOwnMenus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val theme = ThemePreferences.selected(context)
        val percent = FontPreferences.percent(context); val color = FontPreferences.colorHex(context)
        val cache = File(context.filesDir, "intelligence-plan-cache.json")
        val previousCache = if (cache.exists()) cache.readBytes() else null
        try {
            // A newer cached report is legitimate. UI checks must not pin the shipped estimate.
            val cachedPlan = JSONObject(context.assets.open("intelligence-plan.json").bufferedReader().use { it.readText() })
            cachedPlan.put("revision", cachedPlan.getLong("revision") + 1)
            val workstreams = cachedPlan.getJSONArray("workstreams")
            for (index in 0 until workstreams.length()) workstreams.getJSONObject(index).put("percent", 97)
            cache.writeText(cachedPlan.toString())
            FontPreferences.save(context, 150, "")
            for (id in ThemePreferences.ids) {
                ThemePreferences.save(context, id)
                ActivityScenario.launch(AppearanceSettingsActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                    row(activity, "Themes").performClick()
                    assertNotNull(row(activity, "Default"))
                    activity.findViewById<Button>(R.id.btnCloseAppearances).performClick()
                    assertNotNull(row(activity, "Fonts")); assertFalse(activity.isFinishing)
                } }
                ActivityScenario.launch(IntelligenceActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                    assertNotNull(row(activity, "Intelligence Plan"))
                    row(activity, "Commands and learning").performClick()
                    assertNotNull(row(activity, "Review learned commands"))
                    activity.findViewById<Button>(R.id.btnCloseIntelligence).performClick()
                    assertNotNull(row(activity, "Intelligence Plan")); assertFalse(activity.isFinishing)
                } }
                ActivityScenario.launch(IntelligencePlanActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                    assertNotNull(row(activity, "Check plan updates"))
                    val text = visible(activity).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                    assertTrue(text.contains("≈97% estimated scope completion")); assertTrue(text.contains("engineering", ignoreCase = true))
                    val progress = visible(activity).filterIsInstance<android.widget.ProgressBar>()
                    assertEquals(workstreams.length(), progress.size)
                    assertTrue(progress.all { it.progress == 97 })
                    assertFalse(activity.isFinishing)
                } }
            }
        } finally {
            if (previousCache == null) cache.delete() else cache.writeBytes(previousCache)
            ThemePreferences.save(context, theme); FontPreferences.save(context, percent, color)
        }
    }
}

