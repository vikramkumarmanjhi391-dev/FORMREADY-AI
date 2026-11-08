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
 * Phase 3B / Phase 0.4D: On-Device Document & ID Card 4-Corner Detection and Homography Perspective Warp.
 *
 * Responsibilities:
 * 1. Discovers 8-connected foreground document components on a downsampled luminance + Sobel
 *    gradient grid (`maxSampleDim = 240`), suppressing textured background noise (such as wood grain)
 *    using outer-border percentile statistics.
 * 2. Rejects disconnected 4-corner clutter, competing multi-object scenes, and border-clipped documents
 *    from unattended auto-warping.
 * 3. Evaluates 4-edge directional normal-gradient continuity (`minEdgeSupport >= 0.65f`), convex
 *    interior corner angles (`55°..125°`), component solidity (`>= 0.75f`), and area coverage (`16%..92%`).
 * 4. Enforces `AUTO_WARP_CONFIDENCE_THRESHOLD = 0.80f` so any uncertain scene sets
 *    `requiresUserConfirmation = true` (`autoWarpEligible = false`) and opens interactive 4-corner handles.
 * 5. Solves the 8-DOF 3×3 projective homography matrix and performs bilinear perspective
 *    rectification into a flat, upright rectangular [Bitmap].
 */
object DocumentCornerProcessor {

    const val AUTO_WARP_CONFIDENCE_THRESHOLD = 0.80f
    const val MIN_CANDIDATE_AREA_RATIO = 0.05f
    const val MIN_AUTO_WARP_AREA_RATIO = 0.16f
    const val MAX_AUTO_WARP_AREA_RATIO = 0.92f
    const val MIN_EDGE_SUPPORT_RATIO = 0.65f
    const val MIN_QUAD_SOLIDITY = 0.75f

    data class DocumentQuadNorm(
        val topLeft: PointF,
        val topRight: PointF,
        val bottomRight: PointF,
        val bottomLeft: PointF,
        val confidence: Float,
        val requiresUserConfirmation: Boolean = confidence < AUTO_WARP_CONFIDENCE_THRESHOLD,
        val minEdgeSupport: Float = 0f,
        val isClippedAtBorder: Boolean = false,
        val hasCompetingObjects: Boolean = false
    ) {
        fun toPointsList(): List<PointF> = listOf(topLeft, topRight, bottomRight, bottomLeft)

        val cornersNorm: List<PointF>
            get() = toPointsList()

        val autoWarpEligible: Boolean
            get() = !requiresUserConfirmation &&
                confidence >= AUTO_WARP_CONFIDENCE_THRESHOLD &&
                !isClippedAtBorder &&
                !hasCompetingObjects

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

    private data class ConnectedComponentInfo(
        val id: Int,
        val pixelCount: Int,
        val borderTouchCount: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

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

        val totalPixels = w * h
        val lum = IntArray(totalPixels)
        val borderLums = IntArray(2 * (w + h) * 3)
        var borderIdx = 0
        var borderLumSum = 0L

        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val c = pixels[yOff + x]
                val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                lum[yOff + x] = l
                if (x < 3 || x >= w - 3 || y < 3 || y >= h - 3) {
                    if (borderIdx < borderLums.size) {
                        borderLums[borderIdx++] = l
                    }
                    borderLumSum += l
                }
            }
        }

        val sortedBorder = borderLums.copyOf(borderIdx).also { it.sort() }
        val borderMeanLum = if (borderIdx > 0) (borderLumSum / borderIdx).toInt() else 60
        val borderP90Lum = if (sortedBorder.isNotEmpty()) sortedBorder[(sortedBorder.size * 0.90).toInt().coerceIn(0, sortedBorder.lastIndex)] else borderMeanLum
        val borderP35Lum = if (sortedBorder.isNotEmpty()) sortedBorder[(sortedBorder.size * 0.35).toInt().coerceIn(0, sortedBorder.lastIndex)] else borderMeanLum
        val borderP10Lum = if (sortedBorder.isNotEmpty()) sortedBorder[(sortedBorder.size * 0.10).toInt().coerceIn(0, sortedBorder.lastIndex)] else borderMeanLum

        // Compute 3x3 smoothed luminance and Sobel directional gradients
        val gxArr = FloatArray(totalPixels)
        val gyArr = FloatArray(totalPixels)
        val gradMag = FloatArray(totalPixels)
        val isDocCandidate = BooleanArray(totalPixels)

