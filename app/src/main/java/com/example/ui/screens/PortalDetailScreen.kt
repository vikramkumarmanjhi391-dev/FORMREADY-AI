package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DocumentRule
import com.example.data.model.PortalSpec
import com.example.data.model.ProcessedFileResult
import com.example.data.portal.OfficialDomainAllowlist
import com.example.ui.components.StatusBadge
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.SaffronSecondary
import com.example.ui.theme.VerifiedGreen
import java.io.File
import java.util.Locale

/**
 * Phase 4 Full-Screen Portal Detail Screen.
 * Replaces the cramped modal dialog with a dedicated, auditable view of the portal's
 * provenance metadata, non-destructive 180-day review status, and required document rules.
 */
@Composable
fun PortalDetailScreen(
    portal: PortalSpec,
    preparedResults: Map<String, ProcessedFileResult>,
    candidateName: String,
    onCandidateNameChange: (String) -> Unit,
    dateOfPhoto: String,
    onDateOfPhotoChange: (String) -> Unit,
    onSelectRuleToPrepare: (DocumentRule) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val provenance = OfficialDomainAllowlist.evaluatePortalProvenance(portal)

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("portal_detail_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))

            // Top Navigation Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("portal_detail_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Portals"
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = portal.examOrServiceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${portal.department} • ${portal.stateOrCentral} (${portal.recruitmentCycle})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Official Provenance & Governance Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("portal_provenance_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusBadge(status = provenance.effectiveStatus)
                        Text(
                            text = "Checked: ${portal.lastVerifiedDate}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Source: ${portal.sourceDocumentTitle}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!portal.notificationNumber.isNullOrBlank()) {
                        Text(
                            text = "Notification: ${portal.notificationNumber}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Portal URL: ${portal.officialUrl}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (provenance.reviewNote != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFFFFF8E1),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, SaffronSecondary.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = SaffronSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = provenance.reviewNote,
                                    fontSize = 11.sp,
                                    color = Color(0xFF5D4037)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Candidate Name & Date of Photo fields if any document rule requires a strip
            if (portal.documents.any { it.requiresNameAndDateStrip }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Candidate Name & Date of Photo (For Photo Strip)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = candidateName,
                                onValueChange = onCandidateNameChange,
                                label = { Text("Name (CAPITALS)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = dateOfPhoto,
                                onValueChange = onDateOfPhotoChange,
                                label = { Text("DOP (DD-MM-YYYY)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Required Documents (${portal.documents.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${preparedResults.values.count { it.isCompliant }} / ${portal.documents.size} Verified",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (preparedResults.values.count { it.isCompliant } == portal.documents.size) VerifiedGreen else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        items(portal.documents, key = { it.id }) { rule ->
            val result = preparedResults[rule.id]
            val isCompliant = result?.isCompliant == true

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .testTag("portal_doc_card_${rule.id}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        result == null -> MaterialTheme.colorScheme.surface
                        isCompliant -> Color(0xFFE8F5E9)
                        else -> Color(0xFFFFF3E0)
                    }
                ),
                border = BorderStroke(
                    1.dp,
                    when {
                        result == null -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        isCompliant -> EmeraldTertiary
                        else -> SaffronSecondary
                    }
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.documentType.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val formatStr = rule.allowedFormats.joinToString("/") { it.extension.uppercase() }
                            val dimText = when {
                                rule.exactWidthPx != null && rule.exactHeightPx != null ->
                                    "${rule.exactWidthPx}×${rule.exactHeightPx} px"
                                rule.maxWidthPx != null && rule.maxHeightPx != null ->
                                    "Max ${rule.maxWidthPx}×${rule.maxHeightPx} px"
                                else -> "Standard aspect"
                            }
                            val dpiText = rule.dpi?.let { " • $it DPI" } ?: ""
                            Text(
                                text = "$dimText • ${rule.minKb}–${rule.maxKb} KB • $formatStr$dpiText",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (rule.instructions.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = rule.instructions,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onSelectRuleToPrepare(rule) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCompliant) EmeraldTertiary else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("prepare_doc_rule_${rule.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (result != null) "Re-Prepare" else "Prepare",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (result != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isCompliant) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isCompliant) VerifiedGreen else SaffronSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${result.status.label}: ${result.widthPx}×${result.heightPx} px • ${String.format(Locale.US, "%.1f", result.fileSizeKb)} KB • ${result.dpi} DPI",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isCompliant) VerifiedGreen else SaffronSecondary
                            )
                        }
                    }
                }
            }
        }

        if (preparedResults.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        val files = preparedResults.values
                            .map { File(it.processedFilePath) }
                            .filter { it.exists() }
                        if (files.isNotEmpty()) {
                            sharePreparedFiles(context, files)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("share_all_portal_docs_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share All Prepared Documents (${preparedResults.size})", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
