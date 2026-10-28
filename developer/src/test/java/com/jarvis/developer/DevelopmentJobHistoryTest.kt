package com.jarvis.developer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DevelopmentJobHistoryTest {
    private fun job(index: Int, state: String = "QUEUED") = JSONObject()
        .put("id", "job_" + index.toString(16).padStart(64, '0')).put("state", state).put("deviceVerified", false)
    @Test fun pagingReachesEveryStoredJobOnceInStableNewestFirstOrder() {
        val jobs = (0 until 64).map { job(it) }
        val before = jobs.map { it.toString() }
        val seen = mutableListOf<String>()
        var state = DevelopmentJobHistory.State()
        while (true) {
            val page = DevelopmentJobHistory.select(jobs, state)
            assertTrue(page.rows.size <= 8)
            seen.addAll(page.rows.map { it.getString("id") })
            val next = page.olderAnchor ?: break
            state = state.copy(offset = page.offset + 8, anchor = next)
        }
        assertEquals(jobs.asReversed().map { it.getString("id") }, seen)
        assertEquals(64, seen.distinct().size)
        assertEquals(before, jobs.map { it.toString() })
    }
    @Test fun aNewAdmissionDoesNotReplaceTheTaskAtTheCurrentBookmark() {
        val jobs = (0 until 12).map { job(it) }
        val old = DevelopmentJobHistory.select(jobs, DevelopmentJobHistory.State(offset = 8))
        val anchor = old.rows.first().getString("id")
        val refreshed = DevelopmentJobHistory.select(jobs + job(12), DevelopmentJobHistory.State(offset = 8, anchor = anchor))
        assertEquals(anchor, refreshed.rows.first().getString("id"))
        assertEquals(9, refreshed.offset)
        assertNotNull(refreshed.newerAnchor)
    }
    @Test fun filtersIncludeUncertainAndFailedWorkWithoutCallingItCompleted() {
        val jobs = listOf(job(0, "QUEUED"), job(1, "OUTCOME_UNKNOWN"), job(2, "READY_FOR_REVIEW"),
            job(3, "FAILED"), job(4, "PAUSED"), job(5, "CANCELLED"))
        fun states(filter: DevelopmentJobHistory.Filter) = DevelopmentJobHistory.select(jobs,
            DevelopmentJobHistory.State(filter)).rows.map { it.getString("state") }.toSet()
        assertEquals(setOf("QUEUED", "OUTCOME_UNKNOWN"), states(DevelopmentJobHistory.Filter.ACTIVE))
        assertEquals(setOf("OUTCOME_UNKNOWN", "READY_FOR_REVIEW", "FAILED"), states(DevelopmentJobHistory.Filter.ATTENTION))
        assertEquals(setOf("PAUSED", "CANCELLED"), states(DevelopmentJobHistory.Filter.STOPPED))
        assertTrue(DevelopmentJobHistory.Filter.entries.none { "completed" in it.label.lowercase() })
        assertTrue(jobs.none { it.getBoolean("deviceVerified") })
    }
    @Test fun aFocusedReviewOverridesFiltersButNeverFallsBackToAnotherTask() {
        val jobs = (0 until 12).map { job(it) }
        val state = DevelopmentJobHistory.State(DevelopmentJobHistory.Filter.STOPPED, 8)
        val focused = DevelopmentJobHistory.select(jobs, state, jobs.first().getString("id"))
        assertEquals(listOf(jobs.first()), focused.rows)
        assertTrue(focused.focused); assertNull(focused.olderAnchor); assertNull(focused.newerAnchor)
        val missing = DevelopmentJobHistory.select(jobs, state, job(99).getString("id"))
        assertTrue(missing.rows.isEmpty()); assertTrue(missing.description.contains("unavailable"))
    }
    @Test fun vanishedFiltersAndStaleOffsetsProduceBoundedEmptyOrLastPages() {
        val jobs = (0 until 64).map { job(it) }
        val last = DevelopmentJobHistory.select(jobs, DevelopmentJobHistory.State(offset = Int.MAX_VALUE))
        assertEquals(56, last.offset); assertEquals(8, last.rows.size)
        val empty = DevelopmentJobHistory.select(jobs, DevelopmentJobHistory.State(DevelopmentJobHistory.Filter.STOPPED, 56, "missing"))
        assertTrue(empty.rows.isEmpty()); assertNull(empty.newerAnchor); assertNull(empty.olderAnchor)
        assertEquals(0, empty.offset)
    }
    @Test fun anInvalidInventoryCannotSilentlyHideOrDuplicateTasks() {
        assertThrows(IllegalArgumentException::class.java) { DevelopmentJobHistory.select((0..64).map { job(it) }, DevelopmentJobHistory.State()) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentJobHistory.select(listOf(job(1), job(1)), DevelopmentJobHistory.State()) }
    }
    @Test fun theHistoryControllerIsNotAnEditableRuntimeRepairTarget() {
        assertFalse(PatchEngine.allowedPath("developer/src/main/java/com/jarvis/developer/DevelopmentJobHistory.kt"))
    }
}
