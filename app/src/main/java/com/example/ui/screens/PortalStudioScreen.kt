package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.PortalSpec
import com.example.data.model.ProcessedFileResult
import com.example.data.portal.PortalDatabase
import com.example.data.portal.SmartRuleExtractor
import com.example.processing.ImageProcessor
import com.example.processing.PdfProcessor
import com.example.ui.components.DocumentRuleItemView
import com.example.ui.components.PortalCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.SaffronSecondary
import com.example.ui.theme.VerifiedGreen
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun PortalStudioScreen(
    onNavigateToPhoto: (DocumentRule?) -> Unit,
    onNavigateToSign: (DocumentRule?) -> Unit
) {
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = listOf("All", "Civil Services", "Recruitment", "Banking", "Railways", "Entrance", "RTPS / Services", "Identity")

    var selectedPortalForDetail by remember { mutableStateOf<PortalSpec?>(null) }
    var selectedRuleForPrepare by remember { mutableStateOf<DocumentRule?>(null) }

    val preparedResultsByPortal = remember { mutableStateMapOf<String, MutableMap<String, ProcessedFileResult>>() }
    var candidateName by remember { mutableStateOf("") }
    var dateOfPhoto by remember {
        mutableStateOf(java.text.SimpleDateFormat("dd-MM-yyyy", Locale.US).format(java.util.Date()))
    }

    var showExtractorDialog by remember { mutableStateOf(false) }
    var showCustomPortalDialog by remember { mutableStateOf(false) }

    // 1. Full-screen Per-Document Prepare Screen
    if (selectedPortalForDetail != null && selectedRuleForPrepare != null) {
        val activePortal = selectedPortalForDetail!!
        val activeRule = selectedRuleForPrepare!!
        PortalDocumentPrepareScreen(
            portal = activePortal,
            rule = activeRule,
            initialCandidateName = candidateName,
            initialDateOfPhoto = dateOfPhoto,
            onDocumentPrepared = { result ->
                val portalMap = preparedResultsByPortal.getOrPut(activePortal.id) { mutableStateMapOf() }
                portalMap[activeRule.id] = result
            },
            onBack = { selectedRuleForPrepare = null }
        )
        return
    }

    // 2. Full-screen Portal Detail Screen
    if (selectedPortalForDetail != null) {
        val activePortal = selectedPortalForDetail!!
        val portalResults = preparedResultsByPortal[activePortal.id] ?: emptyMap()
        PortalDetailScreen(
            portal = activePortal,
            preparedResults = portalResults,
            candidateName = candidateName,
            onCandidateNameChange = { candidateName = it },
            dateOfPhoto = dateOfPhoto,
            onDateOfPhotoChange = { dateOfPhoto = it },
            onSelectRuleToPrepare = { rule -> selectedRuleForPrepare = rule },
            onBack = { selectedPortalForDetail = null }
        )
        return
    }

    val allPortals = remember(showExtractorDialog, showCustomPortalDialog) { PortalDatabase.getAllPortals() }
    val filteredPortals = allPortals.filter { portal ->
        val matchesCategory = selectedCategory == "All" || portal.category.equals(selectedCategory, ignoreCase = true)
        val matchesQuery = searchQuery.isBlank() ||
                portal.examOrServiceName.contains(searchQuery, ignoreCase = true) ||
                portal.portalName.contains(searchQuery, ignoreCase = true) ||
                portal.department.contains(searchQuery, ignoreCase = true) ||
                portal.stateOrCentral.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesQuery
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("portal_studio_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))

            // Clean Header Row with Title & Compact Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Examination & Service Portals",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Select a portal to view official rules and prepare documents",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showExtractorDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("ai_extractor_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rule Extractor", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = { showCustomPortalDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("add_custom_portal_btn")
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Custom Portal", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Compact Single-Line Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_portal_input"),
                placeholder = {
                    Text(
                        text = "Search UPSC, SSC, BPSC, RRB, Banking...",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Non-Truncated Category Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = {
                            Text(
                                text = category,
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("portal_category_chip_${category.lowercase().replace(" ", "_")}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Portals (${filteredPortals.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))
        }

        items(filteredPortals, key = { it.id }) { portal ->
            PortalCard(
                portal = portal,
                isSelected = false,
                onClick = { selectedPortalForDetail = portal },
                onPrepareClick = { selectedPortalForDetail = portal }
            )
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // Dialog: Rule Extractor
    if (showExtractorDialog) {
        RuleExtractorDialog(
            onDismiss = { showExtractorDialog = false },
            onRulesExtracted = { newPortal ->
                PortalDatabase.addCustomPortal(newPortal)
                showExtractorDialog = false
                selectedPortalForDetail = newPortal
                Toast.makeText(context, "Extracted rules for ${newPortal.examOrServiceName}!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Dialog: Add Custom Portal
    if (showCustomPortalDialog) {
        AddCustomPortalDialog(
            onDismiss = { showCustomPortalDialog = false },
            onAddPortal = { portal ->
                PortalDatabase.addCustomPortal(portal)
                showCustomPortalDialog = false
                selectedPortalForDetail = portal
            }
        )
    }
}

@Composable
fun OneClickPreparationDialog(
    portal: PortalSpec,
    onDismiss: () -> Unit,
    onNavigateToPhoto: (DocumentRule?) -> Unit,
    onNavigateToSign: (DocumentRule?) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Store picked Uris for each document rule ID
    val pickedFilesMap = remember { mutableStateMapOf<String, Uri>() }
    val processedResultsMap = remember { mutableStateMapOf<String, ProcessedFileResult>() }
    var isProcessingAll by remember { mutableStateOf(false) }

    var candidateName by remember { mutableStateOf("") }
    var dateOfPhoto by remember { mutableStateOf("") }

    var currentlySelectingRuleId by remember { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && currentlySelectingRuleId != null) {
            pickedFilesMap[currentlySelectingRuleId!!] = uri
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "One-Click Studio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Text(
                    text = portal.examOrServiceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    StatusBadge(status = portal.verificationStatus)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Upload your genuine original documents below. The studio will automatically crop, resize, set exact dimensions, and compress every document to strictly match ${portal.portalName}'s official portal specifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // If photo requires candidate name & date strip
                    if (portal.documents.any { it.requiresNameAndDateStrip }) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Mandatory Photo Name & Date Stamp",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = candidateName,
                                    onValueChange = { candidateName = it },
                                    label = { Text("Candidate Full Name (CAPITALS)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = dateOfPhoto,
                                    onValueChange = { dateOfPhoto = it },
                                    label = { Text("Date of Photo (e.g. 15-02-2025)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }

                    Text(
                        text = "Required Documents (${portal.documents.size}):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                items(portal.documents) { rule ->
                    val isPicked = pickedFilesMap.containsKey(rule.id)
                    val result = processedResultsMap[rule.id]

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (result != null && result.isCompliant) Color(0xFFE8F5E9)
                            else MaterialTheme.colorScheme.surfaceContainer
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (result != null) EmeraldTertiary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rule.documentType.displayName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Limit: ${rule.minKb}-${rule.maxKb} KB • ${rule.allowedFormats.joinToString("/") { it.extension.uppercase() }}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (result != null) {
                                    Surface(
                                        color = EmeraldTertiary,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "Ready: ${String.format(Locale.US, "%.1f", result.fileSizeKb)} KB",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            currentlySelectingRuleId = rule.id
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isPicked) EmeraldTertiary else MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = if (isPicked) Icons.Default.Check else Icons.Default.UploadFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isPicked) "Picked" else "Select", fontSize = 11.sp)
                                    }
                                }
                            }

                            if (result != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = result.validationMessages.firstOrNull() ?: "Verified Compliant",
                                    fontSize = 11.sp,
                                    color = if (result.isCompliant) VerifiedGreen else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        isProcessingAll = true
                        portal.documents.forEach { rule ->
                            val uri = pickedFilesMap[rule.id]
                            if (uri != null) {
                                val bitmap = ImageProcessor.decodeBitmapFromUri(context, uri)
                                if (bitmap != null) {
                                    val processed = ImageProcessor.processImageForRule(
                                        context = context,
                                        originalBitmap = bitmap,
                                        rule = rule,
                                        candidateName = candidateName,
                                        dateOfPhoto = dateOfPhoto
                                    )
                                    processedResultsMap[rule.id] = processed
                                }
                            }
                        }
                        isProcessingAll = false
                        if (processedResultsMap.isNotEmpty()) {
                            Toast.makeText(context, "All ${processedResultsMap.size} documents prepared & verified!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Please select at least one document file first.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isProcessingAll,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("process_all_portal_docs_btn")
            ) {
                if (isProcessingAll) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Processing...", fontSize = 12.sp)
                } else {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Auto-Prepare All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (processedResultsMap.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        sharePreparedFiles(context, processedResultsMap.values.map { File(it.processedFilePath) })
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share / Save", fontSize = 12.sp)
                }
            } else {
                OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                    Text("Close", fontSize = 12.sp)
                }
            }
        }
    )
}

@Composable
fun RuleExtractorDialog(
    onDismiss: () -> Unit,
    onRulesExtracted: (PortalSpec) -> Unit
) {
    var notificationText by remember { mutableStateOf("") }
    var portalName by remember { mutableStateOf("") }
    var examName by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = SaffronSecondary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("AI Upload Rule Extractor", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Paste text from official advertisement PDF, recruitment notification, or portal instructions. Gemini AI & the rule engine will extract exact dimensions, file sizes, and format limits.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = portalName,
                    onValueChange = { portalName = it },
                    label = { Text("Recruitment Board / Portal Name") },
                    placeholder = { Text("e.g. Haryana HSSC / DSSSB") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = examName,
                    onValueChange = { examName = it },
                    label = { Text("Examination / Post Name") },
                    placeholder = { Text("e.g. Assistant Engineer 2025") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    label = { Text("Official URL (Optional)") },
                    placeholder = { Text("https://...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = notificationText,
                    onValueChange = { notificationText = it },
                    label = { Text("Official Upload Instructions Text") },
                    placeholder = { Text("e.g. Scanned photograph must be between 20 kb to 50 kb, dimensions 200 x 230 pixels. Signature must be 10 to 20 kb in black ink...") },
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val extractedPortal = SmartRuleExtractor.extractRulesFromNotificationText(
                        portalName = portalName,
                        examName = examName,
                        rawText = notificationText,
                        sourceUrl = sourceUrl
                    )
                    onRulesExtracted(extractedPortal)
                },
                enabled = notificationText.isNotBlank() || portalName.isNotBlank(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Extract & Build Rules")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddCustomPortalDialog(
    onDismiss: () -> Unit,
    onAddPortal: (PortalSpec) -> Unit
) {
    var portalName by remember { mutableStateOf("") }
    var examName by remember { mutableStateOf("") }
    var photoMaxKb by remember { mutableStateOf("50") }
    var photoWidth by remember { mutableStateOf("200") }
    var photoHeight by remember { mutableStateOf("230") }
    var signMaxKb by remember { mutableStateOf("20") }
    var pdfMaxKb by remember { mutableStateOf("300") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Portal Profile", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Text(
                        text = "Specify custom parameters for any government, state or university portal.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = portalName,
                        onValueChange = { portalName = it },
                        label = { Text("Portal / University Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = examName,
                        onValueChange = { examName = it },
                        label = { Text("Exam / Course Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Photo Requirements:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = photoMaxKb,
                            onValueChange = { photoMaxKb = it },
                            label = { Text("Max KB") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = photoWidth,
                            onValueChange = { photoWidth = it },
                            label = { Text("Width px") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = photoHeight,
                            onValueChange = { photoHeight = it },
                            label = { Text("Height px") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Signature & Certificate Limits:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = signMaxKb,
                            onValueChange = { signMaxKb = it },
                            label = { Text("Sign Max KB") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = pdfMaxKb,
                            onValueChange = { pdfMaxKb = it },
                            label = { Text("PDF Max KB") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pKb = photoMaxKb.toIntOrNull() ?: 50
                    val pW = photoWidth.toIntOrNull() ?: 200
                    val pH = photoHeight.toIntOrNull() ?: 230
                    val sKb = signMaxKb.toIntOrNull() ?: 20
                    val pdfKb = pdfMaxKb.toIntOrNull() ?: 300

                    val newPortal = PortalSpec(
                        id = "user_portal_${System.currentTimeMillis()}",
                        portalName = portalName.ifBlank { "Custom Portal" },
                        examOrServiceName = examName.ifBlank { "Exam" },
                        department = "Custom User Added",
                        stateOrCentral = "Custom",
                        year = "2025",
                        category = "Recruitment",
                        officialUrl = "https://custom.gov.in",
                        sourceDocumentTitle = "User Configured Rules",
                        verificationStatus = com.example.data.model.VerificationStatus.USER_SUPPLIED,
                        lastVerifiedDate = "Today",
                        documents = listOf(
                            DocumentRule(
                                id = "custom_p_${System.currentTimeMillis()}",
                                documentType = DocumentType.PASSPORT_PHOTO,
                                isRequired = true,
                                minKb = 10,
                                maxKb = pKb,
                                exactWidthPx = pW,
                                exactHeightPx = pH,
                                instructions = "Max $pKb KB, $pW x $pH px"
                            ),
                            DocumentRule(
                                id = "custom_s_${System.currentTimeMillis()}",
                                documentType = DocumentType.SIGNATURE_ENGLISH,
                                isRequired = true,
                                minKb = 5,
                                maxKb = sKb,
                                exactWidthPx = 140,
                                exactHeightPx = 60,
                                instructions = "Max $sKb KB"
                            ),
                            DocumentRule(
                                id = "custom_pdf_${System.currentTimeMillis()}",
                                documentType = DocumentType.CLASS_10_CERTIFICATE,
                                isRequired = false,
                                allowedFormats = listOf(FileFormat.PDF),
                                minKb = 50,
                                maxKb = pdfKb,
                                instructions = "PDF under $pdfKb KB"
                            )
                        )
                    )
                    onAddPortal(newPortal)
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save Portal")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Cancel")
            }
        }
    )
}

fun sharePreparedFiles(context: Context, files: List<File>) {
    try {
        val uris = ArrayList<Uri>()
        files.forEach { file ->
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            uris.add(uri)
        }

        val shareIntent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Prepared Documents"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Error sharing files: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
