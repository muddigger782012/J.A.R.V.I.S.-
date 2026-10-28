package com.assistant.core.services

import java.util.Locale
import java.util.UUID

/** App edits are proposal data; neither parsing nor binding can dispatch a device action. */
object GoalAppCorrections {
    data class Edit(val target: String?, val step: Int? = null)
    private fun clean(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim()
        .replace(Regex("\\s+"), " ").replace(Regex("^actually(?:,|:)?\\s+"), "")

    /** Explicit controls stay reserved even when the step or replacement is malformed. */
    fun explicit(text: String): Edit? {
        val value = clean(text)
        if (!Regex("^change (?:the )?goal (?:app|step)\\b").containsMatchIn(value)) return null
        val app = Regex("^change (?:the )?goal app to(?: (.*))?$").matchEntire(value)
        if (app != null) return Edit(target(text, app.groupValues[1]))
        val numbered = Regex("^change (?:the )?goal step (.+?) to open(?: (.*))?$").matchEntire(value)
            ?: return Edit(null)
        val numberText = numbered.groupValues[1]
        val number = mapOf("first" to 1, "second" to 2, "third" to 3)[numberText]
            ?: numberText.takeUnless { it in setOf("a", "an") }?.let(SpokenTimerDuration::number)
        return Edit(target(text, numbered.groupValues[2]), number?.takeIf { it in 1..3 } ?: -1)
    }

    /** Only the host may apply a short reply while a fresh goal review actually exists. */
    fun contextual(text: String): Edit? {
        val value = clean(text).replace(Regex("^no(?:,|:)?\\s+"), "")
        if (!Regex("^(?:use|open) .+? instead\\b").containsMatchIn(value)) return null
        val match = Regex("^(?:use|open) (.+) instead$").matchEntire(value) ?: return Edit(null)
        return Edit(target(text, match.groupValues[1]))
    }

    private fun target(original: String, label: String): String? = label.takeIf {
        original.length <= 400 && it.length in 1..100 && original.none { c -> c.isISOControl() && !c.isWhitespace() }
    }

    fun revise(plan: GoalPlanning.Plan, edit: Edit, preferred: (String) -> LearnableDeviceAction?,
               resolveApp: (String) -> Pair<String, String>?, canLaunch: (String) -> Boolean): GoalPlanning.Plan? {
        if (!GoalPlanning.valid(plan) || edit.target == null) return null
        val appIndices = plan.actions.indices.filter { RoutineSteps.appAction(plan.actions[it]) != null }
        val index = if (edit.step == null) appIndices.singleOrNull() else (edit.step - 1).takeIf { it in appIndices }
        if (index == null) return null // Never guess which app, or convert a volume/timer step.
        val slot = Regex("^(?:my|the preferred|my preferred) (browser|maps|music) app$").matchEntire(edit.target)?.groupValues?.get(1)
        val step = if (slot == null) ComposableGoalPlanning.Step.App(edit.target) else ComposableGoalPlanning.Step.Preferred(slot)
        val bound = runCatching { ComposableGoalPlanning.bind(ComposableGoalPlanning.Draft(listOf(step)), preferred, resolveApp, canLaunch) }
            .getOrNull()?.singleOrNull() ?: return null
        val actions = plan.actions.toMutableList().apply { this[index] = bound }
        val revised = GoalPlanning.Plan("goal plan ${UUID.randomUUID()}", plan.kind, actions)
        return revised.takeIf { GoalPlanning.valid(it) && runCatching {
            it.actions.mapNotNull(RoutineSteps::appAction).all { app -> canLaunch(app.parameters.getValue("package")) }
        }.getOrDefault(false) }
    }
}
