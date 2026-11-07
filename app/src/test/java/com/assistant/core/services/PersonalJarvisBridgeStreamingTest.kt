package com.assistant.core.services

import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class PersonalJarvisBridgeStreamingTest {
    @Test fun immediateHeadersUseChunkedSseNotContentLength() {
        val headers = String(PersonalJarvisBridgeStreaming.headers, StandardCharsets.US_ASCII)
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(headers.contains("Content-Type: text/event-stream"))
        assertTrue(headers.contains("Transfer-Encoding: chunked"))
        assertFalse(headers.contains("Content-Length:"))
        assertTrue(headers.endsWith("\r\n\r\n"))
    }

    @Test fun chunkUsesUtf8ByteLengthAndTerminatingFrame() {
        val body = "data: {\"text\":\"ready ✅\"}\n\n"
        val encoded = PersonalJarvisBridgeStreaming.chunk(body)
        val frame = String(encoded, StandardCharsets.UTF_8)
        val separator = frame.indexOf("\r\n")
        val encodedLength = frame.substring(0, separator).toInt(16)
        assertEquals(body.toByteArray(StandardCharsets.UTF_8).size, encodedLength)
        assertEquals(body, frame.substring(separator + 2, frame.length - 2))
        assertEquals("\r\n", frame.takeLast(2))
        assertEquals("0\r\n\r\n", String(PersonalJarvisBridgeStreaming.end, StandardCharsets.US_ASCII))
    }

    @Test fun keepalivesAreSseCommentsAndCompletionUsesNormalDataLines() {
        assertTrue(PersonalJarvisBridgeStreaming.CONNECTED.startsWith(":"))
        assertTrue(PersonalJarvisBridgeStreaming.KEEPALIVE.startsWith(":"))
        assertTrue(PersonalJarvisBridgeStreaming.CONNECTED.endsWith("\n\n"))
        val stream = PersonalJarvisBridgeProtocol.stream("Ready", created = 1)
        assertTrue(stream.contains("data: [DONE]\n\n"))
        assertTrue(stream.lineSequence().any { it.startsWith("data: ") && it.contains("Ready") })
        assertTrue(PersonalJarvisBridgeStreaming.timeoutMessage().contains("90 seconds"))
    }
}
