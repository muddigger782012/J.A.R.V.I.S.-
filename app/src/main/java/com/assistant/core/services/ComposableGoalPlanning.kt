package com.assistant.core.services

import java.util.Locale

/** Host-only task drafts. Every clause must bind locally before a review can be saved. */
object ComposableGoalPlanning {
    sealed class Step {
        data class Volume(val percent: Int) : Step()
        data class Timer(val seconds: Int) : Step()
        data class App(val label: String) : Step()
        data class Preferred(val slot: String) : Step()
    }
    data class Draft(val steps: List<Step>, val valid: Boolean = true)
    private fun clean(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim()
        .replace(Regex("\\s+"), " ").removePrefix("please ").removePrefix("can you ").removePrefix("could you ")

    fun parse(text: String): Draft? {
        val value = clean(text)
        if (!Regex("^(?:plan|prepare) (?:a )?task\\b").containsMatchIn(value)) return null
        val body = Regex("^(?:plan|prepare) (?:a )?task to (.+)$").matchEntire(value)?.groupValues?.get(1)
        if (text.length > 600 || text.any { it.isISOControl() && !it.isWhitespace() } || body == null) return Draft(emptyList(), false)
        val clauses = body.split(Regex("\\s+(?:and )?then\\s+"))
        if (clauses.size !in 1..3) return Draft(emptyList(), false)
        val steps = clauses.map { clause ->
            val volume = Regex("^(?:set|lower|raise) (?:the |my )?(?:media )?volume to (.+?)(?: percent|%)$").matchEntire(clause)
            if (volume != null) Step.Volume(SpokenTimerDuration.number(volume.groupValues[1])?.takeIf { it in 0..100 }
                ?: return Draft(emptyList(), false))
            else SpokenTimerDuration.command(clause)?.let { Step.Timer(it) } ?: run {
                val label = Regex("^(?:open|launch) (.{1,100})$").matchEntire(clause)?.groupValues?.get(1)
                    ?: return Draft(emptyList(), false)
                val slot = Regex("^(?:my|the preferred|my preferred) (browser|maps|music) app$").matchEntire(label)?.groupValues?.get(1)
                if (slot == null) Step.App(label) else Step.Preferred(slot)
            }
        }
        return Draft(steps)
    }

    fun drive(text: String): Draft? {
        val value = clean(text)
        if (!Regex("^(?:prepare my drive|help me get ready to drive)\\b").containsMatchIn(value)) return null
        val match = Regex("^(?:prepare my drive|help me get ready to drive)(?: with music and maps)?(?: at (.+?)(?: percent|%) volume)?$").matchEntire(value)
            ?: return Draft(emptyList(), false)
        val percent = match.groupValues[1].takeIf { it.isNotBlank() }?.let(SpokenTimerDuration::number) ?: if (match.groupValues[1].isBlank()) 50 else -1
        if (text.length > 600 || percent !in 0..100) return Draft(emptyList(), false)
        return Draft(listOf(Step.Volume(percent), Step.Preferred("music"), Step.Preferred("maps")))
    }

    fun bind(draft: Draft, preferred: (String) -> LearnableDeviceAction?, resolveApp: (String) -> Pair<String, String>?,
             canLaunch: (String) -> Boolean): List<String>? {
        if (!draft.valid || draft.steps.size !in 1..3) return null
        val actions = draft.steps.map { step -> when (step) {
            is Step.Volume -> "volume:${step.percent}"
            is Step.Timer -> "timer:${step.seconds}"
            is Step.App, is Step.Preferred -> {
                val target = if (step is Step.Preferred) preferred(step.slot) else {
                    val label = (step as Step.App).label
                    val resolved = resolveApp(label) ?: return null
                    if (!resolved.second.equals(label, ignoreCase = true)) return null
                    LearnableDeviceAction("open_app", mapOf("package" to resolved.first, "label" to resolved.second))
                }
                if (target == null || !target.isValid() || target.capabilityId != "open_app" || !canLaunch(target.parameters.getValue("package"))) return null
                RoutineSteps.appStep(target) ?: return null
            }
        } }
        return actions.takeIf(::validActions)
    }

    /** Separate from saved/AI routines: no updater, relative volume, unbound preference or code. */
    fun validActions(actions: List<String>): Boolean {
        if (actions.size !in 1..3 || actions.distinct().size != actions.size) return false
        if (actions.any { RoutineSteps.volumePercent(it) == null && RoutineSteps.appAction(it) == null && RoutineSteps.timerAction(it) == null }) return false
        val volumes = actions.indices.filter { RoutineSteps.volumePercent(actions[it]) != null }
        if (volumes.isNotEmpty() && volumes != listOf(0)) return false
        val timers = actions.indices.filter { RoutineSteps.timerAction(actions[it]) != null }
        if (timers.isNotEmpty() && timers != listOf(actions.lastIndex)) return false
        val apps = actions.mapNotNull(RoutineSteps::appAction).map { it.parameters.getValue("package") }
        return apps.distinct().size == apps.size
    }
}
