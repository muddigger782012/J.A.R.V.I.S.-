package com.assistant.core.storage

import android.content.ContentValues
import java.util.UUID

data class AuditLogEntry(
    val id: String,
    val actionId: String?,
    val adapterUsed: String?,
    val status: String?,
    val message: String?,
    val createdAt: Long
)

class AuditRepository(private val database: Database) {

    fun insert(actionId: String?, adapterUsed: String?, status: String?, message: String?): Long {
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("action_id", actionId)
            put("adapter_used", adapterUsed)
            put("status", status)
            put("message", message)
            put("created_at", System.currentTimeMillis())
        }
        return database.writableDatabase.insert("audit_logs", null, values)
    }

    fun getRecent(limit: Int = 20): List<AuditLogEntry> {
        val logs = mutableListOf<AuditLogEntry>()
        val cursor = database.readableDatabase.query(
            "audit_logs",
            arrayOf("id", "action_id", "adapter_used", "status", "message", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                logs.add(
                    AuditLogEntry(
                        id = it.getString(0),
                        actionId = it.getString(1),
                        adapterUsed = it.getString(2),
                        status = it.getString(3),
                        message = it.getString(4),
                        createdAt = it.getLong(5)
                    )
                )
            }
        }
        return logs
    }
}
