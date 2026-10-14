package com.assistant.core.services

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Bounded per-chat context. It cannot approve actions or add learned commands. */
class ConversationContext(private val directory: File) {
    data class Token(val id: String, val generation: Long, val epoch: String = "")
    private var current = Token("", 0)
    @Synchronized fun bind(id: String, seed: List<Pair<String, String>> = emptyList(), force: Boolean = false, epoch: String = ""): Token {
        require(id.length in 1..200)
        if (id != current.id || epoch != current.epoch || force) current = Token(id, current.generation + 1, epoch)
        if (seed.isNotEmpty() && snapshot().isEmpty()) seed.takeLast(12).forEach { remember(current, it.first, it.second) }
        return current
    }
    @Synchronized fun token() = current
    @Synchronized fun accepts(token: Token) = token == current && token.id.isNotBlank()
    private fun file(id: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "chat-context-$hash.json")
    }
    @Synchronized fun snapshot(): List<Pair<String, String>> = read(current.id)
    private fun read(id: String): List<Pair<String, String>> = if (id.isBlank()) emptyList() else runCatching {
        val source = file(id); require(source.length() <= MAX_BYTES)
        val raw = source.readText(); require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val array = JSONArray(raw)
        (0 until minOf(array.length(), 12)).mapNotNull { i ->
            val row = array.getJSONObject(i)
            val user = row.getString("user"); val assistant = row.getString("assistant")
            (user to assistant).takeIf { allowed(user) && user.length <= 1800 && assistant.length <= 3000 }
        }
    }.getOrDefault(emptyList())
    @Synchronized fun remember(token: Token, user: String, assistant: String): Boolean {
        if (!accepts(token) || !allowed(user)) return false
        return runCatching {
            synchronized(lock) {
                directory.mkdirs()
                RandomAccessFile(File(directory, "chat-context.lock"), "rw").use { mutex -> mutex.channel.lock().use {
                    val rows = (read(token.id) + (user.take(1800) to assistant.take(3000))).takeLast(12).toMutableList()
                    fun bytes() = JSONArray().apply { rows.forEach { (u, a) -> put(JSONObject().put("user", u).put("assistant", a)) } }.toString().toByteArray(Charsets.UTF_8)
                    var serialized = bytes()
                    // Escaping and UTF-8 can exceed character bounds; keep the newest complete exchanges.
                    while (serialized.size > MAX_BYTES && rows.size > 1) { rows.removeAt(0); serialized = bytes() }
                    require(serialized.size <= MAX_BYTES)
                    val destination = file(token.id); val temporary = File(directory, destination.name + ".tmp")
                    FileOutputStream(temporary).use { it.write(serialized); it.fd.sync() }
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    directory.listFiles { f -> f.name.startsWith("chat-context-") && f.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
                } }
            }
            true
        }.getOrDefault(false)
    }
    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 65536
        fun allowed(user: String): Boolean {
            val text = user.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
            if (user.isBlank() || CapabilityDiscovery.isManagementText(user) || LearnedCommandReviewCommands.page(user) != null ||
                text in setOf("assistant memory", "what do you remember about my apps and timers", "learning status", "show learning status", "how many commands have you learned")) return false
            return !text.startsWith("show saved") && !text.startsWith("show learned") && !text.startsWith("show assistant memory") &&
                !text.startsWith("show result") && !text.startsWith("show diagnostics") && !text.startsWith("list routines") && !text.startsWith("show routines") &&
                !CapabilityDiscovery.isManagementText(user) && !text.startsWith("review learned") && !text.startsWith("forget ") && !text.startsWith("teach ")
        }
        fun pairs(lines: List<String>): List<Pair<String, String>> {
            val rows = mutableListOf<Pair<String, String>>()
            var user: String? = null
            lines.forEach { line ->
                if (line.startsWith("You:")) user = line.removePrefix("You:").trim()
                else if (line.startsWith("J.A.R.V.I.S.:")) {
                    val question = user
                    if (question != null && allowed(question)) rows.add(question to line.removePrefix("J.A.R.V.I.S.:").trim())
                    user = null
                }
            }
            return rows.takeLast(12)
        }
    }
}

