package com.jarvis.developer

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DeveloperCommitRecoveryStoreTest {
    private val source = "developer/src/main/java/com/jarvis/developer/Latency.kt"

    private fun result(number: Int) : PatchPublisher.CommitResult {
        val task = "commit-recovery-$number"
        val proposal = (number + 1).toString(16).padStart(64, 'a')
        return PatchPublisher.CommitResult(task, "Gemini", "gemini-2.5-pro",
            proposal, listOf(source), "a".repeat(40),
            (number + 1).toString(16).padStart(40, 'b'),
            PatchEngine.taskBranch(task, proposal, PatchEngine.Target.DEVELOPER),
            "SECRET SOURCE DIFF MUST NOT BE PERSISTED",
            "feature/jarvis-developer", PatchEngine.Target.DEVELOPER)
    }

    @Test fun completedCommitSurvivesRestartButDiffAndApprovalsNeverDo() {
        val dir = Files.createTempDirectory("developer-commit-restart-").toFile()
        val original = result(1)
        DeveloperCommitRecoveryStore(dir).save(original)
        val recovered = DeveloperCommitRecoveryStore(dir).load().single()
        assertEquals(original.copy(diff = ""), recovered)
        assertEquals("", recovered.diff)
        assertEquals(original.commitSha, recovered.commitSha)
        assertEquals(original.baseCommitSha, recovered.baseCommitSha)
        assertEquals(original.paths, recovered.paths)
        val raw = java.io.File(dir, "developer-commit-recovery.bin").readText(Charsets.ISO_8859_1)
        assertFalse(raw.contains("SECRET SOURCE DIFF"))
        assertFalse(raw.contains("token"))
        assertFalse(raw.contains("Approve Developer diff"))
    }

    @Test fun boundedLatestFirstRecordsDedupeWithoutOverwritingParent() {
        val dir = Files.createTempDirectory("developer-recovery-bounds-").toFile()
        val store = DeveloperCommitRecoveryStore(dir)
        (1..14).forEach { store.save(result(it)) }
        assertEquals(10, store.load().size)
        assertEquals(result(14).commitSha, store.load().first().commitSha)
        assertEquals(result(5).commitSha, store.load().last().commitSha)
        store.save(result(12))
        assertEquals(10, store.load().size)
        assertEquals(result(12).commitSha, store.load().first().commitSha)
        assertEquals(1, store.load().count { it.commitSha == result(12).commitSha })
    }

    @Test fun corruptedRecoveryFileFailsClosedInsteadOfPretendingItIsEmpty() {
        val dir = Files.createTempDirectory("developer-recovery-corrupt-").toFile()
        val store = DeveloperCommitRecoveryStore(dir)
        store.save(result(1))
        val path = java.io.File(dir, "developer-commit-recovery.bin")
        val data = path.readBytes()
        data[data.lastIndex] = (data.last().toInt() xor 0x01).toByte()
        path.writeBytes(data)
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        assertThrows(IllegalArgumentException::class.java) { store.save(result(2)) }
    }

    @Test fun personalJarvisCommitCannotEnterDeveloperRecovery() {
        val store = DeveloperCommitRecoveryStore(
            Files.createTempDirectory("developer-recovery-product-").toFile())
        assertThrows(IllegalArgumentException::class.java) {
            store.save(result(1).copy(target = PatchEngine.Target.PERSONAL))
        }
        assertTrue(store.load().isEmpty())
    }
}
