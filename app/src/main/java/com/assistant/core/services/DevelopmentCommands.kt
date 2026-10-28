package com.assistant.core.services

object DevelopmentCommands {
    enum class Kind { CAPABILITIES, STATUS, LIST, REVIEW_RUNTIME, ANALYZE_PERSONAL, ANALYZE_DEVELOPER, RETRY_PENDING, CANCEL, PAUSE, RESUME, RETRY, REVIEW_APK, INSTALL_APK, APPROVE_INSTALL_APK, CANCEL_INSTALL_APK, CHECK_INSTALLED_APK }
    private val apkReview = Regex("""^(?:(?:please|can you|could you|would you) )?(?:(?:open|show)(?: me)? (?:the )?(?:verified )?apk review(?: in developer| for (?:the |my )?development (?:job|task))?|review (?:the |my |this )?(?:verified )?apk (?:from developer|developer (?:just )?built)|open developer(?:'s)? apk review)$""")
    private val apkInstall = Regex("""^(?:(?:please|can you|could you|would you) )?install (?:the |my |this )?(?:latest |verified )?apk (?:that )?developer (?:just )?built$""")
    private val installedCheck = Regex("""^(?:(?:please|can you|could you|would you) )?(?:(?:check|recheck|verify) (?:the |my |this )?apk (?:that )?developer (?:just )?installed|(?:run|repeat) (?:the )?installed apk checks)$""")
    fun parse(input: String): Kind? {
        val text = input.lowercase(java.util.Locale.ROOT).replace('’', '\'').trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
        if (apkReview.matches(text)) return Kind.REVIEW_APK
        if (apkInstall.matches(text)) return Kind.INSTALL_APK
        if (installedCheck.matches(text)) return Kind.CHECK_INSTALLED_APK
        return when (text) {
            "approve this apk installation", "confirm this apk installation" -> Kind.APPROVE_INSTALL_APK
            "cancel apk installation", "cancel this apk installation" -> Kind.CANCEL_INSTALL_APK
            "developer connection status", "check developer connection", "developer capabilities" -> Kind.CAPABILITIES
            "development job status", "developer job status", "how is the development task going" -> Kind.STATUS
            "list development jobs", "show development jobs", "development dashboard" -> Kind.LIST
            "send runtime diagnostics to developer", "send voice diagnostics to developer", "ask developer to review voice diagnostics" -> Kind.REVIEW_RUNTIME
            "cancel development task", "cancel developer job" -> Kind.CANCEL
            "pause development task", "pause developer job" -> Kind.PAUSE
            "resume development task", "resume developer job" -> Kind.RESUME
            "retry development task", "retry developer job" -> Kind.RETRY
            "retry pending development reviews", "retry retained developer reviews" -> Kind.RETRY_PENDING
            "analyze your source code with developer", "ask developer to inspect your source", "analyze personal jarvis source" -> Kind.ANALYZE_PERSONAL
            "analyze the developer source", "inspect jarvis developer source code" -> Kind.ANALYZE_DEVELOPER
            else -> null
        }
    }
}
