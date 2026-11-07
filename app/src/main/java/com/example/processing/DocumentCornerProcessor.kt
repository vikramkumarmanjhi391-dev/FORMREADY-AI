package com.example.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase 3B: On-Device Document & ID Card 4-Corner Detection and Homography Perspective Warp.
 *
 * Responsibilities:
 * 1. Detects the 4 corners (`topLeft`, `topRight`, `bottomRight`, `bottomLeft` in normalized
 *    `[0..1]` coordinates) of a photographed page or ID card using luminance contrast / Sobel
 *    gradient analysis.
 * 2. Computes a quadrilateral confidence score (`0.0f..1.0f`) based on convexity, interior corner
 *    angles (`45°..135°`), area coverage (`>= 18%`), and boundary gradient support.
 * 3. Flags uncertain detections (`confidence < 0.75f`) so the UI presents interactive 4-corner
 *    drag handles rather than silently warping random background edges.
 * 4. Solves the 8-DOF 3×3 projective homography matrix and performs bilinear perspective
 *    rectification into a flat, upright rectangular [Bitmap].
 */
object DocumentCornerProcessor {

    const val AUTO_WARP_CONFIDENCE_THRESHOLD = 0.75f

    data class DocumentQuadNorm(
        val topLeft: PointF,
        val topRight: PointF,
        val bottomRight: PointF,
        val bottomLeft: PointF,
        val confidence: Float,
        val requiresUserConfirmation: Boolean = confidence < AUTO_WARP_CONFIDENCE_THRESHOLD
    ) {
        fun toPointsList(): List<PointF> = listOf(topLeft, topRight, bottomRight, bottomLeft)

        val cornersNorm: List<PointF>
            get() = toPointsList()

        val autoWarpEligible: Boolean
            get() = !requiresUserConfirmation

        companion object {
            fun defaultInsetFrame(marginNorm: Float = 0.07f): DocumentQuadNorm {
                val m = marginNorm.coerceIn(0.02f, 0.25f)
                return DocumentQuadNorm(
                    topLeft = PointF(m, m),
                    topRight = PointF(1f - m, m),
                    bottomRight = PointF(1f - m, 1f - m),
                    bottomLeft = PointF(m, 1f - m),
                    confidence = 0f,
                    requiresUserConfirmation = true
                )
            }
        }
    }

    /**
     * Detects the dominant 4-corner document quadrilateral in [bitmap].
     * Always returns ordered corners (`topLeft`, `topRight`, `bottomRight`, `bottomLeft`) in `[0..1]`.
     */
    fun detectDocumentCorners(bitmap: Bitmap): DocumentQuadNorm {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW < 32 || srcH < 32) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        // Downsample for fast edge/foreground analysis
        val maxSampleDim = 240
        val scale = min(1f, maxSampleDim.toFloat() / max(srcW, srcH).toFloat())
        val w = (srcW * scale).roundToInt().coerceAtLeast(32)
        val h = (srcH * scale).roundToInt().coerceAtLeast(32)
        val sampled = if (w == srcW && h == srcH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }

        val pixels = IntArray(w * h)
        sampled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (sampled !== bitmap && !sampled.isRecycled) {
            sampled.recycle()
        }

