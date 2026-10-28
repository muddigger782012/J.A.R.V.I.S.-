package com.jarvis.developer

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Private unsent text only. A saved draft is neither a proposal nor authority to execute one. */
internal class DevelopmentJobDrafts(directory: File, atomicReplace: ((Path, Path) -> Unit)? = null) {
    data class Key(val job: String, val revision: Long, val target: PatchEngine.Target, val source: String) {
        init {
            Wire.validate(Wire.Request("local-draft", "task.status.request", jobId = job))
            require(revision in 0..1_000_000 && source.matches(Regex("[a-f0-9]{40}")))
        }
    }
    data class Text(val goal: String, val paths: String)
    data class Snapshot(val sequence: Long, val text: Text?, val matches: Boolean)
    private data class Entry(val key: Key, val sequence: Long, val text: Text?)
    private val checkpoint = if (atomicReplace == null) PrivateCheckpointFile(directory, FILE, 32, MAX_BYTES)
        else PrivateCheckpointFile(directory, FILE, 32, MAX_BYTES, atomicReplace)

    fun read(key: Key): Snapshot = checkpoint.transaction { snapshot(load().singleOrNull { it.key.job == key.job }, key) }

    fun save(key: Key, expectedSequence: Long, text: Text): Snapshot = change(key, expectedSequence,
        Text(BuildDiagnostics.localTaskDraft(text.goal), BuildDiagnostics.localTaskDraft(text.paths)))

    /** A tombstone rejects delayed saves from old cards instead of resurrecting forgotten text. */
    fun forget(key: Key, expectedSequence: Long): Snapshot = change(key, expectedSequence, null)

    private fun change(key: Key, expectedSequence: Long, text: Text?): Snapshot = checkpoint.transaction {
        require(expectedSequence in 0 until 1_000_000)
        val entries = load()
        val old = entries.singleOrNull { it.key.job == key.job }
        check((old?.sequence ?: 0) == expectedSequence) { "Saved draft changed; reload it before retrying." }
        val next = Entry(key, expectedSequence + 1, text)
        val updated = entries.filter { it.key.job != key.job } + next
        require(updated.size <= 64)
        val payload = JSONArray(updated.map { entry ->
            JSONObject().put("job", entry.key.job).put("revision", entry.key.revision)
                .put("target", entry.key.target.name).put("source", entry.key.source).put("sequence", entry.sequence)
                .put("text", entry.text?.let { JSONObject().put("goal", it.goal).put("paths", it.paths) } ?: JSONObject.NULL)
        }).toString()
        val bytes = JSONObject().put("version", 1).put("digest", hash(payload)).put("payload", payload)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        checkpoint.write(bytes)
        snapshot(next, key)
    }

    private fun snapshot(entry: Entry?, key: Key) = Snapshot(entry?.sequence ?: 0,
        entry?.text?.takeIf { entry.key == key }, entry == null || entry.key == key)

    private fun load(): List<Entry> {
        val bytes = checkpoint.read() ?: return emptyList()
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("version", "digest", "payload") && root.get("version") == 1)
        val payload = root.getString("payload")
        require(root.getString("digest") == hash(payload))
        val rows = JSONArray(payload); require(rows.length() <= 64)
        val entries = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("job", "revision", "target", "source", "sequence", "text"))
            val key = Key(row.getString("job"), number(row, "revision"),
                PatchEngine.Target.valueOf(row.getString("target")), row.getString("source"))
            val sequence = number(row, "sequence"); require(sequence in 1..1_000_000)
            val text = if (row.isNull("text")) null else row.getJSONObject("text").let {
                require(it.keys().asSequence().toSet() == setOf("goal", "paths"))
                Text(it.getString("goal"), it.getString("paths")).also { value ->
                    require(BuildDiagnostics.localTaskDraft(value.goal) == value.goal &&
                        BuildDiagnostics.localTaskDraft(value.paths) == value.paths)
                }
            }
            Entry(key, sequence, text)
        }
        require(entries.map { it.key.job }.distinct().size == entries.size)
        return entries
    }

    companion object {
        private const val FILE = "development-job-drafts.bin"
        private const val MAX_BYTES = 1_048_576
        fun key(job: JSONObject): Key {
            require(job.getString("kind") in Wire.sourceAnalysisKinds)
            return Key(job.getString("id"), job.getLong("revision"),
                if (job.getString("kind") == "source.personal.analysis.request") PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER,
                job.getJSONObject("analysis").getString("commit"))
        }
        private fun number(row: JSONObject, name: String): Long {
            val value = row.get(name)
            require(value is Int || value is Long)
            return (value as Number).toLong()
        }
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
