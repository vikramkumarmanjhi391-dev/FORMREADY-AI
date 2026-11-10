package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.example.camera.UnifiedMediaInput
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.PreparationStatus
import com.example.data.model.PreviewApprovalStatus
import com.example.data.model.ProcessedFileResult
import java.io.File

/**
 * Shared coordinator that connects both Camera and Gallery inputs ([UnifiedMediaInput])
 * to the deterministic image-preparation engine ([ImageProcessor.processImageForRule])
 * and on-device vision pipelines ([PortraitVisionProcessor], [SignatureVisionProcessor],
 * [DocumentCornerProcessor]) while preserving every setting across Photo Studio,
 * Signature Studio, Document Studio, and Portal Preparation screens.
 */
object OneClickPreparationCoordinator {

    /**
     * Updates explicit user preview acceptance on a [ProcessedFileResult] without re-running
     * vision inference and without allowing user acceptance to override technical compliance failures.
     */
    fun updateUserPreviewAcceptance(
        result: ProcessedFileResult,
        userPreviewAccepted: Boolean
    ): ProcessedFileResult {
        val requiresApproval = result.requiresUserPreviewApproval
        val approvalStatus = when {
            !requiresApproval -> PreviewApprovalStatus.NOT_REQUIRED
            userPreviewAccepted -> PreviewApprovalStatus.APPROVED_BY_USER
            else -> PreviewApprovalStatus.PENDING_USER_APPROVAL
        }
        val isExportReady =
            result.isCompliant &&
            (!result.requiresWhiteBackground || result.technicalBackgroundCompliant) &&
            (!requiresApproval || userPreviewAccepted)
        val updatedStatus = when {
            !result.isCompliant -> result.status
            requiresApproval && !userPreviewAccepted -> PreparationStatus.PENDING_PREVIEW_APPROVAL
            else -> PreparationStatus.SUCCESS_PREPARED
        }
        val filteredMessages = result.validationMessages.filterNot {
            it.startsWith("Preview Approval Pending:") || it.startsWith("Preview Approved:")
        }.toMutableList()
        if (result.isCompliant && requiresApproval) {
            if (userPreviewAccepted) {
                filteredMessages.add("Preview Approved: User reviewed and accepted the processed preview.")
            } else {
                filteredMessages.add("Preview Approval Pending: Please review the processed preview and accept it to mark export-ready.")
            }
        }
        @Suppress("DEPRECATION")
        val legacyVerifiedBg = result.technicalBackgroundCompliant && (!requiresApproval || userPreviewAccepted)
        return result.copy(
            userPreviewAccepted = userPreviewAccepted,
            approvalStatus = approvalStatus,
            isExportReady = isExportReady,
            status = updatedStatus,
            validationMessages = filteredMessages,
            verifiedBackgroundCompliance = legacyVerifiedBg
        )
    }

