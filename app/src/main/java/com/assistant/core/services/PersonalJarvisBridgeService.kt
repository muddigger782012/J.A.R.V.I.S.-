package com.assistant.core.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.assistant.core.PersonalJarvisBridgeActivity
import com.assistant.core.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** User-initiated, foreground, loopback-only endpoint. Deliberately no actions or tools. */
class PersonalJarvisBridgeService : Service() {
    companion object {
        const val ACTION_START = "com.assistant.core.personalbridge.START"
        const val ACTION_STOP = "com.assistant.core.personalbridge.STOP"
        private const val CHANNEL = "personal_jarvis_bridge"
        private const val NOTIFICATION_ID = 8726
        @Volatile var listening = false
            private set
    }

    private val started = AtomicBoolean(false)
    private val acceptor = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(2)
    private val inference = Executors.newFixedThreadPool(2)
    @Volatile private var server: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Even if Beta inherits the manifest declaration, it cannot expose a server.
        if (packageName != "com.assistant.core") {
            stopSelf()
            return START_NOT_STICKY
        }
        val channel = NotificationChannel(CHANNEL, "Personal AI bridge", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, PersonalJarvisBridgeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PersonalJarvisBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Personal Jarvis AI bridge")
            .setContentText("Local AI connection enabled on this phone")
            .setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop bridge", stop).build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (started.compareAndSet(false, true)) acceptor.execute { serve() }
        return START_NOT_STICKY // Explicit user action is required after process death.
    }

    private fun serve() {
        try {
            ServerSocket(PersonalJarvisBridgeProtocol.PORT, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
                server = listener
                listening = true
                while (!Thread.currentThread().isInterrupted && !listener.isClosed) {
                    val client = listener.accept()
                    workers.execute {
                        client.use { runCatching { handle(it) } }
                    }
                }
            }
        } catch (_: Exception) {
            // Port already occupied or service stopped. Never bind to an alternate interface.
        } finally {
            listening = false
            server = null
            stopSelf()
        }
    }

