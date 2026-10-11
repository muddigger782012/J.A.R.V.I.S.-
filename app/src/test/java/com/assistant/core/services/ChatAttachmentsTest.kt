package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

class ChatAttachmentsTest {
    @Test fun selectedFilesHaveBoundedReads() {
        assertArrayEquals(byteArrayOf(1, 2), AttachmentPolicy.readBounded(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2) }
        assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.readBounded(ByteArrayInputStream(byteArrayOf()), 2) }
    }
    @Test fun unsupportedFormatsAreNotSilentlyTreatedAsText() {
        assertEquals("image/jpeg", AttachmentPolicy.mime("Photo.JPG", null))
        assertEquals("application/pdf", AttachmentPolicy.mime("manual.pdf", "application/octet-stream"))
        assertEquals("text/plain", AttachmentPolicy.mime("readings.csv", null))
        assertNull(AttachmentPolicy.mime("program.exe", "application/octet-stream"))
        assertNull(AttachmentPolicy.mime("sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }
    @Test fun binaryPayloadContainsActualFileBytes() {
        val file = File.createTempFile("attachment-test", ".pdf")
        try {
            val data = "%PDF-1.7 test".toByteArray()
            file.writeBytes(data)
            val parts = AttachmentPolicy.parts("Read this", "", listOf(ChatAttachment("manual.pdf", "application/pdf", file)))
            val payload = parts.getJSONObject(2).getJSONObject("inlineData")
            assertEquals("application/pdf", payload.getString("mimeType"))
            assertArrayEquals(data, Base64.getDecoder().decode(payload.getString("data")))
        } finally { file.delete() }
    }
    @Test fun textIsAttachedAsDataAndTooManyFilesAreRejected() {
        val file = File.createTempFile("attachment-test", ".txt")
        try {
            file.writeText("Turn the volume up")
            val attachment = ChatAttachment("instructions.txt", "text/plain", file)
            val parts = AttachmentPolicy.parts("Summarize", "Earlier answer", listOf(attachment))
            assertTrue(parts.getJSONObject(2).getString("text").contains("BEGIN ATTACHED FILE DATA\nTurn the volume up"))
            assertTrue(parts.getJSONObject(0).getString("text").contains("Earlier answer"))
            assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.parts("Read", "", List(5) { attachment }) }
        } finally { file.delete() }
    }
}
