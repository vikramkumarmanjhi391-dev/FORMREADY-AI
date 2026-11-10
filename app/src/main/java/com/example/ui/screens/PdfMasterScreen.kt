package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.example.camera.CameraCaptureMode
import com.example.camera.UnifiedMediaInput
import com.example.camera.UniversalCameraScreen
import com.example.data.model.DocumentType
import com.example.data.model.PortalSpec
import com.example.data.portal.PortalDatabase
import com.example.processing.ImageProcessor
import com.example.processing.OneClickPreparationCoordinator
import com.example.processing.PdfProcessor
import com.example.ui.components.CornerOverlayCoordinateMapper
import com.example.ui.components.InteractiveCornerCropOverlay
import com.example.ui.components.PortalPresetSelectorBar
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.NavyPrimary
import com.example.ui.theme.SaffronSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PdfMasterScreen() {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("1-Click Doc & PDF", "Compress PDF", "Front & Back ID")

    var selectedPortal by remember {
        mutableStateOf(
            PortalDatabase.findPortalById("nsdl_pan") ?: PortalDatabase.getAllPortals().first()
        )
    }

    val docRule = remember(selectedPortal) {
        selectedPortal.documents.firstOrNull {
            it.documentType != DocumentType.PASSPORT_PHOTO &&
                it.documentType != DocumentType.POSTCARD_PHOTO &&
                it.documentType != DocumentType.SIGNATURE_ENGLISH &&
                it.documentType != DocumentType.SIGNATURE_HINDI &&
                it.documentType != DocumentType.THUMB_IMPRESSION_LEFT &&
                it.documentType != DocumentType.THUMB_IMPRESSION_RIGHT
        }
    }
    val portalMaxDocKb = docRule?.maxKb ?: 200
    val portalMinDocKb = docRule?.minKb ?: 20
    val portalDocDpi = docRule?.dpi ?: 200

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("pdf_master_screen")
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "1-Click Document & PDF Studio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "1-Click Clear Scan with dual JPG + PDF output (<200KB / <300KB), multi-page A4 certificates, and Front & Back ID card merger.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            PortalPresetSelectorBar(
                selectedPortalId = selectedPortal.id,
                activeSectionSummary = "${selectedPortal.portalName} • Document: $portalMinDocKb–$portalMaxDocKb KB (JPG + PDF @ $portalDocDpi DPI) • Clear Scan ON",
                onPortalSelected = { portal -> selectedPortal = portal }
            )

            Spacer(modifier = Modifier.height(14.dp))

            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                title,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        when (selectedTabIndex) {
            0 -> item {
                ImagesToPdfSection(
                    selectedPortal = selectedPortal,
                    targetMinKb = portalMinDocKb,
                    targetMaxKb = portalMaxDocKb,
                    targetDpi = portalDocDpi
                )
            }
            1 -> item { CompressPdfSection(defaultTargetMaxKb = portalMaxDocKb) }
            2 -> item { FrontBackMergeSection() }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

const val ONE_CLICK_PDF_APPROVAL_REQUIRED_MESSAGE =
    "Please review and accept the processed preview before exporting."
const val ONE_CLICK_PDF_CORNER_CONFIRMATION_REQUIRED_MESSAGE =
    "Please confirm or adjust the 4 document corners before exporting."
const val ONE_CLICK_PDF_STALE_EXPORT_MESSAGE =
    "Document changed or preview approval was reset. Please review the current preview and start saving again."

private val nextOneClickGenerationSequence = java.util.concurrent.atomic.AtomicLong(0L)

fun newOneClickGenerationId(): String {
    val seq = nextOneClickGenerationSequence.incrementAndGet()
    return "oneclick_gen_${seq}_${java.util.UUID.randomUUID()}"
}

/**
 * Immutable generation token binding a pending PDF export request to the exact
 * [OneClickPreparationCoordinator.OneClickDocumentBundle] snapshot whose preview was reviewed and approved.
 */
data class OneClickPdfExportToken(
    val generationId: String,
    val expectedRuleId: String,
    val expectedJpgPath: String,
    val expectedPdfPath: String,
    val expectedBitmapIdentity: Int
) {
    fun matchesBundle(
        currentGenerationId: String?,
        currentBundle: OneClickPreparationCoordinator.OneClickDocumentBundle?
    ): Boolean {
        if (currentGenerationId == null || currentBundle == null) return false
        return generationId == currentGenerationId &&
            expectedRuleId == currentBundle.jpgResult.ruleId &&
            expectedJpgPath == currentBundle.jpgResult.processedFilePath &&
            expectedPdfPath == currentBundle.pdfFile.absolutePath &&
            expectedBitmapIdentity == System.identityHashCode(currentBundle.cleanedBitmap)
    }

    companion object {
        fun issueForBundle(
            generationId: String,
            bundle: OneClickPreparationCoordinator.OneClickDocumentBundle
        ): OneClickPdfExportToken {
            return OneClickPdfExportToken(
                generationId = generationId,
                expectedRuleId = bundle.jpgResult.ruleId,
                expectedJpgPath = bundle.jpgResult.processedFilePath,
                expectedPdfPath = bundle.pdfFile.absolutePath,
                expectedBitmapIdentity = System.identityHashCode(bundle.cleanedBitmap)
            )
        }
    }
}

sealed class OneClickPdfExportOutcome {
    data class Success(
        val validatedGenerationId: String,
        val validatedBundle: OneClickPreparationCoordinator.OneClickDocumentBundle,
        val exportedPdfFile: File,
        val exportedBytes: Long,
        val exportedSizeKb: Double,
        val statusMessage: String
    ) : OneClickPdfExportOutcome()

    data class AbortedStaleGeneration(
        val userMessage: String = ONE_CLICK_PDF_STALE_EXPORT_MESSAGE
    ) : OneClickPdfExportOutcome()

    data class AbortedNotExportReady(
        val userMessage: String = ONE_CLICK_PDF_APPROVAL_REQUIRED_MESSAGE
    ) : OneClickPdfExportOutcome()

    data class FailedFileOrStream(
        val userMessage: String
    ) : OneClickPdfExportOutcome()
}

/**
 * Validates that the current [OneClickPreparationCoordinator.OneClickDocumentBundle] still matches
 * the [expectedToken] issued when the Save PDF action was initiated and that [isExportReady] is true.
 * Returns `null` when valid, or the specific [OneClickPdfExportOutcome] abort reason otherwise.
 */
fun validateOneClickPdfExportSnapshot(
    expectedToken: OneClickPdfExportToken?,
    currentGenerationId: String?,
    currentBundle: OneClickPreparationCoordinator.OneClickDocumentBundle?
): OneClickPdfExportOutcome? {
    if (expectedToken == null || currentGenerationId == null || currentBundle == null) {
        return OneClickPdfExportOutcome.AbortedStaleGeneration()
    }
    if (!expectedToken.matchesBundle(currentGenerationId, currentBundle)) {
        return OneClickPdfExportOutcome.AbortedStaleGeneration()
    }
    if (currentBundle.requiresManualCornerConfirmation) {
        return OneClickPdfExportOutcome.AbortedNotExportReady(
            userMessage = ONE_CLICK_PDF_CORNER_CONFIRMATION_REQUIRED_MESSAGE
        )
    }
    if (!currentBundle.jpgResult.isExportReady) {
        return OneClickPdfExportOutcome.AbortedNotExportReady()
    }
    return null
}

/**
 * Executes a snapshot-consistent One-Click PDF export:
 * 1. Retrieves the current bundle and generation ID at execution time.
 * 2. Requires that the current bundle still matches [expectedToken].
 * 3. Re-checks `currentBundle.jpgResult.isExportReady`.
 * 4. Copies `currentBundle.pdfFile` (never a separately captured file from another generation).
 * 5. Verifies that `currentBundle.pdfFile` exists, is readable, and is non-empty.
 * 6. Handles a null output stream or copy exceptions without reporting success.
 */
fun executeValidatedOneClickPdfExport(
    expectedToken: OneClickPdfExportToken?,
    currentGenerationIdProvider: () -> String?,
    currentBundleProvider: () -> OneClickPreparationCoordinator.OneClickDocumentBundle?,
    outputStreamProvider: () -> java.io.OutputStream?
): OneClickPdfExportOutcome {
    val currentGenId = currentGenerationIdProvider()
    val currentBundle = currentBundleProvider()

    val validationFailure = validateOneClickPdfExportSnapshot(
        expectedToken = expectedToken,
        currentGenerationId = currentGenId,
        currentBundle = currentBundle
    )
    if (validationFailure != null) {
        return validationFailure
    }

    val validatedBundle = currentBundle!!
    val validatedGenId = currentGenId!!
    val pdfFileToCopy = validatedBundle.pdfFile

    if (!pdfFileToCopy.exists() || !pdfFileToCopy.isFile || !pdfFileToCopy.canRead() || pdfFileToCopy.length() <= 0L) {
        return OneClickPdfExportOutcome.FailedFileOrStream(
            "Failed to save PDF: Processed PDF file is missing or unreadable."
        )
    }

    return try {
        val outStream = outputStreamProvider()
            ?: return OneClickPdfExportOutcome.FailedFileOrStream(
                "Failed to save PDF: Unable to open destination file."
            )

        val copiedBytes = outStream.use { out ->
            FileInputStream(pdfFileToCopy).use { inStream ->
                val bytes = inStream.copyTo(out)
                out.flush()
                bytes
            }
        }

        if (copiedBytes <= 0L) {
            return OneClickPdfExportOutcome.FailedFileOrStream(
                "Failed to save PDF: Copy wrote 0 bytes."
            )
        }

        val exportedKb = copiedBytes / 1024.0
        val statusMsg = "PDF saved (${String.format(Locale.US, "%.1f", exportedKb)} KB)"
        OneClickPdfExportOutcome.Success(
            validatedGenerationId = validatedGenId,
            validatedBundle = validatedBundle,
            exportedPdfFile = pdfFileToCopy,
            exportedBytes = copiedBytes,
            exportedSizeKb = exportedKb,
            statusMessage = statusMsg
        )
    } catch (e: Exception) {
        OneClickPdfExportOutcome.FailedFileOrStream(
            "Failed to save PDF: ${e.message ?: "I/O error"}"
        )
    }
}

@Composable
fun ImagesToPdfSection(
    selectedPortal: PortalSpec = PortalDatabase.getAllPortals().first(),
    targetMinKb: Int = 20,
    targetMaxKb: Int = 200,
    targetDpi: Int = 200,
    initialOneClickOriginalBitmap: Bitmap? = null,
    initialOneClickBundle: OneClickPreparationCoordinator.OneClickDocumentBundle? = null,
    initialOneClickUseGuideCrop: Boolean = false,
    onPendingPdfExportTokenChanged: ((OneClickPdfExportToken?) -> Unit)? = null,
    onOneClickBundleChanged: ((OneClickPreparationCoordinator.OneClickDocumentBundle?, String?) -> Unit)? = null,
    onOneClickProcessingChanged: ((Boolean) -> Unit)? = null,
    documentPrepareRunner: (suspend (Bitmap, android.graphics.RectF?, List<PointF>?) -> OneClickPreparationCoordinator.OneClickDocumentBundle?)? = null,
    onTriggerOneClickPrepareReady: (((sourceBmp: Bitmap, useGuideCrop: Boolean, customQuadNorm: List<PointF>?, isNewInputSource: Boolean) -> Long) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1-Click Single Document Dual JPG + PDF state
    var oneClickOriginalBitmap by remember { mutableStateOf(initialOneClickOriginalBitmap) }
    var oneClickBundle by remember { mutableStateOf(initialOneClickBundle) }
    var oneClickUseGuideCrop by remember {
        mutableStateOf(initialOneClickUseGuideCrop || initialOneClickBundle?.guideCropRectNorm != null)
    }
    var oneClickCustomQuadNorm by remember { mutableStateOf<List<PointF>?>(null) }
    var showOneClickCornerOverlay by remember { mutableStateOf(false) }
    var currentBundleGenerationId by remember {
        mutableStateOf<String?>(if (initialOneClickBundle != null) newOneClickGenerationId() else null)
    }
    var pendingPdfExportToken by remember { mutableStateOf<OneClickPdfExportToken?>(null) }
    var isOneClickProcessing by remember { mutableStateOf(false) }
    val processingRequestSequence = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var activeProcessingRequestId by remember { mutableStateOf(0L) }
    var showOneClickOriginalPreview by remember { mutableStateOf(false) }
    var showSingleDocCamera by remember { mutableStateOf(false) }
    var oneClickSaveMessage by remember { mutableStateOf<String?>(null) }
    var oneClickPreviewAccepted by remember {
        mutableStateOf(initialOneClickBundle?.jpgResult?.userPreviewAccepted ?: false)
    }

    fun updatePendingPdfExportToken(newToken: OneClickPdfExportToken?) {
        pendingPdfExportToken = newToken
        onPendingPdfExportTokenChanged?.invoke(newToken)
    }

    fun updateOneClickProcessing(processing: Boolean) {
        isOneClickProcessing = processing
        onOneClickProcessingChanged?.invoke(processing)
    }

    LaunchedEffect(initialOneClickOriginalBitmap) {
        if (initialOneClickOriginalBitmap != null && initialOneClickOriginalBitmap !== oneClickOriginalBitmap) {
            oneClickOriginalBitmap = initialOneClickOriginalBitmap
        }
    }

    LaunchedEffect(initialOneClickBundle) {
        if (initialOneClickBundle != null && initialOneClickBundle !== oneClickBundle) {
            updatePendingPdfExportToken(null)
            val newGenId = newOneClickGenerationId()
            currentBundleGenerationId = newGenId
            oneClickPreviewAccepted = initialOneClickBundle.jpgResult.userPreviewAccepted
            oneClickSaveMessage = null
            if (initialOneClickBundle.guideCropRectNorm != null) {
                oneClickUseGuideCrop = true
            }
            oneClickBundle = initialOneClickBundle
            onOneClickBundleChanged?.invoke(initialOneClickBundle, newGenId)
        }
    }

    // Multi-page PDF state
    val pickedBitmaps = remember { mutableStateListOf<Bitmap>() }
    var generatedPdfFile by remember { mutableStateOf<File?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }
    var showCameraCapture by remember { mutableStateOf(false) }
    var generatedPageCount by remember { mutableIntStateOf(0) }

    fun showSafeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (android.os.Looper.myLooper() != null) {
            Toast.makeText(context, message, duration).show()
        }
    }

    fun triggerOneClickDocumentPrepare(
        sourceBmp: Bitmap,
        useGuideCrop: Boolean,
        customQuadNorm: List<PointF>? = null,
        isNewInputSource: Boolean = false
    ): Long {
        val requestId = processingRequestSequence.incrementAndGet()
        activeProcessingRequestId = requestId

        // Invalidate any pending PDF export token and reset preview approval immediately
        updatePendingPdfExportToken(null)
        currentBundleGenerationId = null
        oneClickPreviewAccepted = false
        oneClickSaveMessage = null
        oneClickUseGuideCrop = useGuideCrop
        if (isNewInputSource) {
            oneClickCustomQuadNorm = null
            showOneClickCornerOverlay = false
            oneClickBundle = null
        } else {
            oneClickBundle = oneClickBundle?.let { prev ->
                prev.copy(
                    jpgResult = OneClickPreparationCoordinator.updateUserPreviewAcceptance(
                        prev.jpgResult,
                        false
                    )
                )
            }
        }
        onOneClickBundleChanged?.invoke(oneClickBundle, null)
        updateOneClickProcessing(true)

        coroutineScope.launch {
            showOneClickOriginalPreview = false
            try {
                val cropRect = if (useGuideCrop) CameraCaptureMode.DOCUMENT_PAGE.guideCropRectNorm else null
                val bundle = if (documentPrepareRunner != null) {
                    documentPrepareRunner.invoke(sourceBmp, cropRect, customQuadNorm)
                } else {
                    OneClickPreparationCoordinator.autoPrepareDocumentJpgAndPdf(
                        context = context,
                        input = null,
                        preDecodedBitmap = sourceBmp,
                        minKb = targetMinKb,
                        maxKb = targetMaxKb,
                        dpi = targetDpi,
                        applyClearScanFilter = true,
                        cropRectNorm = cropRect,
                        customQuadNorm = customQuadNorm
                    )
                }

                // Latest-request-wins guard: if a newer request started while this request was in flight,
                // discard this stale result, clean up its temporary files safely, and never touch active state.
                if (requestId != activeProcessingRequestId) {
                    if (bundle != null) {
                        OneClickPreparationCoordinator.cleanupBundleTempFiles(
                            bundle = bundle,
                            activeBundle = oneClickBundle
                        )
                    }
                    return@launch
                }

                updatePendingPdfExportToken(null)
                if (bundle != null) {
                    val newGenId = newOneClickGenerationId()
                    currentBundleGenerationId = newGenId
                    oneClickPreviewAccepted = bundle.jpgResult.userPreviewAccepted
                    oneClickBundle = bundle
                    onOneClickBundleChanged?.invoke(bundle, newGenId)
                    showSafeToast(
                        "1-Click Document Ready! JPG: ${String.format(Locale.US, "%.1f", bundle.jpgResult.fileSizeKb)} KB • PDF: ${String.format(Locale.US, "%.1f", bundle.pdfSizeKb)} KB"
                    )
                } else {
                    currentBundleGenerationId = null
                    if (isNewInputSource) {
                        oneClickBundle = null
                    }
                    onOneClickBundleChanged?.invoke(oneClickBundle, null)
                    showSafeToast("1-Click document scan failed")
                }
            } finally {
                if (requestId == activeProcessingRequestId) {
                    updateOneClickProcessing(false)
                }
            }
        }
        return requestId
    }

    LaunchedEffect(onTriggerOneClickPrepareReady) {
        onTriggerOneClickPrepareReady?.invoke(::triggerOneClickDocumentPrepare)
    }

    val singleDocGalleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            updatePendingPdfExportToken(null)
            coroutineScope.launch {
                val input = UnifiedMediaInput.fromGalleryUri(uri, CameraCaptureMode.DOCUMENT_PAGE)
                val bmp = input.resolveBitmap(context)
                if (bmp != null) {
                    oneClickOriginalBitmap = bmp
                    if (pickedBitmaps.isEmpty()) {
                        pickedBitmaps.add(bmp)
                    }
                    triggerOneClickDocumentPrepare(
                        sourceBmp = bmp,
                        useGuideCrop = false,
                        customQuadNorm = null,
                        isNewInputSource = true
                    )
                } else {
                    Toast.makeText(context, "Unable to decode selected document.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showSingleDocCamera) {
        UniversalCameraScreen(
            mode = CameraCaptureMode.DOCUMENT_PAGE,
            instantOneClickByDefault = true,
            onMediaCaptured = { input ->
                updatePendingPdfExportToken(null)
                val bmp = input.toBitmap(context)
                input.cleanupPrivateCameraFileIfTemporary()
                if (bmp != null) {
                    oneClickOriginalBitmap = bmp
                    if (pickedBitmaps.isEmpty()) {
                        pickedBitmaps.add(bmp)
                    }
                    triggerOneClickDocumentPrepare(
                        sourceBmp = bmp,
                        useGuideCrop = true,
                        customQuadNorm = null,
                        isNewInputSource = true
                    )
                } else {
                    Toast.makeText(context, "Failed to decode captured document.", Toast.LENGTH_SHORT).show()
                }
                showSingleDocCamera = false
            },
            onDismiss = { showSingleDocCamera = false }
        )
    }

    val saveOneClickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        val expectedToken = pendingPdfExportToken
        updatePendingPdfExportToken(null)
        if (destUri != null) {
            val immediateValidation = validateOneClickPdfExportSnapshot(
                expectedToken = expectedToken,
                currentGenerationId = currentBundleGenerationId,
                currentBundle = oneClickBundle
            )
            if (immediateValidation != null) {
                val msg = when (immediateValidation) {
                    is OneClickPdfExportOutcome.AbortedStaleGeneration -> immediateValidation.userMessage
                    is OneClickPdfExportOutcome.AbortedNotExportReady -> immediateValidation.userMessage
                    is OneClickPdfExportOutcome.FailedFileOrStream -> immediateValidation.userMessage
                    is OneClickPdfExportOutcome.Success -> ""
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            coroutineScope.launch(Dispatchers.IO) {
                val outcome = executeValidatedOneClickPdfExport(
                    expectedToken = expectedToken,
                    currentGenerationIdProvider = { currentBundleGenerationId },
                    currentBundleProvider = { oneClickBundle },
                    outputStreamProvider = { context.contentResolver.openOutputStream(destUri) }
                )
                withContext(Dispatchers.Main) {
                    when (outcome) {
                        is OneClickPdfExportOutcome.Success -> {
                            oneClickSaveMessage = outcome.statusMessage
                            Toast.makeText(context, outcome.statusMessage, Toast.LENGTH_LONG).show()
                        }
                        is OneClickPdfExportOutcome.AbortedStaleGeneration -> {
                            Toast.makeText(context, outcome.userMessage, Toast.LENGTH_SHORT).show()
                        }
                        is OneClickPdfExportOutcome.AbortedNotExportReady -> {
                            Toast.makeText(context, outcome.userMessage, Toast.LENGTH_SHORT).show()
                        }
                        is OneClickPdfExportOutcome.FailedFileOrStream -> {
                            Toast.makeText(context, outcome.userMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 15)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                var addedCount = 0
                uris.forEach { uri ->
                    val input = UnifiedMediaInput.fromGalleryUri(uri, CameraCaptureMode.MULTI_PAGE_DOCUMENT)
                    val bmp = input.resolveBitmap(context)
                    if (bmp != null) {
                        pickedBitmaps.add(bmp)
                        if (oneClickOriginalBitmap == null) {
                            oneClickOriginalBitmap = bmp
                            triggerOneClickDocumentPrepare(
                                sourceBmp = bmp,
                                useGuideCrop = false,
                                customQuadNorm = null,
                                isNewInputSource = true
                            )
                        }
                        addedCount++
                    }
                }
                if (addedCount < uris.size) {
                    Toast.makeText(context, "Skipped ${uris.size - addedCount} unreadable image(s).", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showCameraCapture) {
        UniversalCameraScreen(
            mode = CameraCaptureMode.MULTI_PAGE_DOCUMENT,
            onMediaCaptured = { input ->
                val bmp = input.toBitmap(context)
                input.cleanupPrivateCameraFileIfTemporary()
                if (bmp != null) {
                    pickedBitmaps.add(bmp)
                    if (oneClickOriginalBitmap == null) {
                        oneClickOriginalBitmap = bmp
                        triggerOneClickDocumentPrepare(
                            sourceBmp = bmp,
                            useGuideCrop = true,
                            customQuadNorm = null,
                            isNewInputSource = true
                        )
                    }
                } else {
                    Toast.makeText(context, "Failed to decode captured page.", Toast.LENGTH_SHORT).show()
                }
                showCameraCapture = false
            },
            onMultiPageCaptured = { files ->
                var added = 0
                files.forEach { file ->
                    val bmp = UnifiedMediaInput.decodeBitmapWithExif(file, maxDimension = 2048)
                    if (bmp != null) {
                        pickedBitmaps.add(bmp)
                        if (oneClickOriginalBitmap == null) {
                            oneClickOriginalBitmap = bmp
                            triggerOneClickDocumentPrepare(
                                sourceBmp = bmp,
                                useGuideCrop = true,
                                customQuadNorm = null,
                                isNewInputSource = true
                            )
                        }
                        added++
                    }
                }
                com.example.camera.UniversalCameraController.cleanupTemporaryFiles(files)
                if (added == 0 && files.isNotEmpty()) {
                    Toast.makeText(context, "Could not decode captured pages.", Toast.LENGTH_SHORT).show()
                }
                showCameraCapture = false
            },
            onDismiss = { showCameraCapture = false }
        )
    }

    // Storage Access Framework: Save PDF to Device
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null && generatedPdfFile != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        FileInputStream(generatedPdfFile!!).use { inStream ->
                            inStream.copyTo(out)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        saveStatusMessage = "PDF saved to device successfully (${String.format(Locale.US, "%.1f", generatedPdfFile!!.length() / 1024.0)} KB)"
                        Toast.makeText(context, saveStatusMessage, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. 1-Click Clear Scan Document Card (Dual JPG + PDF output under Portal Max KB)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(if (showOneClickCornerOverlay) 10.dp else 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "⚡ 1-Click Clear Scan (JPG + PDF)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!showOneClickCornerOverlay) {
                            Text(
                                text = "Auto-crop + Clear Scan contrast filter + Both JPG & PDF (<$targetMaxKb KB for ${selectedPortal.portalName})",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (oneClickOriginalBitmap != null) {
                        OutlinedButton(
                            onClick = { showOneClickCornerOverlay = !showOneClickCornerOverlay },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("toggle_one_click_corners_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CropFree,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (showOneClickCornerOverlay) "Hide Corners" else "Adjust 4 Corners",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (showOneClickCornerOverlay && oneClickOriginalBitmap != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val effectiveGuideRect = oneClickBundle?.guideCropRectNorm
                        ?: if (oneClickUseGuideCrop) CameraCaptureMode.DOCUMENT_PAGE.guideCropRectNorm else null
                    val effectiveConfidence = oneClickBundle?.let { b ->
                        if (b.cornerConfidence > 0f) {
                            b.cornerConfidence
                        } else if (b.detectedCornersNorm.size == 4 && b.guideCropRectNorm == null && !b.guideCropFallbackUsed) {
                            0.75f
                        } else {
                            0f
                        }
                    } ?: 0f
                    val initialOverlayCorners = remember(
                        oneClickOriginalBitmap,
                        oneClickCustomQuadNorm,
                        oneClickBundle?.detectedCornersNorm,
                        effectiveConfidence,
                        effectiveGuideRect
                    ) {
                        OneClickPreparationCoordinator.resolveInitialOverlayCornersNorm(
                            customQuadNorm = oneClickCustomQuadNorm,
                            detectedCornersNorm = oneClickBundle?.detectedCornersNorm ?: emptyList(),
                            cornerConfidence = effectiveConfidence,
                            guideCropRectNorm = effectiveGuideRect
                        )
                    }

                    InteractiveCornerCropOverlay(
                        bitmap = oneClickOriginalBitmap!!,
                        initialCornersNorm = initialOverlayCorners,
                        confidence = oneClickBundle?.cornerConfidence ?: 0f,
                        onApplyCorners = { corners ->
                            val validation = CornerOverlayCoordinateMapper.validateManualDocumentQuad(corners)
                            if (validation.isValid) {
                                val appliedCopy = corners.map { PointF(it.x, it.y) }
                                oneClickCustomQuadNorm = appliedCopy
                                showOneClickCornerOverlay = false
                                triggerOneClickDocumentPrepare(
                                    sourceBmp = oneClickOriginalBitmap!!,
                                    useGuideCrop = oneClickUseGuideCrop,
                                    customQuadNorm = appliedCopy,
                                    isNewInputSource = false
                                )
                            } else {
                                val errMsg = validation.errorMessage
                                    ?: "Invalid document corners. Please adjust into a convex quadrilateral."
                                Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onResetCorners = {
                            oneClickCustomQuadNorm = null
                            triggerOneClickDocumentPrepare(
                                sourceBmp = oneClickOriginalBitmap!!,
                                useGuideCrop = oneClickUseGuideCrop,
                                customQuadNorm = null,
                                isNewInputSource = false
                            )
                        },
                        onInvalidCorners = { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showSingleDocCamera = true },
                            colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("one_click_doc_camera_btn")
                        ) {
                            Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("1-Click Cam Scan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                singleDocGalleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("one_click_doc_gallery_btn")
                        ) {
                            Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick Document", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (isOneClickProcessing) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Applying Clear Scan & preparing JPG + PDF (<$targetMaxKb KB)...", fontSize = 12.sp)
                        }
                    }

                    oneClickBundle?.let { bundle ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, EmeraldTertiary)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldTertiary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "1-Click Clear Scan Ready!",
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldTertiary,
                                            fontSize = 13.sp
                                        )
                                    }
                                    if (oneClickOriginalBitmap != null) {
                                        Surface(
                                            color = NavyPrimary.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable { showOneClickOriginalPreview = !showOneClickOriginalPreview }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(imageVector = Icons.Default.Compare, contentDescription = null, tint = NavyPrimary, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (showOneClickOriginalPreview) "Showing: Before" else "Before / After",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NavyPrimary
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                        .border(1.dp, EmeraldTertiary.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val bmpToShow = if (showOneClickOriginalPreview && oneClickOriginalBitmap != null) {
                                        oneClickOriginalBitmap!!
                                    } else {
                                        bundle.cleanedBitmap
                                    }
                                    Image(
                                        bitmap = bmpToShow.asImageBitmap(),
                                        contentDescription = "Scanned Document Preview",
                                        modifier = Modifier.fillMaxSize().padding(6.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                val jpgKb = bundle.jpgResult.fileSizeKb
                                val pdfKb = bundle.pdfSizeKb
                                Text(
                                    text = "JPG: ${bundle.jpgResult.widthPx}×${bundle.jpgResult.heightPx} px • ${String.format(Locale.US, "%.1f", jpgKb)} KB  |  PDF: ${String.format(Locale.US, "%.1f", pdfKb)} KB (<$targetMaxKb KB)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NavyPrimary
                                )

                                if (oneClickSaveMessage != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "✓ $oneClickSaveMessage",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldTertiary
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = oneClickPreviewAccepted,
                                        onCheckedChange = { accepted ->
                                            oneClickPreviewAccepted = accepted
                                            if (!accepted) {
                                                updatePendingPdfExportToken(null)
                                            }
                                            oneClickBundle?.let { currentBundle ->
                                                val updatedBundle = currentBundle.copy(
                                                    jpgResult = OneClickPreparationCoordinator.updateUserPreviewAcceptance(
                                                        currentBundle.jpgResult,
                                                        accepted
                                                    )
                                                )
                                                oneClickBundle = updatedBundle
                                                onOneClickBundleChanged?.invoke(updatedBundle, currentBundleGenerationId)
                                            }
                                        },
                                        modifier = Modifier.testTag("pdf_one_click_preview_acceptance_checkbox")
                                    )
                                    Text(
                                        text = "I have reviewed the processed preview and approve it for export",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NavyPrimary
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val activeBundle = oneClickBundle
                                            val activeGenId = currentBundleGenerationId
                                            if (activeBundle == null || activeGenId == null || !activeBundle.isExportReady) {
                                                val msg = if (activeBundle?.requiresManualCornerConfirmation == true) {
                                                    ONE_CLICK_PDF_CORNER_CONFIRMATION_REQUIRED_MESSAGE
                                                } else {
                                                    ONE_CLICK_PDF_APPROVAL_REQUIRED_MESSAGE
                                                }
                                                showSafeToast(msg, Toast.LENGTH_SHORT)
                                                return@Button
                                            }
                                            val expectedToken = OneClickPdfExportToken.issueForBundle(
                                                generationId = activeGenId,
                                                bundle = activeBundle
                                            )
                                            coroutineScope.launch {
                                                val latestBundle = oneClickBundle
                                                val latestGenId = currentBundleGenerationId
                                                val validationError = validateOneClickPdfExportSnapshot(
                                                    expectedToken = expectedToken,
                                                    currentGenerationId = latestGenId,
                                                    currentBundle = latestBundle
                                                )
                                                if (validationError != null || latestBundle == null) {
                                                    val msg = when (validationError) {
                                                        is OneClickPdfExportOutcome.AbortedStaleGeneration -> validationError.userMessage
                                                        is OneClickPdfExportOutcome.AbortedNotExportReady -> validationError.userMessage
                                                        else -> ONE_CLICK_PDF_APPROVAL_REQUIRED_MESSAGE
                                                    }
                                                    showSafeToast(msg, Toast.LENGTH_SHORT)
                                                    return@launch
                                                }
                                                val jpgFile = File(latestBundle.jpgResult.processedFilePath)
                                                val uri = ImageProcessor.saveFileToGalleryMediaStore(
                                                    context = context,
                                                    sourceFile = jpgFile,
                                                    displayName = "FormReady_Doc_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg",
                                                    mimeType = "image/jpeg"
                                                )
                                                if (uri.isSuccess) {
                                                    oneClickSaveMessage = "JPG saved to Gallery (${String.format(Locale.US, "%.1f", latestBundle.jpgResult.fileSizeKb)} KB)"
                                                    showSafeToast(oneClickSaveMessage!!, Toast.LENGTH_LONG)
                                                }
                                            }
                                        },
                                        enabled = bundle.isExportReady,
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).testTag("download_doc_jpg_btn")
                                    ) {
                                        Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Save JPG", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            val activeBundle = oneClickBundle
                                            val activeGenId = currentBundleGenerationId
                                            if (activeBundle == null || activeGenId == null || !activeBundle.isExportReady) {
                                                updatePendingPdfExportToken(null)
                                                val msg = if (activeBundle?.requiresManualCornerConfirmation == true) {
                                                    ONE_CLICK_PDF_CORNER_CONFIRMATION_REQUIRED_MESSAGE
                                                } else {
                                                    ONE_CLICK_PDF_APPROVAL_REQUIRED_MESSAGE
                                                }
                                                showSafeToast(msg, Toast.LENGTH_SHORT)
                                                return@Button
                                            }
                                            updatePendingPdfExportToken(
                                                OneClickPdfExportToken.issueForBundle(
                                                    generationId = activeGenId,
                                                    bundle = activeBundle
                                                )
                                            )
                                            val suggestedName = "FormReady_Doc_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
                                            saveOneClickPdfLauncher.launch(suggestedName)
                                        },
                                        enabled = bundle.isExportReady,
                                        colors = ButtonDefaults.buttonColors(containerColor = NavyPrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).testTag("download_doc_pdf_btn")
                                    ) {
                                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Save PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val activeBundle = oneClickBundle
                                            if (activeBundle == null || !activeBundle.isExportReady) {
                                                val msg = if (activeBundle?.requiresManualCornerConfirmation == true) {
                                                    ONE_CLICK_PDF_CORNER_CONFIRMATION_REQUIRED_MESSAGE
                                                } else {
                                                    ONE_CLICK_PDF_APPROVAL_REQUIRED_MESSAGE
                                                }
                                                showSafeToast(msg, Toast.LENGTH_SHORT)
                                                return@OutlinedButton
                                            }
                                            sharePreparedFiles(
                                                context,
                                                listOf(File(activeBundle.jpgResult.processedFilePath), activeBundle.pdfFile)
                                            )
                                        },
                                        enabled = bundle.isExportReady,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.testTag("share_doc_bundle_btn")
                                    ) {
                                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    Spacer(modifier = Modifier.height(14.dp))

    // 2. Multi-Page Images to Single A4 PDF Section
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Multi-Page Images to A4 PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Combine Class 10/12 Marksheets, Degrees, Certificates", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showCameraCapture = true },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("scan_pdf_camera_btn")
                    ) {
                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Scan", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("add_pdf_gallery_btn")
                    ) {
                        Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (pickedBitmaps.isNotEmpty()) {
                Text("Selected Pages (${pickedBitmaps.size}) — Reorder or Rotate:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(pickedBitmaps) { index, bmp ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(width = 100.dp, height = 130.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Page ${index + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Surface(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                                ) {
                                    Text("Page ${index + 1}", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                                IconButton(
                                    onClick = { pickedBitmaps.removeAt(index) },
                                    modifier = Modifier.align(Alignment.TopEnd).size(26.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red, modifier = Modifier.size(16.dp))
                                }
                            }

                            // Page controls: Rotate & Move
                            Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = {
                                        val rotated = PdfProcessor.rotateBitmap(bmp, 90f)
                                        pickedBitmaps[index] = rotated
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.RotateRight, contentDescription = "Rotate 90 deg", modifier = Modifier.size(14.dp))
                                }
                                if (index > 0) {
                                    IconButton(
                                        onClick = {
                                            val item = pickedBitmaps.removeAt(index)
                                            pickedBitmaps.add(index - 1, item)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Move left", modifier = Modifier.size(14.dp))
                                    }
                                }
                                if (index < pickedBitmaps.size - 1) {
                                    IconButton(
                                        onClick = {
                                            val item = pickedBitmaps.removeAt(index)
                                            pickedBitmaps.add(index + 1, item)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Move right", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isGenerating = true
                            saveStatusMessage = null
                            try {
                                val pagesSnapshot = pickedBitmaps.toList()
                                val file = PdfProcessor.createPdfFromBitmaps(
                                    context = context,
                                    bitmaps = pagesSnapshot,
                                    outputFileName = "certificate_${System.currentTimeMillis()}.pdf"
                                )
                                val validation = withContext(Dispatchers.IO) {
                                    com.example.storage.DocumentValidationManager.validateFile(file)
                                }
                                if (validation.isValid && validation.pageCount == pagesSnapshot.size) {
                                    generatedPdfFile = file
                                    generatedPageCount = validation.pageCount
                                    Toast.makeText(
                                        context,
                                        "PDF generated (${validation.pageCount} pages, ${String.format(Locale.US, "%.1f", file.length() / 1024.0)} KB)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    file.delete()
                                    generatedPdfFile = null
                                    Toast.makeText(
                                        context,
                                        validation.errorMessage ?: "Generated PDF failed validation",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                generatedPdfFile = null
                                Toast.makeText(context, "PDF generation failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = !isGenerating && pickedBitmaps.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("generate_pdf_btn")
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Creating PDF...")
                    } else {
                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generate Compliant A4 PDF")
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No certificate images selected yet", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Pick one or more documents to combine into a single clean PDF", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Output Result Card
            generatedPdfFile?.let { pdfFile ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldTertiary)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldTertiary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("PDF Created Successfully! ($generatedPageCount Page${if (generatedPageCount == 1) "" else "s"})", fontWeight = FontWeight.Bold, color = EmeraldTertiary)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("File: ${pdfFile.name} • ${String.format(Locale.US, "%.1f", pdfFile.length() / 1024.0)} KB", fontSize = 12.sp)

                        if (saveStatusMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("✓ $saveStatusMessage", fontSize = 11.sp, color = EmeraldTertiary, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val suggestedName = "document_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
                                    savePdfLauncher.launch(suggestedName)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).testTag("save_pdf_device_btn")
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save to Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { sharePreparedFiles(context, listOf(pdfFile)) },
                                shape = RoundedCornerShape(8.dp)
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
                                        if (pdfFile.exists()) {
                                            val importRes = repo.importFile(
                                                file = pdfFile,
                                                title = "Certificate Document (${pickedBitmaps.size} Pages)",
                                                documentType = com.example.data.model.DocumentType.OTHER_SUPPORTING,
                                                originalFileName = pdfFile.name,
                                                notes = "Generated from ${pickedBitmaps.size} image pages in PDF Master"
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
                            modifier = Modifier.fillMaxWidth().testTag("add_pdf_to_locker_btn"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to Document Locker Vault", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    }
}

enum class PdfCompressionUiStatus {
    SUCCESS,
    WARNING_OVERSIZED,
    VALIDATION_FAILED
}

data class PdfCompressionUiState(
    val status: PdfCompressionUiStatus,
    val isSuccess: Boolean,
    val title: String,
    val subtitle: String,
    val actualSizeBytes: Long,
    val actualSizeKbFormatted: String,
    val targetMaxKb: Int,
    val isValidPdf: Boolean,
    val metTarget: Boolean,
    val pageCountPreserved: Boolean,
    val geometryPreserved: Boolean,
    val pageCount: Int
)

fun buildPdfCompressionUiState(report: PdfProcessor.PdfCompressionReport): PdfCompressionUiState {
    val diskBytes = if (report.outputFile.exists()) report.outputFile.length() else report.actualSizeBytes
    val actualKbStr = String.format(Locale.US, "%.1f", diskBytes / 1024.0)
    val withinTarget = diskBytes in 1L..(report.targetMaxKb * 1024L) && report.metTarget
    return when {
        !report.isValidPdf || !report.pageCountPreserved || !report.geometryPreserved -> {
            val failureReason = when {
                !report.isValidPdf -> "Output PDF failed structure validation."
                !report.pageCountPreserved -> "Page count changed unexpectedly (${report.originalPages.size} -> ${report.compressedPages.size})."
                else -> "Page geometry or aspect ratio changed unexpectedly."
            }
            PdfCompressionUiState(
                status = PdfCompressionUiStatus.VALIDATION_FAILED,
                isSuccess = false,
                title = "PDF Validation Failed",
                subtitle = "$failureReason Measured size: $actualKbStr KB.",
                actualSizeBytes = diskBytes,
                actualSizeKbFormatted = actualKbStr,
                targetMaxKb = report.targetMaxKb,
                isValidPdf = report.isValidPdf,
                metTarget = false,
                pageCountPreserved = report.pageCountPreserved,
                geometryPreserved = report.geometryPreserved,
                pageCount = report.compressedPages.size
            )
        }
        withinTarget -> {
            PdfCompressionUiState(
                status = PdfCompressionUiStatus.SUCCESS,
                isSuccess = true,
                title = "Compressed Within Target ($actualKbStr KB)",
                subtitle = "Measured Size: $actualKbStr KB (≤ ${report.targetMaxKb} KB limit) • ${report.compressedPages.size} page(s) geometry preserved. Inspect text clarity before portal upload.",
                actualSizeBytes = diskBytes,
                actualSizeKbFormatted = actualKbStr,
                targetMaxKb = report.targetMaxKb,
                isValidPdf = true,
                metTarget = true,
                pageCountPreserved = true,
                geometryPreserved = true,
                pageCount = report.compressedPages.size
            )
        }
        else -> {
            PdfCompressionUiState(
                status = PdfCompressionUiStatus.WARNING_OVERSIZED,
                isSuccess = false,
                title = "Target Limit Exceeded ($actualKbStr KB > ${report.targetMaxKb} KB)",
                subtitle = "Measured Size: $actualKbStr KB exceeds the ${report.targetMaxKb} KB limit (${report.compressedPages.size} page(s) geometry preserved). Try fewer pages or a higher KB limit.",
                actualSizeBytes = diskBytes,
                actualSizeKbFormatted = actualKbStr,
                targetMaxKb = report.targetMaxKb,
                isValidPdf = true,
                metTarget = false,
                pageCountPreserved = true,
                geometryPreserved = true,
                pageCount = report.compressedPages.size
            )
        }
    }
}

@Composable
fun CompressPdfSection(defaultTargetMaxKb: Int = 300) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var inputPdfFile by remember { mutableStateOf<File?>(null) }
    var targetMaxKb by remember(defaultTargetMaxKb) { mutableIntStateOf(defaultTargetMaxKb) }
    var compressionReport by remember { mutableStateOf<PdfProcessor.PdfCompressionReport?>(null) }
    var compressionError by remember { mutableStateOf<String?>(null) }
    var isCompressing by remember { mutableStateOf(false) }
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val temp = File(context.cacheDir, "input_doc_${System.currentTimeMillis()}.pdf")
                        FileOutputStream(temp).use { stream.copyTo(it) }
                        stream.close()
                        inputPdfFile = temp
                        compressionReport = null
                        compressionError = null
                        saveStatusMessage = null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val saveCompressedPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        val compFile = compressionReport?.outputFile
        if (destUri != null && compFile != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        FileInputStream(compFile).use { inStream ->
                            inStream.copyTo(out)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        saveStatusMessage = "Compressed PDF saved (${String.format(Locale.US, "%.1f", compFile.length() / 1024.0)} KB)"
                        Toast.makeText(context, saveStatusMessage, Toast.LENGTH_LONG).show()
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Smart PDF Compressor", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Compress large scanned certificates down to portal limits (50-300 KB)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(12.dp))

            if (inputPdfFile != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(inputPdfFile!!.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Original Size: ${String.format(Locale.US, "%.1f", inputPdfFile!!.length() / 1024.0)} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) }) {
                            Icon(imageVector = Icons.Default.UploadFile, contentDescription = "Change PDF")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Target Portal Max Limit: $targetMaxKb KB",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Slider(
                    value = targetMaxKb.toFloat(),
                    onValueChange = { targetMaxKb = it.toInt() },
                    valueRange = 50f..1000f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isCompressing = true
                            saveStatusMessage = null
                            compressionError = null
                            try {
                                val report = PdfProcessor.compressPdfToTargetKbWithReport(
                                    context = context,
                                    inputPdfFile = inputPdfFile!!,
                                    targetMaxKb = targetMaxKb
                                )
                                compressionReport = report
                                val uiState = buildPdfCompressionUiState(report)
                                Toast.makeText(
                                    context,
                                    if (uiState.isSuccess) {
                                        "Compressed to ${uiState.actualSizeKbFormatted} KB (≤ $targetMaxKb KB)"
                                    } else {
                                        uiState.title
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                compressionReport = null
                                compressionError = e.message ?: "Invalid or unreadable PDF file."
                                Toast.makeText(context, "PDF compression failed: $compressionError", Toast.LENGTH_LONG).show()
                            } finally {
                                isCompressing = false
                            }
                        }
                    },
                    enabled = !isCompressing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCompressing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compressing PDF...")
                    } else {
                        Icon(imageVector = Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compress to $targetMaxKb KB")
                    }
                }
            } else {
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select PDF to Compress")
                }
            }

            if (compressionError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("compressed_pdf_error_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = compressionError!!,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            compressionReport?.let { report ->
                val compFile = report.outputFile
                val uiState = buildPdfCompressionUiState(report)
                val statusColor = when (uiState.status) {
                    PdfCompressionUiStatus.SUCCESS -> EmeraldTertiary
                    PdfCompressionUiStatus.WARNING_OVERSIZED -> SaffronSecondary
                    PdfCompressionUiStatus.VALIDATION_FAILED -> MaterialTheme.colorScheme.error
                }
                val cardBg = when (uiState.status) {
                    PdfCompressionUiStatus.SUCCESS -> Color(0xFFE8F5E9)
                    PdfCompressionUiStatus.WARNING_OVERSIZED -> Color(0xFFFFF3E0)
                    PdfCompressionUiStatus.VALIDATION_FAILED -> MaterialTheme.colorScheme.errorContainer
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("compressed_pdf_result_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (uiState.status) {
                                    PdfCompressionUiStatus.SUCCESS -> Icons.Default.CheckCircle
                                    PdfCompressionUiStatus.WARNING_OVERSIZED -> Icons.Default.Warning
                                    PdfCompressionUiStatus.VALIDATION_FAILED -> Icons.Default.ErrorOutline
                                },
                                contentDescription = null,
                                tint = statusColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.title,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = uiState.subtitle,
                            fontSize = 12.sp
                        )

                        if (saveStatusMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("✓ $saveStatusMessage", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val suggestedName = "compressed_${targetMaxKb}kb_${compFile.name}"
                                    saveCompressedPdfLauncher.launch(suggestedName)
                                },
                                enabled = uiState.isValidPdf,
                                colors = ButtonDefaults.buttonColors(containerColor = statusColor),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save to Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { sharePreparedFiles(context, listOf(compFile)) },
                                enabled = uiState.isValidPdf,
                                shape = RoundedCornerShape(8.dp)
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
                                        if (compFile.exists()) {
                                            val importRes = repo.importFile(
                                                file = compFile,
                                                title = "Compressed PDF (< ${targetMaxKb} KB)",
                                                documentType = com.example.data.model.DocumentType.OTHER_SUPPORTING,
                                                originalFileName = compFile.name,
                                                notes = "Compressed via PDF Master"
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
                            modifier = Modifier.fillMaxWidth().testTag("add_compressed_pdf_to_locker_btn"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to Document Locker Vault", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FrontBackMergeSection() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var backBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mergedPdfFile by remember { mutableStateOf<File?>(null) }
    var isMerging by remember { mutableStateOf(false) }
    var isSelectingFront by remember { mutableStateOf(true) }
    var saveStatusMessage by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val bmp = ImageProcessor.decodeBitmapFromUri(context, uri)
                if (bmp != null) {
                    if (isSelectingFront) frontBitmap = bmp else backBitmap = bmp
                    mergedPdfFile = null
                    saveStatusMessage = null
                }
            }
        }
    }

    val saveMergedPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri: Uri? ->
        if (destUri != null && mergedPdfFile != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        FileInputStream(mergedPdfFile!!).use { inStream ->
                            inStream.copyTo(out)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        saveStatusMessage = "Merged PDF saved (${String.format(Locale.US, "%.1f", mergedPdfFile!!.length() / 1024.0)} KB)"
                        Toast.makeText(context, saveStatusMessage, Toast.LENGTH_LONG).show()
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

    var cameraTargetForIdCard by remember { mutableStateOf<String?>(null) } // "front" or "back" or null

    if (cameraTargetForIdCard != null) {
        UniversalCameraScreen(
            mode = CameraCaptureMode.DOCUMENT_PAGE,
            onMediaCaptured = { input ->
                val bmp = input.toBitmap(context)
                input.cleanupPrivateCameraFileIfTemporary()
                if (bmp != null) {
                    if (cameraTargetForIdCard == "front") {
                        frontBitmap = bmp
                    } else {
                        backBitmap = bmp
                    }
                    mergedPdfFile = null
                    saveStatusMessage = null
                } else {
                    Toast.makeText(context, "Could not decode captured ID card side.", Toast.LENGTH_SHORT).show()
                }
                cameraTargetForIdCard = null
            },
            onDismiss = { cameraTargetForIdCard = null }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Front & Back ID Merger", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Combine Aadhaar Card, Voter ID, or PAN Card front and back into one single clean page PDF.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Front Card Box
                Card(
                    modifier = Modifier.weight(1f).height(140.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (frontBitmap != null) {
                            Image(bitmap = frontBitmap!!.asImageBitmap(), contentDescription = "Front side", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Camera",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable { cameraTargetForIdCard = "front" }.padding(horizontal = 6.dp)
                                )
                                Text("•", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                                Text(
                                    text = "Gallery",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable {
                                        isSelectingFront = true
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }.padding(horizontal = 6.dp)
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text("Front Side (ID)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(
                                        onClick = { cameraTargetForIdCard = "front" },
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Cam", fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = {
                                            isSelectingFront = true
                                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        },
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Pick", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Back Card Box
                Card(
                    modifier = Modifier.weight(1f).height(140.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (backBitmap != null) {
                            Image(bitmap = backBitmap!!.asImageBitmap(), contentDescription = "Back side", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Camera",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable { cameraTargetForIdCard = "back" }.padding(horizontal = 6.dp)
                                )
                                Text("•", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                                Text(
                                    text = "Gallery",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable {
                                        isSelectingFront = false
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }.padding(horizontal = 6.dp)
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text("Back Side (ID)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(
                                        onClick = { cameraTargetForIdCard = "back" },
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Cam", fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = {
                                            isSelectingFront = false
                                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        },
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Pick", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    if (frontBitmap != null && backBitmap != null) {
                        coroutineScope.launch {
                            isMerging = true
                            saveStatusMessage = null
                            val merged = PdfProcessor.createFrontAndBackCardPdf(
                                context = context,
                                frontBitmap = frontBitmap!!,
                                backBitmap = backBitmap!!,
                                cardTitle = "Aadhaar / Photo Identity Card"
                            )
                            mergedPdfFile = merged
                            isMerging = false
                            Toast.makeText(context, "Front & Back combined into PDF!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Please select both Front and Back sides first.", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = frontBitmap != null && backBitmap != null && !isMerging,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isMerging) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Merging Sides...")
                } else {
                    Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Merge into Single Page PDF")
                }
            }

            mergedPdfFile?.let { pdfFile ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldTertiary)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Combined PDF Ready!", fontWeight = FontWeight.Bold, color = EmeraldTertiary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Size: ${String.format(Locale.US, "%.1f", pdfFile.length() / 1024.0)} KB", fontSize = 12.sp)

                        if (saveStatusMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("✓ $saveStatusMessage", fontSize = 11.sp, color = EmeraldTertiary, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val suggestedName = "id_card_merged_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
                                    saveMergedPdfLauncher.launch(suggestedName)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save to Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { sharePreparedFiles(context, listOf(pdfFile)) },
                                shape = RoundedCornerShape(8.dp)
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
                                        if (pdfFile.exists()) {
                                            val importRes = repo.importFile(
                                                file = pdfFile,
                                                title = "ID Card (Front & Back Merged)",
                                                documentType = com.example.data.model.DocumentType.AADHAAR_CARD,
                                                originalFileName = pdfFile.name,
                                                notes = "Front and Back sides combined into single A4 PDF"
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
                            modifier = Modifier.fillMaxWidth().testTag("add_merged_card_to_locker_btn"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to Document Locker Vault", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
