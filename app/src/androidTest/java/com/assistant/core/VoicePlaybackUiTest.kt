package com.assistant.core

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.assistant.core.services.NeuralPcmPlayer
import com.assistant.core.services.NeuralSpeechCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class VoicePlaybackUiTest {
    @Test fun pickingMaleVoiceKeepsPickerAndSettingsOpen() {
        ActivityScenario.launch(AssistantVoiceSettingsActivity::class.java).use { scenario ->
            onView(withId(R.id.btnChooseAssistantVoice)).perform(click())
            onView(withText("Lewis · British male")).perform(click())
            onView(withText("Done")).check(matches(isDisplayed())).perform(click())
            onView(withId(R.id.btnChooseAssistantVoice)).check(matches(isDisplayed()))
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
    @Test fun mediaPcm16QueuesFramesAndCompletesOnAndroid() {
        ActivityScenario.launch(AssistantVoiceSettingsActivity::class.java).use { scenario ->
            var player: NeuralPcmPlayer? = null
            scenario.onActivity { player = NeuralPcmPlayer(it) }
            val queued = AtomicBoolean(false)
            val samples = FloatArray(12000) { index -> (kotlin.math.sin(index * 2.0 * Math.PI * 440 / 24000) * 0.05).toFloat() }
            player!!.play(NeuralSpeechCache.Clip(24000, samples), active = { true }, firstQueued = { queued.set(true) })
            assertTrue(queued.get())
        }
    }
}
