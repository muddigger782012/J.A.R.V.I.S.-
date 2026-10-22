package com.assistant.core.services

/** Bounded local requests; every planned label stays data, even if it resembles a host command. */
object ScreenTaskCommands {
    enum class Kind { START, OUTCOME, REVIEW, APPROVE, CONFIRM_STEP, CONTINUE, CANCEL, STATUS, CHOOSE, CORRECT, EXPLICIT, INVALID }
    data class Request(val kind: Kind, val steps: List<ScreenControls.Command> = emptyList(),
                       val text: String = "", val number: Int = 0)
    private val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
    fun stepWord(number: Int) = words.getOrNull(number - 1) ?: number.toString()
    fun number(value: String) = value.toIntOrNull() ?: words.indexOf(value).let { if (it < 0) 0 else it + 1 }
    fun contextual(text: String): Request? {
        val value = ScreenSemantics.clean(text).trimEnd('.', '?', '!')
        return when (value) {
            "stop", "cancel", "stop task", "cancel task" -> Request(Kind.CANCEL)
            "continue", "carry on", "check again" -> Request(Kind.CONTINUE)
            "what's next", "what is next", "where were we", "what are you doing" -> Request(Kind.STATUS)
            "yes", "yes please", "do it", "go ahead" -> Request(Kind.EXPLICIT)
            else -> Regex("(?i)^actually (.{1,100})$").matchEntire(text.trim())?.let { Request(Kind.CORRECT, text = it.groupValues[1]) }
        }
    }
    fun parse(text: String, active: Boolean = false): Request? {
        val raw = text.trim().trimEnd('.', '?', '!').trim()
        val value = ScreenSemantics.clean(raw)
        val exact = mapOf("screen task help" to Kind.REVIEW, "review screen task" to Kind.REVIEW,
            "approve screen task" to Kind.APPROVE, "continue screen task" to Kind.CONTINUE,
            "cancel screen task" to Kind.CANCEL, "stop screen task" to Kind.CANCEL,
            "screen task status" to Kind.STATUS)
        exact[value]?.let { return Request(it) }
        if (active) contextual(raw)?.let { return it }
        Regex("^confirm screen step (\\w+)$").matchEntire(value)?.let {
            val n = number(it.groupValues[1]); return Request(if (n in 1..4) Kind.CONFIRM_STEP else Kind.INVALID, number = n)
        }
        Regex("^use task control (\\w+)$").matchEntire(value)?.let {
            val n = number(it.groupValues[1]); return Request(if (n in 1..20) Kind.CHOOSE else Kind.INVALID, number = n)
        }
        Regex("(?i)^screen task outcome (.{1,120})$").matchEntire(raw)?.let { return Request(Kind.OUTCOME, text = it.groupValues[1]) }
        Regex("(?i)^change screen step to (.{1,100})$").matchEntire(raw)?.let { return Request(Kind.CORRECT, text = it.groupValues[1]) }
        val start = value.startsWith("screen task ") || value.startsWith("screen task:") ||
            value.startsWith("help me ") && (value.contains(" and verify ") || value.contains(" and check for "))
        if (!start) return if (Regex("^(?:confirm screen step|use task control|screen task outcome|change screen step|approve screen task|continue screen task|cancel screen task)\\b").containsMatchIn(value)) Request(Kind.INVALID) else null
        if (raw.length > 380 || raw.any { it in ";\n\r" }) return Request(Kind.INVALID)
        val body = raw.replace(Regex("(?i)^(?:screen task:?[ ]*|help me[ ]+)"), "")
        val parts = body.split(Regex("(?i) and (?:verify|check for|look for) "), limit = 2)
        val labels = parts[0].split(Regex("(?i) then "))
        if (labels.size !in 1..4) return Request(Kind.INVALID)
        val steps = labels.map { label ->
            val target = label.trim().replace(Regex("(?i)^(?:tap|press|click|open|choose|select) +"), "")
            ScreenControls.parse("tap $target")?.takeIf { it.kind == ScreenControls.Kind.TAP_LABEL }
                ?: return Request(Kind.INVALID)
        }
        val expected = parts.getOrNull(1)?.trim().orEmpty()
        if (expected.length > 120 || parts.size == 2 && expected.isBlank()) return Request(Kind.INVALID)
        return Request(Kind.START, steps, expected)
    }
    fun help() = "For a supervised screen task, say 'help me open Menu then Downloads and verify Downloads page'. I can review up to four taps within one unlocked app, ask you to confirm each numbered step, and check the expected visible text. No typing or automatic purchases, messages, permissions or other hidden actions."
}

