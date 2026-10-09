package com.assistant.core.storage

import android.content.ContentValues
import com.assistant.core.models.ActionRequest
import org.json.JSONObject

class ActionRepository(private val database: Database) {

    fun insert(actionRequest: ActionRequest): Long {
        val values = ContentValues().apply {
            put("id", actionRequest.id)
            put("action_type", actionRequest.actionType)
            put("parameters", toJson(actionRequest.parameters))
            put("required_capability", actionRequest.requiredCapability)
            put("risk_level", actionRequest.riskLevel)
            put("created_at", System.currentTimeMillis())
        }
        return database.writableDatabase.insert("actions", null, values)
    }

    private fun toJson(parameters: Map<String, Any?>): String {
        val json = JSONObject()
        parameters.forEach { (key, value) ->
            json.put(key, value ?: JSONObject.NULL)
        }
        return json.toString()
    }
}