/** Durable selection epoch shared by the UI and :wake process, including reopening the same chat. */
internal class ConversationIdentityStore(private val directory: File) {
    data class Identity(val id: String, val epoch: String)
    fun snapshot(): Identity {
        val saved = runCatching {
            val file = File(directory, "live-chat-identity.json"); require(file.length() <= 4096)
            val json = JSONObject(file.readText())
            val id = json.getString("id"); val epoch = json.getString("epoch")
            require(id.length in 1..200 && epoch.matches(Regex("[a-f0-9-]{36}")))
            Identity(id, epoch)
        }.getOrNull()
        if (saved != null) return saved
        // Preserve chats created by APKs that only wrote the compatibility text file.
        val legacy = runCatching { File(directory, "live-chat-conversation.txt").readText().takeIf { it.length in 1..200 } }.getOrNull().orEmpty()
        return Identity(legacy, if (legacy.isBlank()) "" else "legacy")
    }
    fun activate(id: String, force: Boolean = false): Identity = locked {
        require(id.length in 1..200)
        val current = snapshot()
        if (!force && current.id == id && current.epoch != "legacy") return@locked current
        val identity = Identity(id, java.util.UUID.randomUUID().toString())
        write("live-chat-conversation.txt", id)
        write("live-chat-identity.json", JSONObject().put("id", id).put("epoch", identity.epoch).toString())
        identity
    }
    fun <T> withCurrent(expected: Identity, block: () -> T): T? = try {
        locked { if (current() == expected) block() else null }
    } catch (_: java.io.IOException) { null }
    fun current(): Identity = snapshot().let { it.copy(id = it.id.ifBlank { "device" }) }
    internal fun write(name: String, text: String) {
        val temporary = File(directory, "$name.tmp")
        FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    internal fun <T> locked(block: () -> T): T = synchronized(processLock) {
        val path = directory.canonicalPath
        if (lockedDirectory.get() == path) return@synchronized block()
        directory.mkdirs()
        RandomAccessFile(File(directory, "live-chat-identity.lock"), "rw").use { mutex -> mutex.channel.lock().use {
            val previous = lockedDirectory.get()
            lockedDirectory.set(path)
            try { block() } finally { if (previous == null) lockedDirectory.remove() else lockedDirectory.set(previous) }
        } }
    }
    companion object {
        internal val processLock = Any()
        private val lockedDirectory = ThreadLocal<String>()
    }
}

/** The original request identity, rather than whichever chat is active when a provider finishes. */
internal object ConversationRequestScope {
    val identity = ThreadLocal<ConversationIdentityStore.Identity>()
}

/** Cross-process pending proposals. Deletion is conditional on the exact revision read. */
internal class ConversationPendingStore(private val directory: File, name: String) {
    private val identities = ConversationIdentityStore(directory)
    private val filename = "conversation-pending-$name.json"
    private val file get() = File(directory, filename)
    private var observed: String? = null
    init { require(name.matches(Regex("[a-z-]+"))) }
    private fun entry(): JSONObject? = runCatching {
        require(file.length() <= 65536)
        JSONObject(file.readText())
    }.getOrNull()
    fun read(): String? = runCatching { identities.locked {
        observed = null
        val current = identities.current()
        val expected = ConversationRequestScope.identity.get()
        if (expected != null && expected != current) return@locked null
        val entry = entry() ?: return@locked null
        if (entry.optString("conversation") != current.id || entry.optString("epoch") != current.epoch) return@locked null
        val value = entry.optString("pending").takeIf { it.isNotBlank() } ?: return@locked null
        observed = entry.optString("revision").takeIf { it.isNotBlank() } ?: return@locked null
        value
    } }.getOrNull()
    fun write(value: String?): Boolean = runCatching {
        identities.locked {
            val current = identities.current()
            val expected = ConversationRequestScope.identity.get()
            if (expected != null && expected != current) return@locked false
            if (value == null) {
                val revision = observed ?: return@locked false
                val entry = entry() ?: return@locked false
                if (entry.optString("revision") != revision || entry.optString("conversation") != current.id || entry.optString("epoch") != current.epoch) return@locked false
                observed = null
                !file.exists() || file.delete()
            } else {
                val encoded = JSONObject().put("conversation", current.id).put("epoch", current.epoch)
                    .put("revision", java.util.UUID.randomUUID().toString()).put("pending", value).toString()
                require(encoded.toByteArray(Charsets.UTF_8).size <= 65536)
                identities.write(filename, encoded)
                observed = null
                true
            }
        }
    }.getOrDefault(false)
}

