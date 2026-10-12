package com.assistant.core.services

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NeuralSpeechCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun modelVoiceAndPhraseChangesCannotReuseOldAudio() {
        val cache = NeuralSpeechCache(temporary.newFolder())
        val keys = listOf(cache.key("model1", "George", "Yes?"), cache.key("model2", "George", "Yes?"),
            cache.key("model1", "Lewis", "Yes?"), cache.key("model1", "George", "Listening."))
        assertEquals(4, keys.toSet().size)
        cache.write(keys[0], NeuralSpeechCache.Clip(24000, floatArrayOf(0.1f, -0.2f, 0.3f)))
        assertArrayEquals(floatArrayOf(0.1f, -0.2f, 0.3f), cache.read(keys[0])!!.samples, 0f)
        keys.drop(1).forEach { assertNull(cache.read(it)) }
    }
    @Test fun corruptOrTruncatedAudioIsRejectedAndFreshAudioCanReplaceIt() {
        val dir = temporary.newFolder(); val cache = NeuralSpeechCache(dir)
        val key = cache.key("model", "George", "Ready")
        cache.write(key, NeuralSpeechCache.Clip(24000, floatArrayOf(0.1f, 0.2f)))
        val file = File(dir, "$key.pcm"); val bytes = file.readBytes(); bytes[16] = (bytes[16].toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertNull(cache.read(key))
        file.writeBytes(bytes.copyOf(18)); assertNull(cache.read(key))
        cache.write(key, NeuralSpeechCache.Clip(24000, floatArrayOf(0.3f)))
        assertNotNull(cache.read(key))
        assertTrue(runCatching { cache.write(key, NeuralSpeechCache.Clip(24000, floatArrayOf(Float.NaN))) }.isFailure)
    }
    @Test fun cacheIsBoundedAndCanBeClearedWithoutTouchingOtherFiles() {
        val dir = temporary.newFolder(); val cache = NeuralSpeechCache(dir, maxBytes = 60)
        File(dir, "keep.txt").writeText("keep")
        repeat(4) { cache.write(cache.key("model", "George", "$it"), NeuralSpeechCache.Clip(24000, floatArrayOf(0.1f, 0.2f))) }
        assertTrue(cache.bytes() <= 60)
        cache.clear(); assertEquals(0L, cache.bytes()); assertTrue(File(dir, "keep.txt").isFile)
    }
}
