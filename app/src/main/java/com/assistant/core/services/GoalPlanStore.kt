package com.assistant.core.services

import android.content.Context
import com.assistant.core.learning.ApprovedRoutineRegistry
import org.json.JSONArray
import org.json.JSONObject

/** One pending review and one approved definition, separate from learned aliases/routines. */
class GoalPlanStore(private val file: PersonalStateFile, private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(PersonalStateFile(context, "goal-plans.json"))
    private data class State(val pending: GoalPlanning.Plan? = null, val created: Long = 0,
                             val current: GoalPlanning.Plan? = null, val draft: GoalPlanning.Request? = null)

    fun propose(plan: GoalPlanning.Plan): Boolean = file.access {
        require(GoalPlanning.valid(plan))
        require(now() >= 0)
        val saved = decode(read())
        write(encode(saved.copy(pending = plan, created = now(), draft = null)))
    }
    fun askDuration(request: GoalPlanning.Request): Boolean = file.access {
        require(request.kind == GoalPlanning.Kind.FOCUS && request.volume in 0..100)
        require(now() >= 0)
        write(encode(decode(read()).copy(pending = null, created = now(), draft = request.copy(seconds = null, invalidDuration = false))))
    }
    fun durationDraft(): GoalPlanning.Request? = file.access { decode(read()).takeIf { fresh(it.created) }?.draft }
    fun pending(): GoalPlanning.Plan? = file.access { decode(read()).takeIf { fresh(it.created) }?.pending }
    fun current(): GoalPlanning.Plan? = file.access { decode(read()).current }
    fun discardPending(): Boolean = file.access {
        val saved = decode(read())
        if (saved.pending == null && saved.draft == null) true
        else write(encode(saved.copy(pending = null, draft = null, created = 0)))
    }

    /** Call only inside the execution lease after the exact pending review was approved. */
    fun approve(expected: GoalPlanning.Plan): Boolean = file.access {
        val saved = decode(read())
        if (!fresh(saved.created) || saved.pending != expected) false
        else write(encode(State(current = expected)))
    }

    fun find(key: String): ApprovedRoutineRegistry.Routine? {
        if (!GoalPlanning.isKey(key)) return null
        return current()?.takeIf { it.key == key }?.let { ApprovedRoutineRegistry.Routine(it.key, it.actions) }
    }

    private fun fresh(time: Long) = now() - time in 0..120_000
    private fun decode(raw: String?): State {
        if (raw == null) return State()
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        fun plan(name: String): GoalPlanning.Plan? = if (!json.has(name)) null else json.getJSONObject(name).let { item ->
            val actions = item.getJSONArray("actions")
            require(actions.length() == 2)
            GoalPlanning.Plan(item.getString("key"), GoalPlanning.Kind.valueOf(item.getString("kind")),
                (0 until actions.length()).map { actions.getString(it) }).also { require(GoalPlanning.valid(it)) }
        }
        val draft = if (json.has("draftVolume")) GoalPlanning.Request(GoalPlanning.Kind.FOCUS, json.getInt("draftVolume")).also {
            require(it.volume in 0..100)
        } else null
        return State(plan("pending"), json.getLong("created"), plan("current"), draft)
    }
    private fun encode(state: State): String {
        val json = JSONObject().put("version", 1).put("created", state.created)
        fun put(name: String, plan: GoalPlanning.Plan?) { plan?.let {
            json.put(name, JSONObject().put("key", it.key).put("kind", it.kind.name).put("actions", JSONArray(it.actions)))
        } }
        put("pending", state.pending); put("current", state.current)
        state.draft?.let { json.put("draftVolume", it.volume) }
        return json.toString()
    }
}
