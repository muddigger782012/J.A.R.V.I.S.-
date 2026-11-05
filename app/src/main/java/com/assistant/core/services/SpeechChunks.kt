package com.assistant.core.services

object SpeechChunks {
    /**
     * Kokoro's first PCM block is produced after synthesizing the entire first
     * phrase. Use a small first clause to start audible playback sooner, then
     * resume normal-sized chunks for efficient one-ahead synthesis.
     */
    fun lowLatency(text: String, firstLimit: Int = 70, remainingLimit: Int = 180): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val first = split(clean, firstLimit).first()
        val tail = clean.substring(first.length).trimStart()
        return listOf(first) + split(tail, remainingLimit)
    }

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
