package com.example

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [Task::class], version = 2, exportSchema = false)
@TypeConverters(TaskTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tugasku_database"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(DatabaseCallback(context.applicationContext))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val context: Context
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateDatabase(database.taskDao())
                    }
                }
            }

            suspend fun populateDatabase(taskDao: TaskDao) {
                val now = System.currentTimeMillis()
                val kemarin = now - 86_400_000L
                val besok = now + 86_400_000L

                val converters = TaskTypeConverters()

                val sample1 = Task(
                    title = "Presentasi proyek akhir MVVM",
                    description = "Siapkan slide dan demonstrasikan di emulator Android",
                    isCompleted = false,
                    isPinned = true,
                    priority = TaskPriority.HIGH,
                    category = TaskCategory.STUDY,
                    dueDateMillis = now,
                    subtasksRaw = converters.fromSubtaskList(
                        listOf(
                            SubTask(title = "Siapkan slide", isDone = true),
                            SubTask(title = "Cek emulator", isDone = false)
                        )
                    )
                )

                val sample2 = Task(
                    title = "Beli bahan makanan mingguan",
                    description = "Buah, sayur, telur, dan susu",
                    isCompleted = false,
                    isPinned = false,
                    priority = TaskPriority.MEDIUM,
                    category = TaskCategory.SHOPPING,
                    dueDateMillis = besok,
                    subtasksRaw = converters.fromSubtaskList(
                        listOf(
                            SubTask(title = "Apel", isDone = true),
                            SubTask(title = "Telur", isDone = false),
                            SubTask(title = "Susu", isDone = false)
                        )
                    )
                )

                val sample3 = Task(
                    title = "Laporan bulanan tim dev",
                    description = "Rekap progress sprint v2.0",
                    isCompleted = false,
                    isPinned = false,
                    priority = TaskPriority.HIGH,
                    category = TaskCategory.WORK,
                    dueDateMillis = kemarin,
                    subtasksRaw = converters.fromSubtaskList(
                        listOf(
                            SubTask(title = "Rangkum JIRA", isDone = true),
                            SubTask(title = "Kirim email", isDone = false)
                        )
                    )
                )

                val sample4 = Task(
                    title = "Olahraga pagi 30 menit",
                    description = "Jogging ringan di taman",
                    isCompleted = false,
                    isPinned = false,
                    priority = TaskPriority.LOW,
                    category = TaskCategory.PERSONAL,
                    dueDateMillis = null
                )

                taskDao.insertTask(sample1)
                taskDao.insertTask(sample2)
                taskDao.insertTask(sample3)
                taskDao.insertTask(sample4)
            }
        }
    }
}
