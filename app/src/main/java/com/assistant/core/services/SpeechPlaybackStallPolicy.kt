package com.assistant.core.services

/** Measure elapsed time since *last* playback progress, not total reply length. */
internal object SpeechPlaybackStallPolicy {
    fun stalled(nowElapsed: Long, lastProgressElapsed: Long, timeoutMillis: Long = 150_000L): Boolean =
        lastProgressElapsed > 0L && nowElapsed >= lastProgressElapsed &&
            nowElapsed - lastProgressElapsed >= timeoutMillis
}
