package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.TugasKuTheme
import com.example.ui.theme.getSemanticColors
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ============================================================================
// 1. ENUMS & UI STATE DATA MODELS
// ============================================================================

enum class TaskFilter(val label: String) {
    ALL("Semua"),
    ACTIVE("Aktif"),
    COMPLETED("Selesai")
}

enum class TaskSortOrder(val label: String) {
    NEWEST("Terbaru dibuat"),
    PRIORITY_DESC("Prioritas tertinggi"),
    DUE_DATE_ASC("Tenggat terdekat")
}

data class TodoUiState(
    val filteredTasks: List<Task> = emptyList(),
    val currentFilter: TaskFilter = TaskFilter.ALL,
    val currentCategoryFilter: TaskCategory? = null,
    val searchQuery: String = "",
    val sortOrder: TaskSortOrder = TaskSortOrder.NEWEST,
    val allCount: Int = 0,
    val activeCount: Int = 0,
    val completedCount: Int = 0,
    val isLoading: Boolean = true
)

data class DueDateStatus(
    val label: String,
    val isOverdue: Boolean,
    val isToday: Boolean
)

// Helper to calculate due date status relative to device date
fun getDueDateStatus(dueDateMillis: Long?, isCompleted: Boolean): DueDateStatus? {
    if (dueDateMillis == null || isCompleted) return null

    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis

    val dueCal = Calendar.getInstance().apply { timeInMillis = dueDateMillis }
    dueCal.set(Calendar.HOUR_OF_DAY, 0)
    dueCal.set(Calendar.MINUTE, 0)
    dueCal.set(Calendar.SECOND, 0)
    dueCal.set(Calendar.MILLISECOND, 0)
    val dueStart = dueCal.timeInMillis

    val tomorrowStart = todayStart + 86_400_000L

    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("id-ID"))
    val formattedDate = dateFormat.format(Date(dueDateMillis))

    return when {
        dueStart < todayStart -> DueDateStatus("Terlambat ($formattedDate)", isOverdue = true, isToday = false)
        dueStart == todayStart -> DueDateStatus("Hari ini", isOverdue = false, isToday = true)
        dueStart == tomorrowStart -> DueDateStatus("Besok", isOverdue = false, isToday = false)
        else -> DueDateStatus(formattedDate, isOverdue = false, isToday = false)
    }
}

// ============================================================================
// 2. VIEWMODEL LAYER (5-Way Reactive StateFlow)
// ============================================================================

