package com.assistant.core.services

/** Bounded stored step IDs. Timer parameters are data, never shell or executable code. */
object RoutineSteps {
    fun timerAction(step: String): LearnableDeviceAction? {
        val seconds = Regex("^timer:([1-9][0-9]{0,4})$").matchEntire(step)?.groupValues?.get(1) ?: return null
        return LearnableDeviceAction("timer", mapOf("seconds" to seconds)).takeIf { it.isValid() }
    }
    fun preferredSlot(step: String): String? = Regex("^preferred:(browser|maps|music)$")
        .matchEntire(step)?.groupValues?.get(1)

    fun preferredAction(step: String, memory: AssistantMemory, canLaunch: (String) -> Boolean): LearnableDeviceAction? {
        val slot = preferredSlot(step) ?: return null
        return memory.preferred(slot)?.takeIf { it.isValid() && it.capabilityId == "open_app" &&
            runCatching { canLaunch(it.parameters.getValue("package")) }.getOrDefault(false) }
    }

    fun isValid(steps: List<String>): Boolean {
        if (steps.size !in 1..3 || steps.distinct().size != steps.size) return false
        if (steps.any { it !in setOf("volume_up", "volume_down", "app_update") && timerAction(it) == null && preferredSlot(it) == null }) return false
        val targets = steps.filter { timerAction(it) != null || preferredSlot(it) != null }
        return targets.isEmpty() || (targets.size == 1 && steps.last() == targets.single() && "app_update" !in steps)
    }
}

/** Executes approved steps in order; a failed step stops the remaining actions. */
object RoutineExecution {
    enum class Status { VERIFIED, REQUESTED, AWAITING_OUTCOME, REQUESTED_WITHOUT_TRACKING, FAILED }
    data class Result(val steps: List<Pair<String, Status>>, val unavailablePreferred: Boolean = false) {
        val awaitingOutcome get() = steps.any { it.second == Status.AWAITING_OUTCOME }
        val missingOutcomeTracking get() = steps.any { it.second == Status.REQUESTED_WITHOUT_TRACKING }
    }
    /** Resolve once before side effects; preference changes mid-run cannot switch the target. */
    fun runWithPreferred(steps: List<String>, memory: AssistantMemory, canLaunch: (String) -> Boolean,
        execute: (String, LearnableDeviceAction?) -> Status): Result {
        if (!RoutineSteps.isValid(steps)) return Result(emptyList())
        val preferredStep = steps.firstOrNull { RoutineSteps.preferredSlot(it) != null }
        val target = preferredStep?.let { RoutineSteps.preferredAction(it, memory, canLaunch) }
        if (preferredStep != null && target == null) return Result(emptyList(), unavailablePreferred = true)
        return run(steps) { step -> execute(step, target.takeIf { step == preferredStep }) }
    }

    fun run(steps: List<String>, execute: (String) -> Status): Result {
        if (!RoutineSteps.isValid(steps)) return Result(emptyList())
        val results = mutableListOf<Pair<String, Status>>()
        for (step in steps) {
            val status = runCatching { execute(step) }.getOrDefault(Status.FAILED)
            results.add(step to status)
            if (status == Status.FAILED) break
        }
        return Result(results)
    }
}
