package com.assistant.core.services

import java.text.DateFormat
import java.util.Date
import java.util.Locale

class ReminderCommands(private val reminders: () -> PersonalReminders) {
    private var reviewedId: String? = null
    @Synchronized fun discardReview() { reviewedId = null }
    @Synchronized fun confirmation(text: String): String? {
        if (!ConfirmationWords.isConfirmation(text)) return null
        val plan = reminders().pending() ?: return null
        return if (ConfirmationWords.cancels(text)) {
            reviewedId = null
            check(reminders().discardPending())
            "Discarded the pending reminder review. No alarm was registered for that proposal."
        } else review(plan) + " A bare confirmation does not register it; use the exact approval phrase."
    }

    @Synchronized fun reply(input: Input): String {
        val manager = reminders()
        return when (input.command) {
            Command.PREPARE -> {
                reviewedId = null
                if (input.seconds == null || input.title == null || !PersonalReminders.validTitle(input.title)) {
                    check(manager.discardPending())
                    "Use 'remind me in ten minutes to check the oven', with a duration from one second to 24 hours and a title up to 160 characters. For later dates, use 'create a calendar event called Check the oven' and choose its date in your calendar. No new local reminder was approved."
                } else {
                    val plan = manager.prepare(input.title, input.seconds)
                    if (!input.directRequest) review(plan) else {
                        // A fully specified "remind me" request is already authorization for
                        // this reversible local notification. "Prepare" keeps explicit review.
                        val result = manager.approve(plan)
                        if (result.record == null && manager.pending() == plan) review(plan) + " " + result.message
                        else "Local reminder '${plan.title}' for ${time(plan.dueAt)}. ${result.message}"
                    }
                }
            }
            Command.REVIEW_PLAN -> manager.pending()?.let(::review) ?: "No unexpired reminder plan is awaiting approval. Request a new reminder with its duration and title."
            Command.APPROVE -> {
                val plan = manager.pending() ?: return "No unexpired reminder plan is awaiting approval. Request and review a new reminder first."
                if (reviewedId != plan.id) review(plan) + " Review these current details before approving again."
                else manager.approve(plan).let { result ->
                    if (result.record != null) reviewedId = null
                    result.message
                }
            }
            Command.CANCEL_PLAN -> {
                reviewedId = null
                if (manager.discardPending()) "Discarded the pending reminder review. Approved reminders are separate."
                else "Could not confirm deletion of the reminder review. Inspect it before approval."
            }
            Command.REVIEW -> render(manager.records())
            Command.CANCEL -> input.number?.let { manager.cancel(it).message } ?: "Use 'cancel reminder' followed by its number from 'review reminders'."
            Command.RETRY -> input.number?.let { manager.retry(it).message } ?: "Use 'retry reminder' followed by its number from 'review reminders'."
            Command.CONFIRM_SHOWN -> input.number?.let { manager.confirmShown(it).message } ?: "Report a listed uncertain reminder number only after checking that its notification was shown."
            Command.CLEAR -> "Cleared ${manager.clearCompleted()} completed reminder records. Active, blocked, unconfirmed and uncertain reminders remain; posted notifications are not recalled."
            Command.INVALID -> "Use 'review reminders', 'approve reminder', 'cancel reminder plan', or 'cancel reminder' followed by a listed number. No reminder action was performed."
        }
    }
    private fun review(plan: PersonalReminders.Plan): String {
        reviewedId = plan.id
        return "Review local reminder: '${plan.title}' at ${time(plan.dueAt)}. It will be a notification; Android may delay delivery. Nothing is registered for this proposal yet. Say 'approve reminder' or 'cancel reminder plan'. This review expires in two minutes or when its due time passes."
    }
    private fun render(records: List<PersonalReminders.Record>): String = if (records.isEmpty())
        "No local reminders are saved. Try 'remind me in ten minutes to check the oven'."
    else "Local reminders:\n" + records.joinToString("\n") { record ->
        "Reminder ${record.number}: '${record.plan.title}', ${time(record.plan.dueAt)} — " + when (record.status) {
            PersonalReminders.Status.SCHEDULED -> "registered with Android; delivery is approximate and not yet verified."
            PersonalReminders.Status.SCHEDULING -> "registration checkpoint is incomplete; restart recovery can safely re-register the same alarm."
            PersonalReminders.Status.SCHEDULE_FAILED -> "alarm registration unconfirmed; explicitly retry this reminder."
            PersonalReminders.Status.BLOCKED -> "notification blocked; enable notifications and explicitly retry this reminder."
            PersonalReminders.Status.DELIVERING, PersonalReminders.Status.DELIVERY_UNKNOWN -> "delivery uncertain; check notifications, and do not repeat automatically."
            PersonalReminders.Status.POSTED -> "notification was posted; reading or completing the task is unverified."
            PersonalReminders.Status.USER_CONFIRMED -> "you reported seeing the notification; device verification and task completion remain separate."
            PersonalReminders.Status.CANCELLED -> "cancelled; later delivery suppressed."
            PersonalReminders.Status.MISSED -> "more than a day overdue; notification was not posted."
        }
    } + "\nUse 'cancel reminder 1' or 'retry reminder 1' with a listed number. After checking an uncertain notification, 'reminder 1 was shown' records your report without dispatch. 'Clear completed reminder history' removes only posted, owner-confirmed, cancelled or missed records."

