package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DocumentRule
import com.example.ui.screens.DocumentLockerScreen
import com.example.ui.screens.PdfMasterScreen
import com.example.ui.screens.PhotoStudioScreen
import com.example.ui.screens.PortalStudioScreen
import com.example.ui.screens.SignatureStudioScreen

enum class AppNavigationTab(
    val title: String,
    val icon: ImageVector,
    val testTag: String
) {
    PORTALS("Portals", Icons.Default.VerifiedUser, "nav_portals"),
    PHOTO("Photo", Icons.Default.PhotoCamera, "nav_photo"),
    SIGNATURE("Signature", Icons.Default.Draw, "nav_signature"),
    PDF_MASTER("PDF Master", Icons.Default.PictureAsPdf, "nav_pdf"),
    LOCKER("Doc Locker", Icons.Default.FolderShared, "nav_locker")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(AppNavigationTab.PORTALS) }
    var selectedRuleForPhoto by remember { mutableStateOf<DocumentRule?>(null) }
    var selectedRuleForSign by remember { mutableStateOf<DocumentRule?>(null) }
    var showAppInfoDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "FORMREADY AI",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
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
                    DocumentLockerScreen()
                }
            }
        }
    }

    if (showAppInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            title = {
                Text(
                    text = "About FormReady AI",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        text = "Smart Document & Application Studio for Indian Recruitment & Competitive Examinations.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
                    Text(
                        text = "• Built specifically for UPSC, SSC, BPSC, RRB Railways, IBPS Banking, NTA JEE/NEET, Bihar RTPS, Passport Seva, and state recruitment portals.\n" +
                                "• Automatically enforces exact pixel dimensions, aspect ratios, file formats, and strict KB ranges.\n" +
                                "• Smart signature blank-space trimmer and paper contrast whitening.\n" +
                                "• Multi-page certificate PDF generator & compressor.\n" +
                                "• All processing is done locally on device with zero data leakage. Original files are never modified.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAppInfoDialog = false },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text("Close")
                }
            }
        )
    }
}
