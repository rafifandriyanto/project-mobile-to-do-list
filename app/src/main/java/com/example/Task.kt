package com.example

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Ignore
import java.util.UUID

enum class TaskPriority(val label: String, val level: Int) {
    LOW("Rendah", 1),
    MEDIUM("Sedang", 2),
    HIGH("Tinggi", 3)
}

enum class TaskCategory(val label: String) {
    PERSONAL("Pribadi"),
    STUDY("Kuliah"),
    WORK("Kerja"),
    SHOPPING("Belanja")
}

data class SubTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val isDone: Boolean = false
)

/**
 * Room database entity representing a single task item in the "tasks" table (Schema v2).
 */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "isCompleted")
    val isCompleted: Boolean = false,

    @ColumnInfo(name = "isPinned")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "priority")
    val priority: TaskPriority = TaskPriority.MEDIUM,

    @ColumnInfo(name = "category")
    val category: TaskCategory = TaskCategory.PERSONAL,

    @ColumnInfo(name = "dueDateMillis")
    val dueDateMillis: Long? = null,

    @ColumnInfo(name = "subtasksRaw")
    val subtasksRaw: String = "",

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis()
) {
    @Ignore
    val subtasks: List<SubTask> = TaskTypeConverters().toSubtaskList(subtasksRaw)
}
