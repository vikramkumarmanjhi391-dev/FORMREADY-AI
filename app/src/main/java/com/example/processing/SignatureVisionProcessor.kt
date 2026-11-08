package com.example.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.data.model.DocumentType
import com.example.data.model.SignatureProcessingMethod
import com.example.data.model.SignatureValidationEvidence
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase 3A & Phase 0.4A: On-Device Signature & Thumb Impression Vision Processor.
 *
 * Uses Integral-Image Sauvola Local Adaptive Thresholding ($O(1)$ per pixel) to separate
 * genuine handwriting strokes and thumbprint ridges from uneven paper illumination and mobile
 * phone shadows, followed by density-filtered bounding-box extraction and saved-output validation.
 *
 * Distinguishes Sauvola adaptive thresholding from gentle contrast fallback and applies
 * separate calibrated validation thresholds for Handwritten Signatures vs. Thumb Impressions.
 */
object SignatureVisionProcessor {

    const val SIGNATURE_MIN_INK_PIXELS = 25
    const val SIGNATURE_MIN_INK_RATIO = 0.008f
    const val SIGNATURE_MAX_INK_RATIO = 0.28f
    const val SIGNATURE_MIN_BOX_WIDTH_RATIO = 0.15f
    const val SIGNATURE_MIN_BOX_HEIGHT_RATIO = 0.08f

    const val THUMB_MIN_INK_PIXELS = 80
    const val THUMB_MIN_INK_RATIO = 0.060f
    const val THUMB_MAX_INK_RATIO = 0.58f
    const val THUMB_MIN_BOX_WIDTH_RATIO = 0.20f
    const val THUMB_MIN_BOX_HEIGHT_RATIO = 0.20f

    const val MIN_PAPER_WHITENESS_RATIO = 0.85f
    const val MIN_PAPER_MEAN_LUMINANCE = 242

    fun isThumbImpressionType(type: DocumentType): Boolean =
        type == DocumentType.THUMB_IMPRESSION_LEFT ||
            type == DocumentType.THUMB_IMPRESSION_RIGHT

    fun isSignatureOrThumbType(type: DocumentType): Boolean =
        type == DocumentType.SIGNATURE_ENGLISH ||
            type == DocumentType.SIGNATURE_HINDI ||
            type == DocumentType.DECLARATION_HANDWRITTEN ||
            isThumbImpressionType(type)

    data class SignatureExtractionResult(
        val processedBitmap: Bitmap,
        val inkBoundingBoxPx: Rect,
        val inkPixelRatio: Float,
        val backgroundMask: BooleanArray,
        val usedAdaptiveSauvola: Boolean,
        val fallbackReason: String? = null,
        val verifiedInkPixelCount: Int = 0,
        val strokeBoundingBoxFound: Boolean = false,
        val strokeBoxWidthRatio: Float = 0f,
        val strokeBoxHeightRatio: Float = 0f
    ) {
        val cleanedBitmap: Bitmap
            get() = processedBitmap
        val usedGentleFallback: Boolean
            get() = !usedAdaptiveSauvola
    }

    fun extractSignatureOrThumb(
        src: Bitmap,
        preserveInkColor: Boolean = true,
        contrastBoost: Float = 1.25f,
        sauvolaK: Float = 0.18f,
        trimToInkBounds: Boolean = true
    ): SignatureExtractionResult = extractSignatureOrThumb(
        sourceBitmap = src,
        preserveInkColor = preserveInkColor,
        trimMargins = trimToInkBounds,
        sauvolaK = sauvolaK,
        contrastBoost = contrastBoost
    )