        // Adaptive threshold above textured backdrop (e.g., wood-grain desk), while handling
        // bimodal borders when a clipped document extends off 1-2 frame edges (borderP90 - borderP10 > 60)
        val brightThreshold = if (borderP90Lum - borderP10Lum > 60) {
            borderP35Lum + 32
        } else {
            max(borderP90Lum + 22, borderMeanLum + 28)
        }
        val darkThreshold = min(borderP10Lum - 22, borderMeanLum - 28)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val gx = (-lum[(y - 1) * w + (x - 1)] + lum[(y - 1) * w + (x + 1)] -
                    2 * lum[y * w + (x - 1)] + 2 * lum[y * w + (x + 1)] -
                    lum[(y + 1) * w + (x - 1)] + lum[(y + 1) * w + (x + 1)]).toFloat()
                val gy = (-lum[(y - 1) * w + (x - 1)] - 2 * lum[(y - 1) * w + x] - lum[(y - 1) * w + (x + 1)] +
                    lum[(y + 1) * w + (x - 1)] + 2 * lum[(y + 1) * w + x] + lum[(y + 1) * w + (x + 1)]).toFloat()
                gxArr[idx] = gx
                gyArr[idx] = gy
                gradMag[idx] = sqrt(gx * gx + gy * gy)

