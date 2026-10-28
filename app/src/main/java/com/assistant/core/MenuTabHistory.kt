package com.assistant.core

/** Utility navigation is independent of chat messages, tools and conversational task state. */
internal class MenuTabHistory(saved: IntArray? = null) {
    private val pages = saved?.toList()?.takeIf { it.size in 1..32 && it.first() == 0 && it.all { page -> page in 0..7 } }
        ?.toMutableList() ?: mutableListOf(0)
    val current get() = pages.last()
    fun visit(page: Int) {
        require(page in 0..7)
        if (page == current) return
        if (page == 0) { close(); return }
        if (pages.size == 32) pages.removeAt(1)
        pages.add(page)
    }
    fun back(): Int { if (pages.size > 1) pages.removeAt(pages.lastIndex); return current }
    fun close() { pages.clear(); pages.add(0) }
    fun save() = pages.toIntArray()
}
