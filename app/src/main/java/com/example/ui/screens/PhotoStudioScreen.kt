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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.processing.QualityInspector
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

data class PhotoPreset(
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val minKb: Int,
    val maxKb: Int,
    val description: String,
    val requiresStrip: Boolean = false,
    val ratio: Float = 3.5f / 4.5f
)

val PHOTO_PRESETS = listOf(
    PhotoPreset("Standard 35x45 mm", 350, 450, 20, 50, "Universal Indian Passport photo", ratio = 3.5f / 4.5f),
    PhotoPreset("UPSC CSE (Square)", 500, 500, 20, 300, "1:1 ratio with mandatory Name & Date", requiresStrip = true, ratio = 1.0f),
    PhotoPreset("SSC CGL/CHSL", 200, 230, 20, 50, "Exact 200x230 px, 20-50 KB", ratio = 200f / 230f),
    PhotoPreset("BPSC (Bihar)", 250, 250, 10, 25, "Strictly under 25 KB", ratio = 1.0f),
    PhotoPreset("Railway RRB", 320, 400, 30, 70, "CEN format, 320x400 px", ratio = 320f / 400f),
    PhotoPreset("IBPS / SBI Bank", 200, 230, 20, 50, "200x230 px, 20-50 KB", ratio = 200f / 230f),
    PhotoPreset("NTA Postcard (4x6)", 1200, 1800, 10, 200, "Postcard size for NEET/JEE hall", ratio = 4.0f / 6.0f),
    PhotoPreset("Custom Dimensions", 400, 500, 10, 100, "Manually configure px & KB", ratio = 4.0f / 5.0f)
)

data class AspectRatioOption(
    val label: String,
    val ratio: Float? // null for original/freeform
)

val ASPECT_RATIO_OPTIONS = listOf(
    AspectRatioOption("Original", null),
    AspectRatioOption("1:1 (Square)", 1.0f),
    AspectRatioOption("3.5:4.5 (Passport)", 3.5f / 4.5f),
    AspectRatioOption("200:230 (SSC)", 200f / 230f),
    AspectRatioOption("4:6 (Postcard)", 4f / 6f),
    AspectRatioOption("3:4 (Portrait)", 3f / 4f)
)

