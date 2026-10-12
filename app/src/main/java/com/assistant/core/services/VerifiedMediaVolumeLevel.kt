package com.assistant.core.services

/** Android exposes discrete stream levels; percentages select the nearest supported level. */
object VerifiedMediaVolumeLevel {
    fun targetIndex(percent: Int, maximum: Int): Int? {
        if (percent !in 0..100 || maximum <= 0) return null
        return ((maximum.toLong() * percent + 50) / 100).toInt()
    }

    fun execute(percent: Int, maximum: Int, read: () -> Int, set: (Int) -> Unit): Boolean {
        val target = targetIndex(percent, maximum) ?: return false
        return runCatching {
            val before = read()
            if (before !in 0..maximum) return@runCatching false
            if (before != target) set(target)
            read() == target
        }.getOrDefault(false)
    }
}
