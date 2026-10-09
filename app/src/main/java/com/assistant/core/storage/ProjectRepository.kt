package com.assistant.core.storage

import android.content.ContentValues
import com.assistant.core.models.Project

class ProjectRepository(private val database: Database) {

    fun insert(project: Project): Long {
        val values = ContentValues().apply {
            put("id", project.id)
            put("name", project.name)
            put("root_path", project.rootPath)
            put("created_at", project.createdAt)
        }
        return database.writableDatabase.insert("projects", null, values)
    }

    fun getAll(): List<Project> {
        val projects = mutableListOf<Project>()
        val cursor = database.readableDatabase.query(
            "projects",
            arrayOf("id", "name", "root_path", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                projects.add(
                    Project(
                        id = it.getString(0),
                        name = it.getString(1),
                        rootPath = it.getString(2),
                        createdAt = it.getLong(3)
                    )
                )
            }
        }
        return projects
    }
}
