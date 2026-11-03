package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.PreparationStatus
import com.example.data.model.ProcessedFileResult
import com.example.processing.ImageProcessor
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.SaffronSecondary
import com.example.ui.theme.VerifiedGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SignaturePreset(
    val title: String,
    val type: DocumentType,
    val widthPx: Int,
    val heightPx: Int,
    val minKb: Int,
    val maxKb: Int,
    val description: String
)

val SIGNATURE_PRESETS = listOf(
    SignaturePreset("SSC / IBPS (140x60)", DocumentType.SIGNATURE_ENGLISH, 140, 60, 10, 20, "Exact 140x60 px, 10-20 KB"),
    SignaturePreset("UPSC CSE (Square)", DocumentType.SIGNATURE_ENGLISH, 400, 400, 20, 300, "1:1 ratio, 20-300 KB"),
    SignaturePreset("BPSC English Sign", DocumentType.SIGNATURE_ENGLISH, 220, 100, 5, 15, "Strictly under 15 KB"),
    SignaturePreset("BPSC Hindi Sign (हस्ताक्षर)", DocumentType.SIGNATURE_HINDI, 220, 100, 5, 15, "Hindi signature under 15 KB"),
    SignaturePreset("Left Thumb (LTI)", DocumentType.THUMB_IMPRESSION_LEFT, 240, 240, 20, 50, "Left thumb impression 20-50 KB"),
    SignaturePreset("Right Thumb (RTI)", DocumentType.THUMB_IMPRESSION_RIGHT, 240, 240, 20, 50, "Right thumb impression 20-50 KB"),
    SignaturePreset("CTET Sign (35x15 mm)", DocumentType.SIGNATURE_ENGLISH, 350, 150, 3, 30, "3.5 x 1.5 cm format"),
    SignaturePreset("Custom Dimensions", DocumentType.SIGNATURE_ENGLISH, 200, 100, 10, 30, "Custom width, height, and KB")
)

