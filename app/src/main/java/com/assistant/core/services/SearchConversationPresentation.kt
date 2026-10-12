package com.assistant.core.services

/** Hide a search's temporary launch card only when its result exists in the same user turn. */
object SearchConversationPresentation {
    fun visible(lines: List<String>): List<String> = lines.filterIndexed { index, line ->
        val match = Regex("^J\\.A\\.R\\.V\\.I\\.S\\.: (?:Web|Image|Video) search ready: ([^\\n]+)").find(line)
        if (match == null) true else {
            val query = match.groupValues[1].trim()
            lines.drop(index + 1).takeWhile { !it.startsWith("You:") }
                .none { ChatSearchResults.decode(it)?.query == query }
        }
    }
}
