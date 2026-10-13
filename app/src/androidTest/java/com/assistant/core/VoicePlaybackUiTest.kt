package com.assistant.core

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import android.view.View
import android.widget.ScrollView
import org.hamcrest.Matcher
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VoicePlaybackUiTest {
    companion object {
        @JvmStatic @BeforeClass fun installActualPack() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Exercise the Android archive installer, not a host-only unpacker.
            NeuralVoicePack(context).install(AtomicBoolean(false)) { android.util.Log.i("VoiceRegression", it) }
            assertTrue(NeuralVoicePack(context).installed())
            AssistantVoicePreferences(context).save(AssistantVoiceProfile.ORIGINAL, NeuralVoiceCatalog.voices.first().id)
        }
    }
    private fun reveal() = object : ViewAction {
        override fun getDescription() = "Scroll the containing page to this control"
        override fun getConstraints(): Matcher<View> = isDescendantOfA(isAssignableFrom(ScrollView::class.java))
        override fun perform(controller: UiController, view: View) {
            var offset = view.top
            var parent = view.parent as View
            while (parent !is ScrollView) { offset += parent.top; parent = parent.parent as View }
            parent.clearFocus()
            parent.isSmoothScrollingEnabled = false
            parent.scrollTo(0, maxOf(0, offset - parent.paddingTop - 24))
            controller.loopMainThreadUntilIdle()
        }
    }
    @Test fun pickingAndSavingEveryMaleVoiceKeepsSettingsOpen() {
        ActivityScenario.launch(AssistantVoiceSettingsActivity::class.java).use { scenario ->
            for ((index, id) in listOf(R.id.maleGeorge, R.id.maleLewis, R.id.maleMichael).withIndex()) {
                onView(withId(id)).perform(reveal(), click()).check(matches(isChecked()))
                onView(withId(R.id.btnUseAssistantVoice)).perform(reveal(), click())
                scenario.onActivity {
                    assertFalse(it.isFinishing)
                    assertEquals(NeuralVoiceCatalog.voices[index].id, AssistantVoicePreferences(it).voiceName(AssistantVoiceProfile.ORIGINAL))
                }
                onView(withId(id)).perform(reveal()).check(matches(isDisplayed()))
            }
        }
    }
    @Test fun actualKokoroSpeakersGenerateAndPlayOnAndroid() {
        ActivityScenario.launch(AssistantVoiceSettingsActivity::class.java).use { scenario ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = NeuralAssistantSpeech(context, "preview")
            try {
                for (voice in NeuralVoiceCatalog.voices) {
                    val finished = CountDownLatch(1)
                    val failure = AtomicReference<String>()
                    engine.speak("Systems ready.", voice, done = { finished.countDown() }, error = { failure.set(it); finished.countDown() })
                    assertTrue("Timed out for ${voice.label}", finished.await(120, TimeUnit.SECONDS))
                    assertNull("Failed for ${voice.label}", failure.get())
                    val report = NeuralVoiceDiagnostics.report(context)
                    assertTrue(report, report.contains("completed"))
                    scenario.onActivity { assertFalse(it.isFinishing) }
                }
            } finally { engine.shutdown() }
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
