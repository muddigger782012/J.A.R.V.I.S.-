package com.jarvis.developer

import android.os.Bundle
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONObject

/** Bounded local presentation only. Bookmarks, filters and titles cannot authorize a job action. */
internal object DevelopmentJobHistory {
    const val PAGE_SIZE = 8
    enum class Filter(val label: String) {
        ALL("All jobs"), ACTIVE("Active or queued"), ATTENTION("Needs review"), STOPPED("Paused or cancelled");
        fun includes(state: String) = when (this) {
            ALL -> true
            ACTIVE -> state in setOf("QUEUED", "RUNNING", "COMMITTING", "OUTCOME_UNKNOWN", "VERIFYING")
            ATTENTION -> state in setOf("REVIEW_READY", "AWAITING_APPROVAL", "OUTCOME_UNKNOWN", "READY_FOR_REVIEW", "REVIEW_REQUIRED", "FAILED")
            STOPPED -> state in setOf("PAUSED", "CANCELLED")
        }
    }
    data class State(val filter: Filter = Filter.ALL, val offset: Int = 0, val anchor: String? = null,
        val requested: Boolean = false) {
        fun save() = Bundle().apply {
            putString("filter", filter.name); putInt("offset", offset.coerceIn(0, 63))
            anchor?.let { putString("anchor", it) }; putBoolean("requested", requested)
        }
    }
    fun restore(bundle: Bundle?): State {
        if (bundle == null) return State()
        val filter = runCatching { Filter.valueOf(bundle.getString("filter").orEmpty()) }.getOrDefault(Filter.ALL)
        val offset = runCatching { bundle.getInt("offset", 0).coerceIn(0, 63) }.getOrDefault(0)
        val anchor = runCatching { bundle.getString("anchor")?.also {
            Wire.validate(Wire.Request("history-bookmark", "task.status.request", jobId = it))
        } }.getOrNull()
        return State(filter, offset, anchor, runCatching { bundle.getBoolean("requested", false) }.getOrDefault(false))
    }
    data class Page(val rows: List<JSONObject>, val offset: Int, val matches: Int, val total: Int,
        val newerAnchor: String?, val olderAnchor: String?, val focused: Boolean) {
        val description get() = when {
            focused && rows.isEmpty() -> "The requested review is unavailable. Show all development reviews to browse other tasks."
            focused -> "Showing the requested task."
            matches == 0 && total == 0 -> "No stored jobs yet. Requests from Assistant will appear here."
            matches == 0 -> "No jobs match this filter. Choose All jobs to browse the history."
            else -> "Showing ${offset + 1}–${offset + rows.size} of $matches matching jobs ($total stored). Newest requests first."
        }
    }
    fun select(jobs: List<JSONObject>, state: State, focus: String? = null): Page {
        require(jobs.size <= 64 && jobs.map { it.getString("id") }.distinct().size == jobs.size)
        if (focus != null) return Page(jobs.filter { it.getString("id") == focus }, 0,
            jobs.count { it.getString("id") == focus }, jobs.size, null, null, true)
        val matching = jobs.asReversed().filter { state.filter.includes(it.getString("state")) }
        val anchor = state.anchor?.let { id -> matching.indexOfFirst { it.getString("id") == id }.takeIf { it >= 0 } }
        val lastPage = if (matching.isEmpty()) 0 else (matching.size - 1) / PAGE_SIZE * PAGE_SIZE
        val start = anchor ?: state.offset.coerceIn(0, lastPage)
        return Page(matching.drop(start).take(PAGE_SIZE), start, matching.size, jobs.size,
            if (start > 0) matching.getOrNull((start - PAGE_SIZE).coerceAtLeast(0))?.getString("id") else null,
            matching.getOrNull(start + PAGE_SIZE)?.getString("id"), false)
    }
    fun requestLabel(job: JSONObject): String = when (job.optString("kind")) {
        "source.personal.analysis.request" -> "Personal source analysis"
        "source.developer.analysis.request" -> "Developer source analysis"
        else -> "Diagnostic review"
    }
    fun originLabel(job: JSONObject) = if (job.optString("caller") == "com.assistant.core.beta") "Jarvis Beta" else "Personal Jarvis"
}