@Composable
fun SignatureStudioScreen(
    initialRule: DocumentRule? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedResult by remember { mutableStateOf<ProcessedFileResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var selectedPreset by remember { mutableStateOf(SIGNATURE_PRESETS[0]) }

    var autoCropBlankMargins by remember { mutableStateOf(true) }
    var preserveInkColor by remember { mutableStateOf(true) }
    var contrastFactor by remember { mutableFloatStateOf(1.2f) }
    var inkThreshold by remember { mutableIntStateOf(180) }

    var customWidthPx by remember { mutableStateOf("140") }
    var customHeightPx by remember { mutableStateOf("60") }
    var targetMinKb by remember { mutableIntStateOf(10) }
    var targetMaxKb by remember { mutableIntStateOf(20) }
    var selectedFormat by remember { mutableStateOf(FileFormat.JPG) }

    var saveStatusMessage by remember { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val bmp = ImageProcessor.decodeBitmapFromUri(context, uri)
                if (bmp != null) {
                    sourceBitmap = bmp
                    processedResult = null
                    saveStatusMessage = null
                } else {
                    Toast.makeText(context, "Could not open selected signature image.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Storage Access Framework: Save to Device
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(selectedFormat.mimeType)
    ) { destinationUri: Uri? ->
        if (destinationUri != null && processedResult != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val srcFile = File(processedResult!!.processedFilePath)
                    if (srcFile.exists()) {
                        context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                            FileInputStream(srcFile).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            saveStatusMessage = "Successfully saved signature to device (${String.format(Locale.US, "%.1f", processedResult!!.fileSizeKb)} KB)"
                            Toast.makeText(context, saveStatusMessage, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Processed file not found. Please reprocess.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("signature_studio_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Signature & Thumb Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Auto-crop blank white margins, clean paper shadows, darken ink without losing fine strokes, and compress strictly to portal limits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Signature Display Box
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (sourceBitmap != null) {
                        val displayBmp = processedResult?.processedFilePath?.let { File(it) }?.takeIf { it.exists() }?.let {
                            android.graphics.BitmapFactory.decodeFile(it.absolutePath)
                        } ?: sourceBitmap

                        Image(
                            bitmap = displayBmp!!.asImageBitmap(),
                            contentDescription = "Signature Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentScale = ContentScale.Fit
                        )

                        Surface(
                            color = if (processedResult != null) EmeraldTertiary else NavyPrimary,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (processedResult != null) "Cleaned & Verified" else "Original Sample",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(54.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Draw,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Select Scanned Signature or Thumb Impression",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Blue/black pen ink on clean white paper. Original file is never modified.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("select_signature_btn")
                            ) {
                                Text("Choose Signature File", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Preset Selection
            Text(
                text = "Preset Format & Examination",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SIGNATURE_PRESETS) { preset ->
                    FilterChip(
                        selected = selectedPreset.title == preset.title,
                        onClick = {
                            selectedPreset = preset
                            customWidthPx = preset.widthPx.toString()
                            customHeightPx = preset.heightPx.toString()
                            targetMinKb = preset.minKb
                            targetMaxKb = preset.maxKb
                        },
                        label = { Text(preset.title, fontSize = 12.sp) },
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Signature Tuning & Controls
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Signature Cleanup & Controls",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Crop Blank Margins", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Detects inked boundary and removes empty outer margins", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = autoCropBlankMargins,
                            onCheckedChange = { autoCropBlankMargins = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Preserve Pen Ink Color", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Whitens paper background while keeping blue/black ink tones", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = preserveInkColor,
                            onCheckedChange = { preserveInkColor = it }
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
                        onValueChange = { contrastFactor = it },
                        valueRange = 0.8f..2.2f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Paper Whitening Threshold: $inkThreshold",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = inkThreshold.toFloat(),
                        onValueChange = { inkThreshold = it.toInt() },
                        valueRange = 120f..230f,
                        steps = 22,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customWidthPx,
                            onValueChange = { customWidthPx = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Width (px)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = customHeightPx,
                            onValueChange = { customHeightPx = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Height (px)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Target KB Range: $targetMinKb - $targetMaxKb KB",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Slider(
                        value = targetMaxKb.toFloat(),
                        onValueChange = { targetMaxKb = it.toInt() },
                        valueRange = 5f..100f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth().testTag("sign_kb_slider")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            if (sourceBitmap != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val w = customWidthPx.toIntOrNull() ?: 140
                            val h = customHeightPx.toIntOrNull() ?: 60

                            if (w <= 0 || h <= 0) {
                                Toast.makeText(context, "Please enter valid positive dimensions.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            coroutineScope.launch {
                                isProcessing = true
                                saveStatusMessage = null

                                val rule = DocumentRule(
                                    id = "sign_rule_${System.currentTimeMillis()}",
                                    documentType = selectedPreset.type,
                                    allowedFormats = listOf(selectedFormat),
                                    minKb = targetMinKb,
                                    maxKb = targetMaxKb,
                                    exactWidthPx = w,
                                    exactHeightPx = h,
                                    targetAspectRatio = w.toFloat() / h.toFloat(),
                                    requiresWhiteBackground = true
                                )

                                processedResult = ImageProcessor.processImageForRule(
                                    context = context,
                                    originalBitmap = sourceBitmap!!,
                                    rule = rule,
                                    customTargetKb = targetMaxKb,
                                    customWidthPx = w,
                                    customHeightPx = h,
                                    autoCropMargins = autoCropBlankMargins,
                                    contrastFactor = contrastFactor,
                                    inkThreshold = inkThreshold,
                                    preserveInkColor = preserveInkColor
                                )
                                isProcessing = false
                            }
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("process_signature_btn")
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Processing...", fontSize = 12.sp)
                        } else {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Process & Clean", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Replace", fontSize = 12.sp)
                    }
                }
            }

            // Results, Validation, Save to Device & Share Card
            processedResult?.let { res ->
                Spacer(modifier = Modifier.height(16.dp))

                val isSuccess = res.status == PreparationStatus.SUCCESS_PREPARED
                val cardBg = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                val statusColor = if (isSuccess) EmeraldTertiary else SaffronSecondary

                Card(
                    modifier = Modifier.fillMaxWidth().testTag("sign_result_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, statusColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = statusColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = res.status.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Measured Size: ${String.format(Locale.US, "%.1f", res.fileSizeKb)} KB (Allowed: $targetMinKb - $targetMaxKb KB)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Actual Resolution: ${res.widthPx} x ${res.heightPx} px • Format: ${res.fileFormat.extension.uppercase()}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        res.validationMessages.forEach { msg ->
                            Text(
                                text = "• $msg",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (saveStatusMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "✓ $saveStatusMessage",
                                fontSize = 12.sp,
                                color = VerifiedGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Save to Device & Share row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val suggestedName = "signature_${res.widthPx}x${res.heightPx}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.${res.fileFormat.extension}"
                                    saveDocumentLauncher.launch(suggestedName)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                                modifier = Modifier.weight(1f).testTag("save_signature_device_btn")
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save to Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    sharePreparedFiles(context, listOf(File(res.processedFilePath)))
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("share_signature_btn")
                            ) {
                                Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val repo = com.example.data.repository.DocumentLockerRepository(context)
                                        val file = File(res.processedFilePath)
                                        if (file.exists()) {
                                            val importRes = repo.importFile(
                                                file = file,
                                                title = "${selectedPreset.title} (${res.widthPx}x${res.heightPx})",
                                                documentType = selectedPreset.type,
                                                originalFileName = file.name,
                                                notes = "Generated in Signature Studio"
                                            )
                                            withContext(Dispatchers.Main) {
                                                if (importRes.isSuccess) {
                                                    Toast.makeText(context, "Saved to Personal Document Locker!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Locker save failed: ${importRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Locker error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("add_signature_to_locker_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to Document Locker Vault", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
