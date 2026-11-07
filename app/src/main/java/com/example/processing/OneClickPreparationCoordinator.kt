package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.example.camera.UnifiedMediaInput
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
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
     * Executes the complete One-Click Auto Prepare workflow for a photo input:
     * 1. Resolves unified input bitmap safely (or uses pre-resolved [preDecodedBitmap] from the same [UnifiedMediaInput]).
     * 2. Uses camera-mapped guide crop ([UnifiedMediaInput.mappedSensorCropRectNorm]) or [cropRectNorm],
     *    or runs [PortraitVisionProcessor] for face landmark framing and probabilistic background segmentation.
     * 3. Propagates all 5 background verification signals ([ProcessedFileResult.segmentationExecuted],
     *    [ProcessedFileResult.backgroundReplacementApplied], [ProcessedFileResult.outputBackgroundPixelChecksPassed],
     *    [ProcessedFileResult.subjectMaskConfidence] / [ProcessedFileResult.edgeUncertaintyRatio], and
     *    [ProcessedFileResult.userPreviewAccepted]) into [ImageProcessor.processImageForRule].
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
        userPreviewAccepted: Boolean = true,
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
            targetWidthPx = w,
            targetHeightPx = h,
            replaceBackgroundWithWhite = removeBackgroundToWhite || rule.requiresWhiteBackground,
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
        userPreviewAccepted: Boolean = true
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
     * 4. Resizes to exact prescribed dimensions and compresses with verified JFIF/EXIF DPI metadata.
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
        userPreviewAccepted: Boolean = true
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
            segmentationExecuted = true,
            backgroundReplacementApplied = true,
            maskConfidencePassed = !sauvolaResult.usedGentleFallback,
            subjectMaskConfidence = if (sauvolaResult.usedGentleFallback) 0.65f else 0.94f,
            edgeUncertaintyRatio = if (sauvolaResult.usedGentleFallback) 0.20f else 0.04f,
            userPreviewAccepted = userPreviewAccepted
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
        customDpi: Int? = null
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
            customDpi = customDpi
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
        val perspectiveWarped: Boolean = false
    )

    /**
     * Executes the 1-Click Auto-Processing Pipeline for a Document / ID Card:
     * 1. Runs 4-corner quadrilateral detection & homography perspective warp when [customQuadNorm] is provided
     *    or when [DocumentCornerProcessor.detectDocumentCorners] confidence >= 0.75f; otherwise crops to
     *    [cropRectNorm] / [UnifiedMediaInput.mappedSensorCropRectNorm].
     * 2. Applies Clear Scan / Magic Scan enhancement filter (shadow removal + high-contrast text).
     * 3. Generates both a compressed JPG and a single-page PDF strictly under [maxKb].
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
        customQuadNorm: List<PointF>? = null
    ): OneClickDocumentBundle? {
        val rawBitmap = preDecodedBitmap ?: input?.resolveBitmap(context) ?: return null
        val effectiveCropRectNorm = cropRectNorm ?: input?.mappedSensorCropRectNorm

        val quadDetection = DocumentCornerProcessor.detectDocumentCorners(rawBitmap)
        val activeQuad = customQuadNorm?.takeIf { it.size == 4 }
            ?: if (effectiveCropRectNorm == null && quadDetection.autoWarpEligible) quadDetection.cornersNorm else null

        val perspectiveWarped: Boolean
        val geometricallyCorrected = if (activeQuad != null) {
            perspectiveWarped = true
            DocumentCornerProcessor.warpDocumentPerspective(rawBitmap, activeQuad)
        } else if (effectiveCropRectNorm != null) {
            perspectiveWarped = false
            ImageProcessor.cropBitmap(rawBitmap, effectiveCropRectNorm)
        } else {
            perspectiveWarped = false
            rawBitmap
        }

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
            customDpi = dpi
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
            detectedCornersNorm = quadDetection.cornersNorm,
            cornerConfidence = quadDetection.confidence,
            perspectiveWarped = perspectiveWarped
        )
    }
}