    enum class Command { PREPARE, REVIEW_PLAN, APPROVE, CANCEL_PLAN, REVIEW, CANCEL, RETRY, CONFIRM_SHOWN, CLEAR, INVALID }
    data class Input(val command: Command, val seconds: Int? = null, val title: String? = null, val number: Int? = null, val directRequest: Boolean = false)
    companion object {
        private fun clean(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        private fun time(value: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
        fun parse(text: String): Input? {
            val value = clean(text)
            val command = when (value) {
                "review reminders", "list reminders", "show reminders", "show my reminders", "list my reminders",
                "what reminders do i have", "what are my reminders" -> Command.REVIEW
                "review reminder plan" -> Command.REVIEW_PLAN
                "approve reminder" -> Command.APPROVE
                "cancel reminder plan" -> Command.CANCEL_PLAN
                "clear completed reminder history" -> Command.CLEAR
                else -> null
            }
            if (command != null) return Input(command)
            if (Regex("^(?:remind me|prepare (?:a )?(?:local )?reminder) in\\b").containsMatchIn(value)) {
                val match = Regex("^(remind me|prepare (?:a )?(?:local )?reminder) in (.+?) to (.+)$", RegexOption.IGNORE_CASE).matchEntire(text.trim())
                return Input(Command.PREPARE, match?.groupValues?.get(2)?.takeIf { text.length <= 400 }?.let(SpokenTimerDuration::seconds),
                    match?.groupValues?.get(3)?.trim(), directRequest = value.startsWith("remind me "))
            }
            Regex("^remind me to (.+) in (.+)$", RegexOption.IGNORE_CASE).matchEntire(text.trim())?.let { match ->
                val duration = match.groupValues[2]
                val seconds = duration.takeIf { text.length <= 400 }?.let(SpokenTimerDuration::seconds)
                // Preserve untimed calendar requests such as "review the report in French".
                if (seconds != null || Regex("(?i)\\b(?:seconds?|minutes?|hours?|days?|weeks?|months?)\\b|^[0-9]").containsMatchIn(duration))
                    return Input(Command.PREPARE, seconds, match.groupValues[1].trim(), directRequest = true)
            }
            Regex("^(cancel|retry) reminder ([0-9]{1,9})$").matchEntire(value)?.let {
                return Input(if (it.groupValues[1] == "cancel") Command.CANCEL else Command.RETRY, number = it.groupValues[2].toIntOrNull()?.takeIf { n -> n > 0 })
            }
            Regex("^reminder ([0-9]{1,9}) was shown$").matchEntire(value)?.let {
                return Input(Command.CONFIRM_SHOWN, number = it.groupValues[1].toIntOrNull()?.takeIf { n -> n > 0 })
            }
            return if (Regex("^(?:review reminders|list (?:my )?reminders|show (?:my )?reminders|what reminders do i have|what are my reminders|review reminder plan|approve reminder|cancel reminder|retry reminder|clear completed reminder history|reminder [0-9]+ was shown)\\b").containsMatchIn(value)) Input(Command.INVALID) else null
        }
    }
}
