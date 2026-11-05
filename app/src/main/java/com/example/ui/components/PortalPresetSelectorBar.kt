package com.example.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DocumentType
import com.example.data.model.PortalSpec
import com.example.data.portal.PortalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared Top Portal Preset Selector Bar across Photo, Signature, and Document studios.
 * Automatically configures target Width, Height, DPI, Min KB, and Max KB for NSDL PAN,
 * UTI PAN, SSC, Railway, BPSC, CTET, Banking, and GitHub JSON-synced exam portals.
 */
@Composable
fun PortalPresetSelectorBar(
    selectedPortalId: String,
    activeSectionSummary: String,
    onPortalSelected: (PortalSpec) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var showGitHubSyncDialog by remember { mutableStateOf(false) }

    // Load cached synced portals on startup
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, "synced_portals.json")
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                try {
                    PortalDatabase.parseAndRegisterPortalsFromJson(cacheFile.readText())
                    withContext(Dispatchers.Main) { refreshTrigger++ }
                } catch (_: Exception) {}
            }
        }
    }

    val portals = remember(refreshTrigger) { PortalDatabase.getTopStudioPortals() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("portal_preset_selector_bar"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "1-CLICK TARGET PORTAL PRESET",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )
                }

                Surface(
                    onClick = { showGitHubSyncDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.testTag("github_json_sync_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Sync New Forms (GitHub JSON)",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Sync Forms (JSON)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(portals, key = { it.id }) { portal ->
                    val isSelected = portal.id == selectedPortalId
                    val shortLabel = when (portal.id) {
                        "universal_passport" -> "Universal Passport"
                        "nsdl_pan" -> "NSDL / Protean PAN"
                        "uti_pan" -> "UTI PAN Card"
                        "ssc_cgl" -> "SSC / CGL / MTS"
                        "rrb_recruitment" -> "Railway RRB"
                        "bpsc_cce" -> "BPSC / TRE Exam"
                        "ctet_exam" -> "CTET Exam"
                        "ibps_bank" -> "IBPS / SBI Bank"
                        "upsc_cse" -> "UPSC CSE"
                        else -> portal.portalName
                    }

                    FilterChip(
                        selected = isSelected,
                        onClick = { onPortalSelected(portal) },
                        label = {
                            Text(
                                text = shortLabel,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.testTag("portal_chip_${portal.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = activeSectionSummary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }

    if (showGitHubSyncDialog) {
        GitHubJsonPresetSyncDialog(
            context = context,
            onDismiss = { showGitHubSyncDialog = false },
            onSynced = { count ->
                refreshTrigger++
                showGitHubSyncDialog = false
                Toast.makeText(
                    context,
                    "Synced $count new exam form presets! Available in top bar.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun GitHubJsonPresetSyncDialog(
    context: Context,
    onDismiss: () -> Unit,
    onSynced: (Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var githubJsonUrl by remember {
        mutableStateOf("https://raw.githubusercontent.com/formready-ai/portal-presets/main/presets.json")
    }
    var isSyncing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "GitHub JSON Portal Preset Sync (₹0 Server Cost)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Fetch newly announced Government Exam & State Portal sizes (Width, Height, DPI, Min/Max KB) from a remote GitHub Raw JSON file or load the verified exam pack.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = githubJsonUrl,
                    onValueChange = { githubJsonUrl = it },
                    label = { Text("GitHub Raw JSON URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMsg!!,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val synced = PortalDatabase.parseAndRegisterPortalsFromJson(
                                PortalDatabase.DEFAULT_GITHUB_PRESETS_JSON
                            )
                            File(context.filesDir, "synced_portals.json").writeText(
                                PortalDatabase.DEFAULT_GITHUB_PRESETS_JSON
                            )
                            withContext(Dispatchers.Main) {
                                onSynced(synced.size)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("sync_builtin_exam_pack_btn"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Load Verified UP & Bihar Police Exam Pack", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        isSyncing = true
                        errorMsg = null
                        val jsonText = withContext(Dispatchers.IO) {
                            try {
                                val conn = (URL(githubJsonUrl).openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 5000
                                    readTimeout = 5000
                                    requestMethod = "GET"
                                }
                                if (conn.responseCode in 200..299) {
                                    conn.inputStream.bufferedReader().use { it.readText() }
                                } else {
                                    PortalDatabase.DEFAULT_GITHUB_PRESETS_JSON
                                }
                            } catch (_: Exception) {
                                // Offline or custom repo not yet live -> use built-in verified exam pack
                                PortalDatabase.DEFAULT_GITHUB_PRESETS_JSON
                            }
                        }
                        val parsed = withContext(Dispatchers.IO) {
                            File(context.filesDir, "synced_portals.json").writeText(jsonText)
                            PortalDatabase.parseAndRegisterPortalsFromJson(jsonText)
                        }
                        isSyncing = false
                        if (parsed.isNotEmpty()) {
                            onSynced(parsed.size)
                        } else {
                            errorMsg = "Invalid JSON format. Please check schema."
                        }
                    }
                },
                enabled = !isSyncing
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Sync Now")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
