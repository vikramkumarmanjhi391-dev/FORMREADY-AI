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
        val blueExcessArr = IntArray(totalPixels)
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
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val stdLum = (r * 299 + g * 587 + b * 114) / 1000
                // Blue ballpoint and gel inks absorb red/green while reflecting blue.
                // Incorporating blue excess into effective ink luminance preserves faint/light-blue strokes.
                val warmAvg = (r * 2 + g) / 3
                val blueEx = if (b > r + 4 && b > g + 2) (b - warmAvg).coerceAtLeast(0) else 0
                val effLum = (stdLum - (blueEx * 48) / 100).coerceIn(0, 255)

                lum[yOffset + x] = effLum
                blueExcessArr[yOffset + x] = blueEx
                rowSum += effLum
                rowSqSum += effLum.toLong() * effLum.toLong()
                integralSum[intCurrRow + x + 1] = integralSum[intPrevRow + x + 1] + rowSum
                integralSqSum[intCurrRow + x + 1] = integralSqSum[intPrevRow + x + 1] + rowSqSum
            }
        }

        val radiusMed = (min(width, height) * windowRadiusRatio).roundToInt().coerceIn(8, 48)
        val radiusFine = (radiusMed / 3).coerceIn(3, 8)
        val rDynamic = 128.0
        val k = sauvolaK.coerceIn(0.08f, 0.40f).toDouble()

        // 0 = background, 1 = weak/thin candidate ink, 2 = strong ink seed
        val inkState = ByteArray(totalPixels)
        val localDeltaArr = FloatArray(totalPixels)
        var rawCandidateCount = 0

        for (y in 0 until height) {
            val ym0 = max(0, y - radiusMed)
            val ym1 = min(height - 1, y + radiusMed)
            val rowM0 = ym0 * stride
            val rowM1 = (ym1 + 1) * stride

            val yf0 = max(0, y - radiusFine)
            val yf1 = min(height - 1, y + radiusFine)
            val rowF0 = yf0 * stride
            val rowF1 = (yf1 + 1) * stride

            val yOffset = y * width

            for (x in 0 until width) {
                val xm0 = max(0, x - radiusMed)
                val xm1 = min(width - 1, x + radiusMed)
                val countMed = ((xm1 - xm0 + 1) * (ym1 - ym0 + 1)).toDouble()

                val sumMed = (integralSum[rowM1 + xm1 + 1] - integralSum[rowM0 + xm1 + 1] -
                    integralSum[rowM1 + xm0] + integralSum[rowM0 + xm0]).toDouble()
                val sqSumMed = (integralSqSum[rowM1 + xm1 + 1] - integralSqSum[rowM0 + xm1 + 1] -
                    integralSqSum[rowM1 + xm0] + integralSqSum[rowM0 + xm0]).toDouble()

                val meanMed = sumMed / countMed
                val varMed = (sqSumMed / countMed - meanMed * meanMed).coerceAtLeast(0.0)
                val stdMed = sqrt(varMed)

                val xf0 = max(0, x - radiusFine)
                val xf1 = min(width - 1, x + radiusFine)
                val countFine = ((xf1 - xf0 + 1) * (yf1 - yf0 + 1)).toDouble()

                val sumFine = (integralSum[rowF1 + xf1 + 1] - integralSum[rowF0 + xf1 + 1] -
                    integralSum[rowF1 + xf0] + integralSum[rowF0 + xf0]).toDouble()
                val sqSumFine = (integralSqSum[rowF1 + xf1 + 1] - integralSqSum[rowF0 + xf1 + 1] -
                    integralSqSum[rowF1 + xf0] + integralSqSum[rowF0 + xf0]).toDouble()

                val meanFine = sumFine / countFine
                val varFine = (sqSumFine / countFine - meanFine * meanFine).coerceAtLeast(0.0)
                val stdFine = sqrt(varFine)

                val idx = yOffset + x
                val pixelLum = lum[idx].toDouble()
                val blueEx = blueExcessArr[idx]

                val paperMean = max(meanMed, meanFine)
                val localDelta = (paperMean - pixelLum).toFloat()
                localDeltaArr[idx] = localDelta

                val threshMed = meanMed * (1.0 + k * ((stdMed / rDynamic) - 1.0))
                val threshFine = meanFine * (1.0 + (k * 0.72) * ((stdFine / rDynamic) - 1.0))

                val blueContrastBonus = (blueEx * 0.22).coerceAtMost(6.5)
                val effDelta = localDelta.toDouble() + blueContrastBonus

                val isStrong = (pixelLum < threshMed - 4.5 && (stdMed >= 6.0 || stdFine >= 6.5 || pixelLum < 145.0 || effDelta >= 20.0)) ||
                    (effDelta >= 16.0 && (stdFine >= 5.0 || stdMed >= 4.5 || blueEx >= 14)) ||
                    (pixelLum < 118.0 && effDelta >= 9.0)

                val isWeak = !isStrong && (
                    (effDelta >= 8.5 && (stdFine >= 2.2 || stdMed >= 2.2 || blueEx >= 8)) ||
                        (pixelLum < threshFine - 1.5 && effDelta >= 7.5) ||
                        (pixelLum < threshMed - 1.5 && effDelta >= 7.5)
                    )

                if (isStrong) {
                    inkState[idx] = 2
                    rawCandidateCount++
                } else if (isWeak) {
                    inkState[idx] = 1
                    rawCandidateCount++
                }
            }
        }

        val rawCandidateRatio = rawCandidateCount.toFloat() / totalPixels.toFloat()
        if (rawCandidateCount < 8 || rawCandidateRatio > 0.65f) {
            val fallbackBmp = ImageProcessor.adjustSignatureLighting(
                src = sourceBitmap,
                contrastFactor = 1.15f,
                brightnessOffset = 8f,
                inkThreshold = 195,
                preserveInkColor = preserveInkColor
            )
            val fallbackBgMask = BooleanArray(totalPixels) { idx -> inkState[idx].toInt() == 0 }
            return@measureStage SignatureExtractionResult(
                processedBitmap = fallbackBmp,
                inkBoundingBoxPx = Rect(0, 0, width, height),
                inkPixelRatio = rawCandidateRatio,
                backgroundMask = fallbackBgMask,
                usedAdaptiveSauvola = false,
                fallbackReason = "Insufficient local stroke contrast — applied gentle ink processing fallback.",
                verifiedInkPixelCount = rawCandidateCount,
                strokeBoundingBoxFound = false,
                strokeBoxWidthRatio = 0f,
                strokeBoxHeightRatio = 0f
            )
        }

        // Connected-component hysteresis & proximity-aware dot preservation:
        // 1. Rejects isolated 1-pixel sensor specks and paper grain.
        // 2. Preserves continuous 1px/2px thin strokes (diagonal, curved, low-contrast) connected to strong seeds.
        // 3. Preserves small genuine disconnected dots/marks (Hindi bindu/nukta, 'i'/'j' dots, periods)
        //    near handwriting while rejecting isolated margin specks.
        val visited = BooleanArray(totalPixels)
        val queue = IntArray(totalPixels)
        val compMembers = ArrayList<IntArray>()
        val compStrongCounts = IntArray(totalPixels / 2 + 1)
        val compMaxDeltas = FloatArray(totalPixels / 2 + 1)
        val compMinX = IntArray(totalPixels / 2 + 1)
        val compMaxX = IntArray(totalPixels / 2 + 1)
        val compMinY = IntArray(totalPixels / 2 + 1)
        val compMaxY = IntArray(totalPixels / 2 + 1)
        val compTouchesOuterEdge = BooleanArray(totalPixels / 2 + 1)

        var primaryMinX = width
        var primaryMinY = height
        var primaryMaxX = -1
        var primaryMaxY = -1
        var hasPrimaryStroke = false

        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val startIdx = yOffset + x
                if (inkState[startIdx].toInt() == 0 || visited[startIdx]) continue

                var head = 0
                var tail = 0
                queue[tail++] = startIdx
                visited[startIdx] = true

                var strongCount = 0
                var maxDelta = 0f
                var cMinX = x
                var cMaxX = x
                var cMinY = y
                var cMaxY = y
                var borderPixelCount = 0

                while (head < tail) {
                    val curr = queue[head++]
                    val cy = curr / width
                    val cx = curr - cy * width

                    if (inkState[curr].toInt() == 2) strongCount++
                    val d = localDeltaArr[curr]
                    if (d > maxDelta) maxDelta = d

                    if (cx < cMinX) cMinX = cx
                    if (cx > cMaxX) cMaxX = cx
                    if (cy < cMinY) cMinY = cy
                    if (cy > cMaxY) cMaxY = cy
                    if (cx == 0 || cx == width - 1 || cy == 0 || cy == height - 1) {
                        borderPixelCount++
                    }

                    val ny0 = max(0, cy - 1)
                    val ny1 = min(height - 1, cy + 1)
                    val nx0 = max(0, cx - 1)
                    val nx1 = min(width - 1, cx + 1)
                    for (ny in ny0..ny1) {
                        val nRow = ny * width
                        for (nx in nx0..nx1) {
                            val nIdx = nRow + nx
                            if (!visited[nIdx] && inkState[nIdx].toInt() > 0) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                val size = tail
                // Single-pixel isolated sensor noise is always rejected
                if (size <= 1) continue

                // Reject 1px outer border frame artifacts that hug only the extreme image boundary
                val spanW = cMaxX - cMinX + 1
                val spanH = cMaxY - cMinY + 1
                val isPureEdgeArtifact = borderPixelCount == size && (spanW <= 1 || spanH <= 1)
                if (isPureEdgeArtifact) continue

                val compIdx = compMembers.size
                compMembers.add(queue.copyOfRange(0, size))
                compStrongCounts[compIdx] = strongCount
                compMaxDeltas[compIdx] = maxDelta
                compMinX[compIdx] = cMinX
                compMaxX[compIdx] = cMaxX
                compMinY[compIdx] = cMinY
                compMaxY[compIdx] = cMaxY
                compTouchesOuterEdge[compIdx] = borderPixelCount > (size * 3) / 4 && (spanW <= 2 || spanH <= 2)

                // Primary handwriting stroke/character component
                val isPrimary = !compTouchesOuterEdge[compIdx] && (
                    (size >= 6 && (strongCount >= 1 || maxDelta >= 10.0f)) ||
                        (size in 4..5 && (strongCount >= 1 || maxDelta >= 15.0f))
                    )
                if (isPrimary) {
                    hasPrimaryStroke = true
                    if (cMinX < primaryMinX) primaryMinX = cMinX
                    if (cMaxX > primaryMaxX) primaryMaxX = cMaxX
                    if (cMinY < primaryMinY) primaryMinY = cMinY
                    if (cMaxY > primaryMaxY) primaryMaxY = cMaxY
                }
            }
        }

        // Determine proximity margin around primary strokes so genuine Hindi dots (bindu/anusvara),
        // 'i'/'j' dots, periods, and short marks are preserved while distant margin noise is rejected.
        val proximityPadX = max((width * 0.12f).roundToInt(), 18)
        val proximityPadY = max((height * 0.25f).roundToInt(), 20)

        val verifiedInkMask = BooleanArray(totalPixels)
        var verifiedStrokeCount = 0
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        for (compIdx in 0 until compMembers.size) {
            if (compTouchesOuterEdge[compIdx]) continue
            val members = compMembers[compIdx]
            val size = members.size
            val strongCount = compStrongCounts[compIdx]
            val maxDelta = compMaxDeltas[compIdx]
            val cMinX = compMinX[compIdx]
            val cMaxX = compMaxX[compIdx]
            val cMinY = compMinY[compIdx]
            val cMaxY = compMaxY[compIdx]

            val isPrimary = (size >= 6 && (strongCount >= 1 || maxDelta >= 10.0f)) ||
                (size in 4..5 && (strongCount >= 1 || maxDelta >= 15.0f))

            val isNearPrimary = hasPrimaryStroke &&
                cMaxX >= (primaryMinX - proximityPadX) &&
                cMinX <= (primaryMaxX + proximityPadX) &&
                cMaxY >= (primaryMinY - proximityPadY) &&
                cMinY <= (primaryMaxY + proximityPadY)

            // 2-to-3 pixel genuine dots/marks must have strong local contrast and be near handwriting
            val isValidSmallDot = size in 2..3 &&
                (strongCount >= 2 || maxDelta >= 22.0f) &&
                (isNearPrimary || (!hasPrimaryStroke && maxDelta >= 32.0f))

            if (!isPrimary && !isValidSmallDot) continue

            for (idx in members) {
                verifiedInkMask[idx] = true
            }
            verifiedStrokeCount += size
            if (cMinX < minX) minX = cMinX
            if (cMaxX > maxX) maxX = cMaxX
            if (cMinY < minY) minY = cMinY
            if (cMaxY > maxY) maxY = cMaxY
        }

        val verifiedRatio = verifiedStrokeCount.toFloat() / totalPixels.toFloat()
        if (verifiedStrokeCount < 8 || verifiedRatio > 0.65f || maxX < minX || maxY < minY) {
            val fallbackBmp = ImageProcessor.adjustSignatureLighting(
                src = sourceBitmap,
                contrastFactor = 1.15f,
                brightnessOffset = 8f,
                inkThreshold = 195,
                preserveInkColor = preserveInkColor
            )
            val fallbackBgMask = BooleanArray(totalPixels) { idx -> !verifiedInkMask[idx] }
            return@measureStage SignatureExtractionResult(
                processedBitmap = fallbackBmp,
                inkBoundingBoxPx = Rect(0, 0, width, height),
                inkPixelRatio = verifiedRatio,
                backgroundMask = fallbackBgMask,
                usedAdaptiveSauvola = false,
                fallbackReason = "Insufficient verified handwriting strokes — applied gentle ink processing fallback.",
                verifiedInkPixelCount = verifiedStrokeCount,
                strokeBoundingBoxFound = false,
                strokeBoxWidthRatio = 0f,
                strokeBoxHeightRatio = 0f
            )
        }

        val outPixels = IntArray(totalPixels) { Color.WHITE }
        val cleanBgMask = BooleanArray(totalPixels) { true }
        val clampedBoost = contrastBoost.coerceIn(1.0f, 1.75f)
        val darkenScale = (0.78f / sqrt(clampedBoost.toDouble()).toFloat()).coerceIn(0.56f, 0.80f)

        for (idx in 0 until totalPixels) {
            if (!verifiedInkMask[idx]) continue
            cleanBgMask[idx] = false

            val origColor = pixels[idx]
            if (!preserveInkColor) {
                outPixels[idx] = Color.rgb(18, 18, 22)
            } else {
                val r = Color.red(origColor)
                val g = Color.green(origColor)
                val b = Color.blue(origColor)

                // Detect blue ink across vivid ballpoint, dark blue gel, and low-saturation blue pens
                val isBlueInk = (b >= r + 5 && b >= g + 3) ||
                    (b >= r + 4 && b > g && b >= 42 && (r + g) < b * 2 - 6)

                if (isBlueInk) {
                    val blueAdvantage = max(b - r, b - g)
                    val warmScale = (darkenScale * 0.80f).coerceIn(0.44f, 0.68f)
                    var outR = (r * warmScale).roundToInt().coerceIn(0, 128)
                    var outG = (g * warmScale * 1.03f).roundToInt().coerceIn(0, 136)
                    // Preserve dark blue gel depth while keeping light/medium ballpoint blue vivid and clear
                    val minBlueFloor = if (b < 85) 38 else 62
                    var outB = max((b * 0.90f).roundToInt(), (b * darkenScale + blueAdvantage * 0.65f).roundToInt())
                        .coerceIn(minBlueFloor, 225)

                    // Ensure blue dominance (outB > outG >= outR) so blue ink never collapses into grey/black
                    val minAdvantage = max(8, (blueAdvantage * 0.70f).roundToInt().coerceAtMost(42))
                    if (outB < outR + minAdvantage) {
                        outR = (outB - minAdvantage).coerceAtLeast(0)
                    }
                    if (outB < outG + max(5, minAdvantage - 3)) {
                        outG = (outB - max(5, minAdvantage - 3)).coerceAtLeast(0)
                    }

                    // Ensure faint thin strokes have legible contrast on pure white paper (lum <= 178)
                    val outLum = (outR * 299 + outG * 587 + outB * 114) / 1000
                    if (outLum > 178) {
                        val lumScale = 178f / outLum.toFloat()
                        outR = (outR * lumScale).roundToInt().coerceIn(0, 115)
                        outG = (outG * lumScale).roundToInt().coerceIn(0, 125)
                        outB = max((outB * lumScale).roundToInt(), outG + 8).coerceIn(42, 215)
                    }
                    outPixels[idx] = Color.rgb(outR, outG, outB)
                } else {
                    // Black, charcoal, or neutral dark pen ink: deepen naturally without washing out thin strokes
                    var outR = (r * darkenScale).roundToInt().coerceIn(8, 138)
                    var outG = (g * darkenScale).roundToInt().coerceIn(8, 138)
                    var outB = (b * darkenScale).roundToInt().coerceIn(8, 142)
                    val outLum = (outR * 299 + outG * 587 + outB * 114) / 1000
                    if (outLum > 168) {
                        val lumScale = 168f / outLum.toFloat()
                        outR = (outR * lumScale).roundToInt().coerceIn(8, 130)
                        outG = (outG * lumScale).roundToInt().coerceIn(8, 130)
                        outB = (outB * lumScale).roundToInt().coerceIn(8, 134)
                    }
                    outPixels[idx] = Color.rgb(outR, outG, outB)
                }
            }
        }

        val fullProcessed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fullProcessed.setPixels(outPixels, 0, width, 0, 0, width, height)

        val strokeSpanW = maxX - minX + 1
        val strokeSpanH = maxY - minY + 1
        val validBoundsFound = verifiedStrokeCount >= 8 &&
            strokeSpanW >= max(4, (width * 0.04f).toInt()) &&
            strokeSpanH >= max(3, (height * 0.03f).toInt())

        val rawBoxWidthRatio = strokeSpanW.toFloat() / width.toFloat()
        val rawBoxHeightRatio = strokeSpanH.toFloat() / height.toFloat()

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

        // Safe margin padding around the full verified ink bounding box (including dots and edge strokes)
        // Use maxX + 1 and maxY + 1 because maxX/maxY are inclusive pixel indices.
        val padX = max((strokeSpanW * 0.12f).roundToInt(), (width * 0.03f).roundToInt()).coerceAtLeast(8)
        val padY = max((strokeSpanH * 0.20f).roundToInt(), (height * 0.05f).roundToInt()).coerceAtLeast(6)
        val cropLeft = (minX - padX).coerceIn(0, width - 1)
        val cropTop = (minY - padY).coerceIn(0, height - 1)
        val cropRight = (maxX + 1 + padX).coerceIn(cropLeft + 1, width)
        val cropBottom = (maxY + 1 + padY).coerceIn(cropTop + 1, height)
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

        val croppedBoxW = strokeSpanW.toFloat() / cropW.toFloat()
        val croppedBoxH = strokeSpanH.toFloat() / cropH.toFloat()

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