        val lum = IntArray(w * h)
        var borderLumSum = 0L
        var borderCount = 0
        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val c = pixels[yOff + x]
                val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                lum[yOff + x] = l
                if (x < 3 || x >= w - 3 || y < 3 || y >= h - 3) {
                    borderLumSum += l
                    borderCount++
                }
            }
        }
        val borderMeanLum = if (borderCount > 0) (borderLumSum / borderCount).toInt() else 60

        // Sobel gradient + luminance contrast against outer border backdrop
        val gradMag = FloatArray(w * h)
        val isDocCandidate = BooleanArray(w * h)
        var candidateCount = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val gx = -lum[(y - 1) * w + (x - 1)] + lum[(y - 1) * w + (x + 1)] -
                    2 * lum[y * w + (x - 1)] + 2 * lum[y * w + (x + 1)] -
                    lum[(y + 1) * w + (x - 1)] + lum[(y + 1) * w + (x + 1)]
                val gy = -lum[(y - 1) * w + (x - 1)] - 2 * lum[(y - 1) * w + x] - lum[(y - 1) * w + (x + 1)] +
                    lum[(y + 1) * w + (x - 1)] + 2 * lum[(y + 1) * w + x] + lum[(y + 1) * w + (x + 1)]
                val mag = sqrt((gx * gx + gy * gy).toFloat())
                gradMag[idx] = mag

                val lumDiffFromBorder = abs(lum[idx] - borderMeanLum)
                if (lumDiffFromBorder >= 28 || (lum[idx] >= 185 && borderMeanLum < 150)) {
                    isDocCandidate[idx] = true
                    candidateCount++
                }
            }
        }

        if (candidateCount < (w * h * 0.12f).toInt()) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        // Find extreme boundary points in the 4 diagonal directions:
        // TL minimizes (x + y), TR maximizes (x - y), BR maximizes (x + y), BL minimizes (x - y)
        var bestTlScore = Float.MAX_VALUE
        var bestTrScore = -Float.MAX_VALUE
        var bestBrScore = -Float.MAX_VALUE
        var bestBlScore = Float.MAX_VALUE

        var tlX = w * 0.08f
        var tlY = h * 0.08f
        var trX = w * 0.92f
        var trY = h * 0.08f
        var brX = w * 0.92f
        var brY = h * 0.92f
        var blX = w * 0.08f
        var blY = h * 0.92f

        val normW = w.toFloat()
        val normH = h.toFloat()

        for (y in 2 until h - 2) {
            val yOff = y * w
            for (x in 2 until w - 2) {
                val idx = yOff + x
                if (!isDocCandidate[idx]) continue

                // Require local 3x3 candidate support to ignore isolated glare specks
                var support = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (isDocCandidate[(y + dy) * w + (x + dx)]) support++
                    }
                }
                if (support < 5) continue

                val nx = x / normW
                val ny = y / normH
                val sumXY = nx + ny
                val diffXY = nx - ny

                if (sumXY < bestTlScore) {
                    bestTlScore = sumXY
                    tlX = x.toFloat()
                    tlY = y.toFloat()
                }
                if (diffXY > bestTrScore) {
                    bestTrScore = diffXY
                    trX = x.toFloat()
                    trY = y.toFloat()
                }
                if (sumXY > bestBrScore) {
                    bestBrScore = sumXY
                    brX = x.toFloat()
                    brY = y.toFloat()
                }
                if (diffXY < bestBlScore) {
                    bestBlScore = diffXY
                    blX = x.toFloat()
                    blY = y.toFloat()
                }
            }
        }

        val tl = PointF((tlX / normW).coerceIn(0f, 1f), (tlY / normH).coerceIn(0f, 1f))
        val tr = PointF((trX / normW).coerceIn(0f, 1f), (trY / normH).coerceIn(0f, 1f))
        val br = PointF((brX / normW).coerceIn(0f, 1f), (brY / normH).coerceIn(0f, 1f))
        val bl = PointF((blX / normW).coerceIn(0f, 1f), (blY / normH).coerceIn(0f, 1f))

        val confidence = evaluateQuadConfidence(tl, tr, br, bl, gradMag, w, h)
        return if (confidence < 0.25f) {
            DocumentQuadNorm.defaultInsetFrame()
        } else {
            DocumentQuadNorm(
                topLeft = tl,
                topRight = tr,
                bottomRight = br,
                bottomLeft = bl,
                confidence = confidence,
                requiresUserConfirmation = confidence < AUTO_WARP_CONFIDENCE_THRESHOLD
            )
        }
    }

    /**
     * Evaluates geometric validity (convexity, area ratio, interior corner angles `45°..135°`)
     * and edge gradient support along the 4 quadrilateral sides.
     */
    fun evaluateQuadConfidence(
        tl: PointF,
        tr: PointF,
        br: PointF,
        bl: PointF,
        gradMag: FloatArray? = null,
        gridW: Int = 0,
        gridH: Int = 0
    ): Float {
        val pts = listOf(tl, tr, br, bl)
        // Check clockwise convexity using cross products
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross <= 0.001f) return 0f
        }

        // Shoelace area in normalized [0..1] space
        val area = 0.5f * abs(
            (tl.x * tr.y - tr.x * tl.y) +
                (tr.x * br.y - br.x * tr.y) +
                (br.x * bl.y - bl.x * br.y) +
                (bl.x * tl.y - tl.x * bl.y)
        )
        if (area < 0.16f || area > 0.98f) return 0.15f

        // Check interior angles are between 45° and 135°
        var anglePenalty = 0f
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val curr = pts[i]
            val next = pts[(i + 1) % 4]
            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val len1 = hypot(v1x, v1y)
            val len2 = hypot(v2x, v2y)
            if (len1 < 0.10f || len2 < 0.10f) return 0f
            val cosTheta = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1f, 1f)
            val deg = Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
            if (deg < 45f || deg > 135f) return 0.10f
            anglePenalty += abs(deg - 90f) / 180f
        }

        val geometryScore = (1f - anglePenalty * 0.6f).coerceIn(0.2f, 1f)
        if (gradMag == null || gridW <= 4 || gridH <= 4) {
            return geometryScore
        }

        // Sample edge gradient support along the 4 perimeter segments
        var edgeHits = 0
        var totalSamples = 0
        for (i in 0 until 4) {
            val p0 = pts[i]
            val p1 = pts[(i + 1) % 4]
            val steps = 24
            for (s in 1 until steps) {
                val t = s.toFloat() / steps.toFloat()
                val sx = ((p0.x + (p1.x - p0.x) * t) * (gridW - 1)).roundToInt().coerceIn(1, gridW - 2)
                val sy = ((p0.y + (p1.y - p0.y) * t) * (gridH - 1)).roundToInt().coerceIn(1, gridH - 2)
                var maxLocalGrad = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val g = gradMag[(sy + dy) * gridW + (sx + dx)]
                        if (g > maxLocalGrad) maxLocalGrad = g
                    }
                }
                if (maxLocalGrad >= 45f) edgeHits++
                totalSamples++
            }
        }
        val edgeRatio = if (totalSamples > 0) edgeHits.toFloat() / totalSamples.toFloat() else 0f
        return (geometryScore * 0.45f + edgeRatio * 0.55f).coerceIn(0f, 1f)
    }

    fun warpDocumentPerspective(
        src: Bitmap,
        cornersNorm: List<PointF>,
        targetWidthPx: Int? = null,
        targetHeightPx: Int? = null
    ): Bitmap {
        if (cornersNorm.size < 4) return src
        val quad = DocumentQuadNorm(
            topLeft = cornersNorm[0],
            topRight = cornersNorm[1],
            bottomRight = cornersNorm[2],
            bottomLeft = cornersNorm[3],
            confidence = 1f,
            requiresUserConfirmation = false
        )
        return warpDocumentPerspective(
            sourceBitmap = src,
            quadNorm = quad,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx
        )
    }

    /**
     * Warps the 4-corner quadrilateral [quadNorm] in [sourceBitmap] into a flat, upright
     * rectangular [Bitmap] using a 3×3 projective homography and bilinear interpolation.
     */
    fun warpDocumentPerspective(
        sourceBitmap: Bitmap,
        quadNorm: DocumentQuadNorm,
        targetWidthPx: Int? = null,
        targetHeightPx: Int? = null
    ): Bitmap = VisionBenchmarkRecorder.measureStage(
        stage = VisionBenchmarkRecorder.Stage.DOCUMENT_CORNER_WARP,
        imageWidth = sourceBitmap.width,
        imageHeight = sourceBitmap.height
    ) {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        val tl = PointF(quadNorm.topLeft.x * srcW, quadNorm.topLeft.y * srcH)
        val tr = PointF(quadNorm.topRight.x * srcW, quadNorm.topRight.y * srcH)
        val br = PointF(quadNorm.bottomRight.x * srcW, quadNorm.bottomRight.y * srcH)
        val bl = PointF(quadNorm.bottomLeft.x * srcW, quadNorm.bottomLeft.y * srcH)

        val topWidth = hypot(tr.x - tl.x, tr.y - tl.y)
        val bottomWidth = hypot(br.x - bl.x, br.y - bl.y)
        val leftHeight = hypot(bl.x - tl.x, bl.y - tl.y)
        val rightHeight = hypot(br.x - tr.x, br.y - tr.y)

        val dstW = (targetWidthPx ?: max(topWidth, bottomWidth).roundToInt()).coerceIn(64, 2048)
        val dstH = (targetHeightPx ?: max(leftHeight, rightHeight).roundToInt()).coerceIn(64, 2048)

        // Compute inverse homography mapping destination (u, v) in [0..dstW-1, 0..dstH-1]
        // to source pixel coordinates (x, y)
        val dstPts = arrayOf(
            PointF(0f, 0f),
            PointF((dstW - 1).toFloat(), 0f),
            PointF((dstW - 1).toFloat(), (dstH - 1).toFloat()),
            PointF(0f, (dstH - 1).toFloat())
        )
        val srcPts = arrayOf(tl, tr, br, bl)
        val hMat = solveHomography(dstPts, srcPts) ?: return@measureStage sourceBitmap

        val srcPixels = IntArray(srcW * srcH)
        sourceBitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val dstPixels = IntArray(dstW * dstH)

        val h00 = hMat[0]
        val h01 = hMat[1]
        val h02 = hMat[2]
        val h10 = hMat[3]
        val h11 = hMat[4]
        val h12 = hMat[5]
        val h20 = hMat[6]
        val h21 = hMat[7]

        for (v in 0 until dstH) {
            val vf = v.toDouble()
            val rowOffset = v * dstW
            for (u in 0 until dstW) {
                val uf = u.toDouble()
                val denom = (h20 * uf + h21 * vf + 1.0)
                if (abs(denom) < 1e-8) {
                    dstPixels[rowOffset + u] = Color.WHITE
                    continue
                }
                val srcX = ((h00 * uf + h01 * vf + h02) / denom).toFloat()
                val srcY = ((h10 * uf + h11 * vf + h12) / denom).toFloat()

                val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                val y0 = srcY.toInt().coerceIn(0, srcH - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val y1 = (y0 + 1).coerceAtMost(srcH - 1)
                val wx = (srcX - x0).coerceIn(0f, 1f)
                val wy = (srcY - y0).coerceIn(0f, 1f)

                val c00 = srcPixels[y0 * srcW + x0]
                val c10 = srcPixels[y0 * srcW + x1]
                val c01 = srcPixels[y1 * srcW + x0]
                val c11 = srcPixels[y1 * srcW + x1]

                val r = bilinearChannel(Color.red(c00), Color.red(c10), Color.red(c01), Color.red(c11), wx, wy)
                val g = bilinearChannel(Color.green(c00), Color.green(c10), Color.green(c01), Color.green(c11), wx, wy)
                val b = bilinearChannel(Color.blue(c00), Color.blue(c10), Color.blue(c01), Color.blue(c11), wx, wy)
                dstPixels[rowOffset + u] = Color.rgb(r, g, b)
            }
        }

        Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).apply {
            setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        }
    }

    private fun bilinearChannel(c00: Int, c10: Int, c01: Int, c11: Int, wx: Float, wy: Float): Int {
        val top = c00 * (1f - wx) + c10 * wx
        val bottom = c01 * (1f - wx) + c11 * wx
        return (top * (1f - wy) + bottom * wy).roundToInt().coerceIn(0, 255)
    }

    /**
     * Solves the 8-element projective homography vector `[h00, h01, h02, h10, h11, h12, h20, h21]`
     * mapping 4 points `fromPts` to `toPts` using Gaussian elimination with partial pivoting.
     */
    fun solveHomography(fromPts: Array<PointF>, toPts: Array<PointF>): DoubleArray? {
        if (fromPts.size < 4 || toPts.size < 4) return null
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = fromPts[i].x.toDouble()
            val y = fromPts[i].y.toDouble()
            val u = toPts[i].x.toDouble()
            val v = toPts[i].y.toDouble()

            val r0 = i * 2
            a[r0][0] = x
            a[r0][1] = y
            a[r0][2] = 1.0
            a[r0][3] = 0.0
            a[r0][4] = 0.0
            a[r0][5] = 0.0
            a[r0][6] = -x * u
            a[r0][7] = -y * u
            a[r0][8] = u

            val r1 = r0 + 1
            a[r1][0] = 0.0
            a[r1][1] = 0.0
            a[r1][2] = 0.0
            a[r1][3] = x
            a[r1][4] = y
            a[r1][5] = 1.0
            a[r1][6] = -x * v
            a[r1][7] = -y * v
            a[r1][8] = v
        }

        for (col in 0 until 8) {
            var pivotRow = col
            var maxVal = abs(a[col][col])
            for (row in col + 1 until 8) {
                val v = abs(a[row][col])
                if (v > maxVal) {
                    maxVal = v
                    pivotRow = row
                }
            }
            if (maxVal < 1e-10) return null
            if (pivotRow != col) {
                val tmp = a[col]
                a[col] = a[pivotRow]
                a[pivotRow] = tmp
            }

            val pivot = a[col][col]
            for (j in col..8) {
                a[col][j] /= pivot
            }
            for (row in 0 until 8) {
                if (row != col) {
                    val factor = a[row][col]
                    for (j in col..8) {
                        a[row][j] -= factor * a[col][j]
                    }
                }
            }
        }

        return DoubleArray(8) { i -> a[i][8] }
    }
}
