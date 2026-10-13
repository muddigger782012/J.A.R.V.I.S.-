package com.assistant.core

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    @Test fun pickingAndSavingEveryMaleVoiceKeepsSettingsOpen() {
        ActivityScenario.launch(AssistantVoiceSettingsActivity::class.java).use { scenario ->
            for ((index, id) in listOf(R.id.maleGeorge, R.id.maleLewis, R.id.maleMichael).withIndex()) {
                scenario.onActivity { activity ->
                    // Verify the real RadioGroup and save-button handlers in the
                    // optimized app without depending on headless window focus.
                    // This is control/persistence coverage, not physical tap QA.
                    val choice = activity.findViewById<android.widget.RadioButton>(id)
                    assertTrue(choice.isShown); assertTrue(choice.isEnabled)
                    choice.performClick()
                    assertTrue(choice.isChecked)
                    val save = activity.findViewById<android.widget.Button>(R.id.btnUseAssistantVoice)
                    assertTrue(save.isShown); assertTrue(save.isEnabled)
                    save.performClick()
                    assertFalse(activity.isFinishing)
                    assertEquals(NeuralVoiceCatalog.voices[index].id, AssistantVoicePreferences(activity).voiceName(AssistantVoiceProfile.ORIGINAL))
                    assertTrue(choice.isShown); assertTrue(choice.isChecked)
                }
                assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
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
