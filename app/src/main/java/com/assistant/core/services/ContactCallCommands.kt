package com.assistant.core.services

import java.util.Locale

class ContactCallCommands(private val workflow: () -> ContactCallWorkflow) {
    private var reviewed: ContactCallWorkflow.Plan? = null
    @Synchronized fun discardReview() { reviewed = null }
    @Synchronized fun reply(input: Input): String {
        val manager = workflow()
        fun present(plan: ContactCallWorkflow.Plan?): String {
            reviewed = plan
            return plan?.let(manager::render) ?: manager.review()
        }
        val result = when (input.command) {
            Command.PREPARE -> {
                reviewed = null
                val response = input.query?.takeIf { it.length in 1..160 && it.none(Char::isISOControl) }?.let(manager::prepare)
                    ?: manager.cancel() + " Use 'prepare a call to' followed by a full contact name or phone number."
                val plan = manager.pending()
                if (plan != null) present(plan) else response
            }
            Command.REVIEW -> present(manager.pending())
            Command.SELECT -> {
                val current = manager.pending()
                if (current != reviewed || current == null) present(current) + " Review these choices before selecting again."
                else if (input.number == null || input.number !in 1..current.recipients.size) present(current) + " Choose a listed number."
                else {
                    val response = manager.select(current.id, input.number)
                    val plan = manager.pending()
                    if (plan != null) present(plan) else { reviewed = null; response }
                }
            }
            Command.APPROVE -> {
                val current = manager.pending()
                if (current == null) present(null)
                else if (reviewed != current) present(current) + " Review these current details before approving again."
                else if (current.selected == null) present(current) + " Choose a recipient before approving the call."
                else { reviewed = null; manager.approve(current) }
            }
            Command.CANCEL -> { reviewed = null; manager.cancel() }
            Command.STATUS -> { reviewed = null; manager.status() }
            Command.INVALID -> { reviewed = null; "Use 'review contact call', 'choose contact 1', 'approve contact call' or 'cancel contact call'. No call was requested." }
        }
        return result
    }
    @Synchronized fun confirmation(text: String): String? {
        if (!ConfirmationWords.isConfirmation(text)) return null
        val plan = workflow().pending() ?: return null
        return if (ConfirmationWords.cancels(text)) reply(Input(Command.CANCEL))
        else {
            val result = workflow().render(plan)
            reviewed = plan
            result + " A bare confirmation does not place a call; use the exact approval phrase."
        }
    }
    enum class Command { PREPARE, REVIEW, SELECT, APPROVE, CANCEL, STATUS, INVALID }
    data class Input(val command: Command, val query: String? = null, val number: Int? = null)
    companion object {
        fun parse(text: String): Input? {
            val clean = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
            val command = when (clean) {
                "review contact call", "review contact choices" -> Command.REVIEW
                "approve contact call" -> Command.APPROVE
                "cancel contact call" -> Command.CANCEL
                "contact call status" -> Command.STATUS
                else -> null
            }
            if (command != null) return Input(command)
            Regex("(?i)^(?:prepare (?:a )?call to|find contact|look up contact) (.+?)(?: and confirm (?:the )?call)?$")
                .matchEntire(text.trim())?.let { return Input(Command.PREPARE, it.groupValues[1].trim()) }
            Regex("^choose contact ([0-9]{1,2})$").matchEntire(clean)?.let { return Input(Command.SELECT, number = it.groupValues[1].toInt()) }
            return if (Regex("^(?:prepare (?:a )?call to|find contact|look up contact|choose contact|review contact (?:call|choices)|approve contact call|cancel contact call|contact call status)\\b").containsMatchIn(clean)) Input(Command.INVALID) else null
        }
    }
}
