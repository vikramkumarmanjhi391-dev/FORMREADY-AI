package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.database.FolderEntity
import com.example.data.database.FolderWithCount
import com.example.data.database.LockerDocumentEntity
import com.example.data.database.VaultNoteEntity
import com.example.data.repository.DocumentLockerRepository
import com.example.data.repository.VaultNotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GlobalSearchResults(
    val query: String = "",
    val documents: List<LockerDocumentEntity> = emptyList(),
    val notes: List<VaultNoteEntity> = emptyList(),
    val folders: List<FolderWithCount> = emptyList()
)

data class HomeDashboardUiState(
    val activeFilterChip: String = "All",
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val isCreateFolderOpen: Boolean = false,
    val editingFolder: FolderEntity? = null,
    val isNotesSheetOpen: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class HomeDashboardViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DocumentLockerRepository = DocumentLockerRepository(application),
    private val notesRepository: VaultNotesRepository = VaultNotesRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeDashboardUiState())
    val uiState: StateFlow<HomeDashboardUiState> = _uiState.asStateFlow()

    val totalDocsCount: StateFlow<Int> = repository.getTotalDocumentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val importantDocsCount: StateFlow<Int> = repository.getImportantDocumentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val categoryCounts: StateFlow<Map<String, Int>> = repository.getCategoryCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val foldersWithCounts: StateFlow<List<FolderWithCount>> = repository.foldersWithCounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notesCount: StateFlow<Int> = notesRepository.notesCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val allNotes: StateFlow<List<VaultNoteEntity>> = notesRepository.allNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDocuments: StateFlow<List<LockerDocumentEntity>> = repository.allDocuments
        .map { list -> list.sortedByDescending { it.createdAt }.take(6) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<GlobalSearchResults> = _searchQuery.flatMapLatest { query ->
        if (query.isBlank()) {
            flowOf(GlobalSearchResults())
        } else {
            val q = query.trim()
            combine(
                repository.getFilteredAndSortedDocuments(q, "All", com.example.data.repository.SortOption.RECENT),
                notesRepository.searchNotes(q),
                repository.foldersWithCounts
            ) { docs, notes, folders ->
                val matchingFolders = folders.filter { it.folder.name.contains(q, ignoreCase = true) }
                GlobalSearchResults(
                    query = q,
                    documents = docs,
                    notes = notes,
                    folders = matchingFolders
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlobalSearchResults())

    fun setActiveFilterChip(chip: String) {
        _uiState.update { it.copy(activeFilterChip = chip) }
    }

    fun openSearch() {
        _uiState.update { it.copy(isSearchOpen = true) }
    }

    fun closeSearch() {
        _searchQuery.value = ""
        _uiState.update { it.copy(isSearchOpen = false, searchQuery = "") }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun openCreateFolder() {
        _uiState.update { it.copy(isCreateFolderOpen = true, editingFolder = null) }
    }

    fun openEditFolder(folder: FolderEntity) {
        _uiState.update { it.copy(isCreateFolderOpen = true, editingFolder = folder) }
    }

    fun closeFolderDialog() {
        _uiState.update { it.copy(isCreateFolderOpen = false, editingFolder = null) }
    }

    fun saveFolder(name: String, colorHex: String, iconName: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val currentEditing = _uiState.value.editingFolder
            if (currentEditing != null) {
                repository.updateFolder(
                    currentEditing.copy(
                        name = name.trim(),
                        colorHex = colorHex,
                        iconName = iconName
                    )
                )
                _uiState.update { it.copy(statusMessage = "Folder \"${name.trim()}\" updated", isCreateFolderOpen = false, editingFolder = null) }
            } else {
                repository.createFolder(name, colorHex, iconName)
                _uiState.update { it.copy(statusMessage = "Folder \"${name.trim()}\" created", isCreateFolderOpen = false) }
            }
        }
    }

    fun deleteFolder(folderId: String, folderName: String) {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
            _uiState.update { it.copy(statusMessage = "Folder \"$folderName\" deleted (documents preserved)") }
        }
    }

    fun toggleImportant(documentId: String, isImportant: Boolean) {
        viewModelScope.launch {
            repository.toggleImportant(documentId, isImportant)
        }
    }

    fun openNotesSheet() {
        _uiState.update { it.copy(isNotesSheetOpen = true) }
    }

    fun closeNotesSheet() {
        _uiState.update { it.copy(isNotesSheetOpen = false) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? Application
                    ?: throw IllegalStateException("Application missing from CreationExtras")
                return HomeDashboardViewModel(app) as T
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                throw UnsupportedOperationException("Factory requires CreationExtras with Application")
            }
        }
    }
}
