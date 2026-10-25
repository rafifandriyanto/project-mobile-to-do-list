package com.example

import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {
    val allTasks: Flow<List<Task>> = taskDao.getAllTasks()

    suspend fun getById(id: Int): Task? = taskDao.getTaskById(id)

    suspend fun insert(task: Task): Long = taskDao.insertTask(task)

    suspend fun update(task: Task) = taskDao.updateTask(task)

    suspend fun delete(task: Task) = taskDao.deleteTask(task)

    suspend fun deleteCompleted() = taskDao.deleteCompletedTasks()
}
