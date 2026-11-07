package com.example.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase 3A: On-Device Signature & Thumb Impression Vision Processor.
 *
 * Uses Integral-Image Sauvola Local Adaptive Thresholding ($O(1)$ per pixel) to separate
 * genuine handwriting strokes and thumbprint ridges from uneven paper illumination and mobile
 * phone shadows, followed by density-filtered bounding-box extraction.
 *
 * Includes safe fallback to gentle contrast adjustment when stroke coverage is too low.
 */
object SignatureVisionProcessor {

    data class SignatureExtractionResult(
        val processedBitmap: Bitmap,
        val inkBoundingBoxPx: Rect,
        val inkPixelRatio: Float,
        val backgroundMask: BooleanArray,
        val usedAdaptiveSauvola: Boolean,
        val fallbackReason: String? = null
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
                fallbackReason = "Image too small for adaptive thresholding"
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
                fallbackReason = "Insufficient local stroke contrast — applied gentle ink processing fallback."
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

        if (!trimMargins || !validBoundsFound) {
            return@measureStage SignatureExtractionResult(
                processedBitmap = fullProcessed,
                inkBoundingBoxPx = Rect(0, 0, width, height),
                inkPixelRatio = verifiedStrokeCount.toFloat() / totalPixels.toFloat(),
                backgroundMask = cleanBgMask,
                usedAdaptiveSauvola = true
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
        for (cy in 0 until cropH) {
            val srcRow = (cropTop + cy) * width + cropLeft
            val dstRow = cy * cropW
            for (cx in 0 until cropW) {
                croppedMask[dstRow + cx] = cleanBgMask[srcRow + cx]
            }
        }

        SignatureExtractionResult(
            processedBitmap = croppedBmp,
            inkBoundingBoxPx = Rect(cropLeft, cropTop, cropRight, cropBottom),
            inkPixelRatio = verifiedStrokeCount.toFloat() / totalPixels.toFloat(),
            backgroundMask = croppedMask,
            usedAdaptiveSauvola = true
        )
    }
}
