package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import com.example.camera.CameraCaptureMode
import com.example.camera.UnifiedMediaInput
import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.ProcessedFileResult

object OneClickPreparationCoordinator {

    /**
     * Executes the complete One-Click Auto Prepare workflow for a photo input:
     * 1. Resolves unified input bitmap safely.
     * 2. Auto-orients image using EXIF metadata.
     * 3. Calculates aspect ratio and exact pixel dimensions from rule/preset.
     * 4. Crops and resizes without facial distortion.
     * 5. Adds name and date slate strip if required.
     * 6. Compresses according to target KB constraints.
     * 7. Validates generated output file.
     */
    suspend fun autoPreparePhoto(
        context: Context,
        input: UnifiedMediaInput,
        rule: DocumentRule,
        candidateName: String = "",
        dateOfPhoto: String = "",
        customWidthPx: Int? = null,
        customHeightPx: Int? = null,
        customTargetKb: Int? = null
    ): ProcessedFileResult? {
        val bitmap = input.resolveBitmap(context) ?: return null

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
            enforceProportional = true
        )
    }

    /**
     * Overload for automated test calls or coordinator flows returning Result.
     */
    suspend fun autoPreparePhoto(
        context: Context,
        mediaInput: UnifiedMediaInput,
        rule: DocumentRule,
        targetSizeKb: Int? = null,
        customDpi: Int? = null,
        candidateName: String = "",
        dateOfPhoto: String = ""
    ): Result<ProcessedFileResult> {
        val result = autoPreparePhoto(
            context = context,
            input = mediaInput,
            rule = rule,
            candidateName = candidateName,
            dateOfPhoto = dateOfPhoto,
            customTargetKb = targetSizeKb
        )
        return if (result != null) {
            Result.success(result)
        } else {
            Result.failure(IllegalStateException("Failed to process photo from media input"))
        }
    }

    /**
     * Executes the complete One-Click Prepare workflow for a signature or thumb impression:
     * 1. Resolves unified input bitmap.
     * 2. Detects and trims surrounding blank white/gray margins.
     * 3. Whitens paper background while preserving genuine ink strokes and pressure.
     * 4. Applies contrast enhancement and color mode.
     * 5. Resizes to exact prescribed dimensions.
     * 6. Compresses to strict KB limit.
     * 7. Validates generated output file.
     */
    suspend fun autoPrepareSignature(
        context: Context,
        input: UnifiedMediaInput,
        rule: DocumentRule,
        contrastMultiplier: Float = 1.35f,
        preserveInkColor: Boolean = true,
        trimBlankMargins: Boolean = true,
        customWidthPx: Int? = null,
        customHeightPx: Int? = null,
        customTargetKb: Int? = null
    ): ProcessedFileResult? {
        val bitmap = input.resolveBitmap(context) ?: return null

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
            contrastFactor = contrastMultiplier,
            preserveInkColor = preserveInkColor,
            autoCropMargins = trimBlankMargins
        )
    }

    /**
     * Overload for automated test calls or coordinator flows returning Result.
     */
    suspend fun autoPrepareSignature(
        context: Context,
        mediaInput: UnifiedMediaInput,
        rule: DocumentRule,
        targetSizeKb: Int? = null,
        contrastMultiplier: Float = 1.35f,
        preserveInkColor: Boolean = true,
        trimBlankMargins: Boolean = true
    ): Result<ProcessedFileResult> {
        val result = autoPrepareSignature(
            context = context,
            input = mediaInput,
            rule = rule,
            contrastMultiplier = contrastMultiplier,
            preserveInkColor = preserveInkColor,
            trimBlankMargins = trimBlankMargins,
            customTargetKb = targetSizeKb
        )
        return if (result != null) {
            Result.success(result)
        } else {
            Result.failure(IllegalStateException("Failed to process signature from media input"))
        }
    }
}
