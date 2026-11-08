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
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.PreparationStatus
import com.example.data.model.ProcessedFileResult
import com.example.data.repository.DocumentLockerRepository
import com.example.processing.ImageProcessor
import com.example.processing.OneClickPreparationCoordinator
import com.example.processing.QualityInspector
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

const val PHOTO_FALLBACK_BUTTON_LABEL = "Keep Original Background"
const val PHOTO_FALLBACK_TOAST_MESSAGE = "Processed with original background (resize & KB rules applied)"

data class PhotoPreset(
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val minKb: Int,
    val maxKb: Int,
    val description: String,
    val requiresStrip: Boolean = false,
    val ratio: Float = 3.5f / 4.5f,
    val dpi: Int = 300,
    val portalId: String = "universal_passport"
)

val PHOTO_PRESETS = listOf(
    PhotoPreset("Standard 35x45 mm", 350, 450, 20, 50, "Universal Indian Passport photo", ratio = 3.5f / 4.5f, dpi = 300, portalId = "universal_passport"),
    PhotoPreset("NSDL PAN (213x213)", 213, 213, 10, 50, "Protean/NSDL 213x213 px @ 300 DPI, <50 KB", ratio = 1.0f, dpi = 300, portalId = "nsdl_pan"),
    PhotoPreset("UTI PAN (213x213)", 213, 213, 10, 30, "UTIITSL 213x213 px @ 300 DPI, <30 KB", ratio = 1.0f, dpi = 300, portalId = "uti_pan"),
    PhotoPreset("SSC CGL/CHSL", 200, 230, 20, 50, "Exact 200x230 px, 20-50 KB", ratio = 200f / 230f, dpi = 300, portalId = "ssc_cgl"),
    PhotoPreset("Railway RRB", 320, 400, 30, 70, "CEN format, 320x400 px", ratio = 320f / 400f, dpi = 300, portalId = "rrb_recruitment"),
    PhotoPreset("BPSC (Bihar)", 250, 250, 10, 25, "Strictly under 25 KB", ratio = 1.0f, dpi = 300, portalId = "bpsc_cce"),
    PhotoPreset("UPSC CSE (Square)", 500, 500, 20, 300, "1:1 ratio with mandatory Name & Date", requiresStrip = true, ratio = 1.0f, dpi = 300, portalId = "upsc_cse"),
    PhotoPreset("IBPS / SBI Bank", 200, 230, 20, 50, "200x230 px, 20-50 KB", ratio = 200f / 230f, dpi = 300, portalId = "ibps_bank"),
    PhotoPreset("CTET (35x45 mm)", 350, 450, 10, 100, "3.5x4.5 cm, 10-100 KB", ratio = 3.5f / 4.5f, dpi = 300, portalId = "ctet_exam"),
    PhotoPreset("NTA Postcard (4x6)", 1200, 1800, 10, 200, "Postcard size for NEET/JEE hall", ratio = 4.0f / 6.0f, dpi = 300, portalId = "nta_exams"),
    PhotoPreset("Custom Dimensions", 400, 500, 10, 100, "Manually configure px & KB", ratio = 4.0f / 5.0f, dpi = 300, portalId = "custom")
)

data class AspectRatioOption(
    val label: String,
    val ratio: Float? // null for original/freeform
)

val ASPECT_RATIO_OPTIONS = listOf(
    AspectRatioOption("Original", null),
    AspectRatioOption("1:1 (PAN/UPSC)", 1.0f),
    AspectRatioOption("3.5:4.5 (Passport)", 3.5f / 4.5f),
    AspectRatioOption("200:230 (SSC/Bank)", 200f / 230f),
    AspectRatioOption("4:6 (Postcard)", 4f / 6f),
    AspectRatioOption("3:4 (Portrait)", 3f / 4f)
)

