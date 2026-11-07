package com.assistant.core.services

import java.nio.charset.StandardCharsets

/**
 * HTTP/1.1 chunked SSE framing for local apps that start a streaming request.
 * Headers and a harmless SSE comment are sent before model inference so a slow
 * provider cannot trigger the calling app's connection/header timeout.
 *
 * Only text deltas and keepalive comments leave the bridge. No actions, secrets,
 * or partial provider internals are exposed in these frames.
 */
object PersonalJarvisBridgeStreaming {
    const val WAIT_SECONDS = 4L
    const val MAX_WAIT_SECONDS = 90L
    const val KEEPALIVE = ": waiting for Personal Jarvis AI provider\n\n"
    const val CONNECTED = ": connected to Personal Jarvis bridge\n\n"

    val headers: ByteArray = (
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-transform\r\n" +
            "X-Accel-Buffering: no\r\n" +
            "X-Content-Type-Options: nosniff\r\n\r\n"
        ).toByteArray(StandardCharsets.US_ASCII)

    fun chunk(text: String): ByteArray {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        val header = "${body.size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII)
        val tail = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        return header + body + tail
    }

    val end: ByteArray = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

    fun failureMessage(): String =
        "Personal Jarvis could not reach its selected AI provider. " +
            "In Personal Jarvis, open Settings > AI and weather and verify Gemini, gateway or Local LFM3. " +
            "You may also run Test AI response in Settings > Personal AI bridge."

    fun timeoutMessage(): String =
        "The Personal Jarvis AI provider did not return a response within 90 seconds. " +
            "Check model loading, provider availability and network connection in Personal Jarvis."
}