                val l = lum[idx]
                if (l >= brightThreshold || (borderMeanLum > 170 && l <= darkThreshold)) {
                    isDocCandidate[idx] = true
                }
            }
        }

        // Morphological 3x3 filter to preserve 90-degree document corners (which have 4 neighbors
        // inside the quad) while removing isolated 1-pixel and 2-pixel background noise specks
        val filteredCandidate = BooleanArray(totalPixels)
        for (y in 1 until h - 1) {
            val yOff = y * w
            for (x in 1 until w - 1) {
                if (!isDocCandidate[yOff + x]) continue
                var neighbors = 0
                for (dy in -1..1) {
                    val nyOff = (y + dy) * w
                    for (dx in -1..1) {
                        if (isDocCandidate[nyOff + x + dx]) neighbors++
                    }
                }
                if (neighbors >= 4) {
                    filteredCandidate[yOff + x] = true
                }
            }
        }

        // 8-Connected Component Labeling
        val labels = IntArray(totalPixels)
        val components = mutableListOf<ConnectedComponentInfo>()
        val queue = IntArray(totalPixels)
        var nextLabel = 1
        val minComponentPixels = (totalPixels * MIN_CANDIDATE_AREA_RATIO).toInt().coerceAtLeast(32)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val startIdx = y * w + x
                if (!filteredCandidate[startIdx] || labels[startIdx] != 0) continue

                val currentLabel = nextLabel++
                var head = 0
                var tail = 0
                queue[tail++] = startIdx
                labels[startIdx] = currentLabel

                var count = 0
                var borderTouch = 0
                var cMinX = x
                var cMaxX = x
                var cMinY = y
                var cMaxY = y

                while (head < tail) {
                    val curr = queue[head++]
                    count++
                    val cx = curr % w
                    val cy = curr / w
                    if (cx < cMinX) cMinX = cx
                    if (cx > cMaxX) cMaxX = cx
                    if (cy < cMinY) cMinY = cy
                    if (cy > cMaxY) cMaxY = cy
                    if (cx <= 2 || cx >= w - 3 || cy <= 2 || cy >= h - 3) {
                        borderTouch++
                    }

                    for (dy in -1..1) {
                        val ny = cy + dy
                        if (ny <= 0 || ny >= h - 1) continue
                        val nyOff = ny * w
                        for (dx in -1..1) {
                            val nx = cx + dx
                            if (nx <= 0 || nx >= w - 1) continue
                            val nIdx = nyOff + nx
                            if (filteredCandidate[nIdx] && labels[nIdx] == 0) {
                                labels[nIdx] = currentLabel
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                if (count >= minComponentPixels) {
                    components.add(
                        ConnectedComponentInfo(
                            id = currentLabel,
                            pixelCount = count,
                            borderTouchCount = borderTouch,
                            minX = cMinX,
                            minY = cMinY,
                            maxX = cMaxX,
                            maxY = cMaxY
                        )
                    )
                }
            }
        }

        if (components.isEmpty()) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        // Sort candidate components by pixel count descending
        components.sortByDescending { it.pixelCount }
        val primaryComp = components.first()
        val primaryAreaRatio = primaryComp.pixelCount.toFloat() / totalPixels.toFloat()

        // Check for competing rectangular objects (e.g. 2 separate cards or 4-corner clutter blobs)
        val hasCompetingObjects = components.size >= 2 &&
            components[1].pixelCount >= (primaryComp.pixelCount * 0.30f).toInt()

        // Find the 4 corners strictly from the primary connected component's pixels
        val normW = (w - 1).coerceAtLeast(1).toFloat()
        val normH = (h - 1).coerceAtLeast(1).toFloat()
        var bestTlScore = Float.MAX_VALUE
        var bestTrScore = -Float.MAX_VALUE
        var bestBrScore = -Float.MAX_VALUE
        var bestBlScore = Float.MAX_VALUE

        var tlX = primaryComp.minX.toFloat()
        var tlY = primaryComp.minY.toFloat()
        var trX = primaryComp.maxX.toFloat()
        var trY = primaryComp.minY.toFloat()
        var brX = primaryComp.maxX.toFloat()
        var brY = primaryComp.maxY.toFloat()
        var blX = primaryComp.minX.toFloat()
        var blY = primaryComp.maxY.toFloat()

        for (y in primaryComp.minY..primaryComp.maxY) {
            val yOff = y * w
            for (x in primaryComp.minX..primaryComp.maxX) {
                if (labels[yOff + x] != primaryComp.id) continue
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

        // Check if the document is clipped at the image boundary
        val cornerNearBorder = listOf(tl, tr, br, bl).any { pt ->
            pt.x <= 0.015f || pt.x >= 0.985f || pt.y <= 0.015f || pt.y >= 0.985f
        }
        val borderPerimeterRatio = primaryComp.borderTouchCount.toFloat() / max(w, h).toFloat()
        val isClippedAtBorder = cornerNearBorder || borderPerimeterRatio >= 0.12f

        // Compute quad area and component solidity (protecting against L-shaped or hollow artifacts)
        val quadAreaNorm = computeQuadAreaNorm(tl, tr, br, bl)
        val solidity = if (quadAreaNorm > 0.01f) {
            (primaryAreaRatio / quadAreaNorm).coerceIn(0f, 1.2f)
        } else {
            0f
        }

        val edgeEval = evaluateDirectionalEdgeSupport(
            tl = tl,
            tr = tr,
            br = br,
            bl = bl,
            lum = lum,
            gxArr = gxArr,
            gyArr = gyArr,
            gradMag = gradMag,
            gridW = w,
            gridH = h
        )

        val baseConfidence = evaluateQuadConfidence(tl, tr, br, bl, gradMag, w, h)
        val strictAnglesValid = areInteriorAnglesWithinStrictBounds(tl, tr, br, bl, minDeg = 55f, maxDeg = 125f)

        val adjustedConfidence = when {
            !strictAnglesValid -> min(baseConfidence, 0.45f)
            solidity < MIN_QUAD_SOLIDITY -> min(baseConfidence, 0.50f)
            edgeEval.minEdgeSupport < MIN_EDGE_SUPPORT_RATIO -> min(baseConfidence, 0.65f)
            primaryAreaRatio < MIN_AUTO_WARP_AREA_RATIO || primaryAreaRatio > MAX_AUTO_WARP_AREA_RATIO ->
                min(baseConfidence, 0.72f)
            isClippedAtBorder || hasCompetingObjects ->
                min(baseConfidence, 0.74f)
            else -> (baseConfidence * 0.50f + edgeEval.meanEdgeSupport * 0.30f + edgeEval.minEdgeSupport * 0.20f)
                .coerceIn(0f, 1f)
        }

        if (adjustedConfidence < 0.25f) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        val requiresManual = adjustedConfidence < AUTO_WARP_CONFIDENCE_THRESHOLD ||
            edgeEval.minEdgeSupport < MIN_EDGE_SUPPORT_RATIO ||
            !strictAnglesValid ||
            solidity < MIN_QUAD_SOLIDITY ||
            primaryAreaRatio !in MIN_AUTO_WARP_AREA_RATIO..MAX_AUTO_WARP_AREA_RATIO ||
            isClippedAtBorder ||
            hasCompetingObjects

        return DocumentQuadNorm(
            topLeft = tl,
            topRight = tr,
            bottomRight = br,
            bottomLeft = bl,
            confidence = adjustedConfidence,
            requiresUserConfirmation = requiresManual,
            minEdgeSupport = edgeEval.minEdgeSupport,
            isClippedAtBorder = isClippedAtBorder,
            hasCompetingObjects = hasCompetingObjects
        )
    }

    private data class DirectionalEdgeEvaluation(
        val edgeSupports: FloatArray,
        val minEdgeSupport: Float,
        val meanEdgeSupport: Float
    )

    private fun evaluateDirectionalEdgeSupport(
        tl: PointF,
        tr: PointF,
        br: PointF,
        bl: PointF,
        lum: IntArray,
        gxArr: FloatArray,
        gyArr: FloatArray,
        gradMag: FloatArray,
        gridW: Int,
        gridH: Int
    ): DirectionalEdgeEvaluation {
        val pts = listOf(tl, tr, br, bl)
        val supports = FloatArray(4)

        for (i in 0 until 4) {
            val p0 = pts[i]
            val p1 = pts[(i + 1) % 4]
            val dx = (p1.x - p0.x) * (gridW - 1)
            val dy = (p1.y - p0.y) * (gridH - 1)
            val edgeLen = hypot(dx, dy)
            if (edgeLen < 4f) {
                supports[i] = 0f
                continue
            }
            // For clockwise (tl -> tr -> br -> bl) in screen coords (y down),
            // outward unit normal is (dy / edgeLen, -dx / edgeLen)
            val outNx = dy / edgeLen
            val outNy = -dx / edgeLen

            val steps = 28
            var supportedSamples = 0
            var totalSamples = 0

            for (s in 2 until steps - 1) {
                val t = s.toFloat() / steps.toFloat()
                val cx = (p0.x + (p1.x - p0.x) * t) * (gridW - 1)
                val cy = (p0.y + (p1.y - p0.y) * t) * (gridH - 1)

                val ix = (cx - outNx * 3.0f).roundToInt().coerceIn(1, gridW - 2)
                val iy = (cy - outNy * 3.0f).roundToInt().coerceIn(1, gridH - 2)
                val ex = (cx + outNx * 3.0f).roundToInt().coerceIn(1, gridW - 2)
                val ey = (cy + outNy * 3.0f).roundToInt().coerceIn(1, gridH - 2)

                val insideLum = lum[iy * gridW + ix]
                val outsideLum = lum[ey * gridW + ex]
                val stepDiff = abs(insideLum - outsideLum)

                var bestNormalAlignedGrad = 0f
                val sx = cx.roundToInt().coerceIn(2, gridW - 3)
                val sy = cy.roundToInt().coerceIn(2, gridH - 3)
                for (oy in -2..2) {
                    for (ox in -2..2) {
                        val gIdx = (sy + oy) * gridW + (sx + ox)
                        val mag = gradMag[gIdx]
                        if (mag >= 40f) {
                            val align = abs(gxArr[gIdx] * outNx + gyArr[gIdx] * outNy) / mag
                            if (align >= 0.70f && mag > bestNormalAlignedGrad) {
                                bestNormalAlignedGrad = mag
                            }
                        }
                    }
                }

                if (bestNormalAlignedGrad >= 42f || stepDiff >= 24) {
                    supportedSamples++
                }
                totalSamples++
            }
            supports[i] = if (totalSamples > 0) supportedSamples.toFloat() / totalSamples.toFloat() else 0f
        }

        val minSupport = supports.minOrNull() ?: 0f
        val meanSupport = supports.average().toFloat()
        return DirectionalEdgeEvaluation(supports, minSupport, meanSupport)
    }

    private fun computeQuadAreaNorm(tl: PointF, tr: PointF, br: PointF, bl: PointF): Float {
        return 0.5f * abs(
            (tl.x * tr.y - tr.x * tl.y) +
                (tr.x * br.y - br.x * tr.y) +
                (br.x * bl.y - bl.x * br.y) +
                (bl.x * tl.y - tl.x * bl.y)
        )
    }

    private fun areInteriorAnglesWithinStrictBounds(
        tl: PointF,
        tr: PointF,
        br: PointF,
        bl: PointF,
        minDeg: Float,
        maxDeg: Float
    ): Boolean {
        val pts = listOf(tl, tr, br, bl)
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
            if (len1 < 0.10f || len2 < 0.10f) return false
            val cosTheta = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1f, 1f)
            val deg = Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
            if (deg < minDeg || deg > maxDeg) return false
        }
        return true
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

        val area = computeQuadAreaNorm(tl, tr, br, bl)
        if (area < MIN_AUTO_WARP_AREA_RATIO || area > 0.98f) return 0.15f

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
        if (gradMag == null || gridW <= 6 || gridH <= 6) {
            return geometryScore
        }

        var edgeHits = 0
        var totalSamples = 0
        for (i in 0 until 4) {
            val p0 = pts[i]
            val p1 = pts[(i + 1) % 4]
            val steps = 24
            for (s in 1 until steps) {
                val t = s.toFloat() / steps.toFloat()
                val sx = ((p0.x + (p1.x - p0.x) * t) * (gridW - 1)).roundToInt().coerceIn(2, gridW - 3)
                val sy = ((p0.y + (p1.y - p0.y) * t) * (gridH - 1)).roundToInt().coerceIn(2, gridH - 3)
                var maxLocalGrad = 0f
                for (dy in -2..2) {
                    for (dx in -2..2) {
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
