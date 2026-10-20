package com.assistant.core.services

import android.content.Context
import com.assistant.core.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Manual reports and isolated logic checks. Never collects conversations or applies developer changes. */
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
            "Keep the interrupted effect uncertain, avoid duplicate dispatch and allow cancellation of later steps.", "RoutineExecutionCoordinator and LocalRoutineWorkflowAccess"),
        CONTACT_CALL("contact call review", "Prepare a call to a synthetic contact with two numbers, choose one, reconstruct the service and review before approval.",
            "Require the current recipient review, revalidate the number and request at most one call or dialer; never claim connection.", "ContactCallWorkflow, ContactCallCommands and AndroidAssistantService"),
        RESEARCH("research brief", "Use synthetic sources, fail the first retrieval method, then cancel a delayed follow-up and review the saved brief offline.",
            "Fall back to an available method, retain source links, reject late cancelled results and distinguish excerpts from verified facts.", "ResearchBriefs, SearchCoordinator and SearchRouter")
    }
    enum class Outcome { PASSED, FAILED, UNCLEAR }
    enum class Action { ENABLE, DISABLE, CLEAR, REVIEW, BASELINE, COMPARE, RECORD, PROPOSAL, PROPOSALS, REVIEW_PROPOSAL, DISMISS_PROPOSAL, CLEAR_PROPOSALS,
        RUN_LOCAL, REVIEW_LOCAL, CLEAR_LOCAL, BASELINE_LOCAL, COMPARE_LOCAL, SHARE_PROPOSAL }
    data class Command(val action: Action, val scenario: Scenario? = null, val outcome: Outcome? = null)
    data class Sample(val scenario: Scenario, val outcome: Outcome, val build: Int, val buildName: String, val time: Long)
    enum class ProposalStatus { PREPARED, DISMISSED }
    data class Proposal(val source: Sample, val preparedAt: Long, val status: ProposalStatus = ProposalStatus.PREPARED)
    data class Snapshot(val enabled: Boolean, val samples: List<Sample>, val baseline: List<Sample>, val proposals: List<Proposal> = emptyList(),
                        val localRun: LocalFoundationChecks.Run? = null, val localBaseline: LocalFoundationChecks.Run? = null)

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
        Action.ENABLE -> if (setEnabled(true)) "Enabled local improvement evaluation. Record checked results using 'record improvement feedback for correction handling as passed', failed, or unclear. You can also request 'run local foundation checks'. Nothing runs or collects conversations automatically."
            else "Could not enable improvement evaluation. No feedback was recorded."
        Action.DISABLE -> if (setEnabled(false)) "Disabled improvement evaluation recording. Existing reports remain available to review or clear."
            else "Could not confirm the evaluation setting. Review it before recording feedback."
        Action.CLEAR -> if (clear()) "Cleared improvement reports, baselines, local checks and saved proposals; evaluation recording is off. Conversation, preferences and learned commands are separate."
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
        Action.RUN_LOCAL -> runLocalChecks()
        Action.REVIEW_LOCAL -> renderLocal(snapshot().localRun)
        Action.CLEAR_LOCAL -> file.access {
            if (write(encode(decode(read()).copy(localRun = null, localBaseline = null)))) "Cleared local foundation check results and their baseline. Manual reports, saved briefs and recording preference remain."
            else "Could not confirm deletion of local check results."
        }
        Action.BASELINE_LOCAL -> file.access {
            val saved = decode(read())
            val run = saved.localRun
            if (run == null || run.build != build || run.revision != LocalFoundationChecks.REVISION)
                "Run local foundation checks on this build before saving their baseline."
            else if (write(encode(saved.copy(localBaseline = run)))) "Saved the actual local synthetic check results as their separate baseline. Manual owner reports are unchanged."
            else "Could not save the local check baseline."
        }
        Action.COMPARE_LOCAL -> compareLocal(snapshot())
        Action.SHARE_PROPOSAL -> "Use the personal assistant's share improvement proposal command to open Android's review chooser. This evaluation store does not submit or share anything."
    }

    fun handoff(scenario: Scenario): String? {
        val saved = snapshot()
        val proposal = saved.proposals.lastOrNull { it.source.scenario == scenario } ?: return null
        if (proposal.status != ProposalStatus.PREPARED) return null
        return "Jarvis developer handoff — review required\n" + renderProposal(proposal, saved) +
            "\n\nSeparate local-check snapshot at handoff (not owner-reported experience or phone verification):\n" + renderLocal(saved.localRun) +
            "\n\nHandoff boundary: this is the saved synthetic evidence brief, not a patch or verified root cause. Review/reproduce it, prepare a focused change and regression tests, then use normal PR review, CI, signing and publication verification. This handoff contains no raw conversation, screenshots, private targets or provider credentials. Choosing a share destination does not prove that any external submission completed."
    }

    private fun runLocalChecks(): String {
        if (!snapshot().enabled) return "Enable improvement evaluation before running and recording local foundation checks. No checks were run."
        val results = LocalFoundationChecks.run()
        val run = LocalFoundationChecks.Run(build, buildName, now(), LocalFoundationChecks.REVISION, results)
        require(run.time >= 0)
        val saved = file.access {
            val current = decode(read())
            current.enabled && write(encode(current.copy(localRun = run)))
        }
        return if (saved) renderLocal(run) else "Local synthetic checks returned, but recording was disabled or results could not be saved. Review evaluation recording and local storage before retrying; no device action was run."
    }

    private fun renderLocal(run: LocalFoundationChecks.Run?): String {
        if (run == null) return "No local foundation check results are saved. Enable improvement evaluation and say 'run local foundation checks'."
        return "Local synthetic logic checks on ${run.buildName}, revision ${run.revision}" +
            (if (run.build != build || run.revision != LocalFoundationChecks.REVISION) " (previous build or check revision; retest)" else "") + ":\n" +
            run.results.joinToString("\n") { "${it.case.label}: ${it.outcome.name.lowercase(Locale.ROOT)}. Expected: ${it.case.expected}" } +
            "\nThese checks executed local planning, approval, retry, recovery and journal logic with isolated synthetic state and counter dispatchers. No phone action, provider request or user workflow ran. They do not establish live conversation quality, device integration, production signing or upgrade compatibility. Failed or errored cases need developer reproduction and a reviewed regression fix; this command makes no code change. Manual owner reports remain separate. Save a local check baseline before a later retest to compare the same checks."
    }

    private fun compareLocal(saved: Snapshot): String {
        val before = saved.localBaseline ?: return "No local check baseline is saved. Run local foundation checks, then say 'save local check baseline'."
        val after = saved.localRun
        if (after == null || after.build != build) return "Run local foundation checks on this build before comparing."
        if (before.revision != after.revision || after.revision != LocalFoundationChecks.REVISION)
            return "The local check revision changed. Retest and save a new baseline; these results cannot establish a matched comparison."
        return "Actual local synthetic check comparison (${before.buildName} to ${after.buildName}; not device or provider verification):\n" +
            before.results.joinToString("\n") { old ->
                val current = after.results.single { it.case == old.case }
                "${old.case.label}: ${old.outcome.name.lowercase(Locale.ROOT)} -> ${current.outcome.name.lowercase(Locale.ROOT)}" +
                    when {
                        old.outcome == current.outcome -> "; unchanged."
                        old.outcome == LocalFoundationChecks.Outcome.PASSED -> "; regression or incomplete check; investigate."
                        current.outcome == LocalFoundationChecks.Outcome.PASSED -> "; now passed this local check; no causal code-fix claim."
                        else -> "; still failed or incomplete."
                    }
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
        require(version in 1..3 && json.getInt("dataset") == DATASET_VERSION)
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
        fun localRun(key: String): LocalFoundationChecks.Run? = if (version < 3 || !json.has(key)) null else json.getJSONObject(key).let { item ->
            val results = item.getJSONArray("results")
            val revision = item.getInt("revision")
            val expectedCases = if (revision == 1) setOf(LocalFoundationChecks.Case.CORRECTIONS, LocalFoundationChecks.Case.APPROVAL,
                LocalFoundationChecks.Case.RECOVERY, LocalFoundationChecks.Case.RETRIES, LocalFoundationChecks.Case.HISTORY)
                else LocalFoundationChecks.Case.values().toSet()
            require(results.length() == expectedCases.size)
            LocalFoundationChecks.Run(item.getInt("build"), item.getString("buildName"), item.getLong("time"), item.getInt("revision"),
                (0 until results.length()).map { index -> results.getJSONObject(index).let { result ->
                    LocalFoundationChecks.Result(LocalFoundationChecks.Case.valueOf(result.getString("case")), LocalFoundationChecks.Outcome.valueOf(result.getString("outcome")))
                } }).also {
                require(it.build > 0 && it.buildName.length in 1..40 && it.time >= 0 && it.revision > 0)
                require(it.results.map { result -> result.case }.toSet() == expectedCases)
            }
        }
        return Snapshot(json.getBoolean("enabled"), samples("samples", MAX_SAMPLES), samples("baseline", Scenario.values().size), proposals,
            localRun("localRun"), localRun("localBaseline"))
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
        val json = JSONObject().put("version", 3).put("dataset", DATASET_VERSION).put("enabled", saved.enabled)
            .put("samples", rows(saved.samples)).put("baseline", rows(saved.baseline)).put("proposals", proposals)
        fun putRun(key: String, run: LocalFoundationChecks.Run?) { run?.let {
            val results = JSONArray().also { output -> run.results.forEach { result ->
                output.put(JSONObject().put("case", result.case.name).put("outcome", result.outcome.name))
            } }
            json.put(key, JSONObject().put("build", it.build).put("buildName", it.buildName).put("time", it.time).put("revision", it.revision).put("results", results))
        } }
        putRun("localRun", saved.localRun); putRun("localBaseline", saved.localBaseline)
        return json.toString()
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
                "run local foundation checks" -> Action.RUN_LOCAL
                "review local foundation checks" -> Action.REVIEW_LOCAL
                "clear local foundation checks" -> Action.CLEAR_LOCAL
                "save local check baseline" -> Action.BASELINE_LOCAL
                "compare local foundation checks" -> Action.COMPARE_LOCAL
                else -> null
            }
            if (action != null) return Command(action)
            val record = Regex("^record improvement feedback for (.+) as (passed|failed|unclear)$").matchEntire(value)
            if (record != null) {
                val scenario = Scenario.values().firstOrNull { it.label == record.groupValues[1] } ?: return null
                return Command(Action.RECORD, scenario, Outcome.valueOf(record.groupValues[2].uppercase(Locale.ROOT)))
            }
            for ((prefix, proposalAction) in listOf("prepare" to Action.PROPOSAL, "review" to Action.REVIEW_PROPOSAL, "dismiss" to Action.DISMISS_PROPOSAL, "share" to Action.SHARE_PROPOSAL)) {
                val proposal = value.removePrefix("$prefix improvement proposal for ")
                if (proposal != value) Scenario.values().firstOrNull { it.label == proposal }?.let { return Command(proposalAction, it) }
            }
            return null
        }
        fun isManagementInput(text: String): Boolean = command(text) != null || normalize(text).startsWith("record improvement feedback ") ||
            listOf("prepare", "review", "dismiss", "clear", "share").any { normalize(text).startsWith("$it improvement proposal") } ||
            Regex("^(?:run|review|clear|compare) local foundation checks\\b|^save local check baseline\\b").containsMatchIn(normalize(text))
    }
}
