package com.assistant.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Database(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                root_path TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE project_files (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                path TEXT NOT NULL,
                hash TEXT,
                last_modified INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE actions (
                id TEXT PRIMARY KEY,
                action_type TEXT NOT NULL,
                parameters TEXT,
                required_capability TEXT,
                risk_level INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE action_queue (
                id TEXT PRIMARY KEY,
                action_id TEXT NOT NULL,
                status TEXT NOT NULL,
                scheduled_time INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE workflows (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                trigger_type TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE audit_logs (
                id TEXT PRIMARY KEY,
                action_id TEXT,
                adapter_used TEXT,
                status TEXT,
                message TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS audit_logs")
        db.execSQL("DROP TABLE IF EXISTS tasks")
        db.execSQL("DROP TABLE IF EXISTS workflows")
        db.execSQL("DROP TABLE IF EXISTS action_queue")
        db.execSQL("DROP TABLE IF EXISTS actions")
        db.execSQL("DROP TABLE IF EXISTS project_files")
        db.execSQL("DROP TABLE IF EXISTS projects")
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "assistant_platform.db"
        private const val DB_VERSION = 1
    }
}
