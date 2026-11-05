package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.CameraCaptureMode
import com.example.camera.MediaSourceSelectionBar
import com.example.camera.UnifiedMediaInput
import com.example.camera.UniversalCameraScreen
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.PreparationStatus
import com.example.data.model.ProcessedFileResult
import com.example.data.repository.DocumentLockerRepository
import com.example.processing.ImageProcessor
import com.example.processing.OneClickPreparationCoordinator
import com.example.ui.components.PortalPresetSelectorBar
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
    val description: String,
    val dpi: Int = 300,
    val defaultBlackInkOnly: Boolean = false,
    val portalId: String = "universal_passport"
)

val SIGNATURE_PRESETS = listOf(
    SignaturePreset("SSC / IBPS (140x60)", DocumentType.SIGNATURE_ENGLISH, 140, 60, 10, 20, "Exact 140x60 px, 10-20 KB", dpi = 300, portalId = "ssc_cgl"),
    SignaturePreset("NSDL PAN Sign (<20KB)", DocumentType.SIGNATURE_ENGLISH, 140, 60, 5, 20, "Protean/NSDL 300 DPI B&W <20 KB", dpi = 300, defaultBlackInkOnly = true, portalId = "nsdl_pan"),
    SignaturePreset("UTI PAN Sign (600 DPI)", DocumentType.SIGNATURE_ENGLISH, 140, 60, 5, 20, "UTIITSL 600 DPI B&W <20 KB", dpi = 600, defaultBlackInkOnly = true, portalId = "uti_pan"),
    SignaturePreset("BPSC English Sign", DocumentType.SIGNATURE_ENGLISH, 220, 100, 5, 15, "Strictly under 15 KB", dpi = 300, portalId = "bpsc_cce"),
    SignaturePreset("BPSC Hindi Sign (हस्ताक्षर)", DocumentType.SIGNATURE_HINDI, 220, 100, 5, 15, "Hindi signature under 15 KB", dpi = 300, portalId = "bpsc_cce"),
    SignaturePreset("UPSC CSE (Square)", DocumentType.SIGNATURE_ENGLISH, 400, 400, 20, 300, "1:1 ratio, 20-300 KB", dpi = 300, portalId = "upsc_cse"),
    SignaturePreset("Left Thumb (LTI)", DocumentType.THUMB_IMPRESSION_LEFT, 240, 240, 20, 50, "Left thumb impression 20-50 KB", dpi = 300, portalId = "ibps_bank"),
    SignaturePreset("Right Thumb (RTI)", DocumentType.THUMB_IMPRESSION_RIGHT, 240, 240, 20, 50, "Right thumb impression 20-50 KB", dpi = 300, portalId = "ibps_bank"),
    SignaturePreset("CTET Sign (35x15 mm)", DocumentType.SIGNATURE_ENGLISH, 350, 150, 3, 30, "3.5 x 1.5 cm format", dpi = 300, portalId = "ctet_exam"),
    SignaturePreset("Custom Dimensions", DocumentType.SIGNATURE_ENGLISH, 200, 100, 10, 30, "Custom width, height, and KB", dpi = 300, portalId = "custom")
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
    var showOriginalBefore by remember { mutableStateOf(false) }

    var selectedPortalId by remember { mutableStateOf("ssc_cgl") }
    var selectedPreset by remember { mutableStateOf(SIGNATURE_PRESETS[0]) }

    var autoCropBlankMargins by remember { mutableStateOf(true) }
    var preserveInkColor by remember { mutableStateOf(true) }
    var contrastFactor by remember { mutableFloatStateOf(1.25f) }
    var inkThreshold by remember { mutableIntStateOf(185) }

    var customWidthPx by remember { mutableStateOf((initialRule?.exactWidthPx ?: 140).toString()) }
    var customHeightPx by remember { mutableStateOf((initialRule?.exactHeightPx ?: 60).toString()) }
    var customDpi by remember { mutableIntStateOf(initialRule?.dpi ?: 300) }
    var targetMinKb by remember { mutableIntStateOf(initialRule?.minKb ?: 10) }
    var targetMaxKb by remember { mutableIntStateOf(initialRule?.maxKb ?: 20) }
    var selectedFormat by remember { mutableStateOf(FileFormat.JPG) }

    var saveStatusMessage by remember { mutableStateOf<String?>(null) }
    var isCameraOpen by remember { mutableStateOf(false) }
    var currentMediaInput by remember { mutableStateOf<UnifiedMediaInput?>(null) }

    LaunchedEffect(initialRule) {
        if (initialRule != null) {
            customWidthPx = (initialRule.exactWidthPx ?: initialRule.minWidthPx ?: 140).toString()
            customHeightPx = (initialRule.exactHeightPx ?: initialRule.minHeightPx ?: 60).toString()
            targetMinKb = initialRule.minKb
            targetMaxKb = initialRule.maxKb
            customDpi = initialRule.dpi ?: 300
        }
    }

    // Shared 1-Click Signature Auto-Processing Pipeline
    val triggerOneClickSignaturePrepare: (Bitmap, UnifiedMediaInput?) -> Unit = { bmp, mediaInput ->
        val w = customWidthPx.toIntOrNull()?.coerceAtLeast(30) ?: 140
        val h = customHeightPx.toIntOrNull()?.coerceAtLeast(20) ?: 60
        coroutineScope.launch {
            isProcessing = true
            saveStatusMessage = null
            showOriginalBefore = false

            val rule = DocumentRule(
                id = "sign_rule_${System.currentTimeMillis()}",
                documentType = selectedPreset.type,
                allowedFormats = listOf(selectedFormat),
                minKb = targetMinKb,
                maxKb = targetMaxKb,
                exactWidthPx = w,
                exactHeightPx = h,
                dpi = customDpi,
                targetAspectRatio = w.toFloat() / h.toFloat(),
                requiresWhiteBackground = true
            )

            // When captured from the in-app camera's horizontal cutout window, auto-crop strictly to the cutout box first
            val guideCrop = if (mediaInput?.isFromCamera == true) {
                mediaInput.mode.guideCropRectNorm
            } else null

            processedResult = OneClickPreparationCoordinator.autoPrepareSignature(
                context = context,
                input = mediaInput,
                rule = rule,
                preDecodedBitmap = bmp,
                customTargetKb = targetMaxKb,
                customWidthPx = w,
                customHeightPx = h,
                trimBlankMargins = autoCropBlankMargins,
                contrastMultiplier = contrastFactor,
                inkThreshold = inkThreshold,
                preserveInkColor = preserveInkColor,
                enforceProportional = true,
                cropRectNorm = guideCrop,
                customDpi = customDpi
            )
            isProcessing = false
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val previousInput = currentMediaInput
                val mediaInput = UnifiedMediaInput.fromGalleryUri(
                    uri = uri,
                    mode = if (selectedPreset.type == DocumentType.THUMB_IMPRESSION_LEFT || selectedPreset.type == DocumentType.THUMB_IMPRESSION_RIGHT)
                        CameraCaptureMode.THUMB_IMPRESSION
                    else
                        CameraCaptureMode.SIGNATURE
                )
                val bmp = mediaInput.resolveBitmap(context)
                if (bmp != null) {
                    previousInput?.cleanupPrivateCameraFileIfTemporary()
                    currentMediaInput = mediaInput
                    sourceBitmap = bmp
                    triggerOneClickSignaturePrepare(bmp, mediaInput)
                } else {
                    Toast.makeText(context, "Could not open selected signature image.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (isCameraOpen) {
        val captureMode = if (selectedPreset.type == DocumentType.THUMB_IMPRESSION_LEFT || selectedPreset.type == DocumentType.THUMB_IMPRESSION_RIGHT) {
            CameraCaptureMode.THUMB_IMPRESSION
        } else {
            CameraCaptureMode.SIGNATURE
        }
        UniversalCameraScreen(
            mode = captureMode,
            onMediaCaptured = { input ->
                isCameraOpen = false
                val previousInput = currentMediaInput
                currentMediaInput = input
                coroutineScope.launch {
                    val bmp = input.resolveBitmap(context)
                    if (bmp != null) {
                        previousInput?.cleanupPrivateCameraFileIfTemporary()
                        sourceBitmap = bmp
                        triggerOneClickSignaturePrepare(bmp, input)
                    } else {
                        input.cleanupPrivateCameraFileIfTemporary()
                        Toast.makeText(context, "Failed to decode captured signature.", Toast.LENGTH_SHORT).show()
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
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "1-Click Signature & Thumb Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Camera cutout auto-crop, adaptive shadow whitening (#FFFFFF), Black/Blue ink toggle, and Exact Min-Max KB padding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Unified Top Portal Preset Selector Bar
            PortalPresetSelectorBar(
                selectedPortalId = selectedPortalId,
                activeSectionSummary = "Active Sign Target: ${customWidthPx}×${customHeightPx} px • $customDpi DPI • $targetMinKb–$targetMaxKb KB • " +
                    if (preserveInkColor) "Blue/Original Ink" else "Pure Black Ink",
                onPortalSelected = { portalSpec ->
                    selectedPortalId = portalSpec.id
                    val signRule = portalSpec.documents.find {
                        it.documentType == DocumentType.SIGNATURE_ENGLISH ||
                        it.documentType == DocumentType.SIGNATURE_HINDI ||
                        it.documentType == DocumentType.THUMB_IMPRESSION_LEFT
                    }
                    if (signRule != null) {
                        val w = signRule.exactWidthPx ?: signRule.minWidthPx ?: 140
                        val h = signRule.exactHeightPx ?: signRule.minHeightPx ?: 60
                        customWidthPx = w.toString()
                        customHeightPx = h.toString()
                        targetMinKb = signRule.minKb
                        targetMaxKb = signRule.maxKb
                        customDpi = signRule.dpi ?: 300
                        preserveInkColor = signRule.colorMode != com.example.data.model.ColorMode.BLACK_AND_WHITE
                        SIGNATURE_PRESETS.find { it.portalId == portalSpec.id }?.let { selectedPreset = it }
                        if (sourceBitmap != null) {
                            triggerOneClickSignaturePrepare(sourceBitmap!!, currentMediaInput)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Signature Display Box with Before/After Toggle & Exact Px/KB/DPI Badges
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (sourceBitmap != null) {
                        val processedBmp = remember(processedResult?.processedFilePath) {
                            processedResult?.processedFilePath?.let { File(it) }?.takeIf { it.exists() }?.let {
                                BitmapFactory.decodeFile(it.absolutePath)
                            }
                        }
                        val displayBmp = if (showOriginalBefore || processedBmp == null) sourceBitmap else processedBmp

                        Image(
                            bitmap = displayBmp!!.asImageBitmap(),
                            contentDescription = "Signature Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentScale = ContentScale.Fit
                        )

                        // Before / After Toggle Pill (top-start)
                        if (processedResult != null) {
                            Surface(
                                onClick = { showOriginalBefore = !showOriginalBefore },
                                color = Color.Black.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .testTag("sign_before_after_toggle")
                            ) {
                                Text(
                                    text = if (showOriginalBefore) "👁 Viewing: BEFORE (Tap for After)" else "✨ Viewing: AFTER 1-Click (Tap for Before)",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }

                        // Exact Dimensions + DPI + KB Badge (bottom)
                        processedResult?.let { res ->
                            Surface(
                                color = if (res.isCompliant) EmeraldTertiary else SaffronSecondary,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "✓ ${res.widthPx}×${res.heightPx} px • ${res.dpi} DPI • ${String.format(Locale.US, "%.1f", res.fileSizeKb)} KB",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Surface(
                            color = if (processedResult != null) EmeraldTertiary else NavyPrimary,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (processedResult != null) "1-Click Ready" else "Original Sample",
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
                                text = "Capture or Select Signature / Thumb",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "1-Click Cutout Auto-Crop + Shadow Removal + Exact Min–Max KB.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            MediaSourceSelectionBar(
                                onTakePhoto = { isCameraOpen = true },
                                onChooseGallery = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                takePhotoButtonText = "Capture (1-Click Box)",
                                galleryButtonText = "Choose (Gallery)"
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Immediate Results & 1-Click Download to Gallery Card
            processedResult?.let { res ->
                val isSuccess = res.status == PreparationStatus.SUCCESS_PREPARED
                val cardBg = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                val statusColor = if (isSuccess) EmeraldTertiary else SaffronSecondary

                Card(
                    modifier = Modifier.fillMaxWidth().testTag("sign_result_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.2.dp, statusColor)
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
                                text = "${res.status.label} (${String.format(Locale.US, "%.1f", res.fileSizeKb)} KB)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Exact Output: ${res.widthPx} × ${res.heightPx} px • ${res.dpi} DPI • ${res.fileFormat.extension.uppercase()} (Allowed: $targetMinKb–$targetMaxKb KB)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

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

                        Spacer(modifier = Modifier.height(12.dp))

                        // 1-Click Direct Download to Gallery Button
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val srcFile = File(res.processedFilePath)
                                    val fileName = "FormReady_Sign_${selectedPortalId}_${res.widthPx}x${res.heightPx}_${System.currentTimeMillis()}.${res.fileFormat.extension}"
                                    val saveRes = ImageProcessor.saveFileToGalleryMediaStore(
                                        context = context,
                                        sourceFile = srcFile,
                                        displayName = fileName,
                                        mimeType = res.fileFormat.mimeType
                                    )
                                    if (saveRes.isSuccess) {
                                        saveStatusMessage = "${saveRes.getOrNull()} (${String.format(Locale.US, "%.1f", res.fileSizeKb)} KB)"
                                        Toast.makeText(context, saveStatusMessage, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Save failed: ${saveRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("instant_gallery_download_sign_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("1-CLICK DOWNLOAD READY SIGNATURE", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Save to Device & Share row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val suggestedName = "signature_${res.widthPx}x${res.heightPx}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.${res.fileFormat.extension}"
                                    saveDocumentLauncher.launch(suggestedName)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).testTag("save_signature_device_btn")
                            ) {
                                Icon(imageVector = Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save As...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    sharePreparedFiles(context, listOf(File(res.processedFilePath)))
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).testTag("share_signature_btn")
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
                                        val repo = DocumentLockerRepository(context)
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

                Spacer(modifier = Modifier.height(14.dp))
            }

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
                            selectedPortalId = preset.portalId
                            customWidthPx = preset.widthPx.toString()
                            customHeightPx = preset.heightPx.toString()
                            targetMinKb = preset.minKb
                            targetMaxKb = preset.maxKb
                            customDpi = preset.dpi
                            preserveInkColor = !preset.defaultBlackInkOnly
                            if (sourceBitmap != null) {
                                triggerOneClickSignaturePrepare(sourceBitmap!!, currentMediaInput)
                            }
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
                        text = "Adaptive Scanner & Ink Controls",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 1-Click Ink Mode Toggle: Black Ink vs Blue/Original Ink
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Scanner Ink Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = !preserveInkColor,
                                onClick = {
                                    preserveInkColor = false
                                    if (sourceBitmap != null) triggerOneClickSignaturePrepare(sourceBitmap!!, currentMediaInput)
                                },
                                label = { Text("Crisp Black Ink", fontSize = 11.sp) },
                                modifier = Modifier.testTag("sign_black_ink_chip")
                            )
                            FilterChip(
                                selected = preserveInkColor,
                                onClick = {
                                    preserveInkColor = true
                                    if (sourceBitmap != null) triggerOneClickSignaturePrepare(sourceBitmap!!, currentMediaInput)
                                },
                                label = { Text("Blue / Original Ink", fontSize = 11.sp) },
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
                            Text("Auto-Crop Blank Margins", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Detects inked boundary and removes empty outer margins", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = autoCropBlankMargins,
                            onCheckedChange = {
                                autoCropBlankMargins = it
                                if (sourceBitmap != null) triggerOneClickSignaturePrepare(sourceBitmap!!, currentMediaInput)
                            }
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
                        text = "Shadow & Paper Whitening Threshold: $inkThreshold",
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
                        text = "Exact KB Guarantee Window: $targetMinKb – $targetMaxKb KB • $customDpi DPI",
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

            // Action Buttons: One-Click Prepare Signature & Replace
            if (sourceBitmap != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val w = customWidthPx.toIntOrNull() ?: 140
                            val h = customHeightPx.toIntOrNull() ?: 60

                            if (w <= 0 || h <= 0) {
                                Toast.makeText(context, "Please enter valid positive dimensions.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            triggerOneClickSignaturePrepare(sourceBitmap!!, currentMediaInput)
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("one_click_prepare_signature_btn")
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Preparing Signature...", fontSize = 14.sp)
                        } else {
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ONE-CLICK PREPARE SIGNATURE", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isCameraOpen = true },
                            modifier = Modifier.weight(1f).testTag("signature_replace_camera_btn"),
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
                            modifier = Modifier.weight(1f).testTag("signature_replace_gallery_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Change (Gallery)", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
