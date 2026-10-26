package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TaskPlanStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
    private val otherSource = "developer/src/test/java/com/jarvis/developer/TaskFileSummaryTest.kt"

    private fun plan(id: String = "sample", status: TaskPlanner.Status = TaskPlanner.Status.PENDING): TaskPlanner.Plan =
        TaskPlanner.Plan(id, "Improve the scoped file summary", "a".repeat(40), "b".repeat(64), listOf(source), listOf(
            TaskPlanner.Step("inspect", "Inspect relevant code", "Review the pinned source and acceptance criteria", emptyList(), emptyList(),
                status, if (status == TaskPlanner.Status.DONE) "Inspected pinned files" else ""),
            TaskPlanner.Step("implement", "Implement summary", "Use the separate reviewed proposal flow", listOf(source), listOf("inspect")),
            TaskPlanner.Step("verify", "Verify summary", "Record reproducible test evidence separately", emptyList(), listOf("implement"))
        ), listOf(TaskPlanner.Decision(1, null, "Created scoped task", 100)), 0, false, 100)

    private fun newDirectory(): File = File(temporary.newFolder(), "plans")
    private fun checkpoint(directory: File): File = File(directory, "task-plans.bin")
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected checkpoint rejection") } catch (_: Exception) { /* Rejected without changing persisted state. */ }
    }

    @Test fun reopensEveryStatusWithoutChangingCheckpointOrRecovering() {
        for (status in TaskPlanner.Status.values()) {
            val directory = newDirectory()
            val expected = plan(status = status)
            TaskPlanStore(directory).save(expected, null)
            val before = Files.readAllBytes(checkpoint(directory).toPath())
            val loaded = TaskPlanStore(directory).loadAll().single()
            assertEquals(expected, loaded)
            assertEquals(status, loaded.steps.first().status)
            assertFalse(loaded.paused)
            assertArrayEquals(before, Files.readAllBytes(checkpoint(directory).toPath()))
        }
    }

    @Test fun missingCheckpointIsEmptyAndDoesNotCreateStorage() {
        val directory = newDirectory()
        assertEquals(emptyList<TaskPlanner.Plan>(), TaskPlanStore(directory).loadAll())
        assertFalse(directory.exists())
    }

    @Test fun independentStoresRejectStaleOrNonmonotonicRevisionsAndPreserveOtherTasks() {
        val directory = newDirectory()
        val first = TaskPlanStore(directory)
        val second = TaskPlanStore(directory)
        val initial = plan()
        first.save(initial, null)
        first.save(plan("other"), null)
        val next = TaskPlanner.note(initial, "Reviewed pending work", 101)
        second.save(next, 0)
        val stable = Files.readAllBytes(checkpoint(directory).toPath())
        rejected { first.save(TaskPlanner.note(initial, "Stale decision", 102), 0) }
        rejected { first.save(next, null) }
        rejected { first.save(next, 1) }
        rejected { first.save(next.copy(revision = 3), 1) }
        rejected { first.save(plan("third").copy(revision = 1), null) }
        assertArrayEquals(stable, Files.readAllBytes(checkpoint(directory).toPath()))
        assertEquals(listOf(next, plan("other")), TaskPlanStore(directory).loadAll())
    }

    @Test fun savesCannotRewritePinnedScopeTaskOrDecisionHistory() {
        val directory = newDirectory()
        val original = plan()
        val store = TaskPlanStore(directory)
        store.save(original, null)
        val next = TaskPlanner.note(original, "Reviewed the immutable scope", 101)
        val candidates = listOf(
            next.copy(task = "Different task"), next.copy(baseCommitSha = "c".repeat(40)),
            next.copy(analysisId = "d".repeat(64)),
            next.copy(scope = listOf(otherSource), steps = next.steps.map { it.copy(paths = it.paths.map { otherSource }) }),
            next.copy(decisions = next.decisions.mapIndexed { index, decision -> if (index == 0) decision.copy(message = "Rewritten creation") else decision }),
            next.copy(updatedAtMillis = 99, decisions = next.decisions.map { it.copy(atMillis = 99) })
        )
        val stable = Files.readAllBytes(checkpoint(directory).toPath())
        candidates.forEach { candidate -> rejected { store.save(candidate, 0) } }
        assertArrayEquals(stable, Files.readAllBytes(checkpoint(directory).toPath()))
        assertEquals(original, TaskPlanStore(directory).loadAll().single())
    }

    @Test fun loadedListsAreImmutableAndInputListsAreNotRetained() {
        val directory = newDirectory()
        val original = plan()
        val scope = original.scope.toMutableList()
        val paths = original.steps[1].paths.toMutableList()
        val dependencies = original.steps[1].dependsOn.toMutableList()
        val steps = original.steps.map { if (it.id == "implement") it.copy(paths = paths, dependsOn = dependencies) else it }.toMutableList()
        val decisions = original.decisions.toMutableList()
        TaskPlanStore(directory).save(original.copy(scope = scope, steps = steps, decisions = decisions), null)
        scope.clear(); paths.clear(); dependencies.clear(); steps.clear(); decisions.clear()
        val loaded = TaskPlanStore(directory).loadAll().single()
        assertEquals(original, loaded)
        rejected { (loaded.scope as MutableList<String>).clear() }
        rejected { (loaded.steps as MutableList<TaskPlanner.Step>).clear() }
        rejected { (loaded.decisions as MutableList<TaskPlanner.Decision>).clear() }
        rejected { (loaded.steps[1].paths as MutableList<String>).clear() }
        rejected { (loaded.steps[1].dependsOn as MutableList<String>).clear() }
        assertEquals(original, TaskPlanStore(directory).loadAll().single())
    }

    @Test fun simultaneousStoresAllowOnlyOneNextRevision() {
        val directory = newDirectory()
        val original = plan()
        TaskPlanStore(directory).save(original, null)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index -> executor.submit<Boolean> {
                start.await()
                try { TaskPlanStore(directory).save(TaskPlanner.note(original, "Concurrent decision $index", 101), 0); true }
                catch (_: IllegalArgumentException) { false }
            } }
            start.countDown()
            assertEquals(1, futures.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1L, TaskPlanStore(directory).loadAll().single().revision)
        } finally { executor.shutdownNow() }
    }

    @Test fun storageIsBoundedByTaskCountAndTotalEncodedBytes() {
        val directory = newDirectory()
        val store = TaskPlanStore(directory)
        repeat(10) { store.save(plan("task$it"), null) }
        val stable = Files.readAllBytes(checkpoint(directory).toPath())
        rejected { store.save(plan("task10"), null) }
        assertArrayEquals(stable, Files.readAllBytes(checkpoint(directory).toPath()))
        val largeDirectory = newDirectory()
        val largeStore = TaskPlanStore(largeDirectory)
        var rejectedAt = -1
        for (index in 0 until 10) {
            val small = plan("large$index")
            val large = small.copy(task = "界".repeat(3000), steps = small.steps.map { it.copy(detail = "界".repeat(7000)) })
            TaskPlanner.validate(large)
            val previous = if (checkpoint(largeDirectory).exists()) Files.readAllBytes(checkpoint(largeDirectory).toPath()) else null
            try { largeStore.save(large, null) } catch (_: IllegalArgumentException) {
                rejectedAt = index
                assertNotNull(previous)
                assertArrayEquals(previous, Files.readAllBytes(checkpoint(largeDirectory).toPath()))
                break
            }
        }
        assertTrue("Encoded-byte bound must reject before plan-count bound", rejectedAt in 1..9)
        assertTrue(checkpoint(largeDirectory).length() <= 512 * 1024)
    }

    @Test fun atomicMoveFailurePreservesPreviousCheckpointAndCleansOnlyOwnTemporaryFile() {
        val directory = newDirectory()
        val original = plan()
        TaskPlanStore(directory).save(original, null)
        val stable = Files.readAllBytes(checkpoint(directory).toPath())
        val unrelated = File(directory, ".task-plans-unrelated.tmp").apply { writeText("Leave this file alone") }
        val failing = TaskPlanStore(directory) { source, target ->
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "Injected unsupported atomic move")
        }
        rejected { failing.save(TaskPlanner.note(original, "Must not replace old bytes", 101), 0) }
        assertArrayEquals(stable, Files.readAllBytes(checkpoint(directory).toPath()))
        assertEquals(setOf("task-plans.bin", unrelated.name), directory.list()!!.toSet())
        assertEquals(original, TaskPlanStore(directory).loadAll().single())
        val newDirectory = newDirectory()
        rejected { TaskPlanStore(newDirectory) { source, target ->
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "Injected failure")
        }.save(original, null) }
        assertFalse(checkpoint(newDirectory).exists())
        assertTrue(newDirectory.list()!!.isEmpty())
    }

    @Test fun truncatedOversizedCorruptOrUnknownCheckpointsNeverBecomeEmptyOrGetOverwritten() {
        val directory = newDirectory()
        val store = TaskPlanStore(directory)
        store.save(plan(), null)
        val valid = Files.readAllBytes(checkpoint(directory).toPath())
        val broken = listOf(
            byteArrayOf(), valid.copyOf(47), valid.copyOf(48), valid.copyOf(valid.size - 1),
            valid.copyOf().apply { this[0] = 0 },
            valid.copyOf().apply { ByteBuffer.wrap(this, 8, 4).putInt(2) },
            valid.copyOf().apply { ByteBuffer.wrap(this, 12, 4).putInt(Int.MAX_VALUE) },
            valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() },
            valid + byteArrayOf(0), ByteArray(512 * 1024 + 1)
        )
        broken.forEach { bytes ->
            Files.write(checkpoint(directory).toPath(), bytes)
            rejected { store.loadAll() }
            rejected { store.save(plan("replacement"), null) }
            assertArrayEquals(bytes, Files.readAllBytes(checkpoint(directory).toPath()))
        }
    }

    @Test fun checksummedMalformedPayloadsRejectInvalidUtf8CountsDuplicatesBooleansAndTrailingBytes() {
        val directory = newDirectory()
        TaskPlanStore(directory).save(plan(), null)
        val valid = Files.readAllBytes(checkpoint(directory).toPath())
        val payload = valid.copyOfRange(48, valid.size)
        val badPayloads = listOf(
            payload.copyOf().apply { ByteBuffer.wrap(this).putInt(11) },
            payload.copyOf().apply { ByteBuffer.wrap(this, 4, 4).putInt(Int.MAX_VALUE) },
            payload.copyOf().apply { this[8] = 0xc3.toByte(); this[9] = 0x28 },
            payload.copyOf().apply { this[size - 9] = 2 },
            byteArrayOf(0, 0, 0, 2) + payload.copyOfRange(4, payload.size) + payload.copyOfRange(4, payload.size),
            payload + byteArrayOf(0)
        )
        badPayloads.forEach { body ->
            val bytes = envelope(body)
            Files.write(checkpoint(directory).toPath(), bytes)
            rejected { TaskPlanStore(directory).loadAll() }
            rejected { TaskPlanStore(directory).save(plan("replacement"), null) }
            assertArrayEquals(bytes, Files.readAllBytes(checkpoint(directory).toPath()))
        }
    }

    @Test fun symlinkedDirectoriesCheckpointsAndNonregularPathsAreRejected() {
        val parent = temporary.newFolder().toPath()
        val target = Files.createDirectory(parent.resolve("target"))
        val linked = parent.resolve("linked")
        Files.createSymbolicLink(linked, target)
        rejected { TaskPlanStore(linked.toFile()).loadAll() }
        rejected { TaskPlanStore(linked.resolve("nested").toFile()).save(plan(), null) }
        assertEquals(0, target.toFile().list()!!.size)
        val directory = newDirectory()
        directory.mkdirs()
        val outside = File(temporary.newFolder(), "outside.bin").apply { writeText("Do not overwrite") }
        Files.createSymbolicLink(checkpoint(directory).toPath(), outside.toPath())
        rejected { TaskPlanStore(directory).loadAll() }
        rejected { TaskPlanStore(directory).save(plan(), null) }
        assertEquals("Do not overwrite", outside.readText())
        Files.delete(checkpoint(directory).toPath())
        checkpoint(directory).mkdir()
        rejected { TaskPlanStore(directory).loadAll() }
        rejected { TaskPlanStore(directory).save(plan(), null) }
        val fileDirectory = File(temporary.newFolder(), "not-a-directory").apply { writeText("Preserve") }
        rejected { TaskPlanStore(fileDirectory).loadAll() }
        assertEquals("Preserve", fileDirectory.readText())
    }

    @Test fun persistedBytesContainOnlyTaskMetadataAndNoDiffOrApprovalState() {
        val directory = newDirectory()
        val original = plan()
        TaskPlanStore(directory).save(original, null)
        val bytes = Files.readAllBytes(checkpoint(directory).toPath())
        val view = String(bytes, Charsets.UTF_8)
        assertTrue(view.contains(original.baseCommitSha))
        assertTrue(view.contains(original.analysisId))
        assertTrue(view.contains(source))
        assertFalse(view.contains("ApprovedProposal"))
        assertFalse(view.contains("Authorization:"))
        assertFalse(view.contains("diff --git"))
        assertEquals(original, TaskPlanStore(directory).loadAll().single())
    }

    private fun envelope(payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.write("JARVPLN3".toByteArray(Charsets.US_ASCII)); it.writeInt(1); it.writeInt(payload.size)
            it.write(MessageDigest.getInstance("SHA-256").digest(payload)); it.write(payload)
        }
        return output.toByteArray()
    }
}
