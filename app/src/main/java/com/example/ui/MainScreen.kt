package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DocumentRule
import com.example.ui.screens.DocumentLockerScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.NotesVaultScreen
import com.example.ui.screens.PdfMasterScreen
import com.example.ui.screens.PhotoStudioScreen
import com.example.ui.screens.PortalStudioScreen
import com.example.ui.screens.SignatureStudioScreen
import kotlinx.coroutines.launch

enum class AppNavigationTab(
    val title: String,
    val icon: ImageVector,
    val testTag: String
) {
    HOME("Home", Icons.Default.Home, "nav_home"),
    PORTALS("Portals", Icons.Default.VerifiedUser, "nav_portals"),
    PHOTO("Photo", Icons.Default.PhotoCamera, "nav_photo"),
    SIGNATURE("Signature", Icons.Default.Draw, "nav_signature"),
    PDF_MASTER("PDF Master", Icons.Default.PictureAsPdf, "nav_pdf"),
    LOCKER("Doc Locker", Icons.Default.FolderShared, "nav_locker")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var selectedTab by remember { mutableStateOf(AppNavigationTab.HOME) }
    var selectedRuleForPhoto by remember { mutableStateOf<DocumentRule?>(null) }
    var selectedRuleForSign by remember { mutableStateOf<DocumentRule?>(null) }
    var showAppInfoDialog by remember { mutableStateOf(false) }

    // Phase 4 Locker Filter Arguments from Dashboard
    var lockerFilterCategory by remember { mutableStateOf<String?>(null) }
    var lockerFilterFolderId by remember { mutableStateOf<String?>(null) }
    var lockerShowOnlyImportant by remember { mutableStateOf(false) }

    // Phase 4 Encrypted Notes Screen toggle
    var showNotesVault by remember { mutableStateOf(false) }

    // System BackHandler
    BackHandler(enabled = drawerState.isOpen || showNotesVault || selectedTab != AppNavigationTab.HOME) {
        if (drawerState.isOpen) {
            coroutineScope.launch { drawerState.close() }
        } else if (showNotesVault) {
            showNotesVault = false
        } else if (selectedTab != AppNavigationTab.HOME) {
            selectedTab = AppNavigationTab.HOME
        }
    }

    if (showNotesVault) {
        NotesVaultScreen(
            onNavigateBack = { showNotesVault = false }
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp)
            ) {
                // Drawer Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(20.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "FORMREADY AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Offline Secure Application Suite",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Drawer Nav Items
                AppNavigationTab.values().forEach { tab ->
                    NavigationDrawerItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, fontWeight = FontWeight.SemiBold) },
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            if (tab == AppNavigationTab.LOCKER) {
                                lockerFilterCategory = "All"
                                lockerFilterFolderId = null
                                lockerShowOnlyImportant = false
                            }
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Encrypted Notes Quick Item
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.EditNote, contentDescription = "Encrypted Notes") },
                    label = { Text("Vault Notes", fontWeight = FontWeight.SemiBold) },
                    badge = {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "AES-GCM",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showNotesVault = true
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                // About & Privacy Info
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = "About") },
                    label = { Text("About & Security") },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        showAppInfoDialog = true
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (selectedTab != AppNavigationTab.HOME) {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = selectedTab.title.uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open Drawer"
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { showAppInfoDialog = true },
                                modifier = Modifier.testTag("app_info_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "App Information",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag("main_bottom_nav"),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    AppNavigationTab.values().forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = {
                                selectedTab = tab
                                if (tab == AppNavigationTab.LOCKER) {
                                    lockerFilterCategory = "All"
                                    lockerFilterFolderId = null
                                    lockerShowOnlyImportant = false
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontSize = 10.sp,
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    AppNavigationTab.HOME -> {
                        HomeDashboardScreen(
                            onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                            onNavigateToLocker = {
                                lockerFilterCategory = "All"
                                lockerFilterFolderId = null
                                lockerShowOnlyImportant = false
                                selectedTab = AppNavigationTab.LOCKER
                            },
                            onNavigateToFilteredLocker = { category, folderId, showImportant ->
                                lockerFilterCategory = category
                                lockerFilterFolderId = folderId
                                lockerShowOnlyImportant = showImportant
                                selectedTab = AppNavigationTab.LOCKER
                            },
                            onOpenNotes = {
                                showNotesVault = true
                            }
                        )
                    }
                    AppNavigationTab.PORTALS -> {
                        PortalStudioScreen(
                            onNavigateToPhoto = { rule ->
                                selectedRuleForPhoto = rule
                                selectedTab = AppNavigationTab.PHOTO
                            },
                            onNavigateToSign = { rule ->
                                selectedRuleForSign = rule
                                selectedTab = AppNavigationTab.SIGNATURE
                            }
                        )
                    }
                    AppNavigationTab.PHOTO -> {
                        PhotoStudioScreen(initialRule = selectedRuleForPhoto)
                    }
                    AppNavigationTab.SIGNATURE -> {
                        SignatureStudioScreen(initialRule = selectedRuleForSign)
                    }
                    AppNavigationTab.PDF_MASTER -> {
                        PdfMasterScreen()
                    }
                    AppNavigationTab.LOCKER -> {
                        DocumentLockerScreen(
                            initialCategory = lockerFilterCategory,
                            initialFolderId = lockerFilterFolderId,
                            initialShowImportant = lockerShowOnlyImportant
                        )
                    }
                }
            }
        }
    }

    if (showAppInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            title = {
                Text(
                    text = "About & Privacy — FormReady AI",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "On-Device Document & Application Preparation Suite",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• On-Device Image & Document Privacy: All user photos, signatures, thumb impressions, and documents are processed and stored locally on your device. User documents are never uploaded to Google or any external cloud server.\n" +
                                "• Google ML Kit Terms of Service: Portrait face detection and foreground segmentation use bundled Google ML Kit components governed by the Google ML Kit Terms of Service (https://developers.google.com/ml-kit/terms). Per Google's documentation, the ML Kit SDK may contact Google servers periodically for service configuration, compatibility checks, and operational/performance telemetry.\n" +
                                "• Open-Source Libraries: CameraX, Jetpack Compose, Room, Coroutines, Security-Crypto, OkHttp, and Moshi are used under the Apache License 2.0.\n" +
                                "• Hardware AES-GCM Encrypted Document Vault with local Room database storage.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAppInfoDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Close")
                }
            }
        )
    }
}
