package com.assistant.core.services

object SpeechChunks {
    fun split(text: String, limit: Int = 3000): List<String> {
        require(limit >= 2)
        var remaining = text.trim()
        val chunks = mutableListOf<String>()
        while (remaining.length > limit) {
            val sentence = remaining.lastIndexOf(". ", limit - 1)
            val space = remaining.lastIndexOf(' ', limit)
            var end = if (sentence >= limit / 2) sentence + 1 else if (space > 0) space else limit
            if (end < remaining.length && Character.isHighSurrogate(remaining[end - 1]) && Character.isLowSurrogate(remaining[end])) end--
            chunks.add(remaining.substring(0, end).trim())
            remaining = remaining.substring(end).trimStart()
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks
    }
}
