package com.assistant.core.services

import java.text.DateFormat
import java.util.Date
import java.util.Locale

class ReminderCommands(private val research: () -> ResearchBriefs.Snapshot? = { null },
                       private val reminders: () -> PersonalReminders) {
    private var reviewedPlan: PersonalReminders.Review? = null
    @Synchronized fun discardReview() { reviewedPlan = null }
    @Synchronized fun confirmation(text: String): String? {
        if (!ConfirmationWords.isConfirmation(text)) return null
        val plan = reminders().review() ?: return null
        return if (ConfirmationWords.cancels(text)) {
            reviewedPlan = null
            check(reminders().discardPending())
            "Discarded the pending reminder review. No alarm was registered for that proposal."
        } else review(plan) + " A bare confirmation does not register or change an alarm; use the exact approval phrase."
    }

    @Synchronized fun reply(input: Input): String {
        val manager = reminders()
        return when (input.command) {
            Command.PREPARE, Command.PREPARE_RESEARCH -> {
                reviewedPlan = null
                val linked = input.command == Command.PREPARE_RESEARCH
                // Copy the saved report once, before preparing the reminder. A later lookup,
                // failure or clearing of research cannot change the reviewed attachment.
                val snapshot = if (linked && input.seconds != null) research() else null
                val title = if (linked) snapshot?.message?.query?.let { "Review research: $it".take(160) } else input.title
                if (linked && input.seconds != null && snapshot == null) {
                    check(manager.discardPending())
                    "No research brief is saved to attach. Research a topic first, then say 'remind me about this research in ten minutes'. No lookup or reminder was started."
                } else if (input.seconds == null || title == null || !PersonalReminders.validTitle(title)) {
                    check(manager.discardPending())
                    if (linked) "Use 'remind me about this research in ten minutes' or 'prepare a research reminder in ten minutes', with a duration from one second to 24 hours. The exact saved brief will be attached; no lookup or reminder was started."
                    else "Use 'remind me in ten minutes to check the oven', with a duration from one second to 24 hours and a title up to 160 characters. For later dates, use 'create a calendar event called Check the oven' and choose its date in your calendar. No new local reminder was approved."
                } else {
                    val plan = manager.prepare(title, input.seconds, snapshot)
                    if (!input.directRequest) review(plan) else {
                        // A fully specified "remind me" request is already authorization for
                        // this reversible local notification. "Prepare" keeps explicit review.
                        val result = manager.approve(plan)
                        if (result.record == null && manager.pending() == plan) review(plan) + " " + result.message
                        else "Local reminder '${plan.title}' for ${time(plan.dueAt)}. ${result.message}" +
                            result.record?.takeIf { plan.research != null }?.let { " Say 'review reminder research ${it.number}' to reopen its exact saved sources offline." }.orEmpty()
                    }
                }
            }
            Command.EDIT_TIME -> {
                reviewedPlan = null
                val current = manager.review() ?: return "No pending reminder review is available to correct. For an approved reminder, say 'review reminders', then 'change reminder 1 to five minutes' with its number."
                val revised = manager.revise(current, input.seconds)
                if (revised != null) review(revised)
                else "The reminder review changed, expired or could not accept that duration. Use one second to 24 hours and request a fresh review. No alarm was registered or changed by this correction."
            }
            Command.CHANGE_TIME -> {
                reviewedPlan = null
                if (input.number == null || input.seconds == null) {
                    check(manager.discardPending())
                    "Use 'change reminder 1 to five minutes' or 'reschedule reminder one in ten minutes', with a listed number and one second to 24 hours. The old approval review was discarded; approved alarms are unchanged."
                } else manager.prepareChange(input.number, input.seconds)?.let(::review)
                    ?: "That reminder cannot currently change time. Review reminders and choose a scheduled reminder or an unconfirmed alarm registration. Posted, cancelled, blocked or uncertain delivery needs its existing result/recovery controls; no alarm was changed."
            }
            Command.REVIEW_PLAN -> manager.review()?.let(::review) ?: "No unexpired reminder plan is awaiting approval. Request a new reminder with its duration and title."
            Command.APPROVE -> {
                val plan = manager.review() ?: return "No unexpired reminder plan is awaiting approval. Request and review a new reminder first."
                val displayed = reviewedPlan
                if (displayed == null || displayed != plan) review(plan) + " Review these current details before approving again."
                else manager.approve(displayed.plan, displayed.replacing).let { result ->
                    if (result.record != null) reviewedPlan = null
                    (if (displayed.replacing != null && result.record != null) "Time change for reminder ${displayed.replacing.number}: " else "") +
                        result.message + result.record?.takeIf { displayed.plan.research != null }?.let {
                        " Say 'review reminder research ${it.number}' to reopen its exact saved sources offline."
                    }.orEmpty()
                }
            }
            Command.CANCEL_PLAN -> {
                reviewedPlan = null
                if (manager.discardPending()) "Discarded the pending reminder review. Approved reminders are separate."
                else "Could not confirm deletion of the reminder review. Inspect it before approval."
            }
            Command.REVIEW -> render(manager.records())
            Command.REVIEW_RESEARCH -> input.number?.let(manager::research) ?: "Use 'review reminder research' followed by a number from 'review reminders'. No lookup was started."
            Command.CANCEL -> input.number?.let { manager.cancel(it).message } ?: "Use 'cancel reminder' followed by its number from 'review reminders'."
            Command.RETRY -> input.number?.let { manager.retry(it).message } ?: "Use 'retry reminder' followed by its number from 'review reminders'."
            Command.CONFIRM_SHOWN -> input.number?.let { manager.confirmShown(it).message } ?: "Report a listed uncertain reminder number only after checking that its notification was shown."
            Command.CLEAR -> "Cleared ${manager.clearCompleted()} completed reminder records. Active, blocked, unconfirmed and uncertain reminders remain; posted notifications are not recalled."
            Command.INVALID -> {
                reviewedPlan = null
                check(manager.discardPending())
                "Use 'review reminders', 'approve reminder', 'cancel reminder plan', or 'change reminder 1 to five minutes' with a listed number. No reminder action was performed; the pending review was discarded and approved alarms are unchanged."
            }
        }
    }
    private fun review(plan: PersonalReminders.Plan): String = review(PersonalReminders.Review(plan))
    private fun review(proposal: PersonalReminders.Review): String {
        reviewedPlan = proposal
        val plan = proposal.plan
        val attachment = plan.research?.message?.let {
            " Attached saved research '${it.query}', retrieved ${time(it.retrievedAt)}, with ${it.items.size} linked sources. This copy stays with the reminder if later research changes; excerpts are not independently verified."
        }.orEmpty()
        val header = proposal.replacing?.let { "Review time change for reminder ${it.number}: '${plan.title}', from ${time(it.plan.dueAt)} to ${time(plan.dueAt)}. The existing approved alarm is unchanged until approval; 'cancel reminder plan' discards only this proposed change." }
            ?: "Review local reminder: '${plan.title}' at ${time(plan.dueAt)}. Nothing is registered for this proposal yet."
        return "$header$attachment It will be a notification; Android may delay delivery. Say 'approve reminder' or 'cancel reminder plan'. This review expires in two minutes or when its due time passes."
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
        } + if (record.plan.research != null) " Saved sources: 'review reminder research ${record.number}'." else ""
    } + "\nUse 'change reminder 1 to five minutes' to review a time change, or 'cancel reminder 1' or 'retry reminder 1' with a listed number. After checking an uncertain notification, 'reminder 1 was shown' records your report without dispatch. 'Clear completed reminder history' removes only posted, owner-confirmed, cancelled or missed records."

    enum class Command { PREPARE, PREPARE_RESEARCH, EDIT_TIME, CHANGE_TIME, REVIEW_PLAN, APPROVE, CANCEL_PLAN, REVIEW, REVIEW_RESEARCH, CANCEL, RETRY, CONFIRM_SHOWN, CLEAR, INVALID }
    data class Input(val command: Command, val seconds: Int? = null, val title: String? = null, val number: Int? = null, val directRequest: Boolean = false)
    companion object {
        private fun clean(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        private fun time(value: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
        /** Short corrections belong to a live reminder review, otherwise other dialogue may own them. */
        fun contextualEdit(text: String): Input? {
            val value = clean(text).replace(Regex("^actually(?:,|:)?\\s+"), "")
            val match = Regex("^make (?:that|it)(?: (.*))?$").matchEntire(value) ?: return null
            return Input(Command.EDIT_TIME, seconds = match.groupValues[1].takeIf { text.length <= 200 }?.let(SpokenTimerDuration::seconds))
        }
        fun parse(text: String): Input? {
            val value = clean(text)
            val correction = value.replace(Regex("^actually(?:,|:)?\\s+"), "")
            Regex("^change (?:the )?reminder(?: (?:time|duration))? to(?: (.*))?$").matchEntire(correction)?.let {
                return Input(Command.EDIT_TIME, seconds = it.groupValues[1].takeIf { text.length <= 200 }?.let(SpokenTimerDuration::seconds))
            }
            Regex("^(?:change|reschedule) (?:the )?reminder (.+?) (?:to|in|for) (.*)$").matchEntire(correction)?.let {
                val numberText = it.groupValues[1].replace('-', ' ')
                val number = (if (Regex("[0-9]{1,10}").matches(numberText)) numberText.toIntOrNull()
                    else if (numberText !in setOf("a", "an")) SpokenTimerDuration.number(numberText) else null)?.takeIf { n -> n > 0 }
                return Input(Command.CHANGE_TIME, seconds = it.groupValues[2].takeIf { text.length <= 300 }?.let(SpokenTimerDuration::seconds), number = number)
            }
            if (Regex("^(?:change|reschedule) (?:the )?reminder\\b").containsMatchIn(correction)) return Input(Command.INVALID)
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
            Regex("^review reminder research ([0-9]{1,9})$").matchEntire(value)?.let {
                return Input(Command.REVIEW_RESEARCH, number = it.groupValues[1].toIntOrNull()?.takeIf { n -> n > 0 })
            }
            if (value.startsWith("review reminder research")) return Input(Command.INVALID)
            val reference = "(?:this|that|the saved) research"
            val followup = Regex("^(remind me (?:about|to review) $reference|prepare (?:a )?research reminder) in (.+)$").matchEntire(value)
            val timed = Regex("^(remind me|prepare (?:a )?(?:local )?reminder) in (.+?) to review $reference$").matchEntire(value)
            if (followup != null || timed != null) {
                val duration = (followup ?: timed)!!.groupValues[2]
                return Input(Command.PREPARE_RESEARCH, seconds = duration.takeIf { text.length <= 400 }?.let(SpokenTimerDuration::seconds),
                    directRequest = value.startsWith("remind me "))
            }
            if (Regex("^(?:remind me (?:about|to review) $reference|prepare (?:a )?research reminder)\\b|^(?:remind me|prepare (?:a )?(?:local )?reminder) in .+ to review $reference\\b").containsMatchIn(value))
                return Input(Command.PREPARE_RESEARCH)
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
