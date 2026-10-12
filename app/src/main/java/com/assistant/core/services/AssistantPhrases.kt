package com.assistant.core.services

import org.json.JSONObject

data class AssistantPhrases(
    val introduction: String = DEFAULT_INTRODUCTION,
    val wakeReplies: String = "Yes?"
) {
    fun replies() = wakeReplies.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    fun reply(index: Int) = replies().let { if (it.isEmpty()) "" else it[Math.floorMod(index, it.size)] }
    fun toJson() = JSONObject().put("introduction", introduction).put("wakeReplies", wakeReplies)
    companion object {
        const val DEFAULT_INTRODUCTION = "Hello. I'm JARVIS. How can I help?"
        fun fromJson(json: JSONObject) = AssistantPhrases(json.optString("introduction", DEFAULT_INTRODUCTION), json.optString("wakeReplies", "Yes?"))
    }
}
