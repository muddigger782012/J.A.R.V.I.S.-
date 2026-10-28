package com.assistant.core.services

import android.content.Context
import com.assistant.core.BuildConfig
import com.assistant.core.workflow.RoutineWorkflowObservation
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Separately opt-in, categorical workflow observations. Never receives queries, recipients or errors. */
class WorkflowDiagnostics(private val file: PersonalStateFile, private val build: Int, private val buildName: String,
                          private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(PersonalStateFile(context, "workflow-diagnostics.json"), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
    init { require(build > 0 && buildName.length in 1..40) }

    enum class Family(val label: String, val module: String, val fixture: String) {
        CONTACT("contact calls", "ContactCallWorkflow and AndroidAssistantService",
            "Use fictional contacts; test missing permission, ambiguous numbers, changed recipients and interrupted dispatch. Only current explicit approval may request one call or dialer; never claim connection."),
        RESEARCH("research", "ResearchBriefs and SearchCoordinator",
            "Use synthetic sources; fail retrieval, offer an available fallback, cancel a delayed response and reconstruct offline. Preserve an older brief and reject late results without automatic retry."),
        REMINDER("reminders", "PersonalReminders and PersonalReminderRuntime",
            "Use a disposable alarm and notification adapter; test blocked notifications, failed registration, restart and uncertain delivery. Keep stable identities and never repeat an uncertain notification automatically."),
        TASK("tasks", "RoutineExecutionCoordinator, ApprovedRoutineExecutor and GoalPlanStore",
            "Use synthetic goals and saved routines with counting adapters; test exact approval, failed dispatch, owner feedback, interrupted recovery and cancellation. Resume only explicitly authorized pending steps; never repeat an uncertain effect or treat owner confirmation as device verification.")
    }
    enum class Code(val family: Family, val label: String, val review: String? = null) {
        CONTACTS_UNAVAILABLE(Family.CONTACT, "Contact access unavailable", "Check Contacts permission and lookup failures; offer a complete phone number or Android's contact tools."),
        NO_CONTACT(Family.CONTACT, "No callable match", "Check exact and whole-name matching with synthetic contacts; ask for a full name or complete number without picking an arbitrary recipient."),
        RECIPIENT_CHANGED(Family.CONTACT, "Recipient revalidation failed", "Check changed contact rows and permission revocation; require a fresh recipient review."),
        CALL_REJECTED(Family.CONTACT, "Call or dialer request rejected", "Check available Phone handlers and permission fallback; require a fresh review before another request."),
        CALL_UNKNOWN(Family.CONTACT, "Call request outcome uncertain", "Reproduce dispatch interruption and checkpoint failures; direct the owner to the Phone app before another request."),
        CALL_REQUESTED(Family.CONTACT, "Call or dialer requested; connection unverified"),
        NO_SOURCES(Family.RESEARCH, "No usable sources returned", "Check connectivity, search configuration, provider fallback and source filtering with synthetic responses; retain an older brief and offer explicit retry."),
        BRIEF_SAVED(Family.RESEARCH, "Source excerpt brief saved; claims unverified"),
        ALARM_UNCONFIRMED(Family.REMINDER, "Alarm registration unconfirmed", "Check alarm adapter exceptions and registration checkpoints; preserve the reminder identity for an explicit retry."),
        ALARM_REGISTERED(Family.REMINDER, "Alarm registered; delivery unverified"),
        NOTIFICATIONS_BLOCKED(Family.REMINDER, "Notifications unavailable", "Check permission and channel settings; offer notification settings and an explicit retry after access is restored."),
        DELIVERY_UNKNOWN(Family.REMINDER, "Notification delivery uncertain", "Reproduce interrupted posting and missing readback; preserve uncertainty and prevent automatic repeat."),
        NOTIFICATION_POSTED(Family.REMINDER, "Notification posted; reading unverified"),
        MISSED(Family.REMINDER, "Reminder more than a day overdue", "Check restore timing and power restrictions; offer a new current reminder rather than posting a stale one."),
        TASK_BLOCKED(Family.TASK, "Task stopped before its next dispatch", "Reproduce changed approvals, unavailable app targets and checkpoint write failures; preserve existing outcomes and require a current review before further work."),
        TASK_NOT_APPLIED(Family.TASK, "Task step confirmed not applied", "Test a dispatcher that positively reports no effect; investigate the available method and allow only explicit bounded retry of that step."),
        TASK_REQUESTED(Family.TASK, "Task step requested; outcome unverified"),
        TASK_OUTCOME_UNKNOWN(Family.TASK, "Task step effect uncertain", "Reproduce dispatch interruption and missing outcome evidence; inspect the actual result and reject automatic or blind retry."),
        TASK_OWNER_FAILED(Family.TASK, "Owner reported a task step failed", "Reproduce the requested step with synthetic targets and compare owner feedback with dispatch evidence; failure after a request is not proof that no effect occurred."),
        TASK_OWNER_CONFIRMED(Family.TASK, "Owner confirmed a task step; whole objective unverified"),
        TASK_DEVICE_VERIFIED(Family.TASK, "All task steps device verified; broader objective unverified"),
        TASK_RECOVERY_PAUSED(Family.TASK, "Interrupted task paused between steps", "Reproduce restart between verified steps; keep completed actions intact and require explicit resume for pending steps. A safe recovery pause is not itself a code defect.")
    }
    data class Event(val attempt: String, val code: Code, val build: Int, val buildName: String, val time: Long)
    data class Proposal(val id: String, val code: Code, val evidence: List<Event>, val occurrences: Int,
                        val preparedAt: Long, val dismissed: Boolean = false)
    data class Snapshot(val enabled: Boolean = false, val events: List<Event> = emptyList(), val proposals: List<Proposal> = emptyList(),
                        val tasksEnabled: Boolean = false)
    enum class Action { ENABLE, DISABLE, CLEAR, REVIEW, IMPROVEMENTS, PREPARE, REVIEW_PROPOSAL, DISMISS, CLEAR_PROPOSALS, SHARE, INVALID }
    data class Command(val action: Action, val family: Family? = null, val number: Int? = null)

    fun snapshot(): Snapshot = file.access { decode(read()) }
    /** Best effort: diagnostics failure must never change a workflow's action or returned outcome. */
    fun observe(attempt: String, code: Code) { runCatching {
        require(validId(attempt))
        file.access {
            val saved = decode(read())
            if (!saved.enabled || (code.family == Family.TASK && !saved.tasksEnabled) ||
                saved.events.any { it.attempt == attempt && it.code == code }) return@access
            val event = Event(attempt, code, build, buildName, now())
            require(event.time >= 0)
            check(write(encode(saved.copy(events = (saved.events + event).takeLast(MAX_EVENTS)))))
        }
    } }

    /** Receive only the workflow's random identity and a typed checkpoint category. */
    fun observeTask(attempt: String, observation: RoutineWorkflowObservation) = observe(attempt, when (observation) {
        RoutineWorkflowObservation.BLOCKED -> Code.TASK_BLOCKED
        RoutineWorkflowObservation.NOT_APPLIED -> Code.TASK_NOT_APPLIED
        RoutineWorkflowObservation.REQUESTED -> Code.TASK_REQUESTED
        RoutineWorkflowObservation.OUTCOME_UNKNOWN -> Code.TASK_OUTCOME_UNKNOWN
        RoutineWorkflowObservation.OWNER_FAILED -> Code.TASK_OWNER_FAILED
        RoutineWorkflowObservation.OWNER_CONFIRMED -> Code.TASK_OWNER_CONFIRMED
        RoutineWorkflowObservation.DEVICE_VERIFIED -> Code.TASK_DEVICE_VERIFIED
        RoutineWorkflowObservation.RECOVERY_PAUSED -> Code.TASK_RECOVERY_PAUSED
    })

    fun reply(command: Command): String = when (command.action) {
        Action.ENABLE, Action.DISABLE -> file.access {
            val enabled = command.action == Action.ENABLE
            check(write(encode(decode(read()).copy(enabled = enabled, tasksEnabled = enabled))))
            if (enabled) "Enabled local workflow diagnostics for future contact-call, research, reminder and multistep task checkpoints (goals and saved routines). Only categories, random attempt references, build and time are recorded; no names, phone numbers, reminder titles, task names, app targets, queries, source text or errors. Existing stable task history is not imported. Nothing is uploaded. Say 'review workflow diagnostics', 'review workflow improvements', 'disable workflow diagnostics' or 'clear workflow diagnostics'. Manual improvement evaluation is separate."
            else "Disabled workflow diagnostics. Existing observations and briefs remain available to review or clear; manual evaluation is separate."
        }
        Action.CLEAR -> file.access { check(write(null)); "Cleared workflow observations and developer briefs; workflow diagnostics are off. Saved reminders, contact review, research brief and manual evaluation are separate." }
        Action.REVIEW -> snapshot().let { saved ->
            "Workflow diagnostics: ${if (saved.enabled) "on" else "off"}; task checkpoints: ${if (saved.tasksEnabled) "on" else "off"}; ${saved.events.size} retained categorical checkpoints (newest $MAX_EVENTS maximum).\n" +
                saved.events.groupBy { it.build to it.code }.entries.joinToString("\n") { (_, rows) ->
                    "${rows.first().buildName}: ${rows.first().code.label}; ${rows.size} distinct workflow references."
                } + "\nObservations describe checkpoints, not completed calls, read notifications, verified facts, whole-objective success or measured reliability. Task-step owner feedback remains distinct from device verification. Repeated checkpoints for the same workflow and category count once. No private targets or provider errors are recorded." +
                if (saved.enabled && !saved.tasksEnabled) " Earlier consent covers calls, research and reminders only. Say 'enable workflow diagnostics' to explicitly include future goal and routine checkpoints." else ""
        }
        Action.IMPROVEMENTS -> snapshot().let { saved ->
            val groups = recurrent(saved)
            "Workflow improvement review: ${groups.size} recurrent category/build groups in the retained window.\n" +
                groups.joinToString("\n") { rows -> "${rows.first().code.family.label}: ${rows.first().code.label} on ${rows.first().buildName}; ${rows.size} distinct workflows. Review: ${rows.first().code.review}" } +
                "\nA recurrence is a reason to investigate, not a proven code defect. Permissions, configuration or unavailable results may explain it. Say 'prepare workflow improvement for research', 'contact calls', 'reminders' or 'tasks' to freeze current-build evidence into a local developer brief. Saved briefs: ${saved.proposals.size}.\n" +
                saved.proposals.mapIndexed { index, p -> "${index + 1}. ${p.code.label} on ${p.evidence.first().buildName}; ${p.occurrences} workflows; ${if (p.dismissed) "dismissed" else "prepared"}." }.joinToString("\n") +
                "\nUse 'review workflow improvement 1' with a listed number to inspect any retained brief, including earlier or dismissed evidence."
        }
        Action.PREPARE -> prepare(command.family!!)
        Action.REVIEW_PROPOSAL -> snapshot().let { saved ->
            val proposal = if (command.number != null) saved.proposals.getOrNull(command.number - 1)
                else saved.proposals.lastOrNull { it.code.family == command.family }
            proposal?.let(::render) ?: "No matching saved workflow improvement. Review workflow improvements for the current archive."
        }
        Action.DISMISS -> file.access {
            val saved = decode(read()); val last = saved.proposals.indexOfLast { it.code.family == command.family }
            if (last < 0) "No saved workflow improvement for ${command.family!!.label}."
            else { check(write(encode(saved.copy(proposals = saved.proposals.mapIndexed { i, p -> if (i == last) p.copy(dismissed = true) else p })))); "Dismissed the latest ${command.family!!.label} brief. Original evidence remains reviewable; no code change was approved." }
        }
        Action.CLEAR_PROPOSALS -> file.access { val saved = decode(read()); check(write(encode(saved.copy(proposals = emptyList())))); "Cleared workflow developer briefs; observations and the recording setting remain." }
        Action.SHARE -> "Use the personal assistant to request Android's share chooser for a saved workflow improvement. No submission is verified."
        Action.INVALID -> "Use an exact workflow diagnostic control: 'review workflow diagnostics', 'review workflow improvements' or 'prepare workflow improvement for research', 'contact calls', 'reminders' or 'tasks'. Nothing was changed or shared."
    }

    fun handoff(family: Family): String? = snapshot().proposals.lastOrNull { it.code.family == family }?.takeUnless { it.dismissed }?.let(::render)
    private fun recurrent(saved: Snapshot) = saved.events.filter { it.code.review != null }.groupBy { it.build to it.code }
        .values.filter { it.size >= 2 }.sortedByDescending { it.last().time }
    private fun prepare(family: Family): String = file.access {
        val saved = decode(read())
        val evidence = recurrent(saved).firstOrNull { it.first().build == build && it.first().code.family == family }
            ?: return@access "No repeated ${family.label} category is recorded for this build. Enable workflow diagnostics for future requests and review observations; older-build results do not establish a current defect."
        val selected = evidence.takeLast(MAX_LINKS)
        saved.proposals.lastOrNull { it.code == selected.first().code && it.evidence == selected && it.occurrences == evidence.size }?.let { return@access render(it) }
        if (saved.proposals.size >= MAX_PROPOSALS) return@access "Workflow briefs are full. Review them, then explicitly clear workflow improvements when no longer needed; existing evidence was retained."
        val proposal = Proposal(UUID.randomUUID().toString(), selected.first().code, selected, evidence.size, now())
        require(proposal.preparedAt >= 0)
        check(write(encode(saved.copy(proposals = saved.proposals + proposal))))
        render(proposal)
    }
    private fun render(proposal: Proposal): String = "Jarvis workflow developer brief — review required\n" +
        "Local reference: ${proposal.id}. Status: ${if (proposal.dismissed) "dismissed" else "prepared"}.\n" +
        "Observed: ${proposal.code.label} on ${proposal.evidence.first().buildName}; ${proposal.occurrences} distinct workflows in the retained window at preparation. Latest ${proposal.evidence.size} categorical evidence links frozen in this brief.\n" +
        proposal.evidence.joinToString("\n") { "Evidence: ${it.attempt}, ${it.code.name}, ${it.time}." } +
        "\nInvestigate: ${proposal.code.review}\nModules: ${proposal.code.family.module}.\nSynthetic regression: ${proposal.code.family.fixture}\n" +
        "Reproduce with synthetic data, verify a regression test fails before a focused fix, then run it after the change. Compare matched cases and inspect regressions. Use reviewed PRs, CI, signing and updater verification before publication.\n" +
        "This is categorical checkpoint evidence, not owner feedback, a verified root cause or a reliability score. No private target, transcript, provider response or credentials are included. No patch, test run, pull request, permission grant or deployment was performed. Sharing opens Android's chooser; it does not prove an external submission."

    private fun decode(raw: String?): Snapshot {
        if (raw == null) return Snapshot()
        val root = JSONObject(raw); val version = root.getInt("version"); require(version in 1..2)
        fun events(array: JSONArray, limit: Int): List<Event> {
            require(array.length() <= limit)
            return (0 until array.length()).map { i -> array.getJSONObject(i).let {
                Event(it.getString("attempt"), Code.valueOf(it.getString("code")), it.getInt("build"), it.getString("buildName"), it.getLong("time"))
            }.also { require(validId(it.attempt) && it.build > 0 && it.buildName.length in 1..40 && it.time >= 0) } }.also {
                require(it.distinctBy { e -> e.attempt to e.code }.size == it.size)
            }
        }
        val rows = events(root.getJSONArray("events"), MAX_EVENTS)
        val proposals = root.getJSONArray("proposals"); require(proposals.length() <= MAX_PROPOSALS)
        val briefs = (0 until proposals.length()).map { i -> proposals.getJSONObject(i).let {
            Proposal(it.getString("id"), Code.valueOf(it.getString("code")), events(it.getJSONArray("evidence"), MAX_LINKS),
                it.getInt("occurrences"), it.getLong("preparedAt"), it.getBoolean("dismissed"))
        }.also { p ->
            require(validId(p.id) && p.code.review != null && p.evidence.size in 2..MAX_LINKS && p.occurrences in p.evidence.size..MAX_EVENTS && p.preparedAt >= 0)
            require(p.evidence.all { it.code == p.code && it.build == p.evidence.first().build && it.buildName == p.evidence.first().buildName })
        } }
        require(briefs.distinctBy { it.id }.size == briefs.size)
        val enabled = root.getBoolean("enabled")
        val tasksEnabled = version >= 2 && root.getBoolean("tasksEnabled")
        require(!tasksEnabled || enabled)
        return Snapshot(enabled, rows, briefs, tasksEnabled)
    }
    private fun encode(saved: Snapshot): String {
        fun events(values: List<Event>) = JSONArray().also { array -> values.forEach {
            array.put(JSONObject().put("attempt", it.attempt).put("code", it.code.name).put("build", it.build).put("buildName", it.buildName).put("time", it.time))
        } }
        val proposals = JSONArray().also { array -> saved.proposals.forEach {
            array.put(JSONObject().put("id", it.id).put("code", it.code.name).put("evidence", events(it.evidence))
                .put("occurrences", it.occurrences).put("preparedAt", it.preparedAt).put("dismissed", it.dismissed))
        } }
        return JSONObject().put("version", 2).put("enabled", saved.enabled).put("tasksEnabled", saved.tasksEnabled)
            .put("events", events(saved.events)).put("proposals", proposals).toString()
    }
    companion object {
        const val MAX_EVENTS = 200
        const val MAX_PROPOSALS = 16
        const val MAX_LINKS = 20
        private fun validId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun command(text: String): Command? {
            val value = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
            val exact = when (value) {
                "enable workflow diagnostics" -> Action.ENABLE
                "disable workflow diagnostics" -> Action.DISABLE
                "clear workflow diagnostics" -> Action.CLEAR
                "review workflow diagnostics" -> Action.REVIEW
                "review workflow improvements" -> Action.IMPROVEMENTS
                "clear workflow improvements" -> Action.CLEAR_PROPOSALS
                else -> null
            }
            if (exact != null) return Command(exact)
            Regex("^review workflow improvement ([0-9]{1,2})$").matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..MAX_PROPOSALS }?.let { return Command(Action.REVIEW_PROPOSAL, number = it) }
            for ((prefix, action) in listOf("prepare" to Action.PREPARE, "review" to Action.REVIEW_PROPOSAL, "dismiss" to Action.DISMISS, "share" to Action.SHARE)) {
                val label = value.removePrefix("$prefix workflow improvement for ")
                if (label != value) Family.values().firstOrNull { it.label == label }?.let { return Command(action, it) }
            }
            return if (Regex("^(?:enable|disable|clear|review) workflow diagnostics\\b|^(?:prepare|review|dismiss|share|clear) workflow improvements?\\b").containsMatchIn(value)) Command(Action.INVALID) else null
        }
    }
}
