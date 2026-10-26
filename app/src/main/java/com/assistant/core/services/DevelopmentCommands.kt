package com.assistant.core.services

object DevelopmentCommands {
    enum class Kind { CAPABILITIES, STATUS, LIST, REVIEW_RUNTIME, CANCEL, PAUSE, RESUME, RETRY }
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
            else -> null
        }
    }
}
