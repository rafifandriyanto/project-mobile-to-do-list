package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var database: AppDatabase
    private lateinit var taskDao: TaskDao
    private lateinit var repository: TaskRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskDao = database.taskDao()
        repository = TaskRepository(taskDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("TugasKu", appName)
    }

    @Test
    fun `TaskDao CRUD operations work as expected`() = runBlocking {
        // CREATE
        val id1 = taskDao.insertTask(
            Task(title = "Belajar Kotlin", description = "Materi Flow", isCompleted = false, createdAt = 1000L)
        ).toInt()
        val id2 = taskDao.insertTask(
            Task(title = "Kumpulkan laporan", description = "Bab 1-3", isCompleted = false, createdAt = 2000L)
        ).toInt()

        // READ single item by ID
        val fetched1 = taskDao.getTaskById(id1)
        assertNotNull(fetched1)
        assertEquals("Belajar Kotlin", fetched1?.title)

        // READ all tasks ordered by isCompleted ASC, createdAt DESC
        var allTasks = taskDao.getAllTasks().first()
        assertEquals(2, allTasks.size)
        assertEquals("Kumpulkan laporan", allTasks[0].title)
        assertEquals("Belajar Kotlin", allTasks[1].title)

        // UPDATE task fields and completion
        val task2 = taskDao.getTaskById(id2)!!
        taskDao.updateTask(task2.copy(title = "Kumpulkan laporan akhir", isCompleted = true))

        allTasks = taskDao.getAllTasks().first()
        assertFalse(allTasks[0].isCompleted)
        assertTrue(allTasks[1].isCompleted)
        assertEquals("Belajar Kotlin", allTasks[0].title)
        assertEquals("Kumpulkan laporan akhir", allTasks[1].title)

        // READ filtered queries
        val activeTasks = taskDao.getActiveTasks().first()
        val completedTasks = taskDao.getCompletedTasks().first()
        assertEquals(1, activeTasks.size)
        assertEquals(1, completedTasks.size)

        // UPDATE completion by ID
        taskDao.updateTaskCompletion(id1, true)
        assertEquals(2, taskDao.getCompletedTasks().first().size)

        // DELETE by entity and by ID
        taskDao.deleteTask(fetched1!!)
        assertNull(taskDao.getTaskById(id1))

        taskDao.deleteTaskById(id2)
        assertTrue(taskDao.getAllTasks().first().isEmpty())
    }
}
