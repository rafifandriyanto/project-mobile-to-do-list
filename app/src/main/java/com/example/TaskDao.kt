package com.example

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    // READ: Observe all tasks
    @Query("SELECT * FROM tasks")
    fun getAllTasks(): Flow<List<Task>>

    // READ: Fetch a single task by its ID
    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Int): Task?

    // CREATE: Insert a new task (or replace on conflict) and return its row ID
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    // UPDATE: Update an existing task
    @Update
    suspend fun updateTask(task: Task)

    // DELETE: Delete a specific task entity
    @Delete
    suspend fun deleteTask(task: Task)

    // DELETE: Delete all completed tasks
    @Query("DELETE FROM tasks WHERE isCompleted = 1")
    suspend fun deleteCompletedTasks()
}
