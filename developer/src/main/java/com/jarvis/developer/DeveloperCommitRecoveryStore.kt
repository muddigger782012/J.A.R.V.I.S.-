package com.jarvis.developer

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private, bounded, READ-ONLY identity of an already validated GitHub commit.
 * No source, diff, prompt, provider key, token, approval or write credential
 * survives restart. This is a bookmark, not independently verified CI proof.
 * A caller must freshly GET exact CI and validate the current saved plan.
 */
internal class DeveloperCommitRecoveryStore(directory: File) {
    private val file = PrivateCheckpointFile(directory, "developer-commit-recovery.bin",
        HEADER_SIZE, 64 * 1024)

    fun load(): List<PatchPublisher.CommitResult> = file.transaction { read() }

    /** Call only AFTER PatchPublisher.validateOutcome returns for this commit. */
    fun save(commit: PatchPublisher.CommitResult): Unit = file.transaction {
        validate(commit)
        val current = read()
        val entries = (listOf(commit.copy(diff = "")) +
            current.filter { it.commitSha != commit.commitSha || it.branch != commit.branch })
            .take(MAX_COMMITS)
        val json = JSONArray()
        entries.forEach { r ->
            json.put(JSONObject()
                .put("taskId", r.taskId).put("provider", r.provider)
                .put("model", r.model).put("proposalId", r.proposalId)
                .put("baseCommitSha", r.baseCommitSha).put("commitSha", r.commitSha)
                .put("branch", r.branch).put("baseBranch", r.baseBranch)
                .put("target", r.target.name)
                .put("paths", JSONArray(r.paths)))
        }
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD)
        val bytes = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .put(MAGIC).putInt(VERSION).putInt(payload.size)
            .put(hash(payload)).put(payload).array()
        file.write(bytes)
    }

    private fun read(): List<PatchPublisher.CommitResult> {
        val bytes = file.read() ?: return emptyList()
        val input = ByteBuffer.wrap(bytes)
        val magic = ByteArray(MAGIC.size).also(input::get)
        require(magic.contentEquals(MAGIC) && input.int == VERSION) {
            "Developer recovery checkpoint has unsupported format"
        }
        val length = input.int
        require(length >= 2 && length <= MAX_PAYLOAD && length == input.remaining() - HASH_SIZE) {
            "Developer recovery checkpoint length is invalid"
        }
        val expected = ByteArray(HASH_SIZE).also(input::get)
        val payload = ByteArray(length).also(input::get)
        require(!input.hasRemaining() && MessageDigest.isEqual(expected, hash(payload))) {
            "Developer recovery checkpoint hash mismatch"
        }
        val entries = JSONArray(String(payload, StandardCharsets.UTF_8))
        require(entries.length() <= MAX_COMMITS) { "Too many recovered commits" }
        val results = ArrayList<PatchPublisher.CommitResult>()
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val paths = item.getJSONArray("paths")
            require(paths.length() in 1..PatchEngine.MAX_FILES)
            val restored = PatchPublisher.CommitResult(
                item.getString("taskId"), item.getString("provider"),
                item.getString("model"), item.getString("proposalId"),
                (0 until paths.length()).map(paths::getString),
                item.getString("baseCommitSha"), item.getString("commitSha"),
                item.getString("branch"), "", item.getString("baseBranch"),
                PatchEngine.Target.valueOf(item.getString("target"))
            )
            validate(restored)
            require(results.none { it.commitSha == restored.commitSha && it.branch == restored.branch }) {
                "Duplicate recovery commit"
            }
            results += restored
        }
        return java.util.Collections.unmodifiableList(results)
    }

    private fun validate(value: PatchPublisher.CommitResult) {
        PatchPublisher.validateResult(value)
        require(value.target == PatchEngine.Target.DEVELOPER) {
            "Only isolated Developer task identities may be recovered"
        }
        require(value.diff.isEmpty() || value.diff.length <= 1_000_000)
        require(value.provider.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/ -]{0,119}")) &&
            value.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/ -]{0,119}")))
        require(value.paths.all { it.length <= 240 })
    }

    companion object {
        private val MAGIC = "JARVDCR1".toByteArray(StandardCharsets.US_ASCII)
        private const val VERSION = 1
        private const val HASH_SIZE = 32
        private const val HEADER_SIZE = 8 + 4 + 4 + HASH_SIZE
        private const val MAX_PAYLOAD = 64 * 1024 - HEADER_SIZE
        private const val MAX_COMMITS = 10
        private fun hash(input: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(input)
    }
}