    /**
     * Executes the complete One-Click Auto Prepare workflow for a photo input:
     * 1. Resolves unified input bitmap safely (or uses pre-resolved [preDecodedBitmap] from the same [UnifiedMediaInput]).
     * 2. Uses camera-mapped guide crop ([UnifiedMediaInput.mappedSensorCropRectNorm]) or [cropRectNorm],
     *    or runs [PortraitVisionProcessor] for face landmark framing and probabilistic background segmentation.
     * 3. Propagates technical background verification signals ([ProcessedFileResult.segmentationExecuted],
     *    [ProcessedFileResult.backgroundReplacementApplied], [ProcessedFileResult.outputBackgroundPixelChecksPassed],
     *    [ProcessedFileResult.subjectMaskConfidence] / [ProcessedFileResult.edgeUncertaintyRatio])
     *    independently from [ProcessedFileResult.userPreviewAccepted] into [ImageProcessor.processImageForRule].
     * 4. Resizes to exact prescribed dimensions, adds optional Name & DOP strip, and verifies disk output.
     */
    suspend fun autoPreparePhoto(
        context: Context,
        input: UnifiedMediaInput?,
        rule: DocumentRule,
        preDecodedBitmap: Bitmap? = null,
        candidateName: String = "",
        dateOfPhoto: String = "",
        customWidthPx: Int? = null,
        customHeightPx: Int? = null,
        customTargetKb: Int? = null,
        enforceProportional: Boolean = true,
        cropRectNorm: RectF? = null,
        removeBackgroundToWhite: Boolean = false,
        customDpi: Int? = null,
        requiresUserPreviewApproval: Boolean = true,
        userPreviewAccepted: Boolean = false,
        faceEngine: PortraitVisionProcessor.FaceDetectionEngine = PortraitVisionProcessor.mlKitFaceDetectionEngine,
        segmentationEngine: PortraitVisionProcessor.SelfieSegmentationEngine = PortraitVisionProcessor.mlKitSelfieSegmentationEngine
    ): ProcessedFileResult? {
        val bitmap = preDecodedBitmap ?: input?.resolveBitmap(context) ?: return null

        val w = customWidthPx ?: rule.exactWidthPx ?: 350
        val h = customHeightPx ?: rule.exactHeightPx ?: 450
        val targetKb = customTargetKb ?: rule.maxKb
        val effectiveCropRectNorm = cropRectNorm ?: input?.mappedSensorCropRectNorm

        val visionResult = PortraitVisionProcessor.analyzeAndPreparePortrait(
            sourceBitmap = bitmap,
            rule = rule,
            applyWhiteBackground = removeBackgroundToWhite || rule.requiresWhiteBackground,
            manualCropRectNorm = effectiveCropRectNorm,
            faceEngine = faceEngine,
            segmentationEngine = segmentationEngine
        )

        val hasVisionSegmentation = visionResult.segmentationExecuted && visionResult.backgroundMask != null

        return ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = visionResult.preparedBitmap,
            rule = rule,
            candidateName = candidateName,
            dateOfPhoto = dateOfPhoto,
            customTargetKb = targetKb,
            customWidthPx = w,
            customHeightPx = h,
            enforceProportional = enforceProportional,
            cropRectNorm = null, // Already framed/cropped by PortraitVisionProcessor
            removeBackgroundToWhite = removeBackgroundToWhite,
            customDpi = customDpi ?: rule.dpi,
            backgroundMask = visionResult.backgroundMask,
            backgroundMaskWidth = visionResult.preparedBitmap.width,
            backgroundMaskHeight = visionResult.preparedBitmap.height,
            alreadyBackgroundReplaced = hasVisionSegmentation && visionResult.backgroundReplacementApplied,
            segmentationExecuted = visionResult.segmentationExecuted,
            backgroundReplacementApplied = visionResult.backgroundReplacementApplied || removeBackgroundToWhite,
            maskConfidencePassed = visionResult.maskConfidencePassed,
            subjectMaskConfidence = visionResult.subjectMaskConfidence,
            edgeUncertaintyRatio = visionResult.edgeUncertaintyRatio,
            requiresUserPreviewApproval = requiresUserPreviewApproval,
            userPreviewAccepted = userPreviewAccepted
        )
    }

    /**
     * Overload returning [Result] for coordinator flows and unit tests.
     */
    suspend fun autoPreparePhoto(
        context: Context,
        mediaInput: UnifiedMediaInput,
        rule: DocumentRule,
        targetSizeKb: Int? = null,
        customDpi: Int? = null,
        candidateName: String = "",
        dateOfPhoto: String = "",
        enforceProportional: Boolean = true,
        removeBackgroundToWhite: Boolean = false,
        requiresUserPreviewApproval: Boolean = true,
        userPreviewAccepted: Boolean = false
    ): Result<ProcessedFileResult> {
        val result = autoPreparePhoto(
            context = context,
            input = mediaInput,
            rule = rule,
            candidateName = candidateName,
            dateOfPhoto = dateOfPhoto,
            customTargetKb = targetSizeKb,
            enforceProportional = enforceProportional,
            removeBackgroundToWhite = removeBackgroundToWhite,
            customDpi = customDpi,
            requiresUserPreviewApproval = requiresUserPreviewApproval,
            userPreviewAccepted = userPreviewAccepted
        )
        return if (result != null) {
            Result.success(result)
        } else {
            Result.failure(IllegalStateException("Failed to process photo from media input"))
        }
    }

    /**
     * Executes the complete One-Click Prepare workflow for a signature or thumb impression:
     * 1. Resolves unified input bitmap (or uses pre-resolved [preDecodedBitmap]).
     * 2. Crops to the camera signature cutout box coordinates ([cropRectNorm] or [UnifiedMediaInput.mappedSensorCropRectNorm]).
     * 3. Applies [SignatureVisionProcessor] integral-image Sauvola adaptive shadow removal and connected-stroke trimming.
     * 4. Resizes to exact prescribed dimensions, validates saved ink & paper pixels via [SignatureValidationEvidence]
     *    without spoofing portrait segmentation metrics, and compresses with verified JFIF/EXIF DPI metadata.
     */
    suspend fun autoPrepareSignature(
        context: Context,
        input: UnifiedMediaInput?,
        rule: DocumentRule,
        preDecodedBitmap: Bitmap? = null,
        contrastMultiplier: Float = 1.2f,
        brightnessOffset: Float = 0f,
        inkThreshold: Int = 180,
        preserveInkColor: Boolean = true,
        trimBlankMargins: Boolean = true,
        customWidthPx: Int? = null,
        customHeightPx: Int? = null,
        customTargetKb: Int? = null,
        enforceProportional: Boolean = false,
        cropRectNorm: RectF? = null,
        customDpi: Int? = null,
        requiresUserPreviewApproval: Boolean = true,
        userPreviewAccepted: Boolean = false
    ): ProcessedFileResult? {
        val rawBitmap = preDecodedBitmap ?: input?.resolveBitmap(context) ?: return null

        val w = customWidthPx ?: rule.exactWidthPx ?: 300
        val h = customHeightPx ?: rule.exactHeightPx ?: 120
        val targetKb = customTargetKb ?: rule.maxKb
        val effectiveCropRectNorm = cropRectNorm ?: input?.mappedSensorCropRectNorm

        val croppedInput = if (effectiveCropRectNorm != null) {
            ImageProcessor.cropBitmap(rawBitmap, effectiveCropRectNorm)
        } else {
            rawBitmap
        }

        val sauvolaResult = SignatureVisionProcessor.extractSignatureOrThumb(
            src = croppedInput,
            preserveInkColor = preserveInkColor,
            contrastBoost = contrastMultiplier,
            sauvolaK = ((200 - inkThreshold).coerceIn(5, 60) / 200f).coerceIn(0.08f, 0.28f),
            trimToInkBounds = trimBlankMargins
        )

        return ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = sauvolaResult.cleanedBitmap,
            rule = rule,
            customTargetKb = targetKb,
            customWidthPx = w,
            customHeightPx = h,
            enforceProportional = enforceProportional,
            cropRectNorm = null,
            contrastFactor = 1.0f, // Already enhanced by SignatureVisionProcessor
            brightnessOffset = brightnessOffset,
            inkThreshold = 180,
            preserveInkColor = preserveInkColor,
            autoCropMargins = false, // Already trimmed by SignatureVisionProcessor
            customDpi = customDpi ?: rule.dpi,
            backgroundMask = sauvolaResult.backgroundMask,
            backgroundMaskWidth = sauvolaResult.cleanedBitmap.width,
            backgroundMaskHeight = sauvolaResult.cleanedBitmap.height,
            segmentationExecuted = false,
            backgroundReplacementApplied = true,
            maskConfidencePassed = false,
            subjectMaskConfidence = 0f,
            edgeUncertaintyRatio = 0f,
            requiresUserPreviewApproval = requiresUserPreviewApproval,
            userPreviewAccepted = userPreviewAccepted,
            signatureExtractionResult = sauvolaResult
        )
    }

    /**
     * Overload returning [Result] for coordinator flows and unit tests.
     */
    suspend fun autoPrepareSignature(
        context: Context,
        mediaInput: UnifiedMediaInput,
        rule: DocumentRule,
        targetSizeKb: Int? = null,
        contrastMultiplier: Float = 1.2f,
        inkThreshold: Int = 180,
        preserveInkColor: Boolean = true,
        trimBlankMargins: Boolean = true,
        cropRectNorm: RectF? = null,
        customDpi: Int? = null,
        requiresUserPreviewApproval: Boolean = true,
        userPreviewAccepted: Boolean = false
    ): Result<ProcessedFileResult> {
        val result = autoPrepareSignature(
            context = context,
            input = mediaInput,
            rule = rule,
            contrastMultiplier = contrastMultiplier,
            inkThreshold = inkThreshold,
            preserveInkColor = preserveInkColor,
            trimBlankMargins = trimBlankMargins,
            customTargetKb = targetSizeKb,
            cropRectNorm = cropRectNorm,
            customDpi = customDpi,
            requiresUserPreviewApproval = requiresUserPreviewApproval,
            userPreviewAccepted = userPreviewAccepted
        )
        return if (result != null) {
            Result.success(result)
        } else {
            Result.failure(IllegalStateException("Failed to process signature from media input"))
        }
    }

    data class OneClickDocumentBundle(
        val jpgResult: ProcessedFileResult,
        val pdfFile: File,
        val pdfSizeKb: Double,
        val cleanedBitmap: Bitmap,
        val detectedCornersNorm: List<PointF> = emptyList(),
        val cornerConfidence: Float = 0f,
        val perspectiveWarped: Boolean = false,
        val requiresManualCornerConfirmation: Boolean = false,
        val guideCropFallbackUsed: Boolean = false,
        val guideCropRectNorm: RectF? = null
    ) {
        val isExportReady: Boolean
            get() = jpgResult.isExportReady && !requiresManualCornerConfirmation
    }

    /**
     * Safely deletes temporary JPG and PDF output files created for a stale [bundle],
     * ensuring that no file currently referenced by [activeBundle] is ever deleted.
     */
    fun cleanupBundleTempFiles(
        bundle: OneClickDocumentBundle,
        activeBundle: OneClickDocumentBundle? = null
    ) {
        val activeJpgPath = activeBundle?.jpgResult?.processedFilePath?.let { File(it).absolutePath }
        val activePdfPath = activeBundle?.pdfFile?.absolutePath

        val staleJpgFile = File(bundle.jpgResult.processedFilePath)
        if (staleJpgFile.absolutePath != activeJpgPath && staleJpgFile.exists()) {
            runCatching { staleJpgFile.delete() }
        }
        val stalePdfFile = bundle.pdfFile
        if (stalePdfFile.absolutePath != activePdfPath && stalePdfFile.exists()) {
            runCatching { stalePdfFile.delete() }
        }
    }

    /**
     * Converts a normalized [RectF] guide frame into 4 clockwise normalized document corners:
     * Top-Left, Top-Right, Bottom-Right, Bottom-Left, clamped to [0f, 1f].
     */
    fun rectToNormalizedQuadCorners(rect: RectF): List<PointF> {
        val left = rect.left.coerceIn(0f, 1f)
        val top = rect.top.coerceIn(0f, 1f)
        val right = rect.right.coerceIn(left, 1f)
        val bottom = rect.bottom.coerceIn(top, 1f)
        return listOf(
            PointF(left, top),
            PointF(right, top),
            PointF(right, bottom),
            PointF(left, bottom)
        )
    }

    /**
     * Resolves the initial 4 normalized corners for the manual adjustment overlay:
     * 1. Valid user-supplied [customQuadNorm] when present,
     * 2. Otherwise [detectedCornersNorm] when available with positive detector confidence,
     * 3. Otherwise [guideCropRectNorm] converted to 4 clockwise corners when camera guide crop was provided,
     * 4. Otherwise the safe default inset frame.
     */
    fun resolveInitialOverlayCornersNorm(
        customQuadNorm: List<PointF>? = null,
        detectedCornersNorm: List<PointF> = emptyList(),
        cornerConfidence: Float = 0f,
        guideCropRectNorm: RectF? = null
    ): List<PointF> {
        if (customQuadNorm != null &&
            com.example.ui.components.CornerOverlayCoordinateMapper.validateManualDocumentQuad(customQuadNorm).isValid
        ) {
            return customQuadNorm.map { PointF(it.x, it.y) }
        }
        if (cornerConfidence > 0f &&
            detectedCornersNorm.size == 4 &&
            com.example.ui.components.CornerOverlayCoordinateMapper.validateManualDocumentQuad(detectedCornersNorm).isValid
        ) {
            return detectedCornersNorm.map { PointF(it.x, it.y) }
        }
        if (guideCropRectNorm != null) {
            val rectCorners = rectToNormalizedQuadCorners(guideCropRectNorm)
            if (com.example.ui.components.CornerOverlayCoordinateMapper.validateManualDocumentQuad(rectCorners).isValid) {
                return rectCorners
            }
        }
        return DocumentCornerProcessor.DocumentQuadNorm.defaultInsetFrame().cornersNorm
    }

    /**
     * Executes the 1-Click Auto-Processing Pipeline for a Document / ID Card:
     * 1. Priority 1: Valid user-supplied [customQuadNorm] (rejects invalid manual quads immediately).
     * 2. Priority 2: Automatic detected corners when [DocumentCornerProcessor.detectDocumentCorners]
     *    `autoWarpEligible == true` (unified across both Camera and Gallery inputs).
     * 3. Priority 3: Conservative rectangular camera guide crop ([cropRectNorm] / [UnifiedMediaInput.mappedSensorCropRectNorm])
     *    when automatic perspective correction is not eligible.
     * 4. Priority 4: Uncropped original bitmap.
     * 5. Applies Clear Scan / Magic Scan enhancement filter (shadow removal + high-contrast text).
     * 6. Generates both a compressed JPG and a single-page PDF strictly under [maxKb].
     */
    suspend fun autoPrepareDocumentJpgAndPdf(
        context: Context,
        input: UnifiedMediaInput?,
        preDecodedBitmap: Bitmap? = null,
        minKb: Int = 20,
        maxKb: Int = 200,
        dpi: Int = 200,
        applyClearScanFilter: Boolean = true,
        cropRectNorm: RectF? = null,
        customQuadNorm: List<PointF>? = null,
        requiresUserPreviewApproval: Boolean = true,
        userPreviewAccepted: Boolean = false
    ): OneClickDocumentBundle? {
        val rawBitmap = preDecodedBitmap ?: input?.resolveBitmap(context) ?: return null
        val effectiveCropRectNorm = cropRectNorm ?: input?.mappedSensorCropRectNorm

        // Validate user-supplied manual quadrilateral before warping; never pass invalid quads to warpDocumentPerspective.
        val validatedCustomQuad: List<PointF>? = if (customQuadNorm != null) {
            val validation = com.example.ui.components.CornerOverlayCoordinateMapper.validateManualDocumentQuad(customQuadNorm)
            if (!validation.isValid) {
                return null
            }
            customQuadNorm.map { PointF(it.x, it.y) }
        } else {
            null
        }

        val quadDetection = DocumentCornerProcessor.detectDocumentCorners(rawBitmap)
        val activeQuad: List<PointF>? = validatedCustomQuad
            ?: if (quadDetection.autoWarpEligible && quadDetection.cornersNorm.size == 4) {
                quadDetection.cornersNorm
            } else {
                null
            }

        val perspectiveWarped: Boolean
        val guideCropFallbackUsed: Boolean
        val geometricallyCorrected = if (activeQuad != null) {
            perspectiveWarped = true
            guideCropFallbackUsed = false
            DocumentCornerProcessor.warpDocumentPerspective(rawBitmap, activeQuad)
        } else if (effectiveCropRectNorm != null) {
            perspectiveWarped = false
            guideCropFallbackUsed = true
            ImageProcessor.cropBitmap(rawBitmap, effectiveCropRectNorm)
        } else {
            perspectiveWarped = false
            guideCropFallbackUsed = false
            rawBitmap
        }

        val requiresManualCornerConfirmation = validatedCustomQuad == null && !quadDetection.autoWarpEligible
        val resolvedCornersNorm = resolveInitialOverlayCornersNorm(
            customQuadNorm = validatedCustomQuad,
            detectedCornersNorm = quadDetection.cornersNorm,
            cornerConfidence = quadDetection.confidence,
            guideCropRectNorm = effectiveCropRectNorm
        )
        val effectiveConfidence = if (validatedCustomQuad != null) 1.0f else quadDetection.confidence

        val enhanced = if (applyClearScanFilter) {
            ImageProcessor.applyMagicDocFilter(geometricallyCorrected)
        } else {
            geometricallyCorrected
        }

        val docRule = DocumentRule(
            id = "doc_1click_${System.currentTimeMillis()}",
            documentType = DocumentType.AADHAAR_CARD,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = minKb,
            maxKb = maxKb,
            maxWidthPx = 1200,
            maxHeightPx = 1600,
            dpi = dpi,
            requiresWhiteBackground = true
        )

        val jpgRes = ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = enhanced,
            rule = docRule,
            customTargetKb = maxKb,
            enforceProportional = true,
            customDpi = dpi,
            requiresUserPreviewApproval = requiresUserPreviewApproval,
            userPreviewAccepted = userPreviewAccepted
        )

        val rawPdf = PdfProcessor.createPdfFromBitmaps(
            context = context,
            bitmaps = listOf(enhanced),
            outputFileName = "formready_doc_${System.currentTimeMillis()}.pdf"
        )
        val finalPdf = if (rawPdf.length() / 1024 > maxKb) {
            PdfProcessor.compressPdfToTargetKb(context, rawPdf, maxKb)
        } else {
            rawPdf
        }

        return OneClickDocumentBundle(
            jpgResult = jpgRes,
            pdfFile = finalPdf,
            pdfSizeKb = finalPdf.length() / 1024.0,
            cleanedBitmap = enhanced,
            detectedCornersNorm = resolvedCornersNorm,
            cornerConfidence = effectiveConfidence,
            perspectiveWarped = perspectiveWarped,
            requiresManualCornerConfirmation = requiresManualCornerConfirmation,
            guideCropFallbackUsed = guideCropFallbackUsed,
            guideCropRectNorm = effectiveCropRectNorm
        )
    }
}