@Composable
fun PhotoStudioScreen(
    initialRule: DocumentRule? = null,
    initialSourceBitmap: Bitmap? = null,
    initialProcessedResult: ProcessedFileResult? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf(initialSourceBitmap) }
    var processedResult by remember { mutableStateOf(initialProcessedResult) }
    var isProcessing by remember { mutableStateOf(false) }
    var showOriginalBefore by remember { mutableStateOf(false) }

    var selectedPortalId by remember { mutableStateOf("universal_passport") }
    var selectedPreset by remember { mutableStateOf(PHOTO_PRESETS[0]) }
    var selectedCropRatio by remember { mutableStateOf(ASPECT_RATIO_OPTIONS[2]) }

    var customWidthPx by remember { mutableStateOf((initialRule?.exactWidthPx ?: 350).toString()) }
    var customHeightPx by remember { mutableStateOf((initialRule?.exactHeightPx ?: 450).toString()) }
    var customDpi by remember { mutableIntStateOf(initialRule?.dpi ?: 300) }
    var lockAspectRatio by remember { mutableStateOf(true) }

    var targetMinKb by remember { mutableIntStateOf(initialRule?.minKb ?: 20) }
    var targetMaxKb by remember { mutableIntStateOf(initialRule?.maxKb ?: 50) }
    var selectedFormat by remember { mutableStateOf(FileFormat.JPG) }

    var candidateName by remember { mutableStateOf("") }
    var dateOfPhoto by remember {
        mutableStateOf(SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()))
    }
    var addNameDateStrip by remember { mutableStateOf(initialRule?.requiresNameAndDateStrip ?: false) }
    var autoWhiteBackground by remember { mutableStateOf(true) }
    var showFaceGuideOverlay by remember { mutableStateOf(true) }
    var colorMode by remember { mutableStateOf(ColorMode.COLOUR) }

    var qualityReport by remember { mutableStateOf<QualityInspector.QualityReport?>(null) }
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }
    var isCameraOpen by remember { mutableStateOf(false) }
    var currentMediaInput by remember { mutableStateOf<UnifiedMediaInput?>(null) }
    var showAdjustSheet by remember { mutableStateOf(false) }
    var userPreviewAccepted by remember { mutableStateOf(initialProcessedResult?.userPreviewAccepted ?: false) }
    var cameraGeometryUnavailable by remember { mutableStateOf(false) }

    // Apply initialRule if navigated from Portals tab
    LaunchedEffect(initialRule) {
        if (initialRule != null) {
            customWidthPx = (initialRule.exactWidthPx ?: initialRule.minWidthPx ?: 350).toString()
            customHeightPx = (initialRule.exactHeightPx ?: initialRule.minHeightPx ?: 450).toString()
            targetMinKb = initialRule.minKb
            targetMaxKb = initialRule.maxKb
            customDpi = initialRule.dpi ?: 300
            addNameDateStrip = initialRule.requiresNameAndDateStrip
        }
    }

    // Shared 1-Click Auto-Processing function triggered immediately on Capture/Select or button click
    val triggerOneClickAutoPrepare: (Bitmap, UnifiedMediaInput?) -> Unit = { bmp, mediaInput ->
        val w = customWidthPx.toIntOrNull()?.coerceAtLeast(50) ?: 350
        val h = customHeightPx.toIntOrNull()?.coerceAtLeast(50) ?: 450
        userPreviewAccepted = false
        processedResult = processedResult?.let { currentRes ->
            OneClickPreparationCoordinator.updateUserPreviewAcceptance(currentRes, false)
        }
        coroutineScope.launch {
            isProcessing = true
            saveStatusMessage = null
            showOriginalBefore = false

            val rule = DocumentRule(
                id = "photo_rule_${System.currentTimeMillis()}",
                documentType = DocumentType.PASSPORT_PHOTO,
                allowedFormats = listOf(selectedFormat),
                minKb = targetMinKb,
                maxKb = targetMaxKb,
                exactWidthPx = w,
                exactHeightPx = h,
                dpi = customDpi,
                targetAspectRatio = selectedCropRatio.ratio ?: (w.toFloat() / h.toFloat()),
                requiresWhiteBackground = autoWhiteBackground,
                requiresNameAndDateStrip = addNameDateStrip,
                colorMode = colorMode
            )

            // Use calibrated CameraViewportMapper sensor crop when captured from in-app camera;
            // NEVER silently fall back to static mode.guideCropRectNorm
            val cropRect = if (mediaInput?.isFromCamera == true) {
                cameraGeometryUnavailable = mediaInput.mappedSensorCropRectNorm == null
                mediaInput.mappedSensorCropRectNorm
            } else {
                cameraGeometryUnavailable = false
                null
            }

            processedResult = OneClickPreparationCoordinator.autoPreparePhoto(
                context = context,
                input = mediaInput,
                rule = rule,
                preDecodedBitmap = bmp,
                candidateName = candidateName,
                dateOfPhoto = dateOfPhoto,
                customTargetKb = targetMaxKb,
                customWidthPx = w,
                customHeightPx = h,
                enforceProportional = lockAspectRatio,
                cropRectNorm = cropRect,
                removeBackgroundToWhite = autoWhiteBackground,
                customDpi = customDpi,
                requiresUserPreviewApproval = true,
                userPreviewAccepted = false
            )
            isProcessing = false
        }
    }

    // Launcher for selecting an image from Android Photo Picker (zero-permission)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val previousInput = currentMediaInput
                val mediaInput = UnifiedMediaInput.fromGalleryUri(uri, CameraCaptureMode.PASSPORT_PHOTO)
                val bmp = mediaInput.resolveBitmap(context)
                if (bmp != null) {
                    previousInput?.cleanupPrivateCameraFileIfTemporary()
                    currentMediaInput = mediaInput
                    sourceBitmap = bmp
                    qualityReport = QualityInspector.inspectImageQuality(bmp)
                    triggerOneClickAutoPrepare(bmp, mediaInput)
                } else {
                    Toast.makeText(context, "Could not read image file. Please choose another.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Launcher for picking a Signature image to combine in "Photo + Signature Joint" mode
    val jointSignaturePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { signUri: Uri? ->
        if (signUri != null && sourceBitmap != null) {
            userPreviewAccepted = false
            coroutineScope.launch {
                isProcessing = true
                val signInput = UnifiedMediaInput.fromGalleryUri(signUri, CameraCaptureMode.SIGNATURE)
                val signBmp = signInput.resolveBitmap(context)
                if (signBmp != null) {
                    val w = customWidthPx.toIntOrNull() ?: 350
                    val h = ((customHeightPx.toIntOrNull() ?: 450) * 1.25f).toInt()
                    val jointRes = ImageProcessor.createJointPhotoAndSignature(
                        context = context,
                        photoBitmap = sourceBitmap!!,
                        signatureBitmap = signBmp,
                        targetWidthPx = w,
                        targetHeightPx = h,
                        minKb = targetMinKb,
                        maxKb = targetMaxKb,
                        dpi = customDpi,
                        candidateName = if (addNameDateStrip) candidateName else "",
                        dateOfPhoto = if (addNameDateStrip) dateOfPhoto else ""
                    )
                    processedResult = OneClickPreparationCoordinator.updateUserPreviewAcceptance(
                        jointRes.copy(requiresUserPreviewApproval = true),
                        false
                    )
                    Toast.makeText(context, "Joint Photo + Signature created! Review preview to approve.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not read signature image.", Toast.LENGTH_SHORT).show()
                }
                isProcessing = false
            }
        }
    }

    if (isCameraOpen) {
        UniversalCameraScreen(
            mode = CameraCaptureMode.PASSPORT_PHOTO,
            onMediaCaptured = { input ->
                isCameraOpen = false
                val previousInput = currentMediaInput
                currentMediaInput = input
                coroutineScope.launch {
                    val bmp = input.resolveBitmap(context)
                    if (bmp != null) {
                        previousInput?.cleanupPrivateCameraFileIfTemporary()
                        sourceBitmap = bmp
                        qualityReport = QualityInspector.inspectImageQuality(bmp)
                        triggerOneClickAutoPrepare(bmp, input)
                    } else {
                        input.cleanupPrivateCameraFileIfTemporary()
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
        val currentRes = processedResult
        if (destinationUri != null) {
            if (currentRes == null || !currentRes.isExportReady) {
                Toast.makeText(
                    context,
                    "Please review and accept the processed preview before exporting.",
                    Toast.LENGTH_SHORT
                ).show()
                return@rememberLauncherForActivityResult
            }
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val latestRes = processedResult
                    if (latestRes == null || !latestRes.isExportReady) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Please review and accept the processed preview before exporting.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }
                    val srcFile = File(latestRes.processedFilePath)
                    if (srcFile.exists()) {
                        context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                            FileInputStream(srcFile).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            saveStatusMessage = "Successfully saved to device (${String.format(Locale.US, "%.1f", latestRes.fileSizeKb)} KB)"
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

    if (showAdjustSheet) {
        com.example.ui.components.PhotoStudioAdjustBottomSheet(
            customWidthPx = customWidthPx,
            onWidthChange = { customWidthPx = it },
            customHeightPx = customHeightPx,
            onHeightChange = { customHeightPx = it },
            lockAspectRatio = lockAspectRatio,
            onToggleLockAspect = { lockAspectRatio = !lockAspectRatio },
            targetMinKb = targetMinKb,
            targetMaxKb = targetMaxKb,
            onTargetMaxKbChange = { targetMaxKb = it },
            customDpi = customDpi,
            onDpiChange = { customDpi = it },
            selectedFormat = selectedFormat,
            onFormatChange = { selectedFormat = it },
            autoWhiteBackground = autoWhiteBackground,
            onToggleWhiteBackground = { autoWhiteBackground = it },
            addNameDateStrip = addNameDateStrip,
            onToggleNameDateStrip = { addNameDateStrip = it },
            candidateName = candidateName,
            onCandidateNameChange = { candidateName = it },
            dateOfPhoto = dateOfPhoto,
            onDateOfPhotoChange = { dateOfPhoto = it },
            showFaceGuideOverlay = showFaceGuideOverlay,
            onToggleFaceGuide = { showFaceGuideOverlay = it },
            onCreateJointPhotoSign = if (sourceBitmap != null) {
                {
                    showAdjustSheet = false
                    jointSignaturePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            } else null,
            onApplyAndReprocess = {
                sourceBitmap?.let { triggerOneClickAutoPrepare(it, currentMediaInput) }
            },
            onDismiss = { showAdjustSheet = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("photo_studio_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Passport & Portal Photo Studio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${customWidthPx}×${customHeightPx} px • $customDpi DPI • $targetMinKb–$targetMaxKb KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { showAdjustSheet = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("open_photo_adjust_sheet_btn")
                ) {
                    Text("Adjust", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Unified Top Portal Preset Selector Bar
            PortalPresetSelectorBar(
                selectedPortalId = selectedPortalId,
                activeSectionSummary = "Active Photo Target: ${customWidthPx}×${customHeightPx} px • $customDpi DPI • $targetMinKb–$targetMaxKb KB" +
                    if (addNameDateStrip) " • Name & Date ON" else " • Pure White BG",
                onPortalSelected = { portalSpec ->
                    selectedPortalId = portalSpec.id
                    val photoRule = portalSpec.documents.find {
                        it.documentType == DocumentType.PASSPORT_PHOTO || it.documentType == DocumentType.POSTCARD_PHOTO
                    }
                    if (photoRule != null) {
                        val w = photoRule.exactWidthPx ?: photoRule.minWidthPx ?: 350
                        val h = photoRule.exactHeightPx ?: photoRule.minHeightPx ?: 450
                        customWidthPx = w.toString()
                        customHeightPx = h.toString()
                        targetMinKb = photoRule.minKb
                        targetMaxKb = photoRule.maxKb
                        customDpi = photoRule.dpi ?: 300
                        addNameDateStrip = photoRule.requiresNameAndDateStrip
                        autoWhiteBackground = photoRule.requiresWhiteBackground
                        PHOTO_PRESETS.find { it.portalId == portalSpec.id }?.let { selectedPreset = it }
                        if (sourceBitmap != null) {
                            triggerOneClickAutoPrepare(sourceBitmap!!, currentMediaInput)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Photo Preview Frame with Before/After Toggle & Exact Px/KB/DPI Badges
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (sourceBitmap != null) {
                        val processedBmp = remember(processedResult?.processedFilePath) {
                            processedResult?.processedFilePath?.let { File(it) }?.takeIf { it.exists() }?.let {
                                BitmapFactory.decodeFile(it.absolutePath)
                            }
                        }
                        val displayBmp = if (showOriginalBefore || processedBmp == null) sourceBitmap else processedBmp

                        Image(
                            bitmap = displayBmp!!.asImageBitmap(),
                            contentDescription = "Photograph preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentScale = ContentScale.Fit
                        )

                        // Face Guide Oval Overlay (shown when viewing original)
                        if (showFaceGuideOverlay && (processedResult == null || showOriginalBefore)) {
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

                        // Before / After Toggle Pill (top-start)
                        if (processedResult != null) {
                            Surface(
                                onClick = { showOriginalBefore = !showOriginalBefore },
                                color = Color.Black.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .testTag("photo_before_after_toggle")
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

                        // Status badge (top-end)
                        Surface(
                            color = if (processedResult != null) EmeraldTertiary else NavyPrimary,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = if (processedResult != null) "1-Click Ready" else "Original Selected",
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
                                text = "Capture or Select Candidate Photo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "1-Click Auto-Crop + Pure White Background + Exact Pixels, DPI & KB.",
                                style = MaterialTheme.typography.bodySmall,
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
                                takePhotoButtonText = "Take Photo (1-Click)",
                                galleryButtonText = "Choose Gallery"
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Explicit User Preview Approval Gate & Immediate Results Card
            processedResult?.let { res ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = userPreviewAccepted,
                            onCheckedChange = { accepted ->
                                userPreviewAccepted = accepted
                                processedResult = OneClickPreparationCoordinator.updateUserPreviewAcceptance(res, accepted)
                            },
                            modifier = Modifier.testTag("photo_preview_acceptance_checkbox")
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "I have reviewed the processed photo preview and approve it for export",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (res.segmentationExecuted) {
                                Text(
                                    text = "Mask Confidence: ${(res.subjectMaskConfidence * 100).toInt()}% • Edge Uncertainty: ${(res.edgeUncertaintyRatio * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (cameraGeometryUnavailable) {
                                Text(
                                    text = "Camera viewport geometry was unavailable; framed using portrait face detection. Verify crop or retake.",
                                    fontSize = 11.sp,
                                    color = SaffronSecondary
                                )
                            }
                        }
                    }
                }

                val isSuccess = res.isExportReady && res.status == PreparationStatus.SUCCESS_PREPARED
                val cardBg = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                val statusColor = if (isSuccess) EmeraldTertiary else SaffronSecondary

                Card(
                    modifier = Modifier.fillMaxWidth().testTag("photo_result_card"),
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

                        val dpiLabel = if (res.dpi > 0) "${res.dpi} DPI" else "DPI Unverified"
                        Text(
                            text = "Exact Output: ${res.widthPx} × ${res.heightPx} px • $dpiLabel • ${res.fileFormat.extension.uppercase()} (Allowed: $targetMinKb–$targetMaxKb KB)",
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

                        // Primary 1-Click Instant Download to Gallery Button (Zero Watermark)
                        Button(
                            onClick = {
                                if (!res.isExportReady) {
                                    Toast.makeText(
                                        context,
                                        "Please review and accept the processed preview before exporting.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }
                                coroutineScope.launch {
                                    val currentRes = processedResult
                                    if (currentRes == null || !currentRes.isExportReady) {
                                        Toast.makeText(
                                            context,
                                            "Please review and accept the processed preview before exporting.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    val srcFile = File(currentRes.processedFilePath)
                                    val fileName = "FormReady_${selectedPortalId}_${currentRes.widthPx}x${currentRes.heightPx}_${System.currentTimeMillis()}.${currentRes.fileFormat.extension}"
                                    val saveRes = ImageProcessor.saveFileToGalleryMediaStore(
                                        context = context,
                                        sourceFile = srcFile,
                                        displayName = fileName,
                                        mimeType = currentRes.fileFormat.mimeType
                                    )
                                    if (saveRes.isSuccess) {
                                        saveStatusMessage = "${saveRes.getOrNull()} (${String.format(Locale.US, "%.1f", currentRes.fileSizeKb)} KB)"
                                        Toast.makeText(context, saveStatusMessage, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Save failed: ${saveRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = res.isExportReady,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("instant_gallery_download_photo_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("1-CLICK DOWNLOAD READY PHOTO (NO WATERMARK)", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Save As (SAF) & Share row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (!res.isExportReady) {
                                        Toast.makeText(
                                            context,
                                            "Please review and accept the processed preview before exporting.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@OutlinedButton
                                    }
                                    val suggestedName = "photo_${res.widthPx}x${res.heightPx}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.${res.fileFormat.extension}"
                                    saveDocumentLauncher.launch(suggestedName)
                                },
                                enabled = res.isExportReady,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).testTag("save_photo_device_btn")
                            ) {
                                Icon(imageVector = Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save As...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    if (!res.isExportReady) {
                                        Toast.makeText(
                                            context,
                                            "Please review and accept the processed preview before exporting.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@OutlinedButton
                                    }
                                    sharePreparedFiles(context, listOf(File(res.processedFilePath)))
                                },
                                enabled = res.isExportReady,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).testTag("share_photo_btn")
                            ) {
                                Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 1-Tap Keep Original Background (No White BG Filter) Fallback Button
                        OutlinedButton(
                            onClick = {
                                autoWhiteBackground = false
                                colorMode = ColorMode.COLOUR
                                userPreviewAccepted = false
                                sourceBitmap?.let { bmp ->
                                    val w = customWidthPx.toIntOrNull()?.coerceAtLeast(50) ?: 350
                                    val h = customHeightPx.toIntOrNull()?.coerceAtLeast(50) ?: 450
                                    coroutineScope.launch {
                                        isProcessing = true
                                        val unfilteredRule = DocumentRule(
                                            id = "photo_unfiltered_${System.currentTimeMillis()}",
                                            documentType = DocumentType.PASSPORT_PHOTO,
                                            allowedFormats = listOf(selectedFormat),
                                            minKb = targetMinKb,
                                            maxKb = targetMaxKb,
                                            exactWidthPx = w,
                                            exactHeightPx = h,
                                            dpi = customDpi,
                                            targetAspectRatio = selectedCropRatio.ratio ?: (w.toFloat() / h.toFloat()),
                                            requiresWhiteBackground = false,
                                            requiresNameAndDateStrip = addNameDateStrip,
                                            colorMode = ColorMode.COLOUR
                                        )
                                        val cropRect = if (currentMediaInput?.isFromCamera == true) {
                                            currentMediaInput?.mappedSensorCropRectNorm
                                        } else null
                                        processedResult = OneClickPreparationCoordinator.autoPreparePhoto(
                                            context = context,
                                            input = currentMediaInput,
                                            rule = unfilteredRule,
                                            preDecodedBitmap = bmp,
                                            candidateName = candidateName,
                                            dateOfPhoto = dateOfPhoto,
                                            customTargetKb = targetMaxKb,
                                            customWidthPx = w,
                                            customHeightPx = h,
                                            enforceProportional = lockAspectRatio,
                                            cropRectNorm = cropRect,
                                            removeBackgroundToWhite = false,
                                            customDpi = customDpi,
                                            requiresUserPreviewApproval = true,
                                            userPreviewAccepted = false
                                        )
                                        isProcessing = false
                                        Toast.makeText(context, PHOTO_FALLBACK_TOAST_MESSAGE, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("restore_original_photo_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(PHOTO_FALLBACK_BUTTON_LABEL, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                if (!res.isExportReady) {
                                    Toast.makeText(
                                        context,
                                        "Please review and accept the processed preview before exporting.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@OutlinedButton
                                }
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val currentRes = processedResult
                                        if (currentRes == null || !currentRes.isExportReady) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    "Please review and accept the processed preview before exporting.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            return@launch
                                        }
                                        val repo = DocumentLockerRepository(context)
                                        val file = File(currentRes.processedFilePath)
                                        if (file.exists()) {
                                            val importRes = repo.importFile(
                                                file = file,
                                                title = "Passport Photo (${currentRes.widthPx}x${currentRes.heightPx})",
                                                documentType = DocumentType.PASSPORT_PHOTO,
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
                            enabled = res.isExportReady,
                            modifier = Modifier.fillMaxWidth().testTag("add_photo_to_locker_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to Document Locker Vault", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // 4. Student & Cyber-Cafe Power Tools (Photo + Signature Joint & Printable 8/12 Photo Sheet)
            if (sourceBitmap != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Student & Cyber-Cafe 1-Click Tools",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Create Joint Photo + Signature upload or arrange 8/12 passport photos on a 4x6 sheet for ₹10 shop print.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    jointSignaturePickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.weight(1f).testTag("joint_photo_sign_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Photo + Sign Joint", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    userPreviewAccepted = false
                                    coroutineScope.launch {
                                        isProcessing = true
                                        val baseBmp = processedResult?.processedFilePath?.let { BitmapFactory.decodeFile(it) } ?: sourceBitmap!!
                                        val sheetRes = ImageProcessor.createPrintablePassportSheet(
                                            context = context,
                                            photoBitmap = baseBmp,
                                            photoCount = 8,
                                            dpi = 300
                                        )
                                        processedResult = OneClickPreparationCoordinator.updateUserPreviewAcceptance(
                                            sheetRes.copy(requiresUserPreviewApproval = true),
                                            false
                                        )
                                        isProcessing = false
                                        Toast.makeText(context, "4x6 Printable Sheet (8 Photos) Ready! Review preview to approve.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag("print_sheet_8_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("8 Photos (4x6 Sheet)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                userPreviewAccepted = false
                                coroutineScope.launch {
                                    isProcessing = true
                                    val baseBmp = processedResult?.processedFilePath?.let { BitmapFactory.decodeFile(it) } ?: sourceBitmap!!
                                    val sheetRes = ImageProcessor.createPrintablePassportSheet(
                                        context = context,
                                        photoBitmap = baseBmp,
                                        photoCount = 12,
                                        dpi = 300
                                    )
                                    processedResult = OneClickPreparationCoordinator.updateUserPreviewAcceptance(
                                        sheetRes.copy(requiresUserPreviewApproval = true),
                                        false
                                    )
                                    isProcessing = false
                                    Toast.makeText(context, "Printable Sheet (12 Photos) Ready! Review preview to approve.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("print_sheet_12_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create 12 Passport Photos Sheet (300 DPI)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

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

            // Quick Size Presets Row
            Text(
                text = "Quick Dimension Presets",
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
                            selectedPortalId = preset.portalId
                            customWidthPx = preset.widthPx.toString()
                            customHeightPx = preset.heightPx.toString()
                            targetMinKb = preset.minKb
                            targetMaxKb = preset.maxKb
                            customDpi = preset.dpi
                            if (preset.requiresStrip) {
                                addNameDateStrip = true
                            }
                            if (sourceBitmap != null) {
                                triggerOneClickAutoPrepare(sourceBitmap!!, currentMediaInput)
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
                            userPreviewAccepted = false
                            processedResult?.let { currentRes ->
                                processedResult = OneClickPreparationCoordinator.updateUserPreviewAcceptance(currentRes, false)
                            }
                            if (sourceBitmap != null) {
                                triggerOneClickAutoPrepare(sourceBitmap!!, currentMediaInput)
                            }
                        },
                        label = { Text(opt.label, fontSize = 12.sp) },
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Dimensions, White BG, Name & Date Strip, and Compression Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "1-Click Studio Settings (White BG, Name/Date, Px, DPI & KB)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto Pure White Background (#FFFFFF)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("On-device background whitening + face brightness boost", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = autoWhiteBackground,
                            onCheckedChange = {
                                autoWhiteBackground = it
                                if (sourceBitmap != null) triggerOneClickAutoPrepare(sourceBitmap!!, currentMediaInput)
                            },
                            modifier = Modifier.testTag("photo_white_bg_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Add Name & Date on Photo (Exam Mode)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Prints candidate name & DOP strip at bottom of photo", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = addNameDateStrip,
                            onCheckedChange = {
                                addNameDateStrip = it
                                if (sourceBitmap != null) triggerOneClickAutoPrepare(sourceBitmap!!, currentMediaInput)
                            },
                            modifier = Modifier.testTag("photo_name_date_switch")
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
                        text = "Exact KB Guarantee Window: $targetMinKb KB – $targetMaxKb KB • $customDpi DPI",
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
                            triggerOneClickAutoPrepare(sourceBitmap!!, currentMediaInput)
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
                            Text("Auto Preparing in 1-Click...", fontSize = 14.sp)
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

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
