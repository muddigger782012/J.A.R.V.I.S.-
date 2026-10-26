package com.jarvis.developer

/** Process-local read cancellation only; it contains no credential or installation authority. */
internal object DevelopmentArtifactReads {
    private val active = java.util.concurrent.ConcurrentHashMap<String, PatchPreviewCancellation>()
    @Synchronized fun begin(job: String, signal: PatchPreviewCancellation) {
        require(active.containsKey(job) || active.size < 64)
        active.put(job, signal)?.cancel()
    }
    fun finish(job: String, signal: PatchPreviewCancellation) { active.remove(job, signal) }
    fun cancel(job: String) { active.remove(job)?.cancel() }
}
