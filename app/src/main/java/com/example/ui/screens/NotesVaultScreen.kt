package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.VaultNoteEntity
import com.example.ui.components.FOLDER_COLORS
import com.example.ui.viewmodel.VaultNotesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesVaultScreen(
    onNavigateBack: () -> Unit,
    viewModel: VaultNotesViewModel = viewModel(factory = VaultNotesViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    var noteToDelete by remember { mutableStateOf<VaultNoteEntity?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Encrypted Notes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Hardware AES-GCM encrypted at rest",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openNewNote() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_note_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "New note")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search field
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search encrypted notes...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("notes_search_input")
            )

            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Note,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "No notes found" else "No encrypted notes yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Save application credentials, roll numbers, form notes, or sensitive details with hardware encryption.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                val pinnedNotes = notes.filter { it.isPinned }
                val otherNotes = notes.filter { !it.isPinned }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pinnedNotes.isNotEmpty()) {
                        item {
                            Text(
                                text = "PINNED",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                        items(pinnedNotes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                onClick = { viewModel.openViewNote(note) },
                                onEdit = { viewModel.openEditNote(note) },
                                onTogglePin = { viewModel.togglePinned(note) },
                                onDelete = { noteToDelete = note }
                            )
                        }
                    }

                    if (otherNotes.isNotEmpty()) {
                        if (pinnedNotes.isNotEmpty()) {
                            item {
                                Text(
                                    text = "OTHER NOTES",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                                )
                            }
                        }
                        items(otherNotes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                onClick = { viewModel.openViewNote(note) },
                                onEdit = { viewModel.openEditNote(note) },
                                onTogglePin = { viewModel.togglePinned(note) },
                                onDelete = { noteToDelete = note }
                            )
                        }
                    }
                }
            }
        }
    }

    // Note Editor Dialog
    if (uiState.isEditorOpen) {
        NoteEditorDialog(
            title = uiState.editorTitle,
            content = uiState.editorContent,
            colorTag = uiState.editorColor,
            isPinned = uiState.editorIsPinned,
            isSaving = uiState.isSaving,
            isEditing = uiState.editingNote != null,
            onTitleChange = { viewModel.updateEditorTitle(it) },
            onContentChange = { viewModel.updateEditorContent(it) },
            onColorChange = { viewModel.updateEditorColor(it) },
            onTogglePin = { viewModel.toggleEditorPinned() },
            onSave = { viewModel.saveNote() },
            onDismiss = { viewModel.closeEditor() }
        )
    }

    // View Note Dialog
    if (uiState.viewingNote != null && uiState.viewingContent != null) {
        val note = uiState.viewingNote!!
        val content = uiState.viewingContent!!
        AlertDialog(
            onDismissRequest = { viewModel.closeViewer() },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(content))
                    }) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit")
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Decrypted securely from vault",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val noteToEdit = note
                    viewModel.closeViewer()
                    viewModel.openEditNote(noteToEdit)
                }) {
                    Text("Edit")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.closeViewer() }) {
                    Text("Close")
                }
            }
        )
    }

    // Delete Confirmation
    if (noteToDelete != null) {
        val note = noteToDelete!!
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete Encrypted Note?") },
            text = { Text("Are you sure you want to permanently delete \"${note.title}\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteNote(note.id, note.title)
                        noteToDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { noteToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: VaultNoteEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("note_card_${note.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (note.isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options"
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                showMenu = false
                                onEdit()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(if (note.isPinned) "Unpin" else "Pin") },
                            onClick = {
                                showMenu = false
                                onTogglePin()
                            },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }

            if (note.previewSnippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = note.previewSnippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = dateFormatter.format(Date(note.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun NoteEditorDialog(
    title: String,
    content: String,
    colorTag: String,
    isPinned: Boolean,
    isSaving: Boolean,
    isEditing: Boolean,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onTogglePin: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEditing) "Edit Encrypted Note" else "New Encrypted Note",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin Note",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Encrypted in private storage with hardware KeyStore AES-GCM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("Title") },
                    placeholder = { Text("e.g. UPSC Roll Number & Password") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_title_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    label = { Text("Secure Content") },
                    placeholder = { Text("Type confidential notes, reference codes, application notes...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .testTag("note_content_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSave,
                        enabled = !isSaving && (title.isNotBlank() || content.isNotBlank()),
                        modifier = Modifier.testTag("note_save_button")
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save Encrypted")
                        }
                    }
                }
            }
        }
    }
}
