package com.jarvis.developer

/**
 * Delivery gates for all 24 agreed capabilities. A source commit or passing theme check
 * is not proof that an end-user feature works. Unverified work stays unverified.
 */
object DevelopmentDeliveryGate {
    enum class State { NOT_STARTED, IMPLEMENTING, INTEGRATED, VERIFIED, RELEASED }
    data class Capability(val id: Int, val name: String, val state: State = State.NOT_STARTED,
                          val evidenceUrl: String? = null) {
        init {
            require(id in 1..24)
            require(name.isNotBlank())
            if (state == State.VERIFIED || state == State.RELEASED)
                require(evidenceUrl?.startsWith("https://") == true) { "Verification requires evidence" }
        }
    }
    val baseline: List<Capability> = listOf(
        Capability(1, "AI coding engine"),
        Capability(2, "Multi-file generation"),
        Capability(3, "GitHub workflow"),
        Capability(4, "Approved task execution"),
        Capability(5, "Automated diagnosis and repair"),
        Capability(6, "APK build execution"),
        Capability(7, "In-app APK installation"),
        Capability(8, "Self-update"),
        Capability(9, "Update rollback"),
        Capability(10, "Project memory"),
        Capability(11, "Job resume"),
        Capability(12, "Concurrent workstreams"),
        Capability(13, "Graphical previews"),
        Capability(14, "Design recommendations"),
        Capability(15, "Apply design decisions"),
        Capability(16, "Projects and Tools redesign"),
        Capability(17, "Back and Close navigation"),
        Capability(18, "Build dashboard"),
        Capability(19, "Model and API settings"),
        Capability(20, "Terminal and developer tools"),
        Capability(21, "Device permissions and Shizuku"),
        Capability(22, "Personal and Developer isolation"),
        Capability(23, "Verified APK"),
        Capability(24, "Published install/update release")
    )
    init { require(baseline.size == 24 && baseline.map { it.id }.distinct().size == 24) }
    fun update(items: List<Capability>, item: Capability): List<Capability> {
        require(items.size == 24 && items.map { it.id }.toSet() == (1..24).toSet())
        require(items.first { it.id == item.id }.name == item.name)
        return items.map { if (it.id == item.id) item else it }
    }
    fun verifiedCount(items: List<Capability>): Int =
        items.count { it.state == State.VERIFIED || it.state == State.RELEASED }
    fun readyToPublish(items: List<Capability>): Boolean =
        items.size == 24 && items.all { it.state == State.VERIFIED || it.state == State.RELEASED }
}
