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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.FolderEntity
import com.example.data.database.FolderWithCount
import com.example.data.database.LockerDocumentEntity
import com.example.ui.components.FolderDialog
import com.example.ui.components.GlobalSearchDialog
import com.example.ui.viewmodel.HomeDashboardViewModel

data class CategoryCardInfo(
    val categoryName: String,
    val subtitle: String,
    val icon: ImageVector,
    val cardBackground: Color,
    val iconBackground: Color,
    val iconTint: Color
)

val CATEGORY_CARD_DEFINITIONS = listOf(
    CategoryCardInfo(
        categoryName = "Identity",
        subtitle = "Aadhaar, PAN, Voter, Passport",
        icon = Icons.Default.Badge,
        cardBackground = Color(0xFFEFF6FF), // Pastel Blue
        iconBackground = Color(0xFFDBEAFE),
        iconTint = Color(0xFF1D4ED8)
    ),
    CategoryCardInfo(
        categoryName = "Education",
        subtitle = "10th, 12th, Degree, Diploma",
        icon = Icons.Default.School,
        cardBackground = Color(0xFFECFDF5), // Pastel Emerald
        iconBackground = Color(0xFFD1FAE5),
        iconTint = Color(0xFF047857)
    ),
    CategoryCardInfo(
        categoryName = "Certificates",
        subtitle = "Caste, Domicile, Income, EWS",
        icon = Icons.Default.CardMembership,
        cardBackground = Color(0xFFFFFBEB), // Pastel Amber
        iconBackground = Color(0xFFFEF3C7),
        iconTint = Color(0xFFB45309)
    ),
    CategoryCardInfo(
        categoryName = "Work",
        subtitle = "Experience, Offer, Payslip",
        icon = Icons.Default.Work,
        cardBackground = Color(0xFFF5F3FF), // Pastel Purple
        iconBackground = Color(0xFFEDE9FE),
        iconTint = Color(0xFF6D28D9)
    ),
    CategoryCardInfo(
        categoryName = "Photo",
        subtitle = "Passport Photos & Signatures",
        icon = Icons.Default.PhotoCamera,
        cardBackground = Color(0xFFFDF2F8), // Pastel Pink
        iconBackground = Color(0xFFFCE7F3),
        iconTint = Color(0xFFBE185D)
    ),
    CategoryCardInfo(
        categoryName = "Banking",
        subtitle = "Passbook, Cheque, Statement",
        icon = Icons.Default.CreditCard,
        cardBackground = Color(0xFFECFEFF), // Pastel Cyan
        iconBackground = Color(0xFFCFFAFE),
        iconTint = Color(0xFF0E7490)
    ),
    CategoryCardInfo(
        categoryName = "Insurance",
        subtitle = "Health, Life, Receipts",
        icon = Icons.Default.Policy,
        cardBackground = Color(0xFFFEFCE8), // Pastel Yellow
        iconBackground = Color(0xFFFEF9C3),
        iconTint = Color(0xFFA16207)
    ),
    CategoryCardInfo(
        categoryName = "Vehicle",
        subtitle = "RC, License, PUC, Insurance",
        icon = Icons.Default.DirectionsCar,
        cardBackground = Color(0xFFF1F5F9), // Pastel Slate
        iconBackground = Color(0xFFE2E8F0),
        iconTint = Color(0xFF475569)
    ),
    CategoryCardInfo(
        categoryName = "Medical",
        subtitle = "Disability (PwD), Fitness",
        icon = Icons.Default.LocalHospital,
        cardBackground = Color(0xFFFFF1F2), // Pastel Rose
        iconBackground = Color(0xFFFFE4E6),
        iconTint = Color(0xFFBE123C)
    ),
    CategoryCardInfo(
        categoryName = "Other",
        subtitle = "General files & affidavits",
        icon = Icons.Default.Folder,
        cardBackground = Color(0xFFF8FAFC), // Neutral Gray
        iconBackground = Color(0xFFF1F5F9),
        iconTint = Color(0xFF64748B)
    )
)