/** A task never authorizes an unseen target. Each tap still uses the existing fresh-screen review guard. */
class ScreenTasks(private val platform: ScreenControls.Platform, private val now: () -> Long = System::currentTimeMillis) {
    private class Boundary(val guidance: String) : IllegalStateException()
    enum class Phase { NONE, OUTCOME, REVIEW, TARGET, CONFIRM, OBSERVE, PAUSED, COMPLETE, UNCERTAIN }
    private val taps = ScreenControls(platform, now)
    private var steps = emptyList<ScreenControls.Command>()
    private var expected = ""
    private var created = 0L
    private var index = 0
    private var app = ""
    private var beforeKey: String? = null
    private var choices = emptyList<ScreenControls.Control>()
    private var choiceKey = ""
    private var choiceCreated = 0L
    private var approved = false
    @Volatile var phase = Phase.NONE; private set
    @Volatile private var focused = false
    @Volatile var taskKey: String? = null; private set
    val active get() = phase !in setOf(Phase.NONE, Phase.COMPLETE, Phase.UNCERTAIN) && now() - created in 0..300_000
    val contextual get() = active && focused
    val pendingToken get() = if (active && phase == Phase.CONFIRM) taps.pendingToken else null
    val awaitingInput get() = contextual && phase in setOf(Phase.OUTCOME, Phase.REVIEW, Phase.TARGET, Phase.CONFIRM, Phase.OBSERVE, Phase.PAUSED)
    val currentStep get() = index + 1
    @Synchronized fun discard() {
        taps.discard(); steps = emptyList(); expected = ""; app = ""; beforeKey = null; choices = emptyList()
        index = 0; choiceKey = ""; choiceCreated = 0L; approved = false; focused = false; taskKey = null; phase = Phase.NONE
    }
    @Synchronized fun pause() {
        if (expire()) return
        focused = false
        taps.discard(); choices = emptyList()
        if (phase in setOf(Phase.CONFIRM, Phase.TARGET)) phase = Phase.PAUSED
    }
    private fun expire(): Boolean {
        if (phase != Phase.NONE && now() - created !in 0..300_000) { discard(); return true }
        return false
    }
    @Synchronized fun reply(request: ScreenTaskCommands.Request): String {
        if (expire() && request.kind != ScreenTaskCommands.Kind.START) return "The screen task expired. Review a new task; no step was resumed."
        focused = true
        return try { when (request.kind) {
            ScreenTaskCommands.Kind.START -> {
                discard(); steps = request.steps; expected = request.text; created = now(); focused = true
                taskKey = java.util.UUID.randomUUID().toString()
                if (steps.isEmpty()) return ScreenTaskCommands.help()
                phase = if (expected.isBlank()) Phase.OUTCOME else Phase.REVIEW
                if (phase == Phase.OUTCOME) "What exact visible text should I check after the final tap? Say 'screen task outcome' followed by that text. Nothing has run."
                else review()
            }
            ScreenTaskCommands.Kind.OUTCOME -> {
                if (phase != Phase.OUTCOME) return "No screen task is waiting for an outcome. " + ScreenTaskCommands.help()
                expected = request.text; phase = Phase.REVIEW; review()
            }
            ScreenTaskCommands.Kind.REVIEW -> review()
            ScreenTaskCommands.Kind.STATUS -> status()
            ScreenTaskCommands.Kind.CANCEL -> { discard(); "Cancelled the screen task. No later steps will run; already submitted Android actions cannot be undone." }
            ScreenTaskCommands.Kind.EXPLICIT -> "Use 'approve screen task' to approve the plan, then its exact numbered confirmation for each reviewed target. A bare yes cannot run a screen action."
            ScreenTaskCommands.Kind.APPROVE -> {
                if (phase !in setOf(Phase.REVIEW, Phase.PAUSED, Phase.TARGET)) return status()
                if (expected.isBlank()) return "Specify 'screen task outcome' before approving. Nothing has run."
                approved = true
                prepare(snapshot())
            }
            ScreenTaskCommands.Kind.CONTINUE -> when {
                !approved -> review()
                phase == Phase.OBSERVE -> observe()
                phase in setOf(Phase.PAUSED, Phase.TARGET, Phase.CONFIRM) -> prepare(snapshot())
                else -> status()
            }
            ScreenTaskCommands.Kind.CONFIRM_STEP -> confirm(request.number)
            ScreenTaskCommands.Kind.CHOOSE -> choose(request.number)
            ScreenTaskCommands.Kind.CORRECT -> {
                if (phase !in setOf(Phase.REVIEW, Phase.TARGET, Phase.CONFIRM, Phase.PAUSED) || index !in steps.indices)
                    return "Only an unattempted step can be corrected. Check the screen before starting another task."
                val replacement = ScreenControls.parse("tap ${request.text}")?.takeIf { it.kind == ScreenControls.Kind.TAP_LABEL }
                    ?: return "Give one visible control label for the current step. Nothing was changed."
                taps.discard(); choices = emptyList(); approved = false
                steps = steps.toMutableList().also { it[index] = replacement }; phase = Phase.REVIEW; review()
            }
            ScreenTaskCommands.Kind.INVALID -> { pause(); "Use an exact screen task control. " + ScreenTaskCommands.help() }
        } } catch (failure: Exception) {
            taps.discard(); choices = emptyList()
            if (failure is Boundary) {
                if (phase != Phase.OBSERVE) phase = if (approved) Phase.PAUSED else Phase.REVIEW
                failure.guidance
            } else if (phase == Phase.OBSERVE) "The observed result could not be read yet. Check the app, then say 'continue screen task' to inspect again. No tap will be repeated."
            else { phase = if (approved) Phase.PAUSED else Phase.REVIEW; "The current app or Accessibility access could not be verified. Check it, then review or continue the task; no automatic tap retry was made." }
        }
    }
    private fun snapshot(): ScreenControls.Snapshot {
        if (!platform.connected()) throw Boundary(ScreenControls.setup() + " Your task is paused; return to the intended app and continue after access is available.")
        val value = platform.snapshot()
        if (value.blocked) throw Boundary("This screen is protected or is not a supported app window. Use Android's own controls for that part, then return to an unlocked app and continue. No additional tap was attempted.")
        if (app.isBlank()) app = value.packageName
        if (value.packageName != app) throw Boundary("The active app changed. Return to the app reviewed for this task and continue, or cancel and review a new task. No tap was attempted in the other app.")
        return value
    }
    private fun candidates(snapshot: ScreenControls.Snapshot): List<ScreenControls.Control> {
        val request = steps[index]
        val exact = snapshot.controls.filter { it.clickable && it.enabled && ScreenSemantics.matches(it, request) }
        if (exact.isNotEmpty()) return exact
        val label = ScreenSemantics.clean(request.label).removePrefix("the ")
        val aliases = when (label) {
            "menu" -> setOf("menu", "more options", "navigation menu", "open navigation drawer", "show navigation menu", "more")
            else -> setOf(label)
        }
        return snapshot.controls.filter { it.clickable && it.enabled && aliases.any { alias ->
            ScreenSemantics.matches(it, request.copy(label = alias))
        } }
    }
    private fun prepare(value: ScreenControls.Snapshot): String {
        taps.discard(); choices = emptyList(); beforeKey = null
        check(index in steps.indices)
        val matches = candidates(value)
        if (matches.size == 1) return offer(value, matches.single())
        phase = Phase.TARGET
        if (matches.isEmpty()) return "Step ${index + 1}: '${steps[index].label}' is not an available clickable control in ${value.appLabel}. Show screen controls, correct the step with 'change screen step to' and a visible label, or return to the intended app and continue. Nothing was tapped."
        choices = matches.take(20); choiceKey = value.key; choiceCreated = now()
        return "Choose the intended task control; no duplicate is selected automatically:\n" + choices.mapIndexed { i, c ->
            "${i + 1}. ${c.label} — ${ScreenSemantics.describe(c)}"
        }.joinToString("\n") + "\nSay 'use task control two', for example. This only reviews the target."
    }
    private fun offer(snapshot: ScreenControls.Snapshot, control: ScreenControls.Control): String {
        taps.review(snapshot, control); phase = Phase.CONFIRM; choices = emptyList()
        return "Step ${index + 1} of ${steps.size}: tap '${control.label}' in ${snapshot.appLabel} (${ScreenSemantics.describe(control)}). Say 'confirm screen step ${ScreenTaskCommands.stepWord(index + 1)}', or 'cancel screen task'. Nothing was tapped."
    }
    private fun choose(number: Int): String {
        if (phase != Phase.TARGET || now() - choiceCreated !in 0..120_000) return "No current task control choice is available. Say 'continue screen task' to inspect again."
        val target = choices.getOrNull(number - 1) ?: return "Choose one of the current task control numbers."
        val fresh = snapshot()
        if (fresh.key != choiceKey || target !in fresh.controls) { choices = emptyList(); return "The screen changed. Say 'continue screen task' for fresh choices; nothing was tapped." }
        return offer(fresh, target)
    }
    private fun confirm(number: Int): String {
        if (phase != Phase.CONFIRM || number != index + 1) return status() + " No tap was requested by this confirmation."
        // The task phase changes before dispatch, and the underlying review is consumed before reading Android.
        phase = Phase.UNCERTAIN
        val confirmation = taps.confirmReviewedTap()
        choices = emptyList()
        return when (confirmation.outcome) {
            ScreenControls.Result.ACCEPTED -> {
                beforeKey = confirmation.beforeKey; phase = Phase.OBSERVE
                "Android accepted step ${index + 1}. Say 'continue screen task' to inspect its result before any next step. Acceptance alone does not verify the app's outcome."
            }
            ScreenControls.Result.UNKNOWN -> "The step outcome is uncertain. Check the app before starting a new task; this task will not retry it."
            else -> { phase = Phase.PAUSED; confirmation.text + " Say 'continue screen task' for a fresh review of this uncompleted step." }
        }
    }
    private fun observe(): String {
        val value = snapshot()
        if (value.key == beforeKey) return "The screen has not changed since the request. Check it, then continue the task to inspect again. No tap will be repeated."
        if (index + 1 < steps.size) {
            val previous = index
            index++
            if (candidates(value).isEmpty()) {
                index = previous
                return "The screen changed, but the next expected control is not visible. Return to the intended page and continue, or cancel the task. No step will be repeated."
            }
            phase = Phase.TARGET
            return "The screen changed and the next control is visible. " + prepare(value)
        }
        if ((value.text + value.headings).none { ScreenSemantics.clean(it) == ScreenSemantics.clean(expected) })
            return "The screen changed, but '$expected' is not visible yet. Check the app, then continue to inspect again or cancel. The last tap will not be repeated."
        phase = Phase.COMPLETE; taps.discard(); beforeKey = null
        return "Verified the requested visible screen text: '$expected'. The screen task is complete; no further screen actions are queued."
    }
    private fun review(): String {
        if (steps.isEmpty()) return ScreenTaskCommands.help()
        if (phase == Phase.OUTCOME) return "Specify 'screen task outcome' and the exact visible text to verify. Nothing has run."
        if (phase in setOf(Phase.COMPLETE, Phase.UNCERTAIN, Phase.OBSERVE)) return status()
        return "Review screen task in one unlocked app:\n" + steps.mapIndexed { i, step ->
            "${i + 1}. Tap '${step.label}'" + (if (step.contextLabel.isBlank()) "" else " under '${step.contextLabel}'") +
                if (i < index) " — already observed" else ""
        }.joinToString("\n") + "\nCheck for '$expected' after the final tap. " +
            (if (approved) "Say 'continue screen task' to refresh the current target. " else "Say 'approve screen task'. ") +
            "Each target still needs its own numbered confirmation; plan approval alone does not tap anything."
    }
    private fun status(): String = when (phase) {
        Phase.NONE -> "No screen task is active. " + ScreenTaskCommands.help()
        Phase.OUTCOME, Phase.REVIEW -> "A screen task is awaiting an outcome or plan approval. Say 'review screen task'."
        Phase.CONFIRM -> "Step ${index + 1} awaits 'confirm screen step ${ScreenTaskCommands.stepWord(index + 1)}'. Say 'continue screen task' to refresh its target first."
        Phase.TARGET, Phase.PAUSED -> "Screen task paused at step ${index + 1}. Say 'continue screen task' to inspect a fresh target, or 'cancel screen task'."
        Phase.OBSERVE -> "Step ${index + 1} was requested; its result still needs inspection. Say 'continue screen task'. No later tap is approved yet."
        Phase.COMPLETE -> "Verified the requested visible screen text. No further screen actions are queued."
        Phase.UNCERTAIN -> "The previous step's outcome is uncertain. Check the app before starting a new task; no automatic retry is allowed."
    }
}