    private fun line(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= 8192) {
            val value = input.read()
            if (value < 0) throw IllegalArgumentException("Unexpected end of HTTP request")
            if (value == 10) {
                val raw = bytes.toByteArray()
                return String(raw, 0, if (raw.isNotEmpty() && raw.last() == 13.toByte()) raw.size - 1 else raw.size,
                    StandardCharsets.US_ASCII)
            }
            bytes.write(value)
        }
        throw IllegalArgumentException("HTTP header is too long")
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 60000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var streamingStarted = false
        try {
            val parts = line(input).split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw IllegalArgumentException("Bad HTTP request")
            val method = parts[0]
            val path = parts[1]
            val headers = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val h = line(input)
                if (h.isEmpty()) break
                if (++count > 40) throw IllegalArgumentException("Too many headers")
                val at = h.indexOf(':')
                if (at <= 0) throw IllegalArgumentException("Malformed header")
                val key = h.substring(0, at).trim().lowercase()
                if (headers.containsKey(key)) throw IllegalArgumentException("Duplicate header")
                headers[key] = h.substring(at + 1).trim()
            }
            if (!PersonalJarvisBridgeProtocol.authorized(headers["authorization"], PersonalJarvisBridgeToken.getOrCreate(this))) {
                respond(output, 401, PersonalJarvisBridgeProtocol.error("Invalid bridge token", "unauthorized"))
                return
            }
            if (method == "GET" && path == "/v1/models") {
                respond(output, 200, PersonalJarvisBridgeProtocol.models())
                return
            }
            if (method != "POST" || path != "/v1/chat/completions") {
                respond(output, 404, PersonalJarvisBridgeProtocol.error("Unsupported route", "not_found"))
                return
            }
            if (headers.containsKey("transfer-encoding")) throw IllegalArgumentException("Chunked requests are not supported")
            val size = headers["content-length"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Content-Length required")
            if (size !in 1..PersonalJarvisBridgeProtocol.MAX_BODY_BYTES) {
                respond(output, 413, PersonalJarvisBridgeProtocol.error("Request body too large", "body_limit"))
                return
            }
            val bytes = ByteArray(size)
            var done = 0
            while (done < size) {
                val read = input.read(bytes, done, size - done)
                if (read < 0) throw IllegalArgumentException("Truncated request")
                done += read
            }
            val prompt = PersonalJarvisBridgeProtocol.parse(String(bytes, StandardCharsets.UTF_8))
            if (prompt.stream) {
                // Return HTTP headers immediately: external Jarvis gives up if it
                // waits 45 seconds for a slow model before any headers arrive.
                streamingStarted = true
                streamAnswer(output, prompt)
                return
            }
            val routed = route(prompt)
            if (routed == null || routed.answer.providerFailed || routed.answer.reply.isBlank()) {
                respond(output, 503, PersonalJarvisBridgeProtocol.error(
                    "Personal Jarvis provider is unavailable. Check AI settings and disable unwanted fallback.", "provider_unavailable"))
                return
            }
            respond(output, 200, PersonalJarvisBridgeProtocol.completion(routed.answer.reply))
        } catch (_: Exception) {
            // After streaming headers are sent, another HTTP status line would
            // corrupt the event stream. A disconnected client also needs no reply.
            if (!streamingStarted) runCatching {
                respond(output, 400, PersonalJarvisBridgeProtocol.error("Invalid or interrupted request", "invalid_request"))
            }
        }
    }

    private fun streamAnswer(
        output: BufferedOutputStream,
        prompt: PersonalJarvisBridgeProtocol.Prompt
    ) {
        output.write(PersonalJarvisBridgeStreaming.headers)
        output.flush()
        val future = inference.submit<IntelligenceRouting.Routed> { route(prompt) }
        try {
            writeSseChunk(output, PersonalJarvisBridgeStreaming.CONNECTED)
            var elapsed = 0L
            while (true) {
                val reply: String = try {
                    val routed = future.get(PersonalJarvisBridgeStreaming.WAIT_SECONDS, TimeUnit.SECONDS)
                    if (routed.answer.providerFailed || routed.answer.reply.isBlank()) {
                        PersonalJarvisBridgeStreaming.failureMessage()
                    } else routed.answer.reply
                } catch (_: TimeoutException) {
                    elapsed += PersonalJarvisBridgeStreaming.WAIT_SECONDS
                    if (elapsed >= PersonalJarvisBridgeStreaming.MAX_WAIT_SECONDS) {
                        future.cancel(true)
                        PersonalJarvisBridgeStreaming.timeoutMessage()
                    } else {
                        // SSE comments are intentionally ignored by Flutter, but
                        // keep the connection live while the provider is busy.
                        writeSseChunk(output, PersonalJarvisBridgeStreaming.KEEPALIVE)
                        continue
                    }
                } catch (_: Exception) {
                    PersonalJarvisBridgeStreaming.failureMessage()
                }
                writeSseChunk(output, PersonalJarvisBridgeProtocol.stream(reply))
                break
            }
            output.write(PersonalJarvisBridgeStreaming.end)
            output.flush()
        } finally {
            future.cancel(true)
        }
    }

    private fun writeSseChunk(output: BufferedOutputStream, text: String) {
        output.write(PersonalJarvisBridgeStreaming.chunk(text))
        output.flush()
    }

    private fun route(prompt: PersonalJarvisBridgeProtocol.Prompt): IntelligenceRouting.Routed {
        val prefs = RuntimeSettings.read(this, "jarvis_cloud_ai")
        val gemini = DirectOnlineClient(this)
        val gateway = FallbackAiClient(this)
        val local = LocalLfm3Client(this)
        val available = buildSet {
            if (gemini.hasGeminiKey()) add(IntelligenceRouting.Provider.GEMINI)
            if (gateway.isConfigured()) add(IntelligenceRouting.Provider.GATEWAY)
            if (local.isConfigured()) add(IntelligenceRouting.Provider.LOCAL_LFM3)
        }
        val selected = IntelligenceRouting.selected(prefs.getString("primary_provider", "gemini"))
        val conversation = prompt.history.joinToString("\n") { (user, assistant) ->
            "User: ${user.take(2000)}\nJarvis: ${assistant.take(2000)}"
        }.take(9000)
        return IntelligenceRouting.ask(selected, prefs.getBoolean("provider_fallback", true), available) { provider ->
            when (provider) {
                IntelligenceRouting.Provider.GEMINI -> gemini.geminiTextReply(prompt.text, conversation)
                IntelligenceRouting.Provider.GATEWAY -> gateway.ask(prompt.text,
                    learnIntent = false, includeLocation = false, capabilityContext = "{}",
                    conversation = prompt.history)
                IntelligenceRouting.Provider.LOCAL_LFM3 -> local.ask(
                    AssistantPersonalityPreferences.instructions(this).take(2000) + "\n" + prompt.text,
                    prompt.history)
            }
        }
    }

    private fun respond(output: BufferedOutputStream, status: Int, content: String, type: String = "application/json; charset=utf-8") {
        val data = content.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            413 -> "Payload Too Large"; 503 -> "Service Unavailable"; else -> "Error"
        }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(data)
        output.flush()
    }

    override fun onDestroy() {
        listening = false
        runCatching { server?.close() }
        acceptor.shutdownNow()
        workers.shutdownNow()
        inference.shutdownNow()
        super.onDestroy()
    }
}

/** Stored under noBackupFilesDir, not in an exported preference, APK, or GitHub source. */
object PersonalJarvisBridgeToken {
    private const val FILE = "personal-bridge-key-v1"

    @Synchronized fun getOrCreate(context: Context): String {
        val existing = runCatching { File(context.noBackupFilesDir, FILE).readText().trim() }.getOrNull()
        if (existing != null && Regex("[0-9a-f]{64}").matches(existing)) return existing
        return writeNew(context)
    }

    /** Revokes the old bearer secret for every *new* request. Existing replies may finish. */
    @Synchronized fun rotate(context: Context): String = writeNew(context)

    private fun writeNew(context: Context): String {
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)
        val token = secret.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val path = File(context.noBackupFilesDir, FILE)
        val tmp = File(context.noBackupFilesDir, "$FILE.tmp")
        try {
            tmp.writeText(token)
            // Same-directory atomic replacement: failed rotation keeps the old key.
            java.nio.file.Files.move(tmp.toPath(), path.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
        return token
    }
}