@Composable
fun PhotoStudioScreen(
    initialRule: DocumentRule? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedResult by remember { mutableStateOf<ProcessedFileResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var selectedPreset by remember { mutableStateOf(PHOTO_PRESETS[0]) }
    var selectedCropRatio by remember { mutableStateOf(ASPECT_RATIO_OPTIONS[2]) } // 3.5:4.5 by default

    var customWidthPx by remember { mutableStateOf("350") }
    var customHeightPx by remember { mutableStateOf("450") }
    var lockAspectRatio by remember { mutableStateOf(true) }

    var targetMinKb by remember { mutableIntStateOf(20) }
    var targetMaxKb by remember { mutableIntStateOf(50) }
    var selectedFormat by remember { mutableStateOf(FileFormat.JPG) }

    var candidateName by remember { mutableStateOf("") }
    var dateOfPhoto by remember {
        mutableStateOf(SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()))
    }
    var addNameDateStrip by remember { mutableStateOf(false) }
    var showFaceGuideOverlay by remember { mutableStateOf(true) }
    var colorMode by remember { mutableStateOf(ColorMode.COLOUR) }

    var qualityReport by remember { mutableStateOf<QualityInspector.QualityReport?>(null) }
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }
    var isCameraOpen by remember { mutableStateOf(false) }
    var currentMediaInput by remember { mutableStateOf<com.example.camera.UnifiedMediaInput?>(null) }

    // Launcher for selecting an image from Android Photo Picker (zero-permission)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val mediaInput = com.example.camera.UnifiedMediaInput.fromGalleryUri(uri, com.example.camera.CameraCaptureMode.PASSPORT_PHOTO)
                currentMediaInput = mediaInput
                val bmp = mediaInput.resolveBitmap(context)
                if (bmp != null) {
                    sourceBitmap = bmp
                    qualityReport = QualityInspector.inspectImageQuality(bmp)
                    processedResult = null
                    saveStatusMessage = null
                } else {
                    Toast.makeText(context, "Could not read image file. Please choose another.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (isCameraOpen) {
        com.example.camera.UniversalCameraScreen(
            mode = com.example.camera.CameraCaptureMode.PASSPORT_PHOTO,
            onMediaCaptured = { input ->
                isCameraOpen = false
                currentMediaInput = input
                coroutineScope.launch {
                    val bmp = input.resolveBitmap(context)
                    if (bmp != null) {
                        sourceBitmap = bmp
                        qualityReport = QualityInspector.inspectImageQuality(bmp)
                        processedResult = null
                        saveStatusMessage = null
                    } else {
                        Toast.makeText(context, "Failed to decode captured photo.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { isCameraOpen = false }
        )
        return
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
                            saveStatusMessage = "Successfully saved to device (${String.format(Locale.US, "%.1f", processedResult!!.fileSizeKb)} KB)"
                            Toast.makeText(context, saveStatusMessage, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Temporary processed file was missing. Please process again.", Toast.LENGTH_SHORT).show()
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
            .testTag("photo_studio_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "AI Passport Photo Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Auto-orient, crop aspect ratios, add mandatory Name & Date slate, and compress to exact KB limits with verified validation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Photo Preview Frame
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F5F8)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (sourceBitmap != null) {
                        val displayBmp = processedResult?.processedFilePath?.let { File(it) }?.takeIf { it.exists() }?.let {
                            android.graphics.BitmapFactory.decodeFile(it.absolutePath)
                        } ?: sourceBitmap

                        Image(
                            bitmap = displayBmp!!.asImageBitmap(),
                            contentDescription = "Photograph preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Face Guide Oval Overlay (only shown before processing to help alignment)
                        if (showFaceGuideOverlay && processedResult == null) {
                            Box(
                                modifier = Modifier
                                    .size(width = 160.dp, height = 210.dp)
                                    .border(2.dp, Color(0xCC0D47A1), RoundedCornerShape(100.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .align(Alignment.Center)
                                        .background(Color(0x99FF6F00))
                                )
                            }
                        }

                        // Badge indicating original vs processed
                        Surface(
                            color = if (processedResult != null) EmeraldTertiary else NavyPrimary,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (processedResult != null) "Processed & Verified" else "Original Selected",
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
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Select Candidate Photograph",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Photo will be automatically oriented and kept safe. Original file is never modified.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            com.example.camera.MediaSourceSelectionBar(
                                onTakePhoto = { isCameraOpen = true },
                                onChooseGallery = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                takePhotoButtonText = "Take Photo",
                                galleryButtonText = "Choose Gallery"
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // AI Quality Report
            qualityReport?.let { report ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (report.isSharp) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (report.isSharp) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (report.isSharp) VerifiedGreen else SaffronSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Photo Quality Check: Sharpness ${report.sharpnessScore}% • ${report.exposureStatus}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (report.issues.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            report.issues.forEach { issue ->
                                Text(text = "• $issue", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Portal & Size Presets
            Text(
                text = "Portal Presets & Sizes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PHOTO_PRESETS) { preset ->
                    FilterChip(
                        selected = selectedPreset.name == preset.name,
                        onClick = {
                            selectedPreset = preset
                            customWidthPx = preset.widthPx.toString()
                            customHeightPx = preset.heightPx.toString()
                            targetMinKb = preset.minKb
                            targetMaxKb = preset.maxKb
                            if (preset.requiresStrip) {
                                addNameDateStrip = true
                            }
                        },
                        label = { Text(preset.name, fontSize = 12.sp) },
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Aspect Ratio Crop Selector
            Text(
                text = "Crop Aspect Ratio",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ASPECT_RATIO_OPTIONS) { opt ->
                    FilterChip(
                        selected = selectedCropRatio.label == opt.label,
                        onClick = {
                            selectedCropRatio = opt
                            opt.ratio?.let { r ->
                                val w = customWidthPx.toIntOrNull() ?: 350
                                customHeightPx = (w / r).toInt().toString()
                            }
                        },
                        label = { Text(opt.label, fontSize = 12.sp) },
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Dimensions and Compression Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Dimensions & File Size Control",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customWidthPx,
                            onValueChange = { customWidthPx = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Width (px)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        IconButton(
                            onClick = { lockAspectRatio = !lockAspectRatio },
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
                            onValueChange = { customHeightPx = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Height (px)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Target Max File Size: $targetMaxKb KB (Allowed min: $targetMinKb KB)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Slider(
                        value = targetMaxKb.toFloat(),
                        onValueChange = { targetMaxKb = it.toInt() },
                        valueRange = 15f..350f,
                        steps = 33,
                        modifier = Modifier.fillMaxWidth().testTag("photo_kb_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Output Format", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = selectedFormat == FileFormat.JPG,
                                onClick = { selectedFormat = FileFormat.JPG },
                                label = { Text("JPG / JPEG", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = selectedFormat == FileFormat.PNG,
                                onClick = { selectedFormat = FileFormat.PNG },
                                label = { Text("PNG", fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mandatory Name & Date Strip", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = addNameDateStrip,
                            onCheckedChange = { addNameDateStrip = it }
                        )
                    }

                    if (addNameDateStrip) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = candidateName,
                            onValueChange = { candidateName = it },
                            label = { Text("Candidate Full Name (in capitals)") },
                            placeholder = { Text("e.g. VIKRAM KUMAR") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = dateOfPhoto,
                            onValueChange = { dateOfPhoto = it },
                            label = { Text("Date of Photo Capture (DD-MM-YYYY)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Face Alignment Oval Guide", fontSize = 13.sp)
                        Switch(
                            checked = showFaceGuideOverlay,
                            onCheckedChange = { showFaceGuideOverlay = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons: One-Click Auto Prepare & Replace
            if (sourceBitmap != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val w = customWidthPx.toIntOrNull() ?: 350
                            val h = customHeightPx.toIntOrNull() ?: 450

                            if (w <= 0 || h <= 0) {
                                Toast.makeText(context, "Please enter valid positive width and height.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            coroutineScope.launch {
                                isProcessing = true
                                saveStatusMessage = null

                                val rule = DocumentRule(
                                    id = "photo_rule_${System.currentTimeMillis()}",
                                    documentType = DocumentType.PASSPORT_PHOTO,
                                    allowedFormats = listOf(selectedFormat),
                                    minKb = targetMinKb,
                                    maxKb = targetMaxKb,
                                    exactWidthPx = w,
                                    exactHeightPx = h,
                                    targetAspectRatio = selectedCropRatio.ratio ?: (w.toFloat() / h.toFloat()),
                                    requiresNameAndDateStrip = addNameDateStrip,
                                    colorMode = colorMode
                                )

                                processedResult = ImageProcessor.processImageForRule(
                                    context = context,
                                    originalBitmap = sourceBitmap!!,
                                    rule = rule,
                                    candidateName = candidateName,
                                    dateOfPhoto = dateOfPhoto,
                                    customTargetKb = targetMaxKb,
                                    customWidthPx = w,
                                    customHeightPx = h,
                                    enforceProportional = lockAspectRatio
                                )
                                isProcessing = false
                            }
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("one_click_auto_prepare_btn")
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Auto Preparing Photo...", fontSize = 14.sp)
                        } else {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ONE-CLICK AUTO PREPARE", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isCameraOpen = true },
                            modifier = Modifier.weight(1f).testTag("photo_replace_camera_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retake (Camera)", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.weight(1f).testTag("photo_replace_gallery_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Change (Gallery)", fontSize = 11.sp)
                        }
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
                    modifier = Modifier.fillMaxWidth().testTag("photo_result_card"),
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
                            text = "Measured File Size: ${String.format(Locale.US, "%.1f", res.fileSizeKb)} KB (Allowed: $targetMinKb - $targetMaxKb KB)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Actual Resolution: ${res.widthPx} x ${res.heightPx} px (Aspect: ${String.format(Locale.US, "%.2f", res.aspectRatio)}) • Format: ${res.fileFormat.extension.uppercase()}",
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
                                    val suggestedName = "photo_${res.widthPx}x${res.heightPx}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.${res.fileFormat.extension}"
                                    saveDocumentLauncher.launch(suggestedName)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                                modifier = Modifier.weight(1f).testTag("save_photo_device_btn")
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
                                modifier = Modifier.testTag("share_photo_btn")
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
                                                title = "Passport Photo (${res.widthPx}x${res.heightPx})",
                                                documentType = com.example.data.model.DocumentType.PASSPORT_PHOTO,
                                                originalFileName = file.name,
                                                notes = "Generated in Photo Studio for ${selectedPreset.name}"
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
                            modifier = Modifier.fillMaxWidth().testTag("add_photo_to_locker_btn"),
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