class TaskViewModel(private val repository: TaskRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _statusFilter = MutableStateFlow(TaskFilter.ALL)
    private val _categoryFilter = MutableStateFlow<TaskCategory?>(null)
    private val _sortOrder = MutableStateFlow(TaskSortOrder.NEWEST)

    private var recentlyDeletedTask: Task? = null

    val uiState: StateFlow<TodoUiState> = combine(
        repository.allTasks,
        _searchQuery,
        _statusFilter,
        _categoryFilter,
        _sortOrder
    ) { allTasks, query, statusFilter, categoryFilter, sortOrder ->

        val activeCount = allTasks.count { !it.isCompleted }
        val completedCount = allTasks.count { it.isCompleted }

        // 1. Filter by status
        var tasks = when (statusFilter) {
            TaskFilter.ALL -> allTasks
            TaskFilter.ACTIVE -> allTasks.filter { !it.isCompleted }
            TaskFilter.COMPLETED -> allTasks.filter { it.isCompleted }
        }

        // 2. Filter by category
        if (categoryFilter != null) {
            tasks = tasks.filter { it.category == categoryFilter }
        }

        // 3. Filter by search query (title, description, or subtasks)
        if (query.isNotBlank()) {
            val q = query.trim()
            tasks = tasks.filter { task ->
                task.title.contains(q, ignoreCase = true) ||
                        task.description.contains(q, ignoreCase = true) ||
                        task.subtasks.any { sub -> sub.title.contains(q, ignoreCase = true) }
            }
        }

        // 4. Sort: Pinned active tasks first, then sort rule, then completed tasks
        val activeTasks = tasks.filter { !it.isCompleted }
        val doneTasks = tasks.filter { it.isCompleted }.sortedByDescending { it.createdAt }

        val pinnedActive = activeTasks.filter { it.isPinned }
        val unpinnedActive = activeTasks.filter { !it.isPinned }

        val sortedUnpinned = when (sortOrder) {
            TaskSortOrder.NEWEST -> unpinnedActive.sortedByDescending { it.createdAt }
            TaskSortOrder.PRIORITY_DESC -> unpinnedActive.sortedWith(
                compareByDescending<Task> { it.priority.level }.thenByDescending { it.createdAt }
            )
            TaskSortOrder.DUE_DATE_ASC -> unpinnedActive.sortedWith(
                compareBy<Task> { it.dueDateMillis ?: Long.MAX_VALUE }.thenByDescending { it.createdAt }
            )
        }

        val sortedActive = pinnedActive.sortedByDescending { it.createdAt } + sortedUnpinned
        val finalTasks = sortedActive + doneTasks

        TodoUiState(
            filteredTasks = finalTasks,
            currentFilter = statusFilter,
            currentCategoryFilter = categoryFilter,
            searchQuery = query,
            sortOrder = sortOrder,
            allCount = allTasks.size,
            activeCount = activeCount,
            completedCount = completedCount,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TodoUiState()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setStatusFilter(filter: TaskFilter) {
        _statusFilter.value = filter
    }

    fun setCategoryFilter(category: TaskCategory?) {
        _categoryFilter.value = category
    }

    fun setSortOrder(sortOrder: TaskSortOrder) {
        _sortOrder.value = sortOrder
    }

    fun resetFiltersAndSearch() {
        _searchQuery.value = ""
        _statusFilter.value = TaskFilter.ALL
        _categoryFilter.value = null
        _sortOrder.value = TaskSortOrder.NEWEST
    }

    fun addTask(
        title: String,
        description: String,
        priority: TaskPriority,
        category: TaskCategory,
        dueDateMillis: Long?,
        subtasks: List<SubTask>
    ) {
        val cleanTitle = title.trim()
        val cleanDesc = description.trim().take(500)
        if (cleanTitle.isEmpty() || cleanTitle.length > 120) return

        val converters = TaskTypeConverters()
        val task = Task(
            title = cleanTitle,
            description = cleanDesc,
            priority = priority,
            category = category,
            dueDateMillis = dueDateMillis,
            subtasksRaw = converters.fromSubtaskList(subtasks)
        )
        viewModelScope.launch {
            repository.insert(task)
        }
    }

    fun updateTask(
        task: Task,
        title: String,
        description: String,
        priority: TaskPriority,
        category: TaskCategory,
        dueDateMillis: Long?,
        subtasks: List<SubTask>
    ) {
        val cleanTitle = title.trim()
        val cleanDesc = description.trim().take(500)
        if (cleanTitle.isEmpty() || cleanTitle.length > 120) return

        val converters = TaskTypeConverters()
        val updated = task.copy(
            title = cleanTitle,
            description = cleanDesc,
            priority = priority,
            category = category,
            dueDateMillis = dueDateMillis,
            subtasksRaw = converters.fromSubtaskList(subtasks)
        )
        viewModelScope.launch {
            repository.update(updated)
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            repository.update(task.copy(isCompleted = !task.isCompleted))
        }
    }

    fun toggleTaskPin(task: Task) {
        viewModelScope.launch {
            repository.update(task.copy(isPinned = !task.isPinned))
        }
    }

    fun toggleSubTask(task: Task, subTaskId: String) {
        val currentSubtasks = task.subtasks
        val updatedSubtasks = currentSubtasks.map {
            if (it.id == subTaskId) it.copy(isDone = !it.isDone) else it
        }
        val converters = TaskTypeConverters()
        viewModelScope.launch {
            repository.update(task.copy(subtasksRaw = converters.fromSubtaskList(updatedSubtasks)))
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            recentlyDeletedTask = task
            repository.delete(task)
        }
    }

    fun undoDelete() {
        val taskToRestore = recentlyDeletedTask ?: return
        viewModelScope.launch {
            repository.insert(taskToRestore)
            recentlyDeletedTask = null
        }
    }

    fun clearCompletedTasks() {
        viewModelScope.launch {
            repository.deleteCompleted()
        }
    }
}

class TaskViewModelFactory(private val repository: TaskRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaskViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TaskViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ============================================================================
// 3. MAIN UI SCREEN
// ============================================================================

@Composable
fun TodoScreen(viewModel: TaskViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var showFormDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TodoTopBar(
                completedCount = state.completedCount,
                totalCount = state.allCount,
                currentSortOrder = state.sortOrder,
                onSortOrderSelected = { viewModel.setSortOrder(it) },
                onClearCompletedClick = { showClearConfirmDialog = true }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingTask = null
                    showFormDialog = true
                },
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("add_task_fab"),
                shape = RoundedCornerShape(10.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Tambah tugas", style = MaterialTheme.typography.labelLarge) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                // F-07: Search Bar
                TaskSearchBar(
                    query = state.searchQuery,
                    onQueryChange = { viewModel.setSearchQuery(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // F-06: Status Filter Chips
                StatusFilterRow(
                    currentFilter = state.currentFilter,
                    allCount = state.allCount,
                    activeCount = state.activeCount,
                    completedCount = state.completedCount,
                    onFilterSelected = { viewModel.setStatusFilter(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // F-09: Category Filter Chips
                CategoryFilterRow(
                    currentCategory = state.currentCategoryFilter,
                    onCategorySelected = { viewModel.setCategoryFilter(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (!state.isLoading && state.filteredTasks.isEmpty()) {
                    EmptyStateView(
                        searchQuery = state.searchQuery,
                        filter = state.currentFilter,
                        category = state.currentCategoryFilter,
                        onResetAction = { viewModel.resetFiltersAndSearch() },
                        onPrimaryAction = {
                            editingTask = null
                            showFormDialog = true
                        }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.testTag("task_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 88.dp)
                    ) {
                        items(state.filteredTasks, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                onToggleComplete = { viewModel.toggleTaskCompletion(task) },
                                onTogglePin = { viewModel.toggleTaskPin(task) },
                                onToggleSubTask = { subId -> viewModel.toggleSubTask(task, subId) },
                                onEditClick = {
                                    editingTask = task
                                    showFormDialog = true
                                },
                                onDeleteClick = {
                                    viewModel.deleteTask(task)
                                    coroutineScope.launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Tugas \"${task.title}\" dihapus",
                                            actionLabel = "Urungkan",
                                            duration = SnackbarDuration.Short
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.undoDelete()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // F-02 / F-03: Add / Edit Task Form Dialog
        if (showFormDialog) {
            TaskFormDialog(
                task = editingTask,
                onDismiss = { showFormDialog = false },
                onConfirm = { title, desc, priority, category, dueDateMillis, subtasks ->
                    if (editingTask == null) {
                        viewModel.addTask(title, desc, priority, category, dueDateMillis, subtasks)
                    } else {
                        viewModel.updateTask(editingTask!!, title, desc, priority, category, dueDateMillis, subtasks)
                    }
                    showFormDialog = false
                }
            )
        }

        // F-13: Confirm Clear Completed Tasks Dialog
        if (showClearConfirmDialog) {
            ConfirmClearDialog(
                completedCount = state.completedCount,
                onDismiss = { showClearConfirmDialog = false },
                onConfirm = {
                    viewModel.clearCompletedTasks()
                    showClearConfirmDialog = false
                }
            )
        }
    }
}

// ============================================================================
// 4. UI COMPONENTS (TopBar, SearchBar, Filters, TaskCard, Dialogs)
// ============================================================================

@Composable
fun TodoTopBar(
    completedCount: Int,
    totalCount: Int,
    currentSortOrder: TaskSortOrder,
    onSortOrderSelected: (TaskSortOrder) -> Unit,
    onClearCompletedClick: () -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TugasKu",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("top_bar_title")
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Signature Progress Pill with Monospace Font
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = if (totalCount == 0) "0 tugas" else "$completedCount/$totalCount selesai",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("progress_pill_text")
                        )
                    }

                    // F-13: Sort Dropdown Button
                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier.testTag("sort_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Urutkan tugas",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            TaskSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = order.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (order == currentSortOrder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        if (order == currentSortOrder) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSortOrderSelected(order)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // F-13: Bulk Clear Completed Button
                    IconButton(
                        onClick = onClearCompletedClick,
                        enabled = completedCount > 0,
                        modifier = Modifier.testTag("clear_completed_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Bersihkan tugas selesai",
                            tint = if (completedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun TaskSearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Cari tugas, catatan, atau langkah...", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Hapus pencarian",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_bar_input")
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusFilterRow(
    currentFilter: TaskFilter,
    allCount: Int,
    activeCount: Int,
    completedCount: Int,
    onFilterSelected: (TaskFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TaskFilter.entries.forEach { filter ->
            val count = when (filter) {
                TaskFilter.ALL -> allCount
                TaskFilter.ACTIVE -> activeCount
                TaskFilter.COMPLETED -> completedCount
            }
            val isSelected = currentFilter == filter

            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(filter) },
                modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}"),
                shape = RoundedCornerShape(10.dp),
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                label = {
                    Text(
                        text = "${filter.label} ($count)",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFilterRow(
    currentCategory: TaskCategory?,
    onCategorySelected: (TaskCategory?) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Option for All Categories
        val isAllSelected = currentCategory == null
        FilterChip(
            selected = isAllSelected,
            onClick = { onCategorySelected(null) },
            shape = RoundedCornerShape(10.dp),
            label = { Text("Semua Kategori", style = MaterialTheme.typography.bodySmall) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                selectedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = isAllSelected,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                selectedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        TaskCategory.entries.forEach { category ->
            val isSelected = currentCategory == category
            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                shape = RoundedCornerShape(10.dp),
                label = { Text(category.label, style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
fun TaskCard(
    task: Task,
    onToggleComplete: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleSubTask: (String) -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val semanticColors = getSemanticColors()
    val cardBg by animateColorAsState(
        targetValue = if (task.isCompleted) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
        animationSpec = tween(150),
        label = "cardBgAnimation"
    )
    val mutedColor = MaterialTheme.colorScheme.tertiary
    val dueDateStatus = remember(task.dueDateMillis, task.isCompleted) {
        getDueDateStatus(task.dueDateMillis, task.isCompleted)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_card_${task.id}"),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(
            1.dp,
            if (task.isPinned && !task.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = task.isCompleted,
                    onCheckedChange = { onToggleComplete() },
                    modifier = Modifier
                        .testTag("task_checkbox_${task.id}")
                        .semantics {
                            contentDescription = if (task.isCompleted) {
                                "Tandai ${task.title} sebagai belum selesai"
                            } else {
                                "Tandai ${task.title} sebagai selesai"
                            }
                        }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (task.isCompleted) mutedColor else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (task.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = task.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (task.isCompleted) mutedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // F-08, F-09, F-10, F-11 Badges Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Priority Badge
                        val (pBg, pFg) = when (task.priority) {
                            TaskPriority.HIGH -> Pair(semanticColors.dangerSurface, semanticColors.danger)
                            TaskPriority.MEDIUM -> Pair(semanticColors.warningSurface, semanticColors.warning)
                            TaskPriority.LOW -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            color = pBg,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = task.priority.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = pFg,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Category Badge
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = task.category.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Due Date Badge
                        if (dueDateStatus != null) {
                            val (dBg, dFg) = when {
                                dueDateStatus.isOverdue -> Pair(semanticColors.dangerSurface, semanticColors.danger)
                                dueDateStatus.isToday -> Pair(semanticColors.warningSurface, semanticColors.warning)
                                else -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(
                                color = dBg,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = dueDateStatus.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = dFg,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Step Progress Pill
                        if (task.subtasks.isNotEmpty()) {
                            val doneCount = task.subtasks.count { it.isDone }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "$doneCount/${task.subtasks.size} langkah",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // F-12: Pin Button
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (task.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (task.isPinned) "Lepas sematan" else "Sematkan tugas",
                            tint = if (task.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit tugas",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Hapus tugas",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // F-11: Checklist Sub-tugas Inline
            if (task.subtasks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                Spacer(modifier = Modifier.height(4.dp))

                Column(
                    modifier = Modifier.padding(start = 32.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    task.subtasks.forEach { sub ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        ) {
                            Checkbox(
                                checked = sub.isDone,
                                onCheckedChange = { onToggleSubTask(sub.id) },
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = sub.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (sub.isDone) mutedColor else MaterialTheme.colorScheme.onSurface,
                                textDecoration = if (sub.isDone) TextDecoration.LineThrough else TextDecoration.None,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(
    searchQuery: String,
    filter: TaskFilter,
    category: TaskCategory?,
    onResetAction: () -> Unit,
    onPrimaryAction: () -> Unit
) {
    val isFilteringOrSearching = searchQuery.isNotBlank() || category != null || filter != TaskFilter.ALL

    val (title, body, actionLabel) = if (isFilteringOrSearching) {
        Triple(
            "Tugas tidak ditemukan",
            "Tidak ada tugas yang cocok dengan pencarian atau kombinasi filter Anda.",
            "Reset filter & pencarian"
        )
    } else {
        Triple(
            "Belum ada tugas tercatat",
            "Catat hal yang perlu Anda selesaikan hari ini agar lebih terorganisir.",
            "Buat tugas pertama"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp)
            .testTag("empty_state_view"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Assignment,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedButton(
            onClick = if (isFilteringOrSearching) onResetAction else onPrimaryAction,
            modifier = Modifier.testTag("empty_state_action_button"),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ============================================================================
// 5. DIALOGS (TaskFormDialog S-02, TaskDatePickerModal S-03, ConfirmClearDialog S-04)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormDialog(
    task: Task?,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        description: String,
        priority: TaskPriority,
        category: TaskCategory,
        dueDateMillis: Long?,
        subtasks: List<SubTask>
    ) -> Unit
) {
    var title by remember { mutableStateOf(task?.title ?: "") }
    var description by remember { mutableStateOf(task?.description ?: "") }
    var priority by remember { mutableStateOf(task?.priority ?: TaskPriority.MEDIUM) }
    var category by remember { mutableStateOf(task?.category ?: TaskCategory.PERSONAL) }
    var dueDateMillis by remember { mutableStateOf(task?.dueDateMillis) }
    var subtasks by remember { mutableStateOf(task?.subtasks ?: emptyList()) }

    var newSubtaskTitle by remember { mutableStateOf("") }
    var isTitleError by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (task == null) "Tambah tugas baru" else "Edit tugas",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("dialog_title")
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title Input
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            if (it.length <= 120) {
                                title = it
                                isTitleError = false
                            }
                        },
                        label = { Text("Judul tugas *") },
                        placeholder = { Text("Contoh: Kumpulkan laporan praktikum") },
                        isError = isTitleError,
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(if (isTitleError) "Judul tugas wajib diisi" else "")
                                Text(
                                    text = "${title.length}/120",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("task_title_input")
                    )
                }

                // Description Input
                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = {
                            if (it.length <= 500) description = it
                        },
                        label = { Text("Catatan tambahan (opsional)") },
                        placeholder = { Text("Tulis rincian atau langkah singkat...") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(10.dp),
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = "${description.length}/500",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("task_description_input")
                    )
                }

                // Priority Selection
                item {
                    Column {
                        Text(
                            text = "Tingkat Prioritas",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TaskPriority.entries.forEach { p ->
                                val isSelected = priority == p
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { priority = p },
                                    label = { Text(p.label, style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }

                // Category Selection
                item {
                    Column {
                        Text(
                            text = "Kategori",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            TaskCategory.entries.forEach { cat ->
                                val isSelected = category == cat
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { category = cat },
                                    label = { Text(cat.label, style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                )
                            }
                        }
                    }
                }

                // Due Date Selection
                item {
                    Column {
                        Text(
                            text = "Tenggat Waktu",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dateLabel = if (dueDateMillis != null) {
                                SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")).format(Date(dueDateMillis!!))
                            } else {
                                "Pilih tanggal tenggat"
                            }

                            OutlinedButton(
                                onClick = { showDatePicker = true },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CalendarToday,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(dateLabel, style = MaterialTheme.typography.bodySmall)
                            }

                            if (dueDateMillis != null) {
                                TextButton(onClick = { dueDateMillis = null }) {
                                    Text("Hapus", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                // F-11: Subtasks Checklist Editor
                item {
                    Column {
                        Text(
                            text = "Langkah Sub-tugas (${subtasks.size}/10)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        // Existing subtasks list
                        subtasks.forEachIndexed { index, sub ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = sub.isDone,
                                    onCheckedChange = { checked ->
                                        subtasks = subtasks.toMutableList().apply {
                                            this[index] = sub.copy(isDone = checked)
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = sub.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        subtasks = subtasks.filterIndexed { i, _ -> i != index }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Hapus langkah",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Add new subtask row
                        if (subtasks.size < 10) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            ) {
                                OutlinedTextField(
                                    value = newSubtaskTitle,
                                    onValueChange = { if (it.length <= 80) newSubtaskTitle = it },
                                    placeholder = { Text("Tambah langkah kecil...", style = MaterialTheme.typography.bodySmall) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        if (newSubtaskTitle.isNotBlank()) {
                                            subtasks = subtasks + SubTask(title = newSubtaskTitle.trim())
                                            newSubtaskTitle = ""
                                        }
                                    },
                                    enabled = newSubtaskTitle.isNotBlank()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Tambah langkah",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        isTitleError = true
                    } else {
                        onConfirm(title, description, priority, category, dueDateMillis, subtasks)
                    }
                },
                modifier = Modifier.testTag("confirm_task_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (task == null) "Simpan tugas" else "Simpan perubahan")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_task_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Batal", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )

    // S-03: DatePickerDialog Modal
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueDateMillis ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dueDateMillis = datePickerState.selectedDateMillis
                        showDatePicker = false
                    }
                ) {
                    Text("Pilih")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Batal")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
fun ConfirmClearDialog(
    completedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Bersihkan Tugas Selesai",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = "Apakah Anda yakin ingin menghapus $completedCount tugas yang sudah selesai secara permanen?",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Hapus $completedCount tugas")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Batal", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

// ============================================================================
// 6. MAIN ACTIVITY (Entry Point)
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = TaskRepository(database.taskDao())
        val viewModelFactory = TaskViewModelFactory(repository)

        setContent {
            TugasKuTheme {
                val taskViewModel: TaskViewModel = viewModel(factory = viewModelFactory)
                TodoScreen(viewModel = taskViewModel)
            }
        }
    }
}
