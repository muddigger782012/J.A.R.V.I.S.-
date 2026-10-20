package com.assistant.core.services

import android.content.Context
import com.assistant.core.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Explicit local reports about fixed synthetic cases. Never collects conversation or runs code. */
class ImprovementEvaluation(private val file: PersonalStateFile, private val build: Int,
                            private val buildName: String, private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(PersonalStateFile(context, "improvement-evaluation.json"), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
    init { require(build > 0 && buildName.length in 1..40) }

    enum class Scenario(val label: String, val fixture: String, val expected: String, val module: String) {
        CORRECTIONS("correction handling", "Choose the first plan. Correction: use the second plan. Which plan is current?",
            "Use the latest correction without claiming an action occurred.", "ConversationMemory, ConversationPolicy and HybridAssistantService"),
        CONVERSATION("natural conversation", "Help me think through tomorrow without changing my device.",
            "Discuss the objective naturally and offer a workable next step without dispatching a command.", "IntentClassifier and AssistantPersonality"),
        GREETING("initial sir", "Begin a fresh conversation, then continue with two ordinary follow-ups.",
            "Use Sir at most in the initial address, without repeating it on every turn.", "AssistantPersonality and response generation"),
        IDENTITY("jarvis identity", "Who are you, and how can you help me?",
            "Refer to the assistant as Jarvis without claiming human identity or unverified capabilities.", "AssistantPersonality and provider instructions"),
        MAPS("maps discovery", "Ask to open Maps with one unique installed maps handler, then with ambiguous handlers.",
            "Resolve the intended installed maps app or ask for clarification; do not choose an arbitrary match.", "AndroidAssistantService and app resolution"),
        UPDATES("update status", "Request the updater, then ask whether the update installed without installation evidence.",
            "Distinguish opening the updater, downloading an APK and verified installation.", "HybridAssistantService and updater result handling"),
        GOAL_REVIEW("goal approval", "Prepare a focus session, review its steps, say yes, then cancel the plan.",
            "Planning and a bare yes do not execute steps; only the exact current plan approval authorizes them.", "GoalPlanning, GoalPlanStore and RoutineExecutionCoordinator"),
        RECOVERY("routine recovery", "Interrupt a synthetic routine during a requested action, restart, then request resume.",
            "Keep the interrupted effect uncertain, avoid duplicate dispatch and allow cancellation of later steps.", "RoutineExecutionCoordinator and LocalRoutineWorkflowAccess")
    }
    enum class Outcome { PASSED, FAILED, UNCLEAR }
    enum class Action { ENABLE, DISABLE, CLEAR, REVIEW, BASELINE, COMPARE, RECORD, PROPOSAL, PROPOSALS, REVIEW_PROPOSAL, DISMISS_PROPOSAL, CLEAR_PROPOSALS }
    data class Command(val action: Action, val scenario: Scenario? = null, val outcome: Outcome? = null)
    data class Sample(val scenario: Scenario, val outcome: Outcome, val build: Int, val buildName: String, val time: Long)
    enum class ProposalStatus { PREPARED, DISMISSED }
    data class Proposal(val source: Sample, val preparedAt: Long, val status: ProposalStatus = ProposalStatus.PREPARED)
    data class Snapshot(val enabled: Boolean, val samples: List<Sample>, val baseline: List<Sample>, val proposals: List<Proposal> = emptyList())

    fun snapshot(): Snapshot = file.access { decode(read()) }
    fun setEnabled(enabled: Boolean): Boolean = file.access { write(encode(decode(read()).copy(enabled = enabled))) }
    /** Explicit reset removes reports/baseline and returns collection to off, including corrupt data. */
    fun clear(): Boolean = file.access { write(null) }
    fun record(scenario: Scenario, outcome: Outcome): Boolean = file.access {
        val saved = decode(read())
        val time = now()
        if (!saved.enabled || time < 0) false else {
            val next = (saved.samples.filterNot { it.scenario == scenario && it.build == build } +
                Sample(scenario, outcome, build, buildName, time)).takeLast(MAX_SAMPLES)
            write(encode(saved.copy(samples = next)))
        }
    }
    fun saveBaseline(): Boolean = file.access {
        val saved = decode(read())
        val current = saved.samples.filter { it.build == build }
        if (current.isEmpty()) false else write(encode(saved.copy(baseline = current)))
    }

    fun reply(command: Command): String = when (command.action) {
        Action.ENABLE -> if (setEnabled(true)) "Enabled local improvement evaluation. Record only results you explicitly check using 'record improvement feedback for correction handling as passed', failed, or unclear. No conversations are collected automatically."
            else "Could not enable improvement evaluation. No feedback was recorded."
        Action.DISABLE -> if (setEnabled(false)) "Disabled improvement evaluation recording. Existing reports remain available to review or clear."
            else "Could not confirm the evaluation setting. Review it before recording feedback."
        Action.CLEAR -> if (clear()) "Cleared improvement reports, baseline and saved proposals; evaluation recording is off. Conversation, preferences and learned commands are separate."
            else "Could not confirm evaluation deletion."
        Action.REVIEW -> review(snapshot())
        Action.BASELINE -> if (saveBaseline()) "Saved this build's explicitly reported scenario results as the comparison baseline."
            else "Record at least one checked scenario result for this build before saving a baseline."
        Action.COMPARE -> compare(snapshot())
        Action.RECORD -> if (record(command.scenario!!, command.outcome!!)) "Recorded your reported ${command.outcome.name.lowercase(Locale.ROOT)} result for ${command.scenario.label}. This is owner feedback; no test was executed by recording it."
            else "Enable improvement evaluation before recording a checked result. If it is already enabled, restore local storage access and retry."
        Action.PROPOSAL -> prepareProposal(command.scenario!!)
        Action.PROPOSALS -> reviewProposals(snapshot())
        Action.REVIEW_PROPOSAL -> snapshot().let { saved -> saved.proposals.lastOrNull { it.source.scenario == command.scenario }?.let { renderProposal(it, saved) }
            ?: "No saved proposal for ${command.scenario!!.label}. Check and record a result before preparing one." }
        Action.DISMISS_PROPOSAL -> dismissProposal(command.scenario!!)
        Action.CLEAR_PROPOSALS -> file.access {
            if (write(encode(decode(read()).copy(proposals = emptyList())))) "Cleared saved improvement proposals. Evaluation reports, baseline and recording preference remain."
            else "Could not confirm proposal deletion."
        }
    }

    private fun review(saved: Snapshot) = buildString {
        appendLine("Improvement evaluation recording: ${if (saved.enabled) "on" else "off"}. Dataset revision $DATASET_VERSION.")
        appendLine("Synthetic scenarios; owner reports are separate from device verification and automated test results:")
        Scenario.values().forEach { scenario ->
            val sample = saved.samples.lastOrNull { it.build == build && it.scenario == scenario }
            appendLine("${scenario.label}: ${sample?.outcome?.name?.lowercase(Locale.ROOT) ?: "not evaluated for this build"}.")
            appendLine("Try: ${scenario.fixture} Expected: ${scenario.expected}")
        }
        appendLine("The conversation/correction cases preserve an owner-reported positive example from the Phase 6 issue as synthetic coverage; that report is not an independently verified result.")
        append("No raw screenshots, transcripts, provider responses or private targets are collected. Save a baseline after checking scenarios, then compare after retesting. Say 'review improvement proposals' to review saved developer briefs. You can disable or clear these local reports at any time.")
    }

    private fun compare(saved: Snapshot): String {
        if (saved.baseline.isEmpty()) return "No improvement baseline is saved. Check scenarios, record their results and say 'save improvement baseline'."
        val current = saved.samples.filter { it.build == build }.associateBy { it.scenario }
        val paired = saved.baseline.mapNotNull { before -> current[before.scenario]?.let { before to it } }
        if (paired.isEmpty()) return "No matching scenarios were retested for this build. Record checked results before comparing."
        return "Owner-reported scenario comparison (not measured reliability or proof of a code fix):\n" + paired.joinToString("\n") { (before, after) ->
            val change = when {
                Outcome.UNCLEAR in setOf(before.outcome, after.outcome) -> "uncertain; needs another checked result"
                before.outcome == Outcome.FAILED && after.outcome == Outcome.PASSED -> "reported improvement"
                before.outcome == Outcome.PASSED && after.outcome == Outcome.FAILED -> "reported regression"
                else -> "same reported result"
            }
            "${before.scenario.label}: ${before.outcome.name.lowercase(Locale.ROOT)} on ${before.buildName} -> ${after.outcome.name.lowercase(Locale.ROOT)} on ${after.buildName}; $change."
        } + "\nOnly matched synthetic scenarios are compared; untested cases remain unknown."
    }

    private fun prepareProposal(scenario: Scenario): String = file.access {
        val saved = decode(read())
        val sample = saved.samples.lastOrNull { it.build == build && it.scenario == scenario }
            ?: return@access "Check and record '${scenario.label}' for this build before preparing an evidence-linked proposal."
        if (sample.outcome == Outcome.PASSED) return@access "The current '${scenario.label}' report passed. Keep it as a positive regression case; no failure-based change proposal is supported by this report."
        val existing = saved.proposals.firstOrNull { it.source == sample }
        if (existing != null) return@access renderProposal(existing, saved)
        if (saved.proposals.size >= MAX_PROPOSALS) return@access "Saved improvement proposals are full. Review them, then explicitly clear improvement proposals when you no longer need the briefs. No existing evidence was overwritten."
        val preparedAt = now()
        require(preparedAt >= 0)
        val proposal = Proposal(sample, preparedAt)
        val next = saved.copy(proposals = saved.proposals + proposal)
        if (!write(encode(next))) return@access "Could not save the improvement proposal. No code, test or external action was performed."
        renderProposal(proposal, next)
    }

    private fun dismissProposal(scenario: Scenario): String = file.access {
        val saved = decode(read())
        val index = saved.proposals.indexOfLast { it.source.scenario == scenario }
        if (index < 0) return@access "No saved proposal for ${scenario.label} to dismiss."
        val proposals = saved.proposals.mapIndexed { i, proposal -> if (i == index) proposal.copy(status = ProposalStatus.DISMISSED) else proposal }
        if (write(encode(saved.copy(proposals = proposals)))) "Dismissed the latest ${scenario.label} proposal. Its original evidence remains reviewable; no code change was approved or applied."
        else "Could not save the proposal dismissal."
    }

    private fun reviewProposals(saved: Snapshot): String {
        if (saved.proposals.isEmpty()) return "No saved improvement proposals. Record a checked failed or unclear scenario result, then prepare an improvement proposal for that scenario."
        return "Saved local improvement proposals (owner reports, not verified defects):\n" + saved.proposals.joinToString("\n") {
            "${it.source.scenario.label}: ${it.status.name.lowercase(Locale.ROOT)}, reported ${it.source.outcome.name.lowercase(Locale.ROOT)} on ${it.source.buildName}. ${sourceStatus(it, saved)}"
        } + "\nSay 'review improvement proposal for correction handling' using a listed scenario, or dismiss improvement proposal for that scenario. Dismissal retains the original evidence. No patch, pull request or deployment is performed by these controls."
    }

    private fun sourceStatus(proposal: Proposal, saved: Snapshot): String {
        val latest = saved.samples.lastOrNull { it.build == build && it.scenario == proposal.source.scenario }
        return when {
            proposal.source.build != build -> "Source belongs to a previous build; retest before drawing conclusions."
            latest == null -> "Source report is no longer in the evaluation window; evidence is retained in this brief."
            latest != proposal.source -> "A newer report supersedes this source (${latest.outcome.name.lowercase(Locale.ROOT)}); this brief does not establish whether a code fix occurred."
            else -> "Source matches the current report; root cause remains unverified."
        }
    }

    private fun renderProposal(proposal: Proposal, saved: Snapshot): String {
        val sample = proposal.source
        val scenario = sample.scenario
        return """
            Developer change brief: ${scenario.label}
            Local reference: evaluation-${sample.build}-${scenario.name.lowercase(Locale.ROOT)}-${sample.time}-${sample.outcome.name.lowercase(Locale.ROOT)}; dataset revision $DATASET_VERSION.
            Saved status: ${proposal.status.name.lowercase(Locale.ROOT)}. ${sourceStatus(proposal, saved)}
            Evidence: owner reported ${sample.outcome.name.lowercase(Locale.ROOT)} on ${sample.buildName}. This is not device verification or an established root cause.
            Synthetic reproduction: ${scenario.fixture}
            Expected behavior: ${scenario.expected}
            Inspect: ${scenario.module}. Reproduce the issue before selecting a minimal change; preserve working behavior and personal model/provider controls.
            Proposed regression: add a failing test for the synthetic case, plus a positive control and an ambiguous/rejected-input case. Verify the test fails before the correction and passes after it.
            Evaluation: rerun this case and the unchanged positive fixtures, compare explicitly recorded baseline/current results, and keep regressions or unclear outcomes visible.
            Review and delivery: prepare a focused patch and PR, run the full unit/build and optimized Android checks, independently verify production signing and upgrade compatibility, then publish only after required review and verification. Keep the preceding signed APK available for recovery; do not promise a downgrade will be accepted by Android.
            Status: saved locally for human/developer review; dismissal does not erase evidence. No patch, test run, pull request, external message or deployment was performed by this command. This app currently has no configured GitHub write integration; its updater read token is not used for development writes.
        """.trimIndent()
    }

    private fun decode(raw: String?): Snapshot {
        if (raw == null) return Snapshot(false, emptyList(), emptyList())
        val json = JSONObject(raw)
        val version = json.getInt("version")
        require(version in 1..2 && json.getInt("dataset") == DATASET_VERSION)
        fun sample(item: JSONObject) = Sample(Scenario.valueOf(item.getString("scenario")), Outcome.valueOf(item.getString("outcome")),
            item.getInt("build"), item.getString("buildName"), item.getLong("time")).also {
            require(it.build > 0 && it.buildName.length in 1..40 && it.time >= 0)
        }
        fun samples(key: String, limit: Int): List<Sample> {
            val rows = json.getJSONArray(key)
            require(rows.length() <= limit)
            val result = (0 until rows.length()).map { sample(rows.getJSONObject(it)) }
            require(result.distinctBy { it.build to it.scenario }.size == result.size)
            return result
        }
        val proposals = if (version == 1) emptyList() else json.getJSONArray("proposals").let { rows ->
            require(rows.length() <= MAX_PROPOSALS)
            (0 until rows.length()).map { index -> rows.getJSONObject(index).let { item ->
                Proposal(sample(item.getJSONObject("source")), item.getLong("preparedAt"), ProposalStatus.valueOf(item.getString("status"))).also {
                    require(it.preparedAt >= 0 && it.source.outcome != Outcome.PASSED)
                }
            } }.also { require(it.distinctBy { proposal -> proposal.source }.size == it.size) }
        }
        return Snapshot(json.getBoolean("enabled"), samples("samples", MAX_SAMPLES), samples("baseline", Scenario.values().size), proposals)
    }
    private fun encode(saved: Snapshot): String {
        fun row(sample: Sample) = JSONObject().put("scenario", sample.scenario.name).put("outcome", sample.outcome.name)
            .put("build", sample.build).put("buildName", sample.buildName).put("time", sample.time)
        fun rows(samples: List<Sample>) = JSONArray().also { output -> samples.forEach {
            output.put(row(it))
        } }
        val proposals = JSONArray().also { output -> saved.proposals.forEach {
            output.put(JSONObject().put("source", row(it.source)).put("preparedAt", it.preparedAt).put("status", it.status.name))
        } }
        return JSONObject().put("version", 2).put("dataset", DATASET_VERSION).put("enabled", saved.enabled)
            .put("samples", rows(saved.samples)).put("baseline", rows(saved.baseline)).put("proposals", proposals).toString()
    }

    companion object {
        const val DATASET_VERSION = 1
        const val MAX_SAMPLES = 96
        const val MAX_PROPOSALS = 16
        private fun normalize(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        fun command(text: String): Command? {
            if (text.length > 200) return null
            val value = normalize(text)
            val action = when (value) {
                "enable improvement evaluation" -> Action.ENABLE
                "disable improvement evaluation" -> Action.DISABLE
                "clear improvement evaluation" -> Action.CLEAR
                "review improvement evaluation" -> Action.REVIEW
                "save improvement baseline" -> Action.BASELINE
                "compare improvement evaluation" -> Action.COMPARE
                "review improvement proposals" -> Action.PROPOSALS
                "clear improvement proposals" -> Action.CLEAR_PROPOSALS
                else -> null
            }
            if (action != null) return Command(action)
            val record = Regex("^record improvement feedback for (.+) as (passed|failed|unclear)$").matchEntire(value)
            if (record != null) {
                val scenario = Scenario.values().firstOrNull { it.label == record.groupValues[1] } ?: return null
                return Command(Action.RECORD, scenario, Outcome.valueOf(record.groupValues[2].uppercase(Locale.ROOT)))
            }
            for ((prefix, proposalAction) in listOf("prepare" to Action.PROPOSAL, "review" to Action.REVIEW_PROPOSAL, "dismiss" to Action.DISMISS_PROPOSAL)) {
                val proposal = value.removePrefix("$prefix improvement proposal for ")
                if (proposal != value) Scenario.values().firstOrNull { it.label == proposal }?.let { return Command(proposalAction, it) }
            }
            return null
        }
        fun isManagementInput(text: String): Boolean = command(text) != null || normalize(text).startsWith("record improvement feedback ") ||
            listOf("prepare", "review", "dismiss", "clear").any { normalize(text).startsWith("$it improvement proposal") }
    }
}