val QUICK_FILTERS = listOf("All", "Important", "Identity", "Education", "Work", "Recent")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToLocker: () -> Unit,
    onNavigateToFilteredLocker: (category: String?, folderId: String?, showImportant: Boolean) -> Unit,
    onOpenNotes: () -> Unit,
    viewModel: HomeDashboardViewModel = viewModel(factory = HomeDashboardViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val totalDocs by viewModel.totalDocsCount.collectAsState()
    val importantDocs by viewModel.importantDocsCount.collectAsState()
    val categoryCounts by viewModel.categoryCounts.collectAsState()
    val foldersWithCounts by viewModel.foldersWithCounts.collectAsState()
    val notesCount by viewModel.notesCount.collectAsState()
    val recentDocs by viewModel.recentDocuments.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showAboutDialog by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<FolderWithCount?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "FORMREADY",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AI",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Offline Secure Document Vault",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.testTag("nav_drawer_open_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open navigation menu"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.openSearch() },
                        modifier = Modifier.testTag("home_search_action_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    }
                    IconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier.testTag("home_info_action_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "App Info"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Horizontal Quick Filter Chips
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(QUICK_FILTERS) { chip ->
                        val isSelected = uiState.activeFilterChip == chip
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.setActiveFilterChip(chip)
                                when (chip) {
                                    "All" -> onNavigateToLocker()
                                    "Important" -> onNavigateToFilteredLocker(null, null, true)
                                    "Recent" -> onNavigateToLocker()
                                    else -> onNavigateToFilteredLocker(chip, null, false)
                                }
                            },
                            label = {
                                Text(
                                    text = if (chip == "Important" && importantDocs > 0) "Important ($importantDocs)" else chip,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = if (chip == "Important") {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFF59E0B),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }

            // Hero Cards Row (Secure Vault & Quick Notes)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Secure Vault Banner Card
                    Card(
                        modifier = Modifier
                            .weight(1.1f)
                            .height(130.dp)
                            .clickable { onNavigateToLocker() }
                            .testTag("hero_vault_card"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF1E3A8A), Color(0xFF2563EB))
                                    )
                                )
                                .padding(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "$totalDocs Files",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                Column {
                                    Text(
                                        text = "Secure Vault",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Hardware Encrypted",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }

                    // Quick Notes Card
                    Card(
                        modifier = Modifier
                            .weight(0.9f)
                            .height(130.dp)
                            .clickable { onOpenNotes() }
                            .testTag("hero_notes_card"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF4338CA), Color(0xFF6366F1))
                                    )
                                )
                                .padding(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.EditNote,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "$notesCount Notes",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                Column {
                                    Text(
                                        text = "Vault Notes",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Confidential Details",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Custom Folders Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Folders",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${foldersWithCounts.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    TextButton(
                        onClick = { viewModel.openCreateFolder() },
                        modifier = Modifier.testTag("create_folder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Folder")
                    }
                }

                if (foldersWithCounts.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { viewModel.openCreateFolder() },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Create your first custom folder",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Group job forms, exam documents, or tax records",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(foldersWithCounts, key = { it.folder.id }) { folderWithCount ->
                            FolderCardItem(
                                folderWithCount = folderWithCount,
                                onClick = {
                                    onNavigateToFilteredLocker(null, folderWithCount.folder.id, false)
                                },
                                onEdit = { viewModel.openEditFolder(folderWithCount.folder) },
                                onDelete = { folderToDelete = folderWithCount }
                            )
                        }
                    }
                }
            }

            // Categories Section Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Document Categories",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onNavigateToLocker) {
                        Text("View Vault")
                    }
                }
            }

            // Pastel Category Cards (2 columns x 5 rows)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CATEGORY_CARD_DEFINITIONS.chunked(2).forEach { rowPair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowPair.forEach { cardInfo ->
                                val count = categoryCounts[cardInfo.categoryName] ?: 0
                                CategoryCard(
                                    modifier = Modifier.weight(1f),
                                    cardInfo = cardInfo,
                                    count = count,
                                    onClick = {
                                        onNavigateToFilteredLocker(cardInfo.categoryName, null, false)
                                    }
                                )
                            }
                            if (rowPair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Recent Documents Section
            if (recentDocs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recently Added",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onNavigateToLocker) {
                            Text("See All")
                        }
                    }
                }

                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(recentDocs, key = { it.id }) { doc ->
                            RecentDocCard(
                                doc = doc,
                                onClick = onNavigateToLocker,
                                onToggleImportant = { viewModel.toggleImportant(doc.id, !doc.isImportant) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Global Search Dialog
    if (uiState.isSearchOpen) {
        GlobalSearchDialog(
            query = uiState.searchQuery,
            onQueryChange = { viewModel.setSearchQuery(it) },
            results = searchResults,
            onDismiss = { viewModel.closeSearch() },
            onSelectDocument = {
                viewModel.closeSearch()
                onNavigateToLocker()
            },
            onSelectNote = {
                viewModel.closeSearch()
                onOpenNotes()
            },
            onSelectFolder = { folderWithCount ->
                viewModel.closeSearch()
                onNavigateToFilteredLocker(null, folderWithCount.folder.id, false)
            }
        )
    }

    // Create / Edit Folder Dialog
    if (uiState.isCreateFolderOpen) {
        FolderDialog(
            initialFolder = uiState.editingFolder,
            onDismiss = { viewModel.closeFolderDialog() },
            onSave = { name, colorHex, iconName ->
                viewModel.saveFolder(name, colorHex, iconName)
            }
        )
    }

    // Delete Folder Confirmation Dialog
    if (folderToDelete != null) {
        val folder = folderToDelete!!
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Delete Folder?") },
            text = {
                Text(
                    "Are you sure you want to delete folder \"${folder.folder.name}\"?\n\nAll ${folder.documentCount} document(s) in this folder will be kept safely in your Locker and unassigned from the folder."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFolder(folder.folder.id, folder.folder.name)
                        folderToDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete Folder")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { folderToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("About FORMREADY AI")
                }
            },
            text = {
                Column {
                    Text(
                        text = "FORMREADY AI is an offline-first document preparation suite and secure personal vault.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "• Zero Cloud Dependence: All files stay exclusively on your device.\n• Hardware Encryption: Android KeyStore AES-GCM encryption at rest.\n• Custom Folders: Seamless organization for government exam forms, job applications, and ID proofs.\n• Quick Vault Notes: Store sensitive credentials and application numbers securely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showAboutDialog = false }) {
                    Text("Got It")
                }
            }
        )
    }
}

@Composable
private fun CategoryCard(
    modifier: Modifier = Modifier,
    cardInfo: CategoryCardInfo,
    count: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(108.dp)
            .clickable(onClick = onClick)
            .testTag("category_card_${cardInfo.categoryName}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardInfo.cardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cardInfo.iconBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = cardInfo.icon,
                        contentDescription = null,
                        tint = cardInfo.iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.9f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = cardInfo.iconTint
                    )
                }
            }

            Column {
                Text(
                    text = cardInfo.categoryName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = cardInfo.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FolderCardItem(
    folderWithCount: FolderWithCount,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val color = try {
        Color(android.graphics.Color.parseColor(folderWithCount.folder.colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .width(150.dp)
            .height(110.dp)
            .clickable(onClick = onClick)
            .testTag("folder_card_${folderWithCount.folder.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Folder options",
                            tint = Color(0xFF475569)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Folder") },
                            onClick = {
                                showMenu = false
                                onEdit()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Folder", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }

            Column {
                Text(
                    text = folderWithCount.folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folderWithCount.documentCount} items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecentDocCard(
    doc: LockerDocumentEntity,
    onClick: () -> Unit,
    onToggleImportant: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(180.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = doc.fileFormat,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                IconButton(
                    onClick = onToggleImportant,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (doc.isImportant) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Star",
                        tint = if (doc.isImportant) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = doc.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "${doc.category} • ${(doc.fileSizeBytes / 1024)} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
