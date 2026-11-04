package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject

/** Bounded conversation data only. Never source evidence, an executable plan or approval. */
internal class ProjectGenerationDiscussion private constructor(
    val project: String, val target: Int, private val turns: List<Turn>
) {
    private data class Turn(val request: String, val reply: String)
    val size: Int get() = turns.size

    fun lookupTask(task: String): String {
        if (turns.isEmpty()) return task
        val heading = "\n\nEarlier owner requests (source-search hints only):\n"
        val remaining = 4_000 - task.length - heading.length
        if (remaining <= 0) return task
        return task + heading + bounded(turns.asReversed().joinToString("\n") { it.request }, remaining)
    }

    fun append(task: String, reply: String, secrets: List<String>): ProjectGenerationDiscussion {
        require(task.isNotBlank() && reply.isNotBlank())
        val turn = Turn(clean(task, MAX_REQUEST_BYTES, secrets), clean(reply, MAX_REPLY_BYTES, secrets))
        var kept = (turns + turn).takeLast(MAX_TURNS)
        while (ProjectGenerationDiscussion(project, target, kept).encoded().toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES)
            kept = kept.drop(1)
        return ProjectGenerationDiscussion(project, target, kept)
    }

    fun data(): JSONObject = encoded().also { require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    private fun encoded(): JSONObject = JSONObject().put("schemaVersion", 1).put("project", project).put("target", target)
        .put("turns", JSONArray().apply { turns.forEach { turn ->
            put(JSONObject().put("ownerRequest", turn.request).put("assistantReply", turn.reply))
        } })

    companion object {
        const val MAX_TURNS = 3
        const val MAX_BYTES = 10_000
        private const val MAX_REQUEST_BYTES = 800
        private const val MAX_REPLY_BYTES = 1_600
        fun empty(project: String, target: Int): ProjectGenerationDiscussion {
            require(project.matches(Regex("[A-Za-z0-9_-]{1,80}")) && target in 0..1)
            return ProjectGenerationDiscussion(project, target, emptyList())
        }

        fun read(project: String, target: Int, value: JSONObject?, secrets: List<String>): ProjectGenerationDiscussion {
            val empty = empty(project, target)
            if (value == null) return empty // Legacy replies are not silently re-sent to a provider.
            return runCatching {
                require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
                require(value.keys().asSequence().toSet() == setOf("schemaVersion", "project", "target", "turns"))
                require(value.get("schemaVersion") == 1 && value.get("project") == project && value.get("target") == target)
                val items = value.getJSONArray("turns")
                require(items.length() in 1..MAX_TURNS)
                val turns = (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    require(item.keys().asSequence().toSet() == setOf("ownerRequest", "assistantReply"))
                    val request = item.get("ownerRequest") as String
                    val reply = item.get("assistantReply") as String
                    require(request.isNotBlank() && reply.isNotBlank())
                    require(request.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES)
                    require(reply.toByteArray(Charsets.UTF_8).size <= MAX_REPLY_BYTES)
                    Turn(clean(request, MAX_REQUEST_BYTES, secrets), clean(reply, MAX_REPLY_BYTES, secrets))
                }
                ProjectGenerationDiscussion(project, target, turns)
            }.getOrDefault(empty)
        }

        private fun clean(value: String, limit: Int, secrets: List<String>) = bounded(RepositoryRedaction.source(value, secrets), limit)

        private fun bounded(value: String, limit: Int): String {
            val result = StringBuilder()
            var used = 0
            var offset = 0
            while (offset < value.length) {
                val point = value.codePointAt(offset)
                val text = if (point in 0xd800..0xdfff) "\ufffd" else String(Character.toChars(point))
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (used + bytes > limit) break
                result.append(text); used += bytes; offset += Character.charCount(point)
            }
            return result.toString()
        }
    }
}
