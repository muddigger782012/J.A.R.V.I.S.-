package com.jarvis.developer

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import com.jarvis.voicebox.VoiceboxPreferences
import com.jarvis.voicebox.VoiceboxSettingsPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoiceboxSettingsPanelTest {
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()

    @Test fun settingsStayCollapsedUntilRequestedAndPersistChosenVoice() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.getSharedPreferences("voicebox", 0).edit().clear().commit()
        val panel = VoiceboxSettingsPanel(activity, activity.getColor(android.R.color.primary_text_dark))
        activity.setContentView(panel)
        assertFalse(VoiceboxPreferences.load(activity).enabled)
        assertEquals(View.GONE, panel.getChildAt(1).visibility)
        (panel.getChildAt(0) as Button).performClick()
        assertEquals(View.VISIBLE, panel.getChildAt(1).visibility)
        val inputs = descendants(panel).filterIsInstance<EditText>()
        inputs[0].setText("https://voicebox.example")
        inputs[1].setText("George")
        descendants(panel).filterIsInstance<Switch>().single().isChecked = true
        descendants(panel).filterIsInstance<Button>().single { it.text == "Save Voicebox" }.performClick()
        val saved = VoiceboxPreferences.load(activity)
        assertTrue(saved.enabled)
        assertEquals("George", saved.profile)
        assertEquals("https://voicebox.example", saved.endpoint)
        panel.close(); controller.pause().stop().destroy()
    }
    @Test fun invalidHostNeverReplacesSavedSettings() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.getSharedPreferences("voicebox", 0).edit().clear().commit()
        val panel = VoiceboxSettingsPanel(activity, activity.getColor(android.R.color.primary_text_dark))
        val before = VoiceboxPreferences.load(activity)
        descendants(panel).filterIsInstance<EditText>().first().setText("http://192.168.1.2:17493")
        descendants(panel).filterIsInstance<Button>().single { it.text == "Save Voicebox" }.performClick()
        assertEquals(before, VoiceboxPreferences.load(activity))
        panel.close(); controller.pause().stop().destroy()
    }
}
