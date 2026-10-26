package com.jarvis.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryBrowserTest {
    private val path = "developer/src/main/Sample.kt"
    private val snapshot = RepositoryAnalysis.Inventory(
        listOf(RepositoryAnalysis.SourceFile(path, "b".repeat(40), 300)), false, "a".repeat(40)
    )

    @Test fun browserShowsOnlyRequestedLinesWithImmutableProvenance() {
        val output = RepositoryBrowser.source(snapshot, path, "one\ntwo\nthree\nfour", "2-3")
        assertTrue(output.contains("2: two\n3: three"))
        assertFalse(output.contains("1: one"))
        assertFalse(output.contains("4: four"))
        assertTrue(output.contains("Commit: ${snapshot.commitSha}"))
        assertTrue(output.contains("Blob: ${snapshot.files.single().sha}"))
        assertTrue(output.contains("#L2-L3"))
    }

    @Test fun sourceMustBelongToPinnedInventory() {
        rejected { RepositoryBrowser.source(snapshot, "app/unknown.kt", "text", "1-1") }
    }

    @Test fun browserRejectsUnboundedOrInvalidRanges() {
        listOf("1-201", "9-2", "0-1", "1", "1-999999999").forEach { range ->
            rejected { RepositoryBrowser.source(snapshot, path, "text", range) }
        }
        rejected { RepositoryBrowser.source(snapshot, path, "text", "2-3") }
    }

    @Test fun longLinesAreClippedExplicitly() {
        val output = RepositoryBrowser.source(snapshot, path, "x".repeat(30_000), "1-1")
        assertTrue(output.contains("Excerpt clipped"))
        assertTrue(output.length < 25_000)
    }

    @Test fun multilineClippingCitesOnlyDisplayedLines() {
        val output = RepositoryBrowser.source(snapshot, path, "x".repeat(30_000) + "\nsecond\nthird", "1-3")
        assertTrue(output.contains("Lines: 1-1"))
        assertTrue(output.contains("#L1-L1"))
        assertFalse(output.contains("2: second"))
        assertTrue(output.contains("last displayed line may be partial"))
    }

    @Test fun inventoryCanBeScopedWithoutEscapingRepository() {
        assertTrue(RepositoryBrowser.inventory(snapshot, "developer/").contains(path))
        assertFalse(RepositoryBrowser.inventory(snapshot, "app/").contains(path))
        rejected { RepositoryBrowser.inventory(snapshot, "../") }
    }

    @Test fun previewsRedactCredentialLiteralsWithoutChangingProvenance() {
        val credential = "example-" + "sensitive".repeat(3)
        val providerToken = "sk-" + "x".repeat(30)
        val source = "val API_KEY = \"$credential\"\n{\"access_token\":\"$credential\"}\nval sample = \"$providerToken\""
        val output = RepositoryBrowser.source(snapshot, path, source, "1-3")
        assertFalse(output.contains(credential))
        assertFalse(output.contains(providerToken))
        assertTrue(output.contains("[REDACTED]"))
        assertTrue(output.contains("Blob: ${snapshot.files.single().sha}"))
    }

    @Test(timeout = 2_000) fun longNonmatchingSourceDoesNotCauseQuadraticRedaction() {
        val line = "x".repeat(200_000)
        assertTrue(RepositoryRedaction.line(line) == line)
    }

    @Test fun escapedQuotesDoNotRevealCredentialSuffixes() {
        val suffix = "private-suffix-value"
        val source = "val accessToken = \"prefix\\\"$suffix\"; val visible = 1"
        val redacted = RepositoryRedaction.line(source)
        assertFalse(redacted.contains(suffix))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("val visible = 1"))
    }

    private fun rejected(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: IllegalArgumentException) { failed = true }
        catch (_: IllegalStateException) { failed = true }
        assertTrue("Expected a bounded browser rejection", failed)
    }
}
