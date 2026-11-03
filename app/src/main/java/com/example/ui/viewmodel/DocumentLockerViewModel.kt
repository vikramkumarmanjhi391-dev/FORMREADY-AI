package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.database.LockerDocumentEntity
import com.example.data.model.DocumentType
import com.example.data.repository.DocumentLockerRepository
import com.example.data.repository.SortOption
import com.example.data.repository.StorageReconciliationReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream

data class LockerUiState(
    val searchQuery: String = "",
    val selectedCategory: String = "All",
    val sortOption: SortOption = SortOption.RECENT,
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val reconciliationReport: StorageReconciliationReport? = null
)

class DocumentLockerViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DocumentLockerRepository = DocumentLockerRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LockerUiState())
    val uiState: StateFlow<LockerUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow("All")
    private val _sortOption = MutableStateFlow(SortOption.RECENT)

    @OptIn(ExperimentalCoroutinesApi::class)
    val documents: StateFlow<List<LockerDocumentEntity>> = combine(
        _searchQuery,
        _selectedCategory,
        _sortOption
    ) { query, category, sort ->
        Triple(query, category, sort)
    }.flatMapLatest { (query, category, sort) ->
        repository.getFilteredAndSortedDocuments(query, category, sort)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        reconcileStorage()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSelectedCategory(category: String) {
        _selectedCategory.value = category
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setSortOption(sortOption: SortOption) {
        _sortOption.value = sortOption
        _uiState.update { it.copy(sortOption = sortOption) }
    }

    fun reconcileStorage() {
        viewModelScope.launch {
            val report = repository.reconcileStorage()
            _uiState.update { it.copy(reconciliationReport = report) }
        }
    }

    fun checkDuplicate(uri: Uri, onResult: (LockerDocumentEntity?) -> Unit) {
        viewModelScope.launch {
            val existing = repository.checkDuplicate(uri)
            onResult(existing)
        }
    }

    fun importDocument(
        uri: Uri,
        title: String,
        documentType: DocumentType,
        notes: String = "",
        isMasked: Boolean = false,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null, statusMessage = null) }
            val result = repository.importDocument(uri, title, documentType, notes, isMasked)
            result.fold(
                onSuccess = { doc ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            statusMessage = "\"${doc.title}\" encrypted and saved to Locker!"
                        )
                    }
                    onSuccess()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = error.localizedMessage ?: "Failed to import document"
                        )
                    }
                }
            )
        }
    }

    fun updateMetadata(
        id: String,
        title: String,
        documentType: DocumentType,
        notes: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = repository.updateMetadata(id, title, documentType, notes)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusMessage = "Document details updated") }
                    onComplete(true)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Failed to update details") }
                    onComplete(false)
                }
            )
        }
    }

    fun deleteDocument(id: String, title: String) {
        viewModelScope.launch {
            val result = repository.deleteDocument(id)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusMessage = "\"$title\" permanently removed from vault") }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Failed to delete document") }
                }
            )
        }
    }

    fun exportDocumentToUri(id: String, destinationUri: Uri, onSuccess: (Long) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.exportDocumentToUri(id, destinationUri)
            result.fold(
                onSuccess = { bytesWritten ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Document successfully saved to device ($bytesWritten bytes)"
                        )
                    }
                    onSuccess(bytesWritten)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Export failed: ${error.localizedMessage ?: "Unknown error"}"
                        )
                    }
                }
            )
        }
    }

    fun exportDocument(id: String, destinationOutputStream: OutputStream, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.exportDocumentToStream(id, destinationOutputStream)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(statusMessage = "Document successfully exported to device!") }
                    onSuccess()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = "Export failed: ${error.localizedMessage}") }
                }
            )
        }
    }

    fun preparePreviewFile(id: String, onReady: (File?) -> Unit) {
        viewModelScope.launch {
            val result = repository.decryptToTempFile(id)
            result.fold(
                onSuccess = { file -> onReady(file) },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = "Cannot open document: ${error.localizedMessage}") }
                    onReady(null)
                }
            )
        }
    }

    fun prepareShareFile(id: String, onReady: (File?) -> Unit) {
        viewModelScope.launch {
            val result = repository.decryptToTempFile(id)
            result.fold(
                onSuccess = { file -> onReady(file) },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = "Cannot prepare file for sharing: ${error.localizedMessage}") }
                    onReady(null)
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
                    ?: throw IllegalStateException("Application is missing from CreationExtras")
                return DocumentLockerViewModel(app) as T
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                throw UnsupportedOperationException("Factory requires CreationExtras with Application")
            }
        }
    }
}
