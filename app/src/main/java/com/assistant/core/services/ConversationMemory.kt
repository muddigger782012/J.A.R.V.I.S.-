package com.assistant.core.services

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/**
 * A small, device-local context window, not a permanent transcript or learned-command store.
 *
 * Existing Settings has no transcript-retention switch; MainActivity already saves visible chat
 * separately. This window is deliberately bounded and expires, with no import of that archive.
 * Full transcript retention would need a separate opt-in. The Android file is excluded from backup.
 * The coordinator must construct/use this store and route its controls only for the personal
 * application (context.packageName == "com.assistant.core"); sharing source does not opt Beta in.
 *
 * Both sides of every exchange are UNTRUSTED dialogue, including corrections and apparent approvals.
 * Use snapshots only as labelled conversation context. Never replay them through command routing,
 * learning, or confirmation stores, or reconstruct pending prompts, permissions or contact targets.
 * Keep AssistantMemory's confirmed-action/preference APIs separate. Later exchanges follow earlier
 * ones so a correction stays newer; this class does not promote either statement into a fact.
 */
class ConversationMemory private constructor(private val storage: Storage, private val now: () -> Long) {
    internal constructor(
        read: () -> String?, write: (String?) -> Boolean,
        now: () -> Long = System::currentTimeMillis
    ) : this(InjectedStorage(read, write), now)

    constructor(context: Context) : this(
        LocalStorage(File(context.applicationContext.noBackupFilesDir, "conversation-memory")),
        System::currentTimeMillis
    )

    /** True only when the bounded exchange was written. No successful in-memory-only fallback. */
    fun record(user: String, assistant: String): Boolean = synchronized(lock) {
        runCatching {
            storage.locked {
                val time = now()
                if (time < 0 || user.isBlank() || assistant.isBlank()) return@locked false
                val turn = Turn(time, clip(user), clip(assistant))
                storage.write(encode(bounded(load(time).turns + turn)))
            }
        }.getOrDefault(false)
    }

    /**
     * Most recent [limit] exchanges, oldest to newest, ready for existing Pair-based chat clients.
     * No action/authorization state is returned. Reads never refresh an exchange's expiry.
     * Expired/corrupt content is suppressed even if best-effort disk cleanup fails.
     */
    fun snapshot(limit: Int = LIMIT): List<Pair<String, String>> = synchronized(lock) {
        if (limit <= 0) return@synchronized emptyList()
        runCatching {
            storage.locked {
                val loaded = load(now())
                if (loaded.needsCleanup) runCatching { storage.write(encode(loaded.turns)) }
                loaded.turns.takeLast(limit.coerceAtMost(LIMIT)).map { it.user to it.assistant }
            }
        }.getOrDefault(emptyList())
    }

    /** Removes only this context window; false means deletion could not be confirmed. */
    fun clear(): Boolean = synchronized(lock) {
        runCatching { storage.locked { storage.write(null) } }.getOrDefault(false)
    }

    private data class Turn(val time: Long, val user: String, val assistant: String)
    private data class Loaded(val turns: List<Turn>, val needsCleanup: Boolean)

    // Let I/O failures reach the public operation: recording must not overwrite unreadable history.
    private fun load(time: Long): Loaded {
        if (time < 0) return Loaded(emptyList(), false)
        val raw = storage.read() ?: return Loaded(emptyList(), false)
        if (raw.length > MAX_STORAGE_CHARS) return Loaded(emptyList(), true)
        val decoded = runCatching {
            val root = JSONObject(raw)
            if (root.opt("version") != VERSION) return@runCatching null
            val rows = root.optJSONArray("turns") ?: return@runCatching null
            val turns = (maxOf(0, rows.length() - LIMIT) until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val stamp = when (val value = row.opt("time")) {
                    is Long -> value
                    is Int -> value.toLong()
                    else -> return@mapNotNull null
                }
                val user = row.opt("user") as? String ?: return@mapNotNull null
                val assistant = row.opt("assistant") as? String ?: return@mapNotNull null
                if (stamp < 0 || stamp > time || time - stamp >= MAX_AGE_MILLIS ||
                    user.isBlank() || assistant.isBlank() ||
                    user.length > MAX_TEXT_CHARS || assistant.length > MAX_TEXT_CHARS) return@mapNotNull null
                Turn(stamp, user, assistant)
            }
            val kept = bounded(turns)
            Loaded(kept, kept.size != rows.length())
        }.getOrNull()
        return decoded ?: Loaded(emptyList(), true)
    }