/** One private, cross-process interaction domain for direct controls and supervised tasks. */
class ScreenSession(platform: ScreenControls.Platform, now: () -> Long = System::currentTimeMillis) {
    private val direct = ScreenControls(platform, now)
    private val tasks = ScreenTasks(platform, now)
    val active get() = tasks.active
    val contextual get() = tasks.contextual
    val awaitingInput get() = tasks.awaitingInput
    val taskKey get() = tasks.taskKey
    val observing get() = tasks.phase == ScreenTasks.Phase.OBSERVE
    val confirming get() = tasks.phase == ScreenTasks.Phase.CONFIRM
    val currentStep get() = tasks.currentStep
    val pendingToken: String? get() = if (tasks.active) tasks.pendingToken?.let { "task:$it" }
        else direct.pendingToken?.let { "direct:$it" }
    fun discard() { direct.discard(); tasks.discard() }
    fun pause() { direct.discard(); tasks.pause() }
    fun replyApproved(text: String, expectedToken: String): String {
        val current = pendingToken ?: return "No current screen tap review is available. Request the target again, or say 'continue screen task' to refresh a task. Nothing was tapped."
        if (expectedToken.isBlank() || expectedToken != current)
            return "The screen review changed after this confirmation was prepared. Review the current target before confirming again; nothing was tapped."
        return reply(text)
    }
    fun replyInTask(text: String, expectedTask: String): String {
        if (expectedTask.isBlank() || expectedTask != tasks.taskKey)
            return "The screen task changed before its result could be checked. Review the current task; no tap was repeated."
        return reply(text)
    }
    fun reply(text: String): String {
        ScreenTaskCommands.parse(text, tasks.contextual)?.let { direct.discard(); return tasks.reply(it) }
        val command = ScreenControls.parse(text) ?: return "No supported screen request was recognized."
        if (command.kind == ScreenControls.Kind.CONFIRM && tasks.active)
            return "The active task needs its exact numbered step confirmation. Say 'screen task status' to review it; a generic screen confirmation cannot run a task step."
        if (command.kind !in setOf(ScreenControls.Kind.READ, ScreenControls.Kind.LIST, ScreenControls.Kind.APP, ScreenControls.Kind.STATUS, ScreenControls.Kind.HELP)) tasks.discard()
        return direct.reply(command)
    }
    companion object {
        fun recognizes(text: String) = ScreenTaskCommands.parse(text) != null || ScreenControls.parse(text) != null
    }
}
