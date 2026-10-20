package com.assistant.core.services

import com.assistant.core.workflow.RoutineExecutionCoordinator
import com.assistant.core.workflow.RoutineHistory
import java.util.Locale

/** Review authorization is short-lived and local to one service; dialogue cannot restore it. */
class TaskHistoryCommands(private val coordinator: () -> RoutineExecutionCoordinator,
                          private val now: () -> Long = System::currentTimeMillis) {
    private data class Pending(val review: RoutineHistory.Review, val time: Long)
    private var pending: Pending? = null

    @Synchronized fun reply(command: Command): String {
        if (command == Command.CLEAR) {
            val saved = pending
            pending = null
            val age = saved?.let { now() - it.time }
            if (saved == null || age == null || age !in 0..120_000L)
                return "Say 'review task history' in this conversation before clearing completed records. Nothing was removed."
            return coordinator().clearHistory(saved.review).message
        }
        pending = null
        val result = coordinator().reviewHistory()
        val review = result.review ?: return result.message
        if (review.removable.isNotEmpty()) pending = Pending(review, now())
        return buildString {
            appendLine("Task history: ${review.entries.size} retained records; ${review.removable.size} older completed records eligible for cleanup.")
            review.removable.groupingBy { entry ->
                "${if (GoalPlanning.isKey(entry.phrase)) "goal plan" else entry.phrase}: ${entry.status.name.lowercase(Locale.ROOT).replace('_', ' ')}"
            }.eachCount().forEach { (description, count) -> appendLine("$description ($count records).") }
            appendLine("The latest task and every unfinished, failed, in-flight or uncertain task will remain. Cancellation cannot erase an uncertain action.")
            appendLine("Cleanup keeps ${review.retiredFingerprints} existing duplicate-prevention fingerprints and adds fingerprints for removed records; this bounded safety record is not cleared by cleanup.")
            if (review.removable.isEmpty()) append("Nothing is eligible for cleanup.")
            else append("To remove only the reviewed completed records, say 'clear reviewed task history' within two minutes. This does not delete saved routines, preferences, conversation or improvement reports, and does not undo actions.")
        }
    }

    @Synchronized fun discardReview() { pending = null }

    enum class Command { REVIEW, CLEAR }
    companion object {
        private fun normalize(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        fun parse(text: String): Command? = when (normalize(text)) {
            "review task history" -> Command.REVIEW
            "clear reviewed task history" -> Command.CLEAR
            else -> null
        }
        fun isManagementInput(text: String): Boolean = normalize(text).let {
            it.startsWith("review task history") || it.startsWith("clear reviewed task history")
        }
    }
}
