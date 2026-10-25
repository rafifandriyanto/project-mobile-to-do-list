package com.example

import androidx.room.TypeConverter

/**
 * Room TypeConverters for serializing complex objects to SQLite primitive types without external JSON libraries.
 */
class TaskTypeConverters {

    @TypeConverter
    fun fromSubtaskList(subtasks: List<SubTask>): String {
        return subtasks.joinToString("\n") { subTask ->
            val cleanTitle = subTask.title.replace("\n", " ").replace("|", "/")
            val doneFlag = if (subTask.isDone) "1" else "0"
            "${subTask.id}|$doneFlag|$cleanTitle"
        }
    }

    @TypeConverter
    fun toSubtaskList(raw: String): List<SubTask> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 3) {
                    val id = parts[0]
                    val isDone = parts[1] == "1"
                    val title = parts.subList(2, parts.size).joinToString("|")
                    SubTask(id = id, title = title, isDone = isDone)
                } else {
                    null
                }
            }
            .toList()
    }

    @TypeConverter
    fun fromTaskPriority(priority: TaskPriority): String = priority.name

    @TypeConverter
    fun toTaskPriority(raw: String): TaskPriority {
        return runCatching { TaskPriority.valueOf(raw) }.getOrDefault(TaskPriority.MEDIUM)
    }

    @TypeConverter
    fun fromTaskCategory(category: TaskCategory): String = category.name

    @TypeConverter
    fun toTaskCategory(raw: String): TaskCategory {
        return runCatching { TaskCategory.valueOf(raw) }.getOrDefault(TaskCategory.PERSONAL)
    }
}
