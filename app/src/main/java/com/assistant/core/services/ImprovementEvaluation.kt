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
    enum class Action { ENABLE, DISABLE, CLEAR, REVIEW, BASELINE, COMPARE, RECORD, PROPOSAL }
    data class Command(val action: Action, val scenario: Scenario? = null, val outcome: Outcome? = null)
    data class Sample(val scenario: Scenario, val outcome: Outcome, val build: Int, val buildName: String, val time: Long)
    data class Snapshot(val enabled: Boolean, val samples: List<Sample>, val baseline: List<Sample>)

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
        Action.CLEAR -> if (clear()) "Cleared improvement reports and baseline; evaluation recording is off. Conversation, preferences and learned commands are separate."
            else "Could not confirm evaluation deletion."
        Action.REVIEW -> review(snapshot())
        Action.BASELINE -> if (saveBaseline()) "Saved this build's explicitly reported scenario results as the comparison baseline."
            else "Record at least one checked scenario result for this build before saving a baseline."
        Action.COMPARE -> compare(snapshot())
        Action.RECORD -> if (record(command.scenario!!, command.outcome!!)) "Recorded your reported ${command.outcome.name.lowercase(Locale.ROOT)} result for ${command.scenario.label}. This is owner feedback; no test was executed by recording it."
            else "Enable improvement evaluation before recording a checked result. If it is already enabled, restore local storage access and retry."
        Action.PROPOSAL -> proposal(snapshot(), command.scenario!!)
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
        append("No raw screenshots, transcripts, provider responses or private targets are collected. Save a baseline after checking scenarios, then compare after retesting. You can disable or clear these local reports at any time.")
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

    private fun proposal(saved: Snapshot, scenario: Scenario): String {
        val sample = saved.samples.lastOrNull { it.build == build && it.scenario == scenario }
            ?: return "Check and record '${scenario.label}' for this build before preparing an evidence-linked proposal."
        if (sample.outcome == Outcome.PASSED) return "The current '${scenario.label}' report passed. Keep it as a positive regression case; no failure-based change proposal is supported by this report."
        return """
            Developer change brief: ${scenario.label}
            Local reference: evaluation-$build-${scenario.name.lowercase(Locale.ROOT)}-${sample.time}; dataset revision $DATASET_VERSION.
            Evidence: owner reported ${sample.outcome.name.lowercase(Locale.ROOT)} on $buildName. This is not device verification or an established root cause.
            Synthetic reproduction: ${scenario.fixture}
            Expected behavior: ${scenario.expected}
            Inspect: ${scenario.module}. Reproduce the issue before selecting a minimal change; preserve working behavior and personal model/provider controls.
            Proposed regression: add a failing test for the synthetic case, plus a positive control and an ambiguous/rejected-input case. Verify the test fails before the correction and passes after it.
            Evaluation: rerun this case and the unchanged positive fixtures, compare explicitly recorded baseline/current results, and keep regressions or unclear outcomes visible.
            Review and delivery: prepare a focused patch and PR, run the full unit/build and optimized Android checks, independently verify production signing and upgrade compatibility, then publish only after required review and verification. Keep the preceding signed APK available for recovery; do not promise a downgrade will be accepted by Android.
            Status: prepared for human/developer review. No patch, test run, pull request, external message or deployment was performed by this command. This app currently has no configured GitHub write integration; its updater read token is not used for development writes.
        """.trimIndent()
    }

    private fun decode(raw: String?): Snapshot {
        if (raw == null) return Snapshot(false, emptyList(), emptyList())
        val json = JSONObject(raw)
        require(json.getInt("version") == 1 && json.getInt("dataset") == DATASET_VERSION)
        fun samples(key: String, limit: Int): List<Sample> {
            val rows = json.getJSONArray(key)
            require(rows.length() <= limit)
            val result = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { item ->
                Sample(Scenario.valueOf(item.getString("scenario")), Outcome.valueOf(item.getString("outcome")),
                    item.getInt("build"), item.getString("buildName"), item.getLong("time")).also {
                    require(it.build > 0 && it.buildName.length in 1..40 && it.time >= 0)
                }
            } }
            require(result.distinctBy { it.build to it.scenario }.size == result.size)
            return result
        }
        return Snapshot(json.getBoolean("enabled"), samples("samples", MAX_SAMPLES), samples("baseline", Scenario.values().size))
    }
    private fun encode(saved: Snapshot): String {
        fun rows(samples: List<Sample>) = JSONArray().also { output -> samples.forEach {
            output.put(JSONObject().put("scenario", it.scenario.name).put("outcome", it.outcome.name)
                .put("build", it.build).put("buildName", it.buildName).put("time", it.time))
        } }
        return JSONObject().put("version", 1).put("dataset", DATASET_VERSION).put("enabled", saved.enabled)
            .put("samples", rows(saved.samples)).put("baseline", rows(saved.baseline)).toString()
    }

    companion object {
        const val DATASET_VERSION = 1
        const val MAX_SAMPLES = 96
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
                else -> null
            }
            if (action != null) return Command(action)
            val record = Regex("^record improvement feedback for (.+) as (passed|failed|unclear)$").matchEntire(value)
            if (record != null) {
                val scenario = Scenario.values().firstOrNull { it.label == record.groupValues[1] } ?: return null
                return Command(Action.RECORD, scenario, Outcome.valueOf(record.groupValues[2].uppercase(Locale.ROOT)))
            }
            val proposal = value.removePrefix("prepare improvement proposal for ")
            if (proposal != value) Scenario.values().firstOrNull { it.label == proposal }?.let { return Command(Action.PROPOSAL, it) }
            return null
        }
        fun isManagementInput(text: String): Boolean = command(text) != null || normalize(text).startsWith("record improvement feedback ") ||
            normalize(text).startsWith("prepare improvement proposal ")
    }
}
