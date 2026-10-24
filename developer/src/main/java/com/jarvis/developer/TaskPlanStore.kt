package com.jarvis.developer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Developer-private task metadata only. This store never reads or writes repository source. */
class TaskPlanStore private constructor(
    directory: File,
    private val atomicReplace: (Path, Path) -> Unit,
    @Suppress("UNUSED_PARAMETER") internalMarker: Boolean
) {
    constructor(directory: File) : this(directory, { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }, true)

    /** Allows the failure path to be exercised without filesystem-permission assumptions. */
    internal constructor(directory: File, atomicReplace: (Path, Path) -> Unit) :
        this(directory, atomicReplace, true)

    private val checkpoint = PrivateCheckpointFile(directory, FILE_NAME, HEADER_BYTES, MAX_BYTES, atomicReplace)

    /** A damaged checkpoint is an error; there is no stale-backup or empty-state fallback. */
    fun loadAll(): List<TaskPlanner.Plan> = synchronized(checkpointLock) {
        readCheckpoint()
    }

    /** Save exactly one next revision while retaining every other stored plan. */
    fun save(plan: TaskPlanner.Plan, expectedRevision: Long?): Unit = synchronized(checkpointLock) {
        // Do not validate a caller's lists and then serialize those same mutable references.
        val frozen = snapshot(plan)
        TaskPlanner.validate(frozen)
        val existing = readCheckpoint()
        val index = existing.indexOfFirst { it.id == frozen.id }
        if (index < 0) {
            require(expectedRevision == null && frozen.revision == 0L) { "A new task must start at revision zero." }
            require(existing.size < MAX_PLANS) { "Task plan limit reached." }
        } else {
            val previous = existing[index]
            require(expectedRevision != null && expectedRevision == previous.revision) { "Task changed; reload before saving." }
            require(previous.revision < Long.MAX_VALUE && frozen.revision == previous.revision + 1L) {
                "Task revision must advance exactly once."
            }
            require(frozen.updatedAtMillis >= previous.updatedAtMillis) { "Task checkpoint time cannot move backwards." }
            require(frozen.task == previous.task && frozen.baseCommitSha == previous.baseCommitSha &&
                frozen.analysisId == previous.analysisId && frozen.scope == previous.scope) {
                "Task source, analysis and scope are immutable."
            }
            require(frozen.decisions.size == previous.decisions.size + 1 &&
                frozen.decisions.take(previous.decisions.size) == previous.decisions) {
                "Task decision history must be preserved and extended exactly once."
            }
        }
        val updated = existing.toMutableList()
        if (index < 0) updated.add(frozen) else updated[index] = frozen
        val payload = encodePayload(updated)
        val bytes = encodeCheckpoint(payload)
        checkpoint.write(bytes)
    }

    private fun readCheckpoint(): List<TaskPlanner.Plan> {
        val data = checkpoint.read() ?: return emptyList()
        return DataInputStream(ByteArrayInputStream(data)).use { input ->
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Task checkpoint format is invalid." }
            require(input.readInt() == VERSION) { "Task checkpoint version is unsupported." }
            val length = input.readInt()
            require(length >= 4 && length == data.size - HEADER_BYTES) { "Task checkpoint payload length is invalid." }
            val expectedHash = ByteArray(HASH_BYTES)
            input.readFully(expectedHash)
            val payload = ByteArray(length)
            input.readFully(payload)
            require(input.read() == -1) { "Task checkpoint contains trailing data." }
            require(MessageDigest.isEqual(expectedHash, sha256(payload))) { "Task checkpoint checksum does not match." }
            decodePayload(payload)
        }
    }

    private fun encodeCheckpoint(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_BYTES - HEADER_BYTES) { "Task plans exceed the total byte limit." }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(payload.size)
            output.write(sha256(payload))
            output.write(payload)
        }
        return bytes.toByteArray()
    }

    private fun encodePayload(plans: List<TaskPlanner.Plan>): ByteArray {
        require(plans.size <= MAX_PLANS && plans.map { it.id }.distinct().size == plans.size) { "Task plan collection is invalid." }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(LimitedOutput(bytes, MAX_BYTES - HEADER_BYTES)).use { output ->
            output.writeInt(plans.size)
            for (plan in plans) {
                TaskPlanner.validate(plan)
                output.text(plan.id)
                output.text(plan.task)
                output.text(plan.baseCommitSha)
                output.text(plan.analysisId)
                output.strings(plan.scope)
                output.writeInt(plan.steps.size)
                for (step in plan.steps) {
                    output.text(step.id)
                    output.text(step.title)
                    output.text(step.detail)
                    output.strings(step.paths)
                    output.strings(step.dependsOn)
                    output.text(step.status.name)
                    output.text(step.checkpoint)
                }
                output.writeInt(plan.decisions.size)
                for (decision in plan.decisions) {
                    output.writeInt(decision.sequence)
                    output.writeBoolean(decision.stepId != null)
                    decision.stepId?.let { output.text(it) }
                    output.text(decision.message)
                    output.writeLong(decision.atMillis)
                }
                output.writeLong(plan.revision)
                output.writeBoolean(plan.paused)
                output.writeLong(plan.updatedAtMillis)
            }
        }
        return bytes.toByteArray()
    }

    private fun decodePayload(payload: ByteArray): List<TaskPlanner.Plan> =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val count = input.boundedCount(MAX_PLANS)
            val plans = ArrayList<TaskPlanner.Plan>(count)
            repeat(count) {
                val id = input.text()
                val task = input.text()
                val baseCommitSha = input.text()
                val analysisId = input.text()
                val scope = input.strings(8)
                val steps = ArrayList<TaskPlanner.Step>()
                repeat(input.boundedCount(24)) {
                    val stepId = input.text()
                    val title = input.text()
                    val detail = input.text()
                    val paths = input.strings(8)
                    val dependencies = input.strings(24)
                    val statusName = input.text()
                    val status = TaskPlanner.Status.values().firstOrNull { it.name == statusName }
                    steps.add(TaskPlanner.Step(stepId, title, detail, paths, dependencies,
                        status ?: throw IllegalArgumentException("Task checkpoint status is invalid."), input.text()))
                }
                val decisions = ArrayList<TaskPlanner.Decision>()
                repeat(input.boundedCount(200)) {
                    val sequence = input.readInt()
                    val stepId = if (input.strictBoolean()) input.text() else null
                    decisions.add(TaskPlanner.Decision(sequence, stepId, input.text(), input.readLong()))
                }
                val plan = snapshot(TaskPlanner.Plan(id, task, baseCommitSha, analysisId, scope, steps, decisions,
                    input.readLong(), input.strictBoolean(), input.readLong()))
                TaskPlanner.validate(plan)
                require(plans.none { it.id == id }) { "Task checkpoint contains duplicate task IDs." }
                plans.add(plan)
            }
            require(input.read() == -1) { "Task checkpoint payload contains trailing data." }
            java.util.Collections.unmodifiableList(plans)
        }

    private fun DataOutputStream.text(value: String) {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        require(encoded.remaining() <= MAX_STRING_BYTES) { "Task text exceeds its byte limit." }
        writeInt(encoded.remaining())
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        write(bytes)
    }

    private fun snapshot(plan: TaskPlanner.Plan): TaskPlanner.Plan {
        require(plan.scope.size <= 8 && plan.steps.size <= 24 && plan.decisions.size <= 200) {
            "Task checkpoint collection size is invalid."
        }
        return plan.copy(scope = immutable(plan.scope), steps = immutable(plan.steps.map { step ->
            require(step.paths.size <= 8 && step.dependsOn.size <= 24) { "Task checkpoint step size is invalid." }
            step.copy(paths = immutable(step.paths), dependsOn = immutable(step.dependsOn))
        }), decisions = immutable(plan.decisions.map { it.copy() }))
    }

    private fun <T> immutable(values: List<T>): List<T> = java.util.Collections.unmodifiableList(ArrayList(values))

    private fun DataInputStream.text(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES && length <= available()) { "Task checkpoint text length is invalid." }
        val bytes = ByteArray(length)
        readFully(bytes)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun DataOutputStream.strings(values: List<String>) {
        writeInt(values.size)
        for (value in values) text(value)
    }

    private fun DataInputStream.strings(maximum: Int): List<String> {
        val result = ArrayList<String>()
        repeat(boundedCount(maximum)) { result.add(text()) }
        return java.util.Collections.unmodifiableList(result)
    }

    private fun DataInputStream.boundedCount(maximum: Int): Int {
        val count = readInt()
        require(count in 0..maximum) { "Task checkpoint collection size is invalid." }
        return count
    }

    private fun DataInputStream.strictBoolean(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Task checkpoint boolean is invalid.")
    }

    private class LimitedOutput(private val output: OutputStream, private val maximum: Int) : OutputStream() {
        private var count = 0
        override fun write(value: Int) {
            require(count < maximum) { "Task plans exceed the total byte limit." }
            output.write(value)
            count++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length <= maximum - count) { "Task plans exceed the total byte limit." }
            output.write(bytes, offset, length)
            count += length
        }
    }

    private companion object {
        // Shared by every store instance, so read/compare/replace is one in-process transaction.
        val checkpointLock = Any()
        const val FILE_NAME = "task-plans.bin"
        val MAGIC = "JARVPLN3".toByteArray(StandardCharsets.US_ASCII)
        const val VERSION = 1
        const val HASH_BYTES = 32
        const val HEADER_BYTES = 8 + 4 + 4 + HASH_BYTES
        const val MAX_BYTES = 512 * 1024
        const val MAX_STRING_BYTES = 256_000
        const val MAX_PLANS = 10
        fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    }
}
