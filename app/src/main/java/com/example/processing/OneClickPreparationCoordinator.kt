package com.example.processing

import android.content.Context
import android.graphics.Bitmap
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
 * while preserving every setting across Photo Studio, Signature Studio, and Document Studio.
 */
object OneClickPreparationCoordinator {

    /**
     * Executes the complete One-Click Auto Prepare workflow for a photo input:
     * 1. Resolves unified input bitmap safely (or uses pre-resolved [preDecodedBitmap] from the same [UnifiedMediaInput]).
     * 2. Auto-orients image using EXIF metadata.
     * 3. Optionally replaces background with pure #FFFFFF white and enhances facial lighting on-device.
     * 4. Calculates aspect ratio and exact pixel dimensions from rule/preset.
     * 5. Crops and resizes without facial distortion.
     * 6. Adds name and date slate strip if required.
     * 7. Applies color mode transformations (Colour / Grayscale / B&W).
     * 8. Compresses according to target KB constraints, injects exact DPI header, and pads if below Min KB.
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
        customDpi: Int? = null
    ): ProcessedFileResult? {
        val bitmap = preDecodedBitmap ?: input?.resolveBitmap(context) ?: return null

        val w = customWidthPx ?: rule.exactWidthPx ?: 350
        val h = customHeightPx ?: rule.exactHeightPx ?: 450
        val targetKb = customTargetKb ?: rule.maxKb

        return ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = bitmap,
            rule = rule,
            candidateName = candidateName,
            dateOfPhoto = dateOfPhoto,
            customTargetKb = targetKb,
            customWidthPx = w,
            customHeightPx = h,
            enforceProportional = enforceProportional,
            cropRectNorm = cropRectNorm,
            removeBackgroundToWhite = removeBackgroundToWhite,
            customDpi = customDpi ?: rule.dpi
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
        removeBackgroundToWhite: Boolean = false
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
            customDpi = customDpi
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
     * 2. Optionally crops strictly to the camera signature cutout box coordinates ([cropRectNorm]).
     * 3. Detects and trims surrounding blank white/gray margins when [trimBlankMargins] is enabled.
     * 4. Whitens paper background & removes mobile shadows while sharpening genuine ink strokes.
     * 5. Resizes to exact prescribed dimensions.
     * 6. Compresses to strict Min-Max KB limit (with safe JPEG padding if below Min KB) & injects DPI.
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
        customDpi: Int? = null
    ): ProcessedFileResult? {
        val bitmap = preDecodedBitmap ?: input?.resolveBitmap(context) ?: return null

        val w = customWidthPx ?: rule.exactWidthPx ?: 300
        val h = customHeightPx ?: rule.exactHeightPx ?: 120
        val targetKb = customTargetKb ?: rule.maxKb

        return ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = bitmap,
            rule = rule,
            customTargetKb = targetKb,
            customWidthPx = w,
            customHeightPx = h,
            enforceProportional = enforceProportional,
            cropRectNorm = cropRectNorm,
            contrastFactor = contrastMultiplier,
            brightnessOffset = brightnessOffset,
            inkThreshold = inkThreshold,
            preserveInkColor = preserveInkColor,
            autoCropMargins = trimBlankMargins,
            customDpi = customDpi ?: rule.dpi
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
        val cleanedBitmap: Bitmap
    )

    /**
     * Executes the 1-Click Auto-Processing Pipeline for a Document / ID Card:
     * 1. Auto-crops to document frame ([cropRectNorm]) or trims blank borders.
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
        cropRectNorm: RectF? = null
    ): OneClickDocumentBundle? {
        val rawBitmap = preDecodedBitmap ?: input?.resolveBitmap(context) ?: return null

        val cropped = if (cropRectNorm != null) {
            ImageProcessor.cropBitmap(rawBitmap, cropRectNorm)
        } else {
            rawBitmap
        }

        val enhanced = if (applyClearScanFilter) {
            ImageProcessor.applyMagicDocFilter(cropped)
        } else {
            cropped
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
            cleanedBitmap = enhanced
        )
    }
}
