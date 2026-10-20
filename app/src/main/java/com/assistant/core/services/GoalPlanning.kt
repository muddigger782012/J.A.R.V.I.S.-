package com.assistant.core.services

import java.util.Locale
import java.util.UUID

/** Three bounded offline goal templates. Parsing and planning have no device effects. */
object GoalPlanning {
    enum class Kind(val title: String, val defaultVolume: Int) {
        FOCUS("Focus session", 25), MUSIC("Music session", 35), NAVIGATION("Navigation preparation", 50)
    }
    data class Request(val kind: Kind, val volume: Int, val seconds: Int? = null, val appLabel: String? = null, val invalidDuration: Boolean = false)
    data class Plan(val key: String, val kind: Kind, val actions: List<String>)
    enum class Command { REVIEW, APPROVE, CANCEL, STATUS, RESUME, WORKED, FAILED }

    fun command(text: String): Command? = when (clean(text)) {
        "review goal plan" -> Command.REVIEW
        "approve goal plan" -> Command.APPROVE
        "cancel goal plan" -> Command.CANCEL
        "goal plan status" -> Command.STATUS
        "resume goal plan" -> Command.RESUME
        "the goal worked" -> Command.WORKED
        "the goal failed" -> Command.FAILED
        else -> null
    }

    fun parse(text: String): Request? {
        if (text.length > 400) return null
        val value = clean(text).removePrefix("please ").removePrefix("can you ").removePrefix("could you ")
        val volume = "(?: (?:at|with) ([0-9]{1,3})(?: percent|%)(?: media)? volume)?"
        val focus = Regex("^(?:help me focus|(?:plan|prepare|set up|start) (?:a )?(?:focus|study) session)(?: for (.+?))?$volume$").matchEntire(value)
        if (focus != null) {
            val duration = focus.groupValues[1]
            val seconds = duration.takeIf { it.isNotBlank() }?.let(SpokenTimerDuration::seconds)
            val level = focus.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: Kind.FOCUS.defaultVolume
            return Request(Kind.FOCUS, level, seconds, invalidDuration = duration.isNotBlank() && seconds == null)
        }
        for ((kind, prefix) in listOf(
            Kind.MUSIC to "(?:help me listen to music|(?:plan|prepare|set up|start) (?:a )?music session)",
            Kind.NAVIGATION to "(?:help me get ready to navigate|prepare navigation|(?:plan|prepare|set up) (?:a )?navigation session)"
        )) {
            val match = Regex("^$prefix$volume(?: using (.{1,100}))?$").matchEntire(value) ?: continue
            val level = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: kind.defaultVolume
            return Request(kind, level, appLabel = match.groupValues[2].takeIf { it.isNotBlank() })
        }
        return null
    }

    fun plan(request: Request, preferred: (String) -> LearnableDeviceAction?,
             resolveApp: (String) -> Pair<String, String>?, canLaunch: (String) -> Boolean): Plan? {
        if (request.volume !in 0..100 || request.invalidDuration) return null
        val last = when (request.kind) {
            Kind.FOCUS -> request.seconds?.takeIf { it in 1..86400 }?.let { "timer:$it" } ?: return null
            else -> {
                val action = if (request.appLabel != null) {
                    val resolved = resolveApp(request.appLabel) ?: return null
                    if (!resolved.second.equals(request.appLabel, ignoreCase = true)) return null
                    LearnableDeviceAction("open_app", mapOf("package" to resolved.first, "label" to resolved.second))
                } else preferred(if (request.kind == Kind.MUSIC) "music" else "maps") ?: return null
                if (!action.isValid() || action.capabilityId != "open_app" || !canLaunch(action.parameters.getValue("package"))) return null
                RoutineSteps.appStep(action) ?: return null
            }
        }
        return Plan("goal plan ${UUID.randomUUID()}", request.kind, listOf("volume:${request.volume}", last))
    }

    fun isKey(key: String): Boolean = key.startsWith("goal plan ") &&
        runCatching { UUID.fromString(key.removePrefix("goal plan ")).toString() == key.removePrefix("goal plan ") }.getOrDefault(false)

    fun valid(plan: Plan): Boolean = isKey(plan.key) &&
        plan.actions.size == 2 && RoutineSteps.isValid(plan.actions) && RoutineSteps.volumePercent(plan.actions[0]) != null &&
        (if (plan.kind == Kind.FOCUS) RoutineSteps.timerAction(plan.actions[1]) != null else RoutineSteps.appAction(plan.actions[1]) != null)

    fun review(plan: Plan) = "Review ${plan.kind.title.lowercase(Locale.ROOT)}: " +
        plan.actions.joinToString(" then ", transform = RoutineProposal::describe) +
        ". These are proposed steps; nothing has run. Say 'approve goal plan' to request them or 'cancel goal plan' to discard." +
        when (plan.kind) {
            Kind.MUSIC -> " After the app opens, select music or press Play there."
            Kind.NAVIGATION -> " After Maps opens, choose your destination to start navigation."
            Kind.FOCUS -> ""
        }

    private fun clean(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
}
