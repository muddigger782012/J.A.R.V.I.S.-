package com.assistant.core.services

import java.util.Locale
import java.util.UUID

/** Bounded offline goals and host-bound task composition. Planning has no device effects. */
object GoalPlanning {
    enum class Kind(val title: String, val defaultVolume: Int) {
        FOCUS("Focus session", 25), MUSIC("Music session", 35), NAVIGATION("Navigation preparation", 50),
        COMPOSED("Custom task", 0), DRIVE("Drive preparation", 50)
    }
    data class Request(val kind: Kind, val volume: Int, val seconds: Int? = null, val appLabel: String? = null, val invalidDuration: Boolean = false,
                       val composition: ComposableGoalPlanning.Draft? = null)
    data class Plan(val key: String, val kind: Kind, val actions: List<String>)
    enum class Field { DURATION, VOLUME }
    data class Edit(val field: Field, val value: Int?)
    enum class Command { REVIEW, APPROVE, CANCEL, STATUS, RESUME, WORKED, FAILED }

    fun command(text: String): Command? = when (clean(text)) {
        "review goal plan" -> Command.REVIEW
        "approve goal plan" -> Command.APPROVE
        "cancel goal plan" -> Command.CANCEL
        "goal plan status" -> Command.STATUS
        "resume goal plan" -> Command.RESUME
        "the goal worked", "the goal step worked" -> Command.WORKED
        "the goal failed", "the goal step failed" -> Command.FAILED
        else -> null
    }

    /** A correction changes only a pending review, never an approved or running execution. */
    fun edit(text: String): Edit? {
        val value = clean(text).replace(Regex("^actually(?:,|:)?\\s+"), "")
        val volume = Regex("^change (?:the )?goal volume to(?: (.*))?$").matchEntire(value)
        if (volume != null) {
            val number = Regex("^(.+?)(?: percent|%)$").matchEntire(volume.groupValues[1])?.groupValues?.get(1)
            return Edit(Field.VOLUME, number?.takeIf { text.length <= 200 }?.let(SpokenTimerDuration::number)?.takeIf { it in 0..100 })
        }
        val duration = Regex("^(?:make that|change (?:the )?goal duration to)(?: (.*))?$").matchEntire(value) ?: return null
        return Edit(Field.DURATION, duration.groupValues[1].takeIf { text.length <= 200 }?.let(SpokenTimerDuration::seconds))
    }

    fun revise(plan: Plan, edit: Edit): Plan? {
        if (!valid(plan) || edit.value == null) return null
        val actions = plan.actions.toMutableList()
        when (edit.field) {
            Field.DURATION -> {
                val index = actions.indexOfFirst { RoutineSteps.timerAction(it) != null }
                if (index < 0 || edit.value !in 1..86400) return null
                actions[index] = "timer:${edit.value}"
            }
            Field.VOLUME -> {
                val index = actions.indexOfFirst { RoutineSteps.volumePercent(it) != null }
                if (index < 0 || edit.value !in 0..100) return null
                actions[index] = "volume:${edit.value}"
            }
        }
        return Plan("goal plan ${UUID.randomUUID()}", plan.kind, actions).takeIf(::valid)
    }

    fun parse(text: String): Request? {
        ComposableGoalPlanning.parse(text)?.let { return Request(Kind.COMPOSED, 0, composition = it) }
        ComposableGoalPlanning.drive(text)?.let { return Request(Kind.DRIVE, 50, composition = it) }
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
        if (request.kind in setOf(Kind.COMPOSED, Kind.DRIVE)) {
            val actions = ComposableGoalPlanning.bind(request.composition ?: return null, preferred, resolveApp, canLaunch) ?: return null
            return Plan("goal plan ${UUID.randomUUID()}", request.kind, actions).takeIf(::valid)
        }
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

    fun valid(plan: Plan): Boolean = isKey(plan.key) && if (plan.kind == Kind.COMPOSED) ComposableGoalPlanning.validActions(plan.actions)
        else if (plan.kind == Kind.DRIVE) ComposableGoalPlanning.validActions(plan.actions) && plan.actions.size == 3 &&
            RoutineSteps.volumePercent(plan.actions[0]) != null && plan.actions.drop(1).all { RoutineSteps.appAction(it) != null }
        else plan.actions.size == 2 && RoutineSteps.isValid(plan.actions) && RoutineSteps.volumePercent(plan.actions[0]) != null &&
            (if (plan.kind == Kind.FOCUS) RoutineSteps.timerAction(plan.actions[1]) != null else RoutineSteps.appAction(plan.actions[1]) != null)

    fun review(plan: Plan) = "Review ${plan.kind.title.lowercase(Locale.ROOT)}: " +
        plan.actions.joinToString(" then ", transform = RoutineProposal::describe) +
        ". These are proposed steps; nothing has run. Say 'approve goal plan' to request them or 'cancel goal plan' to discard." +
        when (plan.kind) {
            Kind.MUSIC -> " After the app opens, select music or press Play there."
            Kind.NAVIGATION -> " After Maps opens, choose your destination to start navigation."
            Kind.FOCUS -> ""
            Kind.DRIVE -> " Choose music in its app, return to Jarvis and report 'the goal step worked', then 'resume goal plan' to request Maps. Set your destination in Maps. No playback or navigation completion is verified."
            Kind.COMPOSED -> " Each app or timer request pauses for your checked result: 'the goal step worked' or 'the goal step failed'. Use 'resume goal plan' to continue pending steps; no uncertain step repeats automatically."
        }

    fun isManagementInput(text: String) = command(text) != null || parse(text) != null ||
        Regex("^(?:review|approve|cancel|resume) goal plan\\b|^goal plan status\\b|^the goal(?: step)? (?:worked|failed)\\b").containsMatchIn(clean(text))

    /** These replies can redisplay a pending review, never authorize it or another offer. */
    fun isReviewReply(text: String) = ConfirmationWords.isConfirmation(text) || clean(text) in setOf("please", "go ahead", "please do")

    private fun clean(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
}
