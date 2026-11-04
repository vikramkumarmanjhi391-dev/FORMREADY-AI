package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.database.VaultNoteEntity
import com.example.data.repository.VaultNotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VaultNotesUiState(
    val searchQuery: String = "",
    val isEditorOpen: Boolean = false,
    val editingNote: VaultNoteEntity? = null,
    val editorTitle: String = "",
    val editorContent: String = "",
    val editorColor: String = "#F8FAFC",
    val editorIsPinned: Boolean = false,
    val isDecrypting: Boolean = false,
    val isSaving: Boolean = false,
    val viewingNote: VaultNoteEntity? = null,
    val viewingContent: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class VaultNotesViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: VaultNotesRepository = VaultNotesRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(VaultNotesUiState())
    val uiState: StateFlow<VaultNotesUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<VaultNoteEntity>> = _searchQuery.flatMapLatest { query ->
        if (query.isBlank()) {
            repository.allNotes
        } else {
            repository.searchNotes(query)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notesCount: StateFlow<Int> = repository.notesCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun openNewNote() {
        _uiState.update {
            it.copy(
                isEditorOpen = true,
                editingNote = null,
                editorTitle = "",
                editorContent = "",
                editorColor = "#F8FAFC",
                editorIsPinned = false,
                errorMessage = null
            )
        }
    }

    fun openEditNote(note: VaultNoteEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDecrypting = true, errorMessage = null) }
            val decryptedResult = repository.getDecryptedNoteContent(note.id)
            decryptedResult.fold(
                onSuccess = { content ->
                    _uiState.update {
                        it.copy(
                            isDecrypting = false,
                            isEditorOpen = true,
                            editingNote = note,
                            editorTitle = note.title,
                            editorContent = content,
                            editorColor = note.colorTag,
                            editorIsPinned = note.isPinned
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isDecrypting = false,
                            errorMessage = "Failed to decrypt note: ${err.localizedMessage}"
                        )
                    }
                }
            )
        }
    }

    fun openViewNote(note: VaultNoteEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDecrypting = true, errorMessage = null) }
            val decryptedResult = repository.getDecryptedNoteContent(note.id)
            decryptedResult.fold(
                onSuccess = { content ->
                    _uiState.update {
                        it.copy(
                            isDecrypting = false,
                            viewingNote = note,
                            viewingContent = content
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isDecrypting = false,
                            errorMessage = "Failed to decrypt note: ${err.localizedMessage}"
                        )
                    }
                }
            )
        }
    }

    fun closeViewer() {
        _uiState.update { it.copy(viewingNote = null, viewingContent = null) }
    }

    fun updateEditorTitle(title: String) {
        _uiState.update { it.copy(editorTitle = title) }
    }

    fun updateEditorContent(content: String) {
        _uiState.update { it.copy(editorContent = content) }
    }

    fun updateEditorColor(color: String) {
        _uiState.update { it.copy(editorColor = color) }
    }

    fun toggleEditorPinned() {
        _uiState.update { it.copy(editorIsPinned = !it.editorIsPinned) }
    }

    fun closeEditor() {
        _uiState.update {
            it.copy(
                isEditorOpen = false,
                editingNote = null,
                editorTitle = "",
                editorContent = "",
                editorColor = "#F8FAFC",
                editorIsPinned = false
            )
        }
    }

    fun saveNote() {
        val state = _uiState.value
        val title = state.editorTitle.trim()
        val content = state.editorContent.trim()
        if (title.isBlank() && content.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Cannot save an empty note") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val editing = state.editingNote
            if (editing != null) {
                val result = repository.updateNote(
                    noteId = editing.id,
                    title = if (title.isBlank()) "Untitled Note" else title,
                    content = content,
                    colorTag = state.editorColor,
                    isPinned = state.editorIsPinned
                )
                result.fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                isEditorOpen = false,
                                editingNote = null,
                                statusMessage = "Encrypted note updated"
                            )
                        }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = "Failed to update note: ${err.localizedMessage}"
                            )
                        }
                    }
                )
            } else {
                val result = repository.createNote(
                    title = if (title.isBlank()) "Untitled Note" else title,
                    content = content,
                    colorTag = state.editorColor,
                    isPinned = state.editorIsPinned
                )
                result.fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                isEditorOpen = false,
                                statusMessage = "Note encrypted & saved in vault"
                            )
                        }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = "Failed to save note: ${err.localizedMessage}"
                            )
                        }
                    }
                )
            }
        }
    }

    fun togglePinned(note: VaultNoteEntity) {
        viewModelScope.launch {
            repository.togglePinned(note.id, !note.isPinned)
        }
    }

    fun deleteNote(noteId: String, title: String) {
        viewModelScope.launch {
            val result = repository.deleteNote(noteId)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusMessage = "\"$title\" deleted from vault", viewingNote = null, viewingContent = null) }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(errorMessage = "Failed to delete note: ${err.localizedMessage}") }
                }
            )
        }
    }

    fun clearMessages() {
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
                return VaultNotesViewModel(app) as T
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                throw UnsupportedOperationException("Factory requires CreationExtras with Application")
            }
        }
    }
}
