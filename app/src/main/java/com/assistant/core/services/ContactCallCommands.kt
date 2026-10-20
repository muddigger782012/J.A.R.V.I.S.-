package com.assistant.core.services

import java.util.Locale

class ContactCallCommands(private val workflow: () -> ContactCallWorkflow) {
    private var reviewed: ContactCallWorkflow.Plan? = null
    @Synchronized fun discardReview() { reviewed = null }
    @Synchronized fun reply(input: Input): String {
        val manager = workflow()
        val result = when (input.command) {
            Command.PREPARE -> {
                reviewed = null
                input.query?.takeIf { it.length in 1..160 && it.none(Char::isISOControl) }?.let(manager::prepare)
                    ?: manager.cancel() + " Use 'prepare a call to' followed by a full contact name or phone number."
            }
            Command.REVIEW -> manager.review()
            Command.SELECT -> {
                val current = manager.pending()
                if (current != reviewed || current == null) manager.review() + " Review these choices before selecting again."
                else if (input.number == null || input.number !in 1..current.recipients.size) manager.review() + " Choose a listed number."
                else manager.select(current.id, input.number)
            }
            Command.APPROVE -> {
                val current = manager.pending()
                if (current == null) manager.review()
                else if (reviewed != current) manager.review() + " Review these current details before approving again."
                else manager.approve(current)
            }
            Command.CANCEL -> manager.cancel()
            Command.STATUS -> manager.status()
            Command.INVALID -> "Use 'review contact call', 'choose contact 1', 'approve contact call' or 'cancel contact call'. No call was requested."
        }
        reviewed = if (input.command == Command.INVALID) null else manager.pending()
        return result
    }
    @Synchronized fun confirmation(text: String): String? {
        if (!ConfirmationWords.isConfirmation(text) || workflow().pending() == null) return null
        return if (ConfirmationWords.cancels(text)) reply(Input(Command.CANCEL))
        else {
            val result = workflow().review()
            reviewed = workflow().pending()
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
