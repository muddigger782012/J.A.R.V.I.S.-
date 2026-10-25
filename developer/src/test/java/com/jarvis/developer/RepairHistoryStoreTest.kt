package com.jarvis.developer

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepairHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = File(temporary.newFolder(), "history")
    private fun bookmark(index: Int = 1, target: PatchEngine.Target = PatchEngine.Target.PERSONAL): RepairHistoryStore.Bookmark =
        RepairHistoryStore.Bookmark(target, PatchEngine.taskBranch("sample-$index", "b".repeat(64), target),
            index.toString(16).padStart(40, '0'))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }

    @Test fun missingHistoryCreatesNoFilesAndReopensOnlyMinimalReferences() {
        val directory = directory()
        assertTrue(RepairHistoryStore(directory).load().isEmpty())
        assertFalse(directory.exists())
        val entries = listOf(bookmark(), bookmark(2, PatchEngine.Target.DEVELOPER))
        entries.forEach { RepairHistoryStore(directory).save(it) }
        assertEquals(entries.reversed(), RepairHistoryStore(directory).load())
        val bytes = File(directory, "repair-history.bin").readBytes().toString(Charsets.ISO_8859_1)
        for (forbidden in listOf("approved", "diff --git", "provider", "token", "task prompt", "app/src/", "developer/src/")) {
            assertFalse(bytes.contains(forbidden))
        }
        rejected { (RepairHistoryStore(directory).load() as MutableList).clear() }
    }

    @Test fun deduplicatesAndBoundsMostRecentlySavedBookmarksAcrossInstances() {
        val directory = directory()
        for (index in 1..12) RepairHistoryStore(directory).save(bookmark(index))
        assertEquals((12 downTo 3).map(::bookmark), RepairHistoryStore(directory).load())
        RepairHistoryStore(directory).save(bookmark(5))
        assertEquals(listOf(bookmark(5)) + (12 downTo 3).filter { it != 5 }.map(::bookmark), RepairHistoryStore(directory).load())
    }

    @Test fun rejectsForeignProductsNonTaskBranchesTokensAndNonCommitIdentities() {
        val directory = directory()
        val original = bookmark()
        RepairHistoryStore(directory).save(original)
        for (invalid in listOf(original.copy(branch = "main"), original.copy(branch = "feature/jarvis-developer"),
            original.copy(target = PatchEngine.Target.DEVELOPER), original.copy(branch = "../escape"),
            original.copy(branch = original.branch + "\nAuthorization: private"),
            original.copy(branch = "feature/jarvis-personal-repair/ghp_" + "x".repeat(35) + "-" + "b".repeat(12)),
            original.copy(commitSha = "a".repeat(39)), original.copy(commitSha = "A".repeat(40)))) {
            rejected { RepairHistoryStore(directory).save(invalid) }
            assertEquals(listOf(original), RepairHistoryStore(directory).load())
        }
    }

    @Test fun corruptHistoryIsNeverSilentlyEmptyOrOverwrittenButCanBeExplicitlyCleared() {
        val directory = directory()
        RepairHistoryStore(directory).save(bookmark())
        val file = File(directory, "repair-history.bin")
        val valid = file.readBytes()
        val cases = listOf(valid.copyOf(12), valid + byteArrayOf(0), ByteArray(8193),
            valid.clone().apply { this[11] = 2 }, valid.clone().apply { this[lastIndex] = 0 })
        for (bytes in cases) {
            file.writeBytes(bytes)
            rejected { RepairHistoryStore(directory).load() }
            rejected { RepairHistoryStore(directory).save(bookmark(2)) }
            assertArrayEquals(bytes, file.readBytes())
            RepairHistoryStore(directory).clear()
            assertTrue(RepairHistoryStore(directory).load().isEmpty())
        }
    }

    @Test fun atomicFailurePreservesOriginalAndCleansOnlyItsTemporaryFile() {
        val directory = directory()
        RepairHistoryStore(directory).save(bookmark())
        val before = File(directory, "repair-history.bin").readBytes()
        val unrelated = File(directory, ".unrelated.tmp").apply { writeText("preserve") }
        val failing = RepairHistoryStore(directory) { source, target ->
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "modeled filesystem refusal")
        }
        rejected { failing.save(bookmark(2)) }
        rejected { failing.clear() }
        assertArrayEquals(before, File(directory, "repair-history.bin").readBytes())
        assertEquals(setOf("repair-history.bin", unrelated.name), directory.list()!!.toSet())
        assertEquals("preserve", unrelated.readText())
    }

    @Test fun rejectsLinkedDirectoryLinkedCheckpointAndNonRegularCheckpointWithoutTouchingTargets() {
        val parent = temporary.newFolder()
        val external = temporary.newFolder()
        val directoryLink = File(parent, "linked")
        Files.createSymbolicLink(directoryLink.toPath(), external.toPath())
        for (directory in listOf(directoryLink, File(directoryLink, "nested"))) {
            rejected { RepairHistoryStore(directory).load() }
            rejected { RepairHistoryStore(directory).save(bookmark()) }
            rejected { RepairHistoryStore(directory).clear() }
        }
        assertTrue(external.list()!!.isEmpty())
        val directory = directory().apply { mkdirs() }
        val outside = File(external, "outside").apply { writeText("preserve") }
        val checkpoint = File(directory, "repair-history.bin")
        Files.createSymbolicLink(checkpoint.toPath(), outside.toPath())
        rejected { RepairHistoryStore(directory).clear() }
        rejected { RepairHistoryStore(directory).save(bookmark()) }
        rejected { RepairHistoryStore(directory).load() }
        assertEquals("preserve", outside.readText())
        Files.delete(checkpoint.toPath())
        checkpoint.mkdir()
        rejected { RepairHistoryStore(directory).clear() }
        rejected { RepairHistoryStore(directory).load() }
    }

    @Test fun concurrentSavesRetainBothReferencesAndKeepTaskPlanFormatIndependent() {
        val directory = directory()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map { index -> executor.submit {
                start.await(); RepairHistoryStore(directory).save(bookmark(index))
            } }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(setOf(bookmark(1), bookmark(2)), RepairHistoryStore(directory).load().toSet())
            assertTrue(TaskPlanStore(directory).loadAll().isEmpty())
            assertFalse(File(directory, "task-plans.bin").exists())
        } finally { executor.shutdownNow() }
    }
}
