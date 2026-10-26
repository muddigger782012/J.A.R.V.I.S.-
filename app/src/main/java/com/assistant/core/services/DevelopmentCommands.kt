package com.assistant.core.services

object DevelopmentCommands {
    enum class Kind { CAPABILITIES, STATUS, LIST, REVIEW_RUNTIME, ANALYZE_PERSONAL, ANALYZE_DEVELOPER, RETRY_PENDING, CANCEL, PAUSE, RESUME, RETRY }
    fun parse(input: String): Kind? {
        val text = input.lowercase(java.util.Locale.ROOT).replace('’', '\'').trim().trimEnd('.', '?', '!')
        return when (text) {
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