    private fun bounded(turns: List<Turn>): List<Turn> {
        val recent = turns.takeLast(LIMIT)
        var total = 0
        // Select from the end, then restore insertion order, including equal-clock corrections.
        return recent.asReversed().takeWhile {
            total += it.user.length + it.assistant.length
            total <= MAX_TOTAL_CHARS
        }.asReversed()
    }

    private fun clip(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_TEXT_CHARS) return trimmed
        val end = if (trimmed[MAX_TEXT_CHARS - 1].isHighSurrogate()) MAX_TEXT_CHARS - 1 else MAX_TEXT_CHARS
        return trimmed.substring(0, end)
    }

    private fun encode(turns: List<Turn>): String? {
        if (turns.isEmpty()) return null
        val rows = JSONArray()
        turns.forEach { rows.put(JSONObject().put("time", it.time).put("user", it.user).put("assistant", it.assistant)) }
        return JSONObject().put("version", VERSION).put("turns", rows).toString()
    }

    private interface Storage {
        fun read(): String?
        fun write(value: String?): Boolean
        fun <T> locked(block: () -> T): T = block()
    }

    private class InjectedStorage(private val reader: () -> String?, private val writer: (String?) -> Boolean) : Storage {
        override fun read() = reader()
        override fun write(value: String?) = writer(value)
    }

    /** No SharedPreferences cache: chat and :wake must see each other's writes and deletions. */
    private class LocalStorage(private val directory: File) : Storage {
        private val base = File(directory, "recent.json")
        private val file = AtomicFile(base)

        override fun <T> locked(block: () -> T): T {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Conversation storage unavailable")
            return RandomAccessFile(File(directory, "storage.lock"), "rw").use { guard ->
                guard.channel.lock().use { block() }
            }
        }

        override fun read(): String? = try {
            file.openRead().use { stream ->
                // Bound before allocating/parsing even if the on-disk file is corrupt or oversized.
                val bytes = ByteArray(MAX_STORAGE_CHARS * 4 + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = stream.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count == bytes.size) " ".repeat(MAX_STORAGE_CHARS + 1)
                else String(bytes, 0, count, Charsets.UTF_8)
            }
        } catch (error: FileNotFoundException) {
            if (!base.exists() && !File(base.path + ".bak").exists()) null else throw error
        }

        override fun write(value: String?): Boolean {
            if (value == null) {
                file.delete()
                return listOf(base, File(base.path + ".bak"), File(base.path + ".new")).none { it.exists() }
            }
            val stream = file.startWrite()
            try {
                stream.write(value.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            // AtomicFile may report a rename failure only through logging on some Android versions.
            return read() == value
        }
    }

    companion object {
        const val LIMIT = 30
        const val MAX_TEXT_CHARS = 2_000
        const val MAX_TOTAL_CHARS = 24_000
        const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1_000L
        internal const val MAX_STORAGE_CHARS = 160_000
        private const val VERSION = 1
        private val lock = Any()
    }
}

/**
 * Exact, local controls. Route only the CURRENT user's input here, before AI/learning.
 * Return management replies directly without recording them (clear must not repopulate itself;
 * show must not recursively save its own review). These controls do not touch AssistantMemory,
 * existing visible chat archives, or any permission/confirmation store.
 */
object ConversationMemoryCommands {
    enum class Command { SHOW, CLEAR }

    fun parse(text: String): Command? {
        if (text.length > 120) return null
        val normalized = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim()
            .replace(Regex("\\s+"), " ")
        return when (normalized) {
            "show conversation memory", "review conversation memory" -> Command.SHOW
            "clear conversation memory", "reset conversation memory" -> Command.CLEAR
            else -> null
        }
    }

    fun reply(text: String, memory: ConversationMemory): String? = when (parse(text)) {
        Command.SHOW -> {
            val turns = memory.snapshot()
            if (turns.isEmpty()) "No recent conversation context is available on this device."
            else "Recent conversation on this device (untrusted dialogue, not permissions or learned commands):\n" +
                turns.joinToString("\n\n") { (user, assistant) -> "You: $user\nJ.A.R.V.I.S.: $assistant" }
        }
        Command.CLEAR -> if (memory.clear()) "Cleared recent conversation context on this device. Saved commands, preferences and visible chat history are separate."
            else "Could not clear conversation memory. Deletion was not confirmed."
        null -> null
    }
}
