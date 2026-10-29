package com.jarvis.developer

import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevelopmentJobDraftsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun key(index: Int = 1) = DevelopmentJobDrafts.Key("job_" + index.toString(16).padStart(64, '0'),
        2, PatchEngine.Target.DEVELOPER, "a".repeat(40))
    private fun file() = File(folder.root, "development-job-drafts.bin")
    private val text = DevelopmentJobDrafts.Text("Improve the empty state\nKeep offline support", "developer/src/main/java/com/jarvis/developer/EmptyState.kt\n")

    @Test fun reconstructionRetainsOnlyTheOwnersUnsentText() {
        val saved = DevelopmentJobDrafts(folder.root).save(key(), 0, text)
        assertEquals(1L, saved.sequence)
        assertEquals(text, DevelopmentJobDrafts(folder.root).read(key()).text)
        val envelope = JSONObject(file().readText())
        val row = org.json.JSONArray(envelope.getString("payload")).getJSONObject(0)
        assertEquals(setOf("job", "revision", "target", "source", "sequence", "text"), row.keys().asSequence().toSet())
        assertEquals(setOf("goal", "paths"), row.getJSONObject("text").keys().asSequence().toSet())
    }
    @Test fun anotherJobStateProductOrSourceCannotReceiveAnOldDraft() {
        val store = DevelopmentJobDrafts(folder.root); store.save(key(), 0, text)
        for (changed in listOf(key(2), key().copy(revision = 3), key().copy(target = PatchEngine.Target.PERSONAL), key().copy(source = "b".repeat(40)))) {
            val read = store.read(changed); assertNull(read.text)
            assertEquals(changed.job != key().job, read.matches)
        }
        assertEquals(text, store.read(key()).text)
    }
    @Test fun forgettingRetainsATombstoneThatRejectsDelayedWriters() {
        val store = DevelopmentJobDrafts(folder.root); val saved = store.save(key(), 0, text)
        val forgotten = store.forget(key(), saved.sequence)
        assertNull(forgotten.text); assertEquals(2L, forgotten.sequence)
        assertThrows(IllegalStateException::class.java) { store.save(key(), saved.sequence, text) }
        assertNull(DevelopmentJobDrafts(folder.root).read(key()).text)
        assertEquals(text, store.save(key(), forgotten.sequence, text).text)
    }
    @Test fun concurrentEditorsCannotSilentlyOverwriteEachOther() {
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (0..1).map { i -> pool.submit<Boolean> {
                start.await(); runCatching { DevelopmentJobDrafts(folder.root).save(key(), 0, text.copy(goal = "Editor $i")) }.isSuccess
            } }
            start.countDown(); assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1L, DevelopmentJobDrafts(folder.root).read(key()).sequence)
        } finally { pool.shutdownNow() }
    }
    @Test fun credentialRedactionPreservesOrdinaryFormattingUrlsAndUnicode() {
        val raw = "Fix the UI 😀\nSee https://github.com/example/repo\napi_key=very-private-value\nAuthorization: Bearer private-header\n-----BEGIN PRIVATE KEY-----\nprivate-tail"
        val result = DevelopmentJobDrafts(folder.root).save(key(), 0, text.copy(goal = raw))
        assertTrue(result.text!!.goal.startsWith("Fix the UI 😀\nSee https://github.com/example/repo\n"))
        for (secret in listOf("very-private-value", "private-header", "private-tail")) assertFalse(file().readText().contains(secret))
        assertEquals(result, DevelopmentJobDrafts(folder.root).read(key()))
    }
    @Test fun invalidTextOrIdentityDoesNotChangeTheSavedCopy() {
        val store = DevelopmentJobDrafts(folder.root); store.save(key(), 0, text); val before = file().readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save(key(), 1, text.copy(goal = "x".repeat(12_001))) }
        assertThrows(IllegalArgumentException::class.java) { store.save(key(), 1, text.copy(goal = "\uD800")) }
        assertThrows(IllegalArgumentException::class.java) { key().copy(source = "not-a-commit") }
        assertThrows(Exception::class.java) { key().copy(job = "../other-task") }
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun corruptionNeverBecomesAnEmptySuccessOrAnImplicitReset() {
        val store = DevelopmentJobDrafts(folder.root); store.save(key(), 0, text)
        file().writeText(file().readText().replace("empty state", "different state")); val corrupt = file().readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.read(key()) }
        assertThrows(IllegalArgumentException::class.java) { store.save(key(), 1, text) }
        assertThrows(IllegalArgumentException::class.java) { store.forget(key(), 1) }
        assertArrayEquals(corrupt, file().readBytes())
    }
    @Test fun atomicWriteFailurePreservesThePreviousDraft() {
        DevelopmentJobDrafts(folder.root).save(key(), 0, text); val before = file().readBytes()
        val broken = DevelopmentJobDrafts(folder.root) { source, target -> throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "fixture") }
        assertThrows(AtomicMoveNotSupportedException::class.java) { broken.save(key(), 1, text.copy(goal = "Replacement")) }
        assertArrayEquals(before, file().readBytes())
        assertFalse(folder.root.listFiles()!!.any { it.name.startsWith(".private-checkpoint-") })
    }
    @Test fun symbolicLinksCannotRedirectDraftReadsOrWrites() {
        val outside = folder.newFolder("outside"); val data = File(outside, "data").apply { writeText("private fixture") }
        Files.createSymbolicLink(file().toPath(), data.toPath())
        assertThrows(IllegalArgumentException::class.java) { DevelopmentJobDrafts(folder.root).read(key()) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentJobDrafts(folder.root).save(key(), 0, text) }
        assertEquals("private fixture", data.readText())
    }
    @Test fun theStoreIsBoundedAndReadsDoNotRewriteItsContent() {
        val store = DevelopmentJobDrafts(folder.root)
        for (i in 1..64) store.save(key(i), 0, text)
        val before = file().readBytes(); store.read(key()); assertArrayEquals(before, file().readBytes())
        assertThrows(IllegalArgumentException::class.java) { store.save(key(65), 0, text) }
        assertArrayEquals(before, file().readBytes())
    }
    @Test fun emptyDraftsAreDistinctFromForgottenDrafts() {
        val store = DevelopmentJobDrafts(folder.root)
        assertEquals(DevelopmentJobDrafts.Text("", ""), store.save(key(), 0, DevelopmentJobDrafts.Text("", "")).text)
        assertNotNull(DevelopmentJobDrafts(folder.root).read(key()).text)
        assertNull(store.forget(key(), 1).text)
    }
    @Test fun draftInfrastructureCannotBeModifiedByRuntimeGeneratedPatches() {
        for (name in listOf("DevelopmentJobDrafts", "DevelopmentJobDraftWorkbench"))
            assertFalse(PatchEngine.allowedPath("developer/src/main/java/com/jarvis/developer/$name.kt"))
    }
    @Test fun approvalShapedDataIsRejectedEvenWithARecomputedChecksum() {
        val store = DevelopmentJobDrafts(folder.root); store.save(key(), 0, text)
        val root = JSONObject(file().readText()); val rows = org.json.JSONArray(root.getString("payload"))
        rows.getJSONObject(0).getJSONObject("text").put("approval", "not-authority")
        val payload = rows.toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        file().writeText(root.put("payload", payload).put("digest", digest).toString())
        assertThrows(IllegalArgumentException::class.java) { store.read(key()) }
    }
}
