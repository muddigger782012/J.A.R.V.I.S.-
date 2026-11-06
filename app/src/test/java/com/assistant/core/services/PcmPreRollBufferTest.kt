package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class PcmPreRollBufferTest {
    @Test fun retainsOnlyRecentFramesWithoutKeepingUnboundedConversationAudio() {
        val buffer = PcmPreRollBuffer(3)
        repeat(8) { buffer.offer(byteArrayOf(it.toByte())) }
        assertEquals(3, buffer.size)
        assertEquals(listOf(5, 6, 7), buffer.drain().map { it[0].toInt() })
        assertEquals(0, buffer.size)
    }

    @Test fun copiesIncomingAudioAndClearsAfterUserTurn() {
        val buffer = PcmPreRollBuffer(2)
        val source = byteArrayOf(5, 6)
        buffer.offer(source)
        source[0] = 99
        assertArrayEquals(byteArrayOf(5, 6), buffer.drain().single())
        buffer.offer(byteArrayOf(7))
        buffer.clear()
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun ignoresEmptyFramesAndRejectsUnlimitedPreroll() {
        val buffer = PcmPreRollBuffer()
        buffer.offer(byteArrayOf())
        assertEquals(0, buffer.size)
        assertThrows(IllegalArgumentException::class.java) { PcmPreRollBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { PcmPreRollBuffer(251) }
    }
}
