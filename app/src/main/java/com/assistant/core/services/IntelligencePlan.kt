package com.assistant.core.services

import org.json.JSONObject

data class IntelligencePlan(val revision: Long, val updated: String, val throughVersion: String,
    val note: String, val milestone: String, val workstreams: List<Workstream>) {
    data class Workstream(val id: String, val title: String, val percent: Int, val status: String, val implemented: String, val remaining: String)
    val overall: Int get() = kotlin.math.round(workstreams.map { it.percent }.average()).toInt()
    companion object {
        val ids = listOf("router", "settings", "learning", "builder", "dashboard", "providers", "updates")
        fun parse(raw: String): IntelligencePlan? = runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= 65536)
            val json = JSONObject(raw)
            fun number(obj: JSONObject, key: String): Long {
                val value = obj.get(key)
                require(value is Int || value is Long)
                return (value as Number).toLong()
            }
            fun text(obj: JSONObject, key: String, max: Int): String = obj.getString(key).also {
                require(it.isNotBlank() && it.length <= max && it.none { c -> c.code < 32 && c != '\n' })
            }
            require(number(json, "schema") == 1L)
            val revision = number(json, "revision").also { require(it in 1..1000000000) }
            val date = text(json, "updated", 10).also { java.time.LocalDate.parse(it) }
            val version = text(json, "throughVersion", 40).also { require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) }
            val array = json.getJSONArray("workstreams")
            require(array.length() == ids.size)
            val rows = (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                val id = text(row, "id", 20)
                val percent = number(row, "percent").also { require(it in 0..100) }.toInt()
                Workstream(id, text(row, "title", 100), percent, text(row, "status", 200), text(row, "implemented", 1600), text(row, "remaining", 1600))
            }
            require(rows.map { it.id } == ids)
            IntelligencePlan(revision, date, version, text(json, "note", 1500), text(json, "milestone", 2000), rows)
        }.getOrNull()
    }
}
