package com.jarvis.developer

/** Shared strict JSON syntax/duplicate-key validator; callers bound UTF-8 bytes before parsing. */
internal class BoundedJsonGrammar(private val text: String) {
    private var cursor = 0
    private var nodes = 0
    fun validate() {
        whitespace()
        require(cursor < text.length && text[cursor] == '{')
        value(0)
        whitespace()
        require(cursor == text.length)
    }
    private fun value(depth: Int) {
        require(depth <= 32 && ++nodes <= 20_000)
        whitespace()
        require(cursor < text.length)
        when (text[cursor]) {
            '{' -> {
                cursor++; whitespace()
                val keys = HashSet<String>()
                if (take('}')) return
                do {
                    whitespace(); require(keys.add(string())); whitespace(); require(take(':'))
                    value(depth + 1); whitespace()
                    if (take('}')) return
                } while (take(','))
                error("Invalid JSON object")
            }
            '[' -> {
                cursor++; whitespace()
                if (take(']')) return
                do { value(depth + 1); whitespace(); if (take(']')) return } while (take(','))
                error("Invalid JSON array")
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val start = cursor
                while (cursor < text.length && text[cursor] in "-+0123456789.eE") cursor++
                require(text.substring(start, cursor).matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))
            }
        }
    }
    private fun string(): String {
        require(take('"'))
        val result = StringBuilder()
        while (cursor < text.length) {
            val character = text[cursor++]
            if (character == '"') {
                var index = 0
                while (index < result.length) {
                    val current = result[index++]
                    if (Character.isHighSurrogate(current)) {
                        require(index < result.length && Character.isLowSurrogate(result[index++]))
                    } else require(!Character.isLowSurrogate(current))
                }
                return result.toString()
            }
            require(character.code >= 32)
            if (character != '\\') result.append(character) else {
                require(cursor < text.length)
                when (val escape = text[cursor++]) {
                    '"', '\\', '/' -> result.append(escape)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        require(cursor + 4 <= text.length)
                        val digits = text.substring(cursor, cursor + 4)
                        require(digits.matches(Regex("[0-9a-fA-F]{4}")))
                        result.append(digits.toInt(16).toChar()); cursor += 4
                    }
                    else -> error("Invalid JSON escape")
                }
            }
        }
        error("Unterminated JSON string")
    }
    private fun literal(expected: String) { require(text.startsWith(expected, cursor)); cursor += expected.length }
    private fun take(character: Char): Boolean = if (cursor < text.length && text[cursor] == character) { cursor++; true } else false
    private fun whitespace() { while (cursor < text.length && text[cursor] in " \t\n\r") cursor++ }
}
