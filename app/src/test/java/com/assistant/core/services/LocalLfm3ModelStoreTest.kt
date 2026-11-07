package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class LocalLfm3ModelStoreTest {
    @Test fun exactFileNamesAreRealAndNeverBuiltFromUserInput() {
        val atomic = LocalLfm3Models.selected("atomic")
        val extreme = LocalLfm3Models.selected("extreme")
        assertEquals("LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf", atomic.ggufFile)
        assertEquals("LFM3-1.7B-Uncensored-Q4_K_M-imatrix.gguf", extreme.ggufFile)
        assertEquals("https://huggingface.co/AtomicChat/LFM3-1.7B-Uncensored-GGUF/resolve/main/LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf",
            LocalLfm3DownloadRules.source(atomic))
        assertTrue(LocalLfm3DownloadRules.source(extreme).startsWith("https://huggingface.co/OpenIntelligenceNet/"))
    }

    @Test fun requireSha256AndBoundedByteSizeBeforeAnyDownload() {
        val hash = "e".repeat(64)
        assertNull(LocalLfm3DownloadRules.metadata("1100000000", null, null, null))
        assertNull(LocalLfm3DownloadRules.metadata("1100000000", null, "invalid", null))
        assertNull(LocalLfm3DownloadRules.metadata("200", null, hash, null))
        assertNull(LocalLfm3DownloadRules.metadata("4000000000", null, hash, null))
        val info = LocalLfm3DownloadRules.metadata("120", "1100000000", "\"$hash\"", null)
        assertEquals(1_100_000_000L, info!!.bytes)
        assertEquals(hash, info.sha256)
        assertEquals(hash, LocalLfm3DownloadRules.sha256(null, "\"$hash\""))
    }

    @Test fun resumeOnlyOnExactRangeAndSize() {
        assertTrue(LocalLfm3DownloadRules.rangeAccepted(206, "bytes 100-999/1000", 100, 1000))
        assertFalse(LocalLfm3DownloadRules.rangeAccepted(200, "bytes 100-999/1000", 100, 1000))
        assertFalse(LocalLfm3DownloadRules.rangeAccepted(206, "bytes 99-999/1000", 100, 1000))
        assertFalse(LocalLfm3DownloadRules.rangeAccepted(206, "bytes 100-999/1001", 100, 1000))
        assertFalse(LocalLfm3DownloadRules.rangeAccepted(206, "bytes 0-999/1000", 0, 1000))
        assertFalse(LocalLfm3DownloadRules.rangeAccepted(206, "bytes 100-999/1000", 1000, 1000))
    }

    @Test fun progressIsSafeForInvalidAndCompleteSizes() {
        assertEquals(0, LocalLfm3DownloadRules.progress(1, 0))
        assertEquals(0, LocalLfm3DownloadRules.progress(0, 1_000))
        assertEquals(50, LocalLfm3DownloadRules.progress(500, 1_000))
        assertEquals(100, LocalLfm3DownloadRules.progress(1_500, 1_000))
    }
}