    /**
     * Processes a signature or thumb impression bitmap using local adaptive Sauvola thresholding:
     * 1. Computes luminance integral images (`sum` and `sqSum`) in a single pass.
     * 2. Evaluates local mean $m(x,y)$ and local standard deviation $s(x,y)$ over a
     *    $(2r+1)\times(2r+1)$ window:
     *    $$T(x,y) = m(x,y) \cdot \left[1 + k \cdot \left(\frac{s(x,y)}{R} - 1\right)\right]$$
     * 3. Preserves natural blue or black pen ink chroma on foreground strokes while turning
     *    paper and uneven shadow pixels to pure `#FFFFFF` white.
     * 4. Computes a noise-filtered bounding box around genuine ink strokes and crops with a safe
     *    margin when [trimMargins] is true.
     */
    fun extractSignatureOrThumb(
        sourceBitmap: Bitmap,
        preserveInkColor: Boolean = true,
        trimMargins: Boolean = true,
        sauvolaK: Float = 0.18f,
        windowRadiusRatio: Float = 0.045f,
        contrastBoost: Float = 1.25f
    ): SignatureExtractionResult = VisionBenchmarkRecorder.measureStage(
        stage = VisionBenchmarkRecorder.Stage.SAUVOLA_SIGNATURE,
        imageWidth = sourceBitmap.width,
        imageHeight = sourceBitmap.height
    ) {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        if (width < 8 || height < 8) {
            return@measureStage SignatureExtractionResult(
                processedBitmap = sourceBitmap,
                inkBoundingBoxPx = Rect(0, 0, width, height),
                inkPixelRatio = 0f,
                backgroundMask = BooleanArray((width * height).coerceAtLeast(1)) { true },
                usedAdaptiveSauvola = false,
                fallbackReason = "Image too small for adaptive thresholding",
                verifiedInkPixelCount = 0,
                strokeBoundingBoxFound = false,
                strokeBoxWidthRatio = 0f,
                strokeBoxHeightRatio = 0f
            )
        }

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val lum = IntArray(totalPixels)
        val stride = width + 1
        val integralSum = LongArray((width + 1) * (height + 1))
        val integralSqSum = LongArray((width + 1) * (height + 1))

        for (y in 0 until height) {
            var rowSum = 0L
            var rowSqSum = 0L
            val yOffset = y * width
            val intCurrRow = (y + 1) * stride
            val intPrevRow = y * stride
            for (x in 0 until width) {
                val c = pixels[yOffset + x]
                val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                lum[yOffset + x] = l
                rowSum += l
                rowSqSum += l.toLong() * l.toLong()
                integralSum[intCurrRow + x + 1] = integralSum[intPrevRow + x + 1] + rowSum
                integralSqSum[intCurrRow + x + 1] = integralSqSum[intPrevRow + x + 1] + rowSqSum
            }
        }

        val radius = (min(width, height) * windowRadiusRatio).roundToInt().coerceIn(8, 48)
        val rDynamic = 128.0
        val k = sauvolaK.coerceIn(0.08f, 0.40f).toDouble()

        val isInkPixel = BooleanArray(totalPixels)
        var rawInkCount = 0

        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            val row0 = y0 * stride
            val row1 = (y1 + 1) * stride
            val yOffset = y * width

            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                val count = ((x1 - x0 + 1) * (y1 - y0 + 1)).toDouble()

                val sum = (integralSum[row1 + x1 + 1] - integralSum[row0 + x1 + 1] -
                    integralSum[row1 + x0] + integralSum[row0 + x0]).toDouble()
                val sqSum = (integralSqSum[row1 + x1 + 1] - integralSqSum[row0 + x1 + 1] -
                    integralSqSum[row1 + x0] + integralSqSum[row0 + x0]).toDouble()

                val mean = sum / count
                val variance = (sqSum / count - mean * mean).coerceAtLeast(0.0)
                val stdDev = sqrt(variance)

                val localThreshold = mean * (1.0 + k * ((stdDev / rDynamic) - 1.0))
                val pixelLum = lum[yOffset + x].toDouble()

                // Require local contrast (stdDev >= 7.5) or dark absolute ink (pixelLum < 115)
                // so uniform grey paper shadows never trigger false ink pixels
                if (pixelLum < localThreshold - 6.0 && (stdDev >= 7.5 || pixelLum < 115.0)) {
                    isInkPixel[yOffset + x] = true
                    rawInkCount++
                }
            }
        }

        val inkRatio = rawInkCount.toFloat() / totalPixels.toFloat()
        // If no meaningful strokes were detected (or >65% of image triggered), fall back safely
        if (rawInkCount < 12 || inkRatio > 0.65f) {
            val fallbackBmp = ImageProcessor.adjustSignatureLighting(
                src = sourceBitmap,
                contrastFactor = 1.15f,
                brightnessOffset = 8f,
                inkThreshold = 195,
                preserveInkColor = preserveInkColor
            )
            val fallbackBgMask = BooleanArray(totalPixels) { idx -> !isInkPixel[idx] }
            return@measureStage SignatureExtractionResult(
                processedBitmap = fallbackBmp,
                inkBoundingBoxPx = Rect(0, 0, width, height),
                inkPixelRatio = inkRatio,
                backgroundMask = fallbackBgMask,
                usedAdaptiveSauvola = false,
                fallbackReason = "Insufficient local stroke contrast — applied gentle ink processing fallback.",
                verifiedInkPixelCount = rawInkCount,
                strokeBoundingBoxFound = false,
                strokeBoxWidthRatio = 0f,
                strokeBoxHeightRatio = 0f
            )
        }

        // Filter isolated 1-pixel noise specks before computing stroke bounding box and output pixels
        val outPixels = IntArray(totalPixels) { Color.WHITE }
        val cleanBgMask = BooleanArray(totalPixels) { true }

        val borderMarginX = (width * 0.02f).toInt().coerceAtLeast(1)
        val borderMarginY = (height * 0.02f).toInt().coerceAtLeast(1)
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var verifiedStrokeCount = 0

        for (y in 1 until height - 1) {
            val yOffset = y * width
            for (x in 1 until width - 1) {
                val idx = yOffset + x
                if (!isInkPixel[idx]) continue

                // Count 3x3 neighborhood ink support to reject isolated single-pixel sensor noise
                var neighbors = 0
                for (dy in -1..1) {
                    val ny = (y + dy) * width
                    for (dx in -1..1) {
                        if (isInkPixel[ny + x + dx]) neighbors++
                    }
                }
                if (neighbors < 2) continue

                cleanBgMask[idx] = false
                verifiedStrokeCount++

                if (x in borderMarginX until (width - borderMarginX) &&
                    y in borderMarginY until (height - borderMarginY)
                ) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }

                val origColor = pixels[idx]
                if (!preserveInkColor) {
                    outPixels[idx] = Color.rgb(18, 18, 22)
                } else {
                    val r = Color.red(origColor)
                    val g = Color.green(origColor)
                    val b = Color.blue(origColor)
                    // Deepen genuine blue/black ink while preserving natural pen hue
                    val boost = (1f / contrastBoost.coerceIn(1.0f, 2.0f))
                    val outR = (r * 0.65f * boost).roundToInt().coerceIn(0, 140)
                    val outG = (g * 0.65f * boost).roundToInt().coerceIn(0, 140)
                    val outB = if (b > r + 12 && b > g + 8) {
                        // Preserve blue ballpoint/gel pen ink richness
                        (b * 0.85f).roundToInt().coerceIn(45, 210)
                    } else {
                        (b * 0.65f * boost).roundToInt().coerceIn(0, 150)
                    }
                    outPixels[idx] = Color.rgb(outR, outG, outB)
                }
            }
        }

        val fullProcessed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fullProcessed.setPixels(outPixels, 0, width, 0, 0, width, height)

        val validBoundsFound = verifiedStrokeCount >= 10 &&
            maxX > minX && maxY > minY &&
            (maxX - minX) >= (width * 0.06f).toInt() &&
            (maxY - minY) >= (height * 0.05f).toInt()

        val rawBoxWidthRatio = if (maxX > minX) (maxX - minX + 1).toFloat() / width.toFloat() else 0f
        val rawBoxHeightRatio = if (maxY > minY) (maxY - minY + 1).toFloat() / height.toFloat() else 0f

        if (!trimMargins || !validBoundsFound) {
            return@measureStage SignatureExtractionResult(
                processedBitmap = fullProcessed,
                inkBoundingBoxPx = Rect(0, 0, width, height),
                inkPixelRatio = verifiedStrokeCount.toFloat() / totalPixels.toFloat(),
                backgroundMask = cleanBgMask,
                usedAdaptiveSauvola = true,
                verifiedInkPixelCount = verifiedStrokeCount,
                strokeBoundingBoxFound = validBoundsFound,
                strokeBoxWidthRatio = rawBoxWidthRatio,
                strokeBoxHeightRatio = rawBoxHeightRatio
            )
        }

        val padX = ((maxX - minX) * 0.12f).roundToInt().coerceAtLeast(8)
        val padY = ((maxY - minY) * 0.18f).roundToInt().coerceAtLeast(6)
        val cropLeft = (minX - padX).coerceAtLeast(0)
        val cropTop = (minY - padY).coerceAtLeast(0)
        val cropRight = (maxX + padX).coerceAtMost(width)
        val cropBottom = (maxY + padY).coerceAtMost(height)
        val cropW = (cropRight - cropLeft).coerceAtLeast(1)
        val cropH = (cropBottom - cropTop).coerceAtLeast(1)

        val croppedBmp = Bitmap.createBitmap(fullProcessed, cropLeft, cropTop, cropW, cropH)
        if (croppedBmp !== fullProcessed && !fullProcessed.isRecycled) {
            fullProcessed.recycle()
        }

        val croppedMask = BooleanArray(cropW * cropH)
        var croppedInkCount = 0
        for (cy in 0 until cropH) {
            val srcRow = (cropTop + cy) * width + cropLeft
            val dstRow = cy * cropW
            for (cx in 0 until cropW) {
                val isBg = cleanBgMask[srcRow + cx]
                croppedMask[dstRow + cx] = isBg
                if (!isBg) croppedInkCount++
            }
        }

        val croppedBoxW = (maxX - minX + 1).toFloat() / cropW.toFloat()
        val croppedBoxH = (maxY - minY + 1).toFloat() / cropH.toFloat()

        SignatureExtractionResult(
            processedBitmap = croppedBmp,
            inkBoundingBoxPx = Rect(cropLeft, cropTop, cropRight, cropBottom),
            inkPixelRatio = croppedInkCount.toFloat() / (cropW * cropH).toFloat(),
            backgroundMask = croppedMask,
            usedAdaptiveSauvola = true,
            verifiedInkPixelCount = croppedInkCount,
            strokeBoundingBoxFound = true,
            strokeBoxWidthRatio = croppedBoxW.coerceIn(0f, 1f),
            strokeBoxHeightRatio = croppedBoxH.coerceIn(0f, 1f)
        )
    }

    /**
     * Validates the actual saved signature or thumb-impression bitmap on disk.
     *
     * Important architectural invariants:
     * 1. Never uses portrait segmentation fields (`segmentationExecuted`, `subjectMaskConfidence`,
     *    `edgeUncertaintyRatio`).
     * 2. Distinguishes `sauvolaExecuted` from `usedGentleFallback`; `usedGentleFallback == true`
     *    NEVER acts as proof of compliance or bypasses ink/whiteness bounds.
     * 3. Enforces separate calibrated bounds for Handwritten Signatures vs. Thumb Impressions.
     */
    fun validateSavedSignatureOrThumbImage(
        savedBitmap: Bitmap,
        documentType: DocumentType,
        extractionResult: SignatureExtractionResult? = null
    ): SignatureValidationEvidence {
        val isThumb = isThumbImpressionType(documentType)
        val minRequiredPixels = if (isThumb) THUMB_MIN_INK_PIXELS else SIGNATURE_MIN_INK_PIXELS
        val minRequiredRatio = if (isThumb) THUMB_MIN_INK_RATIO else SIGNATURE_MIN_INK_RATIO
        val maxAllowedRatio = if (isThumb) THUMB_MAX_INK_RATIO else SIGNATURE_MAX_INK_RATIO
        val minBoxWidthRatio = if (isThumb) THUMB_MIN_BOX_WIDTH_RATIO else SIGNATURE_MIN_BOX_WIDTH_RATIO
        val minBoxHeightRatio = if (isThumb) THUMB_MIN_BOX_HEIGHT_RATIO else SIGNATURE_MIN_BOX_HEIGHT_RATIO

        val width = savedBitmap.width
        val height = savedBitmap.height
        val totalPixels = (width * height).coerceAtLeast(1)

        val sauvolaExecuted = extractionResult?.usedAdaptiveSauvola ?: false
        val usedGentleFallback = extractionResult?.usedGentleFallback ?: false
        val method = if (sauvolaExecuted && !usedGentleFallback) {
            SignatureProcessingMethod.SAUVOLA_ADAPTIVE_THRESHOLD
        } else {
            SignatureProcessingMethod.GENTLE_CONTRAST_FALLBACK
        }

        if (width < 8 || height < 8) {
            return SignatureValidationEvidence(
                processingMethod = method,
                sauvolaExecuted = sauvolaExecuted,
                usedGentleFallback = usedGentleFallback,
                fallbackReason = extractionResult?.fallbackReason ?: "Saved image dimensions too small",
                verifiedInkPixelCount = 0,
                inkPixelRatio = 0f,
                minRequiredInkRatio = minRequiredRatio,
                maxAllowedInkRatio = maxAllowedRatio,
                inkPresenceVerified = false,
                inkRatioWithinBounds = false,
                strokeBoundingBoxFound = false,
                strokeBoxWidthRatio = 0f,
                strokeBoxHeightRatio = 0f,
                paperWhitenessRatio = 0f,
                paperMeanLuminance = 0
            )
        }

        val pixels = IntArray(totalPixels)
        savedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val rawInkMask = BooleanArray(totalPixels)
        for (idx in 0 until totalPixels) {
            val c = pixels[idx]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val isBlueInk = b > r + 18 && b > g + 10 && lum < 228
            if (lum < 210 || isBlueInk) {
                rawInkMask[idx] = true
            }
        }

        var verifiedInkCount = 0
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        var paperCount = 0
        var nearWhitePaperCount = 0
        var paperLumSum = 0L

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val idx = rowOffset + x
                var isVerifiedInk = false
                if (rawInkMask[idx]) {
                    var neighbors = 0
                    val y0 = max(0, y - 1)
                    val y1 = min(height - 1, y + 1)
                    val x0 = max(0, x - 1)
                    val x1 = min(width - 1, x + 1)
                    for (ny in y0..y1) {
                        val nRow = ny * width
                        for (nx in x0..x1) {
                            if (rawInkMask[nRow + nx]) neighbors++
                        }
                    }
                    if (neighbors >= 2) {
                        isVerifiedInk = true
                    }
                }

                if (isVerifiedInk) {
                    verifiedInkCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                } else {
                    val c = pixels[idx]
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    val chromaDiff = maxOf(r, g, b) - minOf(r, g, b)
                    paperCount++
                    paperLumSum += lum
                    if (r >= 240 && g >= 240 && b >= 240 && chromaDiff <= 15) {
                        nearWhitePaperCount++
                    }
                }
            }
        }

        val inkRatio = verifiedInkCount.toFloat() / totalPixels.toFloat()
        val boxWidthRatio = if (maxX >= minX) (maxX - minX + 1).toFloat() / width.toFloat() else 0f
        val boxHeightRatio = if (maxY >= minY) (maxY - minY + 1).toFloat() / height.toFloat() else 0f

        val paperWhitenessRatio = if (paperCount > 0) nearWhitePaperCount.toFloat() / paperCount.toFloat() else 0f
        val paperMeanLum = if (paperCount > 0) (paperLumSum / paperCount).toInt() else 0

        val inkPresenceVerified = verifiedInkCount >= minRequiredPixels
        val inkRatioWithinBounds = inkRatio in minRequiredRatio..maxAllowedRatio
        val strokeBoundingBoxFound = inkPresenceVerified &&
            boxWidthRatio >= minBoxWidthRatio &&
            boxHeightRatio >= minBoxHeightRatio

        return SignatureValidationEvidence(
            processingMethod = method,
            sauvolaExecuted = sauvolaExecuted,
            usedGentleFallback = usedGentleFallback,
            fallbackReason = extractionResult?.fallbackReason,
            verifiedInkPixelCount = verifiedInkCount,
            inkPixelRatio = inkRatio,
            minRequiredInkRatio = minRequiredRatio,
            maxAllowedInkRatio = maxAllowedRatio,
            inkPresenceVerified = inkPresenceVerified,
            inkRatioWithinBounds = inkRatioWithinBounds,
            strokeBoundingBoxFound = strokeBoundingBoxFound,
            strokeBoxWidthRatio = boxWidthRatio,
            strokeBoxHeightRatio = boxHeightRatio,
            paperWhitenessRatio = paperWhitenessRatio,
            paperMeanLuminance = paperMeanLum
        )
    }
}

