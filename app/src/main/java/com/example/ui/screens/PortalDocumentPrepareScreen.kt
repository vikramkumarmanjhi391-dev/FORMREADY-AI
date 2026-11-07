package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.CaptureIntent
import com.example.camera.MediaSourceSelectionBar
import com.example.camera.UnifiedMediaInput
import com.example.camera.UniversalCameraScreen
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.PortalSpec
import com.example.data.model.PreparationStatus
import com.example.data.model.ProcessedFileResult
import com.example.processing.DocumentCornerProcessor
import com.example.processing.ImageProcessor
import com.example.processing.OneClickPreparationCoordinator
import com.example.processing.PdfProcessor
import com.example.ui.components.InteractiveCornerCropOverlay
import com.example.ui.components.PhotoStudioAdjustBottomSheet
import com.example.ui.components.SignatureStudioAdjustBottomSheet
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.SaffronSecondary
import com.example.ui.theme.VerifiedGreen
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Phase 4 Full-Screen Per-Document Preparation & Verification Screen.
 * Routes Photo, Signature, Thumb, and Document/ID rules to their dedicated vision pipelines
 * with preview acceptance, 4-corner adjustment for documents, and an Adjust bottom sheet.
 */
@Composable
fun PortalDocumentPrepareScreen(
    portal: PortalSpec,
    rule: DocumentRule,
    initialCandidateName: String,
    initialDateOfPhoto: String,
    onDocumentPrepared: (ProcessedFileResult) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isPhotoRule = rule.documentType == DocumentType.PASSPORT_PHOTO ||
        rule.documentType == DocumentType.POSTCARD_PHOTO
    val isSignOrThumbRule = rule.documentType == DocumentType.SIGNATURE_ENGLISH ||
        rule.documentType == DocumentType.SIGNATURE_HINDI ||
        rule.documentType == DocumentType.THUMB_IMPRESSION_LEFT ||
        rule.documentType == DocumentType.THUMB_IMPRESSION_RIGHT
    val isDocumentOrIdRule = !isPhotoRule && !isSignOrThumbRule

    val captureIntent = remember(portal.id, rule.id) {
        CaptureIntent.ForPortalRule(
            portalId = portal.id,
            portalName = portal.examOrServiceName,
            rule = rule
        )
    }

    var isCameraOpen by remember { mutableStateOf(false) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentMediaInput by remember { mutableStateOf<UnifiedMediaInput?>(null) }
    var processedResult by remember { mutableStateOf<ProcessedFileResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showOriginalBefore by remember { mutableStateOf(false) }
    var showAdjustSheet by remember { mutableStateOf(false) }
    var showCornerOverlay by remember { mutableStateOf(false) }

    var userPreviewAccepted by remember { mutableStateOf(true) }
    var detectedCornersNorm by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var detectedCornerConfidence by remember { mutableFloatStateOf(0f) }

    // Manual override states
    var customWidthPx by remember(rule.id) {
        mutableStateOf((rule.exactWidthPx ?: rule.maxWidthPx ?: if (isPhotoRule) 350 else if (isSignOrThumbRule) 140 else 800).toString())
    }
    var customHeightPx by remember(rule.id) {
        mutableStateOf((rule.exactHeightPx ?: rule.maxHeightPx ?: if (isPhotoRule) 450 else if (isSignOrThumbRule) 60 else 1100).toString())
    }
    var lockAspectRatio by remember { mutableStateOf(true) }
    var targetMinKb by remember(rule.id) { mutableIntStateOf(rule.minKb) }
    var targetMaxKb by remember(rule.id) { mutableIntStateOf(rule.maxKb) }
    var customDpi by remember(rule.id) { mutableIntStateOf(rule.dpi ?: 300) }
    var selectedFormat by remember(rule.id) {
        mutableStateOf(rule.allowedFormats.firstOrNull() ?: FileFormat.JPG)
    }
    var autoWhiteBackground by remember(rule.id) { mutableStateOf(rule.requiresWhiteBackground) }
    var addNameDateStrip by remember(rule.id) { mutableStateOf(rule.requiresNameAndDateStrip) }
    var candidateName by remember { mutableStateOf(initialCandidateName) }
    var dateOfPhoto by remember { mutableStateOf(initialDateOfPhoto) }
    var showFaceGuideOverlay by remember { mutableStateOf(true) }

    var preserveInkColor by remember { mutableStateOf(rule.colorMode != com.example.data.model.ColorMode.BLACK_AND_WHITE) }
    var autoCropBlankMargins by remember { mutableStateOf(true) }
    var contrastFactor by remember { mutableFloatStateOf(1.25f) }
    var inkThreshold by remember { mutableIntStateOf(185) }

    BackHandler(onBack = onBack)

    val runPreparationPipeline: (Bitmap, UnifiedMediaInput?, List<PointF>?, Boolean) -> Unit = { bmp, mediaInput, customQuad, previewAccepted ->
        coroutineScope.launch {
            isProcessing = true
            val w = customWidthPx.toIntOrNull()?.coerceAtLeast(30) ?: (rule.exactWidthPx ?: 350)
            val h = customHeightPx.toIntOrNull()?.coerceAtLeast(20) ?: (rule.exactHeightPx ?: 450)

            val effectiveRule = rule.copy(
                minKb = targetMinKb,
                maxKb = targetMaxKb,
                exactWidthPx = if (rule.exactWidthPx != null) w else null,
                exactHeightPx = if (rule.exactHeightPx != null) h else null,
                dpi = customDpi,
                requiresWhiteBackground = autoWhiteBackground,
                requiresNameAndDateStrip = addNameDateStrip
            )

            when {
                isPhotoRule -> {
                    val res = OneClickPreparationCoordinator.autoPreparePhoto(
                        context = context,
                        input = mediaInput,
                        rule = effectiveRule,
                        preDecodedBitmap = bmp,
                        candidateName = candidateName,
                        dateOfPhoto = dateOfPhoto,
                        customWidthPx = w,
                        customHeightPx = h,
                        customTargetKb = targetMaxKb,
                        enforceProportional = lockAspectRatio,
                        removeBackgroundToWhite = autoWhiteBackground,
                        customDpi = customDpi,
                        userPreviewAccepted = previewAccepted
                    )
                    if (res != null) {
                        processedResult = res
                        onDocumentPrepared(res)
                    }
                }
                isSignOrThumbRule -> {
                    val res = OneClickPreparationCoordinator.autoPrepareSignature(
                        context = context,
                        input = mediaInput,
                        rule = effectiveRule,
                        preDecodedBitmap = bmp,
                        contrastMultiplier = contrastFactor,
                        inkThreshold = inkThreshold,
                        preserveInkColor = preserveInkColor,
                        trimBlankMargins = autoCropBlankMargins,
                        customWidthPx = w,
                        customHeightPx = h,
                        customTargetKb = targetMaxKb,
                        enforceProportional = true,
                        customDpi = customDpi,
                        userPreviewAccepted = previewAccepted
                    )
                    if (res != null) {
                        processedResult = res
                        onDocumentPrepared(res)
                    }
                }
                else -> {
                    // Document / ID Card Pipeline (Phase 3B)
                    val quadDetection = DocumentCornerProcessor.detectDocumentCorners(bmp)
                    detectedCornersNorm = quadDetection.cornersNorm
                    detectedCornerConfidence = quadDetection.confidence

                    val warpedBmp = when {
                        customQuad != null && customQuad.size == 4 ->
                            DocumentCornerProcessor.warpDocumentPerspective(bmp, customQuad)
                        quadDetection.autoWarpEligible ->
                            DocumentCornerProcessor.warpDocumentPerspective(bmp, quadDetection.cornersNorm)
                        else -> {
                            showCornerOverlay = true
                            bmp
                        }
                    }

                    val cleanedBmp = ImageProcessor.applyMagicDocFilter(warpedBmp)
                    if (rule.allowedFormats.contains(FileFormat.PDF) && !rule.allowedFormats.contains(FileFormat.JPG)) {
                        val rawPdf = PdfProcessor.createPdfFromBitmaps(
                            context = context,
                            bitmaps = listOf(cleanedBmp),
                            outputFileName = "portal_${portal.id}_${rule.id}.pdf"
                        )
                        val pdfReport = PdfProcessor.compressPdfToTargetKbWithReport(
                            context = context,
                            inputPdfFile = rawPdf,
                            targetMaxKb = targetMaxKb
                        )
                        val pdfCompliant = pdfReport.metTarget && pdfReport.actualSizeKb >= targetMinKb
                        val pdfStatus = when {
                            !pdfReport.metTarget -> PreparationStatus.SIZE_EXCEEDED
                            pdfReport.actualSizeKb < targetMinKb -> PreparationStatus.SIZE_BELOW_MIN
                            else -> PreparationStatus.SUCCESS_PREPARED
                        }
                        val pdfRes = ProcessedFileResult(
                            ruleId = rule.id,
                            documentType = rule.documentType,
                            originalFileName = pdfReport.outputFile.name,
                            processedFilePath = pdfReport.outputFile.absolutePath,
                            fileFormat = FileFormat.PDF,
                            fileSizeBytes = pdfReport.actualSizeBytes,
                            fileSizeKb = pdfReport.actualSizeKb,
                            widthPx = cleanedBmp.width,
                            heightPx = cleanedBmp.height,
                            pageCount = pdfReport.outputPageCount,
                            isCompliant = pdfCompliant,
                            validationMessages = listOf(
                                "PDF Verified: ${String.format(Locale.US, "%.1f", pdfReport.actualSizeKb)} KB (Allowed: $targetMinKb–$targetMaxKb KB), Pages: ${pdfReport.outputPageCount}"
                            ),
                            status = pdfStatus,
                            dpi = customDpi,
                            userPreviewAccepted = previewAccepted
                        )
                        processedResult = pdfRes
                        onDocumentPrepared(pdfRes)
                    } else {
                        val res = ImageProcessor.processImageForRule(
                            context = context,
                            originalBitmap = cleanedBmp,
                            rule = effectiveRule,
                            customTargetKb = targetMaxKb,
                            customDpi = customDpi,
                            userPreviewAccepted = previewAccepted
                        )
                        processedResult = res
                        onDocumentPrepared(res)
                    }
                }
            }
            isProcessing = false
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val previous = currentMediaInput
                val mediaInput = UnifiedMediaInput.fromGalleryUri(
                    uri = uri,
                    mode = captureIntent.captureMode,
                    captureIntent = captureIntent
                )
                val bmp = mediaInput.resolveBitmap(context)
                if (bmp != null) {
                    previous?.cleanupPrivateCameraFileIfTemporary()
                    currentMediaInput = mediaInput
                    sourceBitmap = bmp
                    userPreviewAccepted = true
                    runPreparationPipeline(bmp, mediaInput, null, true)
                } else {
                    Toast.makeText(context, "Could not decode selected image.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (isCameraOpen) {
        UniversalCameraScreen(
            mode = captureIntent.captureMode,
            captureIntent = captureIntent,
            onMediaCaptured = { input ->
                isCameraOpen = false
                val previous = currentMediaInput
                currentMediaInput = input
                coroutineScope.launch {
                    val bmp = input.resolveBitmap(context)
                    if (bmp != null) {
                        previous?.cleanupPrivateCameraFileIfTemporary()
                        sourceBitmap = bmp
                        userPreviewAccepted = true
                        runPreparationPipeline(bmp, input, null, true)
                    } else {
                        input.cleanupPrivateCameraFileIfTemporary()
                        Toast.makeText(context, "Failed to decode camera capture.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { isCameraOpen = false }
        )
        return
    }

    if (showAdjustSheet) {
        if (isSignOrThumbRule) {
            SignatureStudioAdjustBottomSheet(
                customWidthPx = customWidthPx,
                onWidthChange = { customWidthPx = it },
                customHeightPx = customHeightPx,
                onHeightChange = { customHeightPx = it },
                targetMinKb = targetMinKb,
                targetMaxKb = targetMaxKb,
                onTargetMaxKbChange = { targetMaxKb = it },
                customDpi = customDpi,
                onDpiChange = { customDpi = it },
                preserveInkColor = preserveInkColor,
                onPreserveInkColorChange = { preserveInkColor = it },
                autoCropBlankMargins = autoCropBlankMargins,
                onToggleAutoCropMargins = { autoCropBlankMargins = it },
                contrastFactor = contrastFactor,
                onContrastFactorChange = { contrastFactor = it },
                inkThreshold = inkThreshold,
                onInkThresholdChange = { inkThreshold = it },
                onApplyAndReprocess = {
                    sourceBitmap?.let { runPreparationPipeline(it, currentMediaInput, null, userPreviewAccepted) }
                },
                onDismiss = { showAdjustSheet = false }
            )
        } else {
            PhotoStudioAdjustBottomSheet(
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
                onApplyAndReprocess = {
                    sourceBitmap?.let { runPreparationPipeline(it, currentMediaInput, null, userPreviewAccepted) }
                },
                onDismiss = { showAdjustSheet = false }
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("portal_doc_prepare_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))

            // Top bar with Back and Adjust
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("portal_doc_prepare_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Column {
                        Text(
                            text = rule.documentType.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${portal.examOrServiceName} • ${targetMinKb}–${targetMaxKb} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showAdjustSheet = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("portal_doc_adjust_btn")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Adjust", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Preview Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (isProcessing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Preparing & verifying on-device...", fontSize = 12.sp)
                        }
                    } else if (sourceBitmap != null) {
                        val processedBmp = remember(processedResult?.processedFilePath) {
                            processedResult?.processedFilePath?.let { File(it) }
                                ?.takeIf { it.exists() && !it.name.endsWith(".pdf", ignoreCase = true) }
                                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                        }
                        val displayBmp = if (showOriginalBefore || processedBmp == null) sourceBitmap else processedBmp

                        Image(
                            bitmap = displayBmp!!.asImageBitmap(),
                            contentDescription = "Document Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentScale = ContentScale.Fit
                        )

                        if (processedResult != null) {
                            Surface(
                                onClick = { showOriginalBefore = !showOriginalBefore },
                                color = Color.Black.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = if (showOriginalBefore) "Before (Tap for After)" else "After (Tap for Before)",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Text(
                                text = "Capture or Select ${rule.documentType.displayName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = rule.instructions.ifBlank {
                                    "Required: ${targetMinKb}–${targetMaxKb} KB • $customDpi DPI"
                                },
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
                                takePhotoButtonText = "Camera",
                                galleryButtonText = "Gallery"
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive 4-Corner Overlay for Documents & ID Cards
            if (isDocumentOrIdRule && sourceBitmap != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { showCornerOverlay = !showCornerOverlay },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("toggle_doc_corners_btn")
                    ) {
                        Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (showCornerOverlay) "Hide 4-Corner Crop" else "Adjust 4 Corners", fontSize = 12.sp)
                    }
                }

                if (showCornerOverlay) {
                    Spacer(modifier = Modifier.height(8.dp))
                    InteractiveCornerCropOverlay(
                        bitmap = sourceBitmap!!,
                        initialCornersNorm = detectedCornersNorm,
                        confidence = detectedCornerConfidence,
                        onApplyCorners = { corners ->
                            showCornerOverlay = false
                            runPreparationPipeline(sourceBitmap!!, currentMediaInput, corners, userPreviewAccepted)
                        },
                        onResetCorners = {
                            runPreparationPipeline(sourceBitmap!!, currentMediaInput, null, userPreviewAccepted)
                        }
                    )
                }
            }

            // 6-Part Background Verification: User Preview Confirmation Checkbox
            if (sourceBitmap != null && (rule.requiresWhiteBackground || autoWhiteBackground)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
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
                                sourceBitmap?.let { bmp ->
                                    runPreparationPipeline(bmp, currentMediaInput, null, accepted)
                                }
                            },
                            modifier = Modifier.testTag("preview_acceptance_checkbox")
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "I have reviewed the subject edges and accept this preview",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            processedResult?.let { res ->
                                if (res.segmentationExecuted) {
                                    Text(
                                        text = "Mask Confidence: ${(res.subjectMaskConfidence * 100).toInt()}% • Edge Uncertainty: ${(res.edgeUncertaintyRatio * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Processed Result Status & Actions Card
            processedResult?.let { res ->
                Spacer(modifier = Modifier.height(10.dp))
                val isSuccess = res.status == PreparationStatus.SUCCESS_PREPARED
                val statusColor = if (isSuccess) EmeraldTertiary else SaffronSecondary

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("portal_doc_result_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                    ),
                    border = BorderStroke(1.2.dp, statusColor)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
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

                        res.validationMessages.forEach { msg ->
                            Text(
                                text = "• $msg",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val srcFile = File(res.processedFilePath)
                                        val fileName = "FormReady_${portal.id}_${rule.documentType.name.lowercase()}_${System.currentTimeMillis()}.${res.fileFormat.extension}"
                                        val saveRes = ImageProcessor.saveFileToGalleryMediaStore(
                                            context = context,
                                            sourceFile = srcFile,
                                            displayName = fileName,
                                            mimeType = res.fileFormat.mimeType
                                        )
                                        if (saveRes.isSuccess) {
                                            Toast.makeText(context, "Saved: ${saveRes.getOrNull()}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("portal_doc_save_gallery_btn"),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    sharePreparedFiles(context, listOf(File(res.processedFilePath)))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = onBack,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("portal_doc_done_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Done — Return to Portal Checklist", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (sourceBitmap != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { isCameraOpen = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retake Camera", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change Gallery", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
