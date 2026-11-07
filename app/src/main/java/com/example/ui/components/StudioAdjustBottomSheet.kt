package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FileFormat
import java.util.Locale

/**
 * Clean ModalBottomSheet housing advanced manual controls for Photo Studio,
 * Signature Studio, and Per-Document Portal Preparation so the main studio
 * screens remain focused on the preview and primary action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoStudioAdjustBottomSheet(
    customWidthPx: String,
    onWidthChange: (String) -> Unit,
    customHeightPx: String,
    onHeightChange: (String) -> Unit,
    lockAspectRatio: Boolean,
    onToggleLockAspect: () -> Unit,
    targetMinKb: Int,
    targetMaxKb: Int,
    onTargetMaxKbChange: (Int) -> Unit,
    customDpi: Int,
    onDpiChange: (Int) -> Unit,
    selectedFormat: FileFormat,
    onFormatChange: (FileFormat) -> Unit,
    autoWhiteBackground: Boolean,
    onToggleWhiteBackground: (Boolean) -> Unit,
    addNameDateStrip: Boolean,
    onToggleNameDateStrip: (Boolean) -> Unit,
    candidateName: String,
    onCandidateNameChange: (String) -> Unit,
    dateOfPhoto: String,
    onDateOfPhotoChange: (String) -> Unit,
    showFaceGuideOverlay: Boolean,
    onToggleFaceGuide: (Boolean) -> Unit,
    onCreateJointPhotoSign: (() -> Unit)? = null,
    onCreatePrintSheet8: (() -> Unit)? = null,
    onCreatePrintSheet12: (() -> Unit)? = null,
    onApplyAndReprocess: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("photo_adjust_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Adjust Photo Parameters",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Configure dimensions, DPI, file size bounds, and optional name/date slate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            // White background toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("White Background Replacement (#FFFFFF)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Uses on-device segmentation with edge-uncertainty check", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = autoWhiteBackground,
                    onCheckedChange = onToggleWhiteBackground,
                    modifier = Modifier.testTag("photo_white_bg_switch")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Name & Date strip toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Add Name & Date on Photo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Prints candidate name & DOP strip at bottom of photo", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = addNameDateStrip,
                    onCheckedChange = onToggleNameDateStrip,
                    modifier = Modifier.testTag("photo_name_date_switch")
                )
            }

            if (addNameDateStrip) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = candidateName,
                    onValueChange = onCandidateNameChange,
                    label = { Text("Candidate Full Name (CAPITALS)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = dateOfPhoto,
                    onValueChange = onDateOfPhotoChange,
                    label = { Text("Date of Photo (DD-MM-YYYY)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Dimensions & Aspect Lock
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customWidthPx,
                    onValueChange = { onWidthChange(it.filter { ch -> ch.isDigit() }) },
                    label = { Text("Width (px)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                IconButton(
                    onClick = onToggleLockAspect,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(
                        imageVector = if (lockAspectRatio) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Lock proportions",
                        tint = if (lockAspectRatio) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }

                OutlinedTextField(
                    value = customHeightPx,
                    onValueChange = { onHeightChange(it.filter { ch -> ch.isDigit() }) },
                    label = { Text("Height (px)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // DPI & Format
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Print Resolution (DPI)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(200, 300, 600).forEach { dpiOpt ->
                        FilterChip(
                            selected = customDpi == dpiOpt,
                            onClick = { onDpiChange(dpiOpt) },
                            label = { Text("$dpiOpt DPI", fontSize = 11.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Output Format", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedFormat == FileFormat.JPG,
                        onClick = { onFormatChange(FileFormat.JPG) },
                        label = { Text("JPG", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedFormat == FileFormat.PNG,
                        onClick = { onFormatChange(FileFormat.PNG) },
                        label = { Text("PNG", fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Target File Size Window: $targetMinKb – $targetMaxKb KB",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = targetMaxKb.toFloat(),
                onValueChange = { onTargetMaxKbChange(it.toInt()) },
                valueRange = 15f..350f,
                steps = 33,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("photo_kb_slider")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Show Face Alignment Guide", fontSize = 13.sp)
                Switch(
                    checked = showFaceGuideOverlay,
                    onCheckedChange = onToggleFaceGuide
                )
            }

            if (onCreateJointPhotoSign != null || onCreatePrintSheet8 != null || onCreatePrintSheet12 != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = "Print & Combined Upload Utilities",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onCreateJointPhotoSign != null) {
                        OutlinedButton(
                            onClick = onCreateJointPhotoSign,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("joint_photo_sign_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Photo + Sign", fontSize = 11.sp)
                        }
                    }
                    if (onCreatePrintSheet8 != null) {
                        OutlinedButton(
                            onClick = onCreatePrintSheet8,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("print_sheet_8_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("8-Photo Sheet", fontSize = 11.sp)
                        }
                    }
                }
                if (onCreatePrintSheet12 != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onCreatePrintSheet12,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("print_sheet_12_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("12-Photo Printable Sheet (300 DPI)", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    onApplyAndReprocess()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("apply_photo_adjustments_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply Changes", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureStudioAdjustBottomSheet(
    customWidthPx: String,
    onWidthChange: (String) -> Unit,
    customHeightPx: String,
    onHeightChange: (String) -> Unit,
    targetMinKb: Int,
    targetMaxKb: Int,
    onTargetMaxKbChange: (Int) -> Unit,
    customDpi: Int,
    onDpiChange: (Int) -> Unit,
    preserveInkColor: Boolean,
    onPreserveInkColorChange: (Boolean) -> Unit,
    autoCropBlankMargins: Boolean,
    onToggleAutoCropMargins: (Boolean) -> Unit,
    contrastFactor: Float,
    onContrastFactorChange: (Float) -> Unit,
    inkThreshold: Int,
    onInkThresholdChange: (Int) -> Unit,
    onApplyAndReprocess: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("signature_adjust_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Adjust Signature & Ink Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Configure Sauvola adaptive shadow removal, ink mode, dimensions, and KB target.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ink Color Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !preserveInkColor,
                        onClick = { onPreserveInkColorChange(false) },
                        label = { Text("Black Ink", fontSize = 11.sp) },
                        modifier = Modifier.testTag("sign_black_ink_chip")
                    )
                    FilterChip(
                        selected = preserveInkColor,
                        onClick = { onPreserveInkColorChange(true) },
                        label = { Text("Blue / Original", fontSize = 11.sp) },
                        modifier = Modifier.testTag("sign_blue_ink_chip")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Trim Connected Stroke Bounds", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Removes empty paper margins around ink strokes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = autoCropBlankMargins,
                    onCheckedChange = onToggleAutoCropMargins
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Ink Contrast Boost: ${String.format(Locale.US, "%.1f", contrastFactor)}x",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = contrastFactor,
                onValueChange = onContrastFactorChange,
                valueRange = 0.8f..2.2f,
                steps = 14,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Adaptive Shadow Sensitivity: $inkThreshold",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = inkThreshold.toFloat(),
                onValueChange = { onInkThresholdChange(it.toInt()) },
                valueRange = 120f..230f,
                steps = 22,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customWidthPx,
                    onValueChange = { onWidthChange(it.filter { ch -> ch.isDigit() }) },
                    label = { Text("Width (px)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = customHeightPx,
                    onValueChange = { onHeightChange(it.filter { ch -> ch.isDigit() }) },
                    label = { Text("Height (px)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Print Resolution (DPI)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(200, 300, 600).forEach { dpiOpt ->
                        FilterChip(
                            selected = customDpi == dpiOpt,
                            onClick = { onDpiChange(dpiOpt) },
                            label = { Text("$dpiOpt DPI", fontSize = 11.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Target File Size Window: $targetMinKb – $targetMaxKb KB",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = targetMaxKb.toFloat(),
                onValueChange = { onTargetMaxKbChange(it.toInt()) },
                valueRange = 5f..100f,
                steps = 19,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sign_kb_slider")
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    onApplyAndReprocess()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("apply_sign_adjustments_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply Changes", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
