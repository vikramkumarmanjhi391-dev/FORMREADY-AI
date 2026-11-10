package com.example.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 3B.1: True Contour-Based On-Device Document & ID Card Quadrilateral Detection
 * and Homography Perspective Rectification.
 *
 * Pipeline Summary:
 * 1. Downsamples input to a bounded working grid (`maxSampleDim = 240`) without mutating or
 *    recycling the caller's [Bitmap].
 * 2. Computes luminance, Sobel directional gradients, and adaptive background-suppressed foreground
 *    candidates using outer-border percentile statistics.
 * 3. Labels 8-connected components, identifies the dominant document candidate, and performs an
 *    exterior background flood-fill from the grid boundary so internal printed text, stamps, photos,
 *    and dark markings inside the document are filled rather than mistaken for exterior boundaries.
 * 4. Extracts the ordered 8-connected outer boundary via Moore-Neighbor boundary tracing (with
 *    Jacob's stopping criterion), deduplicates points, and strips 1-pixel spurs.
 * 5. Computes the clockwise 2D convex hull via Andrew's Monotone Chain algorithm on packed primitive
 *    coordinates.
 * 6. Simplifies the outer contour into a four-sided polygon using controlled-tolerance closed-loop
 *    Douglas–Peucker simplification combined with orthogonal total-least-squares 4-side line fitting
 *    and line-line intersection (accurately recovering true corners even on rotated, perspective-skewed,
 *    or rounded-corner ID cards).
 * 7. Validates quadrilateral geometry, contour approximation error, per-side contour coverage,
 *    4-edge directional gradient support (`minEdgeSupport >= 0.65f`), interior angles (`55°..125°`),
 *    solidity (`>= 0.75f`), area coverage (`16%..92%`), border clipping, and competing objects.
 * 8. Enforces `AUTO_WARP_CONFIDENCE_THRESHOLD = 0.80f`; uncertain or ambiguous scenes always set
 *    `requiresUserConfirmation = true` (`autoWarpEligible = false`).
 */
object DocumentCornerProcessor {

    const val AUTO_WARP_CONFIDENCE_THRESHOLD = 0.80f
    const val MIN_CANDIDATE_AREA_RATIO = 0.05f
    const val MIN_AUTO_WARP_AREA_RATIO = 0.16f
    const val MAX_AUTO_WARP_AREA_RATIO = 0.92f
    const val MIN_EDGE_SUPPORT_RATIO = 0.65f
    const val MIN_QUAD_SOLIDITY = 0.75f

    private const val MAX_SAMPLE_DIM = 240
    private const val MIN_CONTOUR_POINTS = 20
    private const val MAX_P90_CONTOUR_ERROR_NORM = 0.055f
    private const val MAX_MEAN_CONTOUR_ERROR_NORM = 0.032f
    private const val MAX_CORNER_TO_CONTOUR_DIST_NORM = 0.068f
    private const val MIN_CONTOUR_SIDE_COVERAGE = 0.65f
    private const val MAX_HULL_DEFICIT_RATIO = 0.085f
    private const val MAX_INTERIOR_HOLE_RATIO = 0.42f

    // Clockwise 8-neighborhood offsets: W, NW, N, NE, E, SE, S, SW
    private val MOORE_DX = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
    private val MOORE_DY = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)

    // Canonical unit corner directions from centroid: TL, TR, BR, BL
    private val CANONICAL_DIR_X = floatArrayOf(-0.70710677f, 0.70710677f, 0.70710677f, -0.70710677f)
    private val CANONICAL_DIR_Y = floatArrayOf(-0.70710677f, -0.70710677f, 0.70710677f, 0.70710677f)

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

    private data class FittedLine2D(
        val nx: Float,
        val ny: Float,
        val c: Float
    )

    private data class ContourQuadFitEvaluation(
        val cornersGrid: Array<PointF>,
        val meanContourErrorNorm: Float,
        val p90ContourErrorNorm: Float,
        val maxHullErrorNorm: Float,
        val maxCornerToContourDistNorm: Float,
        val minSideContourCoverage: Float,
        val meanSideContourCoverage: Float,
        val fitScore: Float,
        val isApproximationValid: Boolean
    )

    private fun packCoord(x: Int, y: Int): Int = (x shl 16) or (y and 0xFFFF)
    private fun unpackX(packed: Int): Int = packed ushr 16
    private fun unpackY(packed: Int): Int = packed and 0xFFFF

    /**
     * Detects the dominant 4-corner document quadrilateral in [bitmap] using outer-contour
     * boundary tracing, convex-hull extraction, Douglas–Peucker polygon simplification, and
     * 4-side orthogonal line fitting.
     *
     * Always returns ordered corners (`topLeft`, `topRight`, `bottomRight`, `bottomLeft`) in `[0..1]`.
     */
    fun detectDocumentCorners(bitmap: Bitmap): DocumentQuadNorm {
        if (bitmap.isRecycled) {
            return DocumentQuadNorm.defaultInsetFrame()
        }
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW < 32 || srcH < 32) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        val scale = min(1f, MAX_SAMPLE_DIM.toFloat() / max(srcW, srcH).toFloat())
        val w = (srcW * scale).roundToInt().coerceAtLeast(32)
        val h = (srcH * scale).roundToInt().coerceAtLeast(32)
        val sampled = if (w == srcW && h == srcH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }

        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        sampled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (sampled !== bitmap && !sampled.isRecycled) {
            sampled.recycle()
        }

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
        val borderP90Lum = if (sortedBorder.isNotEmpty()) {
            sortedBorder[(sortedBorder.size * 0.90).toInt().coerceIn(0, sortedBorder.lastIndex)]
        } else {
            borderMeanLum
        }
        val borderP35Lum = if (sortedBorder.isNotEmpty()) {
            sortedBorder[(sortedBorder.size * 0.35).toInt().coerceIn(0, sortedBorder.lastIndex)]
        } else {
            borderMeanLum
        }
        val borderP10Lum = if (sortedBorder.isNotEmpty()) {
            sortedBorder[(sortedBorder.size * 0.10).toInt().coerceIn(0, sortedBorder.lastIndex)]
        } else {
            borderMeanLum
        }

        // Compute Sobel directional gradients and candidate foreground mask.
        // Only treat the border as bimodal when a genuinely bright clipped document extends across
        // the frame edge (borderP90Lum >= 170 and spread > 85), preventing noisy dark/textured
        // backgrounds from lowering the threshold below the noise floor.
        val gxArr = FloatArray(totalPixels)
        val gyArr = FloatArray(totalPixels)
        val gradMag = FloatArray(totalPixels)
        val isDocCandidate = BooleanArray(totalPixels)

        val brightThreshold = if (borderP90Lum >= 170 && borderP90Lum - borderP10Lum > 85) {
            borderP35Lum + 32
        } else {
            max(borderP90Lum + 18, borderMeanLum + 28)
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

        // Morphological 3x3 neighborhood filter preserving 90-degree corners (4 neighbors)
        // while suppressing isolated 1-pixel and 2-pixel background noise specks.
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

        components.sortByDescending { it.pixelCount }
        val primaryComp = components.first()

        // Exterior flood-fill from image border so internal dark text, printed tables, stamps,
        // or holes completely inside the primary document component are filled and never
        // mistaken for the outer document boundary.
        val filledPrimaryMask = BooleanArray(totalPixels)
        val filledPixelCount = buildHoleFilledPrimaryMask(
            labels = labels,
            primaryId = primaryComp.id,
            w = w,
            h = h,
            bfsQueue = queue,
            outFilledMask = filledPrimaryMask
        )
        val primaryAreaRatio = filledPixelCount.toFloat() / totalPixels.toFloat()
        val interiorHoleRatio = (filledPixelCount - primaryComp.pixelCount).coerceAtLeast(0).toFloat() /
            filledPixelCount.coerceAtLeast(1).toFloat()

        // Extract outer boundary using Moore-Neighbor boundary tracing (Jacob's stopping criterion)
        val contourPacked = traceOuterContourMooreNeighbor(
            mask = filledPrimaryMask,
            w = w,
            h = h,
            minX = primaryComp.minX,
            minY = primaryComp.minY,
            maxX = primaryComp.maxX,
            maxY = primaryComp.maxY
        )
        if (contourPacked.size < MIN_CONTOUR_POINTS) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        // Compute clockwise 2D convex hull from the outer contour boundary points
        val hullPacked = computeConvexHullClockwise(contourPacked)
        if (hullPacked.size < 4) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        val hullAreaPx = computePolygonAreaPacked(hullPacked)
        val hullDeficitRatio = if (hullAreaPx > 16f) {
            ((hullAreaPx - filledPixelCount.toFloat()) / hullAreaPx).coerceAtLeast(0f)
        } else {
            1f
        }

        // Check for competing objects (either a second large disconnected component OR
        // two overlapping document-like sheets forming a multi-lobe concave silhouette)
        val hasDisconnectedCompetitor = components.size >= 2 &&
            components[1].pixelCount >= (primaryComp.pixelCount * 0.30f).toInt()
        val hasOverlappingMultiObjectSilhouette = hullDeficitRatio > 0.11f && primaryAreaRatio >= 0.20f
        val hasCompetingObjects = hasDisconnectedCompetitor || hasOverlappingMultiObjectSilhouette

        // Simplify the outer contour / convex hull into a 4-sided polygon via controlled
        // Douglas–Peucker simplification and 4-side orthogonal least-squares line fitting
        val quadFit = extractContourQuadrilateral(
            contourPacked = contourPacked,
            hullPacked = hullPacked,
            w = w,
            h = h
        ) ?: return DocumentQuadNorm.defaultInsetFrame()

        val orderedGridCorners = orderQuadCornersClockwise(quadFit.cornersGrid)
        val normW = (w - 1).coerceAtLeast(1).toFloat()
        val normH = (h - 1).coerceAtLeast(1).toFloat()

        val rawTlX = orderedGridCorners[0].x / normW
        val rawTlY = orderedGridCorners[0].y / normH
        val rawTrX = orderedGridCorners[1].x / normW
        val rawTrY = orderedGridCorners[1].y / normH
        val rawBrX = orderedGridCorners[2].x / normW
        val rawBrY = orderedGridCorners[2].y / normH
        val rawBlX = orderedGridCorners[3].x / normW
        val rawBlY = orderedGridCorners[3].y / normH

        val tl = PointF(rawTlX.coerceIn(0f, 1f), rawTlY.coerceIn(0f, 1f))
        val tr = PointF(rawTrX.coerceIn(0f, 1f), rawTrY.coerceIn(0f, 1f))
        val br = PointF(rawBrX.coerceIn(0f, 1f), rawBrY.coerceIn(0f, 1f))
        val bl = PointF(rawBlX.coerceIn(0f, 1f), rawBlY.coerceIn(0f, 1f))

        // Validate that the clamped normalized corners still form a strictly convex,
        // non-self-intersecting quadrilateral with distinct vertices
        if (!isValidConvexQuadNorm(tl, tr, br, bl)) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        // Check if the document is clipped at the image boundary
        val cornerOutOfBounds = listOf(rawTlX, rawTlY, rawTrX, rawTrY, rawBrX, rawBrY, rawBlX, rawBlY).any { v ->
            v < 0.015f || v > 0.985f
        }
        val cornerNearBorder = listOf(tl, tr, br, bl).any { pt ->
            pt.x <= 0.015f || pt.x >= 0.985f || pt.y <= 0.015f || pt.y >= 0.985f
        }
        val borderPerimeterRatio = primaryComp.borderTouchCount.toFloat() / max(w, h).toFloat()
        val isClippedAtBorder = cornerOutOfBounds || cornerNearBorder || borderPerimeterRatio >= 0.12f

        // Compute quad area and component solidity (protecting against L-shaped, occluded, or hollow artifacts)
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
        val contourGeometryValid = quadFit.isApproximationValid &&
            hullDeficitRatio <= MAX_HULL_DEFICIT_RATIO &&
            interiorHoleRatio <= MAX_INTERIOR_HOLE_RATIO

        val contourQualityScore = (
            quadFit.meanSideContourCoverage * 0.55f +
                quadFit.minSideContourCoverage * 0.45f -
                quadFit.p90ContourErrorNorm * 2.0f
            ).coerceIn(0f, 1f)

        val adjustedConfidence = when {
            !strictAnglesValid -> min(baseConfidence, 0.45f)
            solidity < MIN_QUAD_SOLIDITY || interiorHoleRatio > MAX_INTERIOR_HOLE_RATIO ->
                min(baseConfidence, 0.50f)
            !contourGeometryValid ->
                min(baseConfidence, 0.58f)
            edgeEval.minEdgeSupport < MIN_EDGE_SUPPORT_RATIO ->
                min(baseConfidence, 0.65f)
            primaryAreaRatio < MIN_AUTO_WARP_AREA_RATIO || primaryAreaRatio > MAX_AUTO_WARP_AREA_RATIO ||
                quadAreaNorm < MIN_AUTO_WARP_AREA_RATIO || quadAreaNorm > MAX_AUTO_WARP_AREA_RATIO ->
                min(baseConfidence, 0.72f)
            isClippedAtBorder || hasCompetingObjects ->
                min(baseConfidence, 0.74f)
            else -> (
                baseConfidence * 0.42f +
                    edgeEval.meanEdgeSupport * 0.25f +
                    edgeEval.minEdgeSupport * 0.18f +
                    contourQualityScore * 0.15f
                ).coerceIn(0f, 1f)
        }

        if (adjustedConfidence < 0.25f) {
            return DocumentQuadNorm.defaultInsetFrame()
        }

        val requiresManual = adjustedConfidence < AUTO_WARP_CONFIDENCE_THRESHOLD ||
            edgeEval.minEdgeSupport < MIN_EDGE_SUPPORT_RATIO ||
            !strictAnglesValid ||
            !contourGeometryValid ||
            solidity < MIN_QUAD_SOLIDITY ||
            primaryAreaRatio !in MIN_AUTO_WARP_AREA_RATIO..MAX_AUTO_WARP_AREA_RATIO ||
            quadAreaNorm !in MIN_AUTO_WARP_AREA_RATIO..MAX_AUTO_WARP_AREA_RATIO ||
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

    /**
     * Flood-fills the exterior background from the 4 border edges of the working grid (`w x h`).
     * Any pixel not reached by the exterior flood-fill is either part of [primaryId] or an
     * enclosed internal hole (text, stamp, dark photo box) inside [primaryId].
     */
    private fun buildHoleFilledPrimaryMask(
        labels: IntArray,
        primaryId: Int,
        w: Int,
        h: Int,
        bfsQueue: IntArray,
        outFilledMask: BooleanArray
    ): Int {
        val total = w * h
        val exterior = BooleanArray(total)
        var head = 0
        var tail = 0

        for (x in 0 until w) {
            val topIdx = x
            if (labels[topIdx] != primaryId && !exterior[topIdx]) {
                exterior[topIdx] = true
                bfsQueue[tail++] = topIdx
            }
            val botIdx = (h - 1) * w + x
            if (labels[botIdx] != primaryId && !exterior[botIdx]) {
                exterior[botIdx] = true
                bfsQueue[tail++] = botIdx
            }
        }
        for (y in 1 until h - 1) {
            val leftIdx = y * w
            if (labels[leftIdx] != primaryId && !exterior[leftIdx]) {
                exterior[leftIdx] = true
                bfsQueue[tail++] = leftIdx
            }
            val rightIdx = y * w + (w - 1)
            if (labels[rightIdx] != primaryId && !exterior[rightIdx]) {
                exterior[rightIdx] = true
                bfsQueue[tail++] = rightIdx
            }
        }

        while (head < tail) {
            val curr = bfsQueue[head++]
            val cx = curr % w
            val cy = curr / w

            if (cx > 0) {
                val n = curr - 1
                if (!exterior[n] && labels[n] != primaryId) {
                    exterior[n] = true
                    bfsQueue[tail++] = n
                }
            }
            if (cx < w - 1) {
                val n = curr + 1
                if (!exterior[n] && labels[n] != primaryId) {
                    exterior[n] = true
                    bfsQueue[tail++] = n
                }
            }
            if (cy > 0) {
                val n = curr - w
                if (!exterior[n] && labels[n] != primaryId) {
                    exterior[n] = true
                    bfsQueue[tail++] = n
                }
            }
            if (cy < h - 1) {
                val n = curr + w
                if (!exterior[n] && labels[n] != primaryId) {
                    exterior[n] = true
                    bfsQueue[tail++] = n
                }
            }
        }

        var filledCount = 0
        for (i in 0 until total) {
            val inside = !exterior[i]
            outFilledMask[i] = inside
            if (inside) filledCount++
        }
        return filledCount
    }

    /**
     * Traces the 8-connected outer boundary of [mask] clockwise using Moore-Neighbor boundary
     * tracing with Jacob's stopping criterion, then strips consecutive duplicates and 1-pixel spurs.
     */
    private fun traceOuterContourMooreNeighbor(
        mask: BooleanArray,
        w: Int,
        h: Int,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int
    ): IntArray {
        var startX = -1
        var startY = -1
        val safeMinY = minY.coerceIn(1, h - 2)
        val safeMaxY = maxY.coerceIn(1, h - 2)
        val safeMinX = minX.coerceIn(1, w - 2)
        val safeMaxX = maxX.coerceIn(1, w - 2)

        outer@ for (y in safeMinY..safeMaxY) {
            val yOff = y * w
            for (x in safeMinX..safeMaxX) {
                if (mask[yOff + x]) {
                    startX = x
                    startY = y
                    break@outer
                }
            }
        }
        if (startX < 0 || startY < 0) return IntArray(0)

        val maxSteps = (w + h) * 8
        val raw = IntArray(maxSteps)
        var rawLen = 0

        var currX = startX
        var currY = startY
        // Entering from West (startX - 1, startY), which is guaranteed to be outside the mask
        var backtrackDir = 0

        for (stepCount in 0 until maxSteps) {
            val packed = packCoord(currX, currY)
            if (rawLen == 0 || raw[rawLen - 1] != packed) {
                raw[rawLen++] = packed
            }

            var foundNext = false
            var nextX = currX
            var nextY = currY
            var nextBacktrackDir = backtrackDir

            for (k in 1..8) {
                val dir = (backtrackDir + k) and 7
                val nx = currX + MOORE_DX[dir]
                val ny = currY + MOORE_DY[dir]
                if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) {
                    nextX = nx
                    nextY = ny
                    nextBacktrackDir = if ((dir and 1) == 0) (dir + 6) and 7 else (dir + 5) and 7
                    foundNext = true
                    break
                }
            }

            if (!foundNext) break
            if (nextX == startX && nextY == startY && (nextBacktrackDir == 0 || rawLen >= MIN_CONTOUR_POINTS)) {
                break
            }
            currX = nextX
            currY = nextY
            backtrackDir = nextBacktrackDir
        }

        if (rawLen < 4) return raw.copyOf(rawLen)

        // Remove 1-pixel retraced spurs (where point[i-1] == point[i+1])
        val cleaned = IntArray(rawLen)
        var cleanLen = 0
        for (i in 0 until rawLen) {
            val p = raw[i]
            if (cleanLen >= 2 && cleaned[cleanLen - 2] == p) {
                cleanLen--
            } else if (cleanLen == 0 || cleaned[cleanLen - 1] != p) {
                cleaned[cleanLen++] = p
            }
        }
        while (cleanLen >= 3 && cleaned[cleanLen - 1] == cleaned[0]) {
            cleanLen--
        }
        return cleaned.copyOf(cleanLen)
    }

    /**
     * Computes the clockwise 2D convex hull (in screen coordinates, y-down) of [contourPacked]
     * using Andrew's Monotone Chain algorithm on primitive packed coordinates.
     */
    private fun computeConvexHullClockwise(contourPacked: IntArray): IntArray {
        if (contourPacked.size < 4) return IntArray(0)
        val sorted = contourPacked.copyOf().also { it.sort() }

        var uniqueLen = 0
        for (i in sorted.indices) {
            if (uniqueLen == 0 || sorted[i] != sorted[uniqueLen - 1]) {
                sorted[uniqueLen++] = sorted[i]
            }
        }
        if (uniqueLen < 4) return IntArray(0)

        val hull = IntArray(uniqueLen * 2)
        var k = 0

        // Upper hull (left-to-right): keep clockwise turns (cross > 0 in y-down screen coords)
        for (i in 0 until uniqueLen) {
            val p = sorted[i]
            while (k >= 2 && crossPacked(hull[k - 2], hull[k - 1], p) <= 0L) {
                k--
            }
            hull[k++] = p
        }

        // Lower hull (right-to-left)
        val t = k + 1
        for (i in uniqueLen - 2 downTo 0) {
            val p = sorted[i]
            while (k >= t && crossPacked(hull[k - 2], hull[k - 1], p) <= 0L) {
                k--
            }
            hull[k++] = p
        }

        return hull.copyOf(k - 1)
    }

    private fun crossPacked(o: Int, a: Int, b: Int): Long {
        val ox = unpackX(o).toLong()
        val oy = unpackY(o).toLong()
        val ax = unpackX(a).toLong()
        val ay = unpackY(a).toLong()
        val bx = unpackX(b).toLong()
        val by = unpackY(b).toLong()
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)
    }

    private fun computePolygonAreaPacked(poly: IntArray): Float {
        if (poly.size < 3) return 0f
        var sum = 0L
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            sum += unpackX(a).toLong() * unpackY(b).toLong() - unpackX(b).toLong() * unpackY(a).toLong()
        }
        return 0.5f * abs(sum.toFloat())
    }

    private fun computePolygonPerimeterPacked(poly: IntArray): Float {
        if (poly.size < 2) return 0f
        var perim = 0f
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            perim += hypot((unpackX(b) - unpackX(a)).toFloat(), (unpackY(b) - unpackY(a)).toFloat())
        }
        return perim
    }

    /**
     * Simplifies the closed clockwise polygon [hullPacked] using Douglas–Peucker starting from
     * the antipodal diameter pair `(idxA, idxB)` at tolerance [epsilonPx].
     */
    private fun simplifyClosedPolygonDouglasPeucker(hullPacked: IntArray, epsilonPx: Float): IntArray {
        val n = hullPacked.size
        if (n <= 4) return hullPacked.copyOf()

        var idxA = 0
        var idxB = n / 2
        var maxDistSq = -1f
        for (i in 0 until n) {
            val ax = unpackX(hullPacked[i]).toFloat()
            val ay = unpackY(hullPacked[i]).toFloat()
            for (j in i + 1 until n) {
                val dx = unpackX(hullPacked[j]).toFloat() - ax
                val dy = unpackY(hullPacked[j]).toFloat() - ay
                val dSq = dx * dx + dy * dy
                if (dSq > maxDistSq) {
                    maxDistSq = dSq
                    idxA = i
                    idxB = j
                }
            }
        }

        val chain1 = buildCyclicChain(hullPacked, idxA, idxB)
        val chain2 = buildCyclicChain(hullPacked, idxB, idxA)
        val keep1 = BooleanArray(chain1.size).apply {
            this[0] = true
            this[lastIndex] = true
        }
        val keep2 = BooleanArray(chain2.size).apply {
            this[0] = true
            this[lastIndex] = true
        }

        dpRecursive(chain1, 0, chain1.lastIndex, epsilonPx, keep1)
        dpRecursive(chain2, 0, chain2.lastIndex, epsilonPx, keep2)

        val result = IntArray(chain1.size + chain2.size)
        var outIdx = 0
        for (i in 0 until chain1.lastIndex) {
            if (keep1[i]) result[outIdx++] = chain1[i]
        }
        for (i in 0 until chain2.lastIndex) {
            if (keep2[i]) result[outIdx++] = chain2[i]
        }
        return result.copyOf(outIdx)
    }

    private fun buildCyclicChain(poly: IntArray, startIdx: Int, endIdx: Int): IntArray {
        val n = poly.size
        val count = ((endIdx - startIdx + n) % n) + 1
        val out = IntArray(count)
        for (i in 0 until count) {
            out[i] = poly[(startIdx + i) % n]
        }
        return out
    }

    private fun dpRecursive(
        chain: IntArray,
        first: Int,
        last: Int,
        epsilonPx: Float,
        keep: BooleanArray
    ) {
        if (last <= first + 1) return
        val ax = unpackX(chain[first]).toFloat()
        val ay = unpackY(chain[first]).toFloat()
        val bx = unpackX(chain[last]).toFloat()
        val by = unpackY(chain[last]).toFloat()

        var maxDist = -1f
        var splitIdx = -1
        for (i in first + 1 until last) {
            val px = unpackX(chain[i]).toFloat()
            val py = unpackY(chain[i]).toFloat()
            val d = pointToSegmentDistance(px, py, ax, ay, bx, by)
            if (d > maxDist) {
                maxDist = d
                splitIdx = i
            }
        }

        if (maxDist > epsilonPx && splitIdx != -1) {
            keep[splitIdx] = true
            dpRecursive(chain, first, splitIdx, epsilonPx, keep)
            dpRecursive(chain, splitIdx, last, epsilonPx, keep)
        }
    }

    private fun mergeNearCollinearVertices(poly: IntArray, minTurnDeg: Float = 11f): IntArray {
        if (poly.size <= 4) return poly
        var current = poly.copyOf()
        val minCos = cos(Math.toRadians(minTurnDeg.toDouble())).toFloat()

        var changed = true
        while (changed && current.size > 4) {
            changed = false
            val m = current.size
            for (i in 0 until m) {
                val prev = current[(i + m - 1) % m]
                val curr = current[i]
                val next = current[(i + 1) % m]
                val v1x = (unpackX(curr) - unpackX(prev)).toFloat()
                val v1y = (unpackY(curr) - unpackY(prev)).toFloat()
                val v2x = (unpackX(next) - unpackX(curr)).toFloat()
                val v2y = (unpackY(next) - unpackY(curr)).toFloat()
                val l1 = hypot(v1x, v1y)
                val l2 = hypot(v2x, v2y)
                if (l1 < 2f || l2 < 2f || (v1x * v2x + v1y * v2y) / (l1 * l2) >= minCos) {
                    val nextArr = IntArray(m - 1)
                    var k = 0
                    for (j in 0 until m) {
                        if (j != i) nextArr[k++] = current[j]
                    }
                    current = nextArr
                    changed = true
                    break
                }
            }
        }
        return current
    }

    /**
     * Simplifies the outer contour and convex hull into a four-sided polygon by combining
     * controlled-tolerance Douglas–Peucker simplification with orthogonal total-least-squares
     * 4-side line fitting and intersection validation.
     */
    private fun extractContourQuadrilateral(
        contourPacked: IntArray,
        hullPacked: IntArray,
        w: Int,
        h: Int
    ): ContourQuadFitEvaluation? {
        val hullPerim = computePolygonPerimeterPacked(hullPacked)
        if (hullPerim < 32f) return null

        // Run Douglas–Peucker with controlled tolerance to isolate dominant polygon sides
        var eps = max(1.25f, hullPerim * 0.011f)
        var dpPoly = simplifyClosedPolygonDouglasPeucker(hullPacked, eps)
        while (dpPoly.size > 12 && eps < hullPerim * 0.08f) {
            eps *= 1.25f
            dpPoly = simplifyClosedPolygonDouglasPeucker(hullPacked, eps)
        }
        dpPoly = mergeNearCollinearVertices(dpPoly, minTurnDeg = 11f)
        if (dpPoly.size < 4) {
            dpPoly = simplifyClosedPolygonDouglasPeucker(hullPacked, max(0.8f, hullPerim * 0.005f))
            if (dpPoly.size < 4) return null
        }

        val m = dpPoly.size
        var bestEval: ContourQuadFitEvaluation? = null

        fun considerCandidate(corners: Array<PointF>) {
            val eval = evaluateContourFitToQuad(
                cornersGrid = corners,
                contourPacked = contourPacked,
                hullPacked = hullPacked,
                w = w,
                h = h
            ) ?: return
            if (bestEval == null || eval.fitScore > bestEval!!.fitScore) {
                bestEval = eval
            }
        }

        // 1. 4-Side Orthogonal Line Fitting & Intersection across dominant DP edges
        //    (recovers true sharp corners even when physical document corners are rounded)
        val minSideLen = hullPerim * 0.065f
        val validEdges = IntArray(m)
        var validEdgeCount = 0
        for (i in 0 until m) {
            val a = dpPoly[i]
            val b = dpPoly[(i + 1) % m]
            val len = hypot((unpackX(b) - unpackX(a)).toFloat(), (unpackY(b) - unpackY(a)).toFloat())
            if (len >= minSideLen) {
                validEdges[validEdgeCount++] = i
            }
        }

        val edgeFittedLines = arrayOfNulls<FittedLine2D>(m)
        for (idx in 0 until validEdgeCount) {
            val e = validEdges[idx]
            val a = dpPoly[e]
            val b = dpPoly[(e + 1) % m]
            edgeFittedLines[e] = fitSideLineFromContour(
                ax = unpackX(a).toFloat(),
                ay = unpackY(a).toFloat(),
                bx = unpackX(b).toFloat(),
                by = unpackY(b).toFloat(),
                contourPacked = contourPacked
            )
        }

        if (validEdgeCount >= 4) {
            for (i0 in 0 until validEdgeCount - 3) {
                val l0 = edgeFittedLines[validEdges[i0]] ?: continue
                for (i1 in i0 + 1 until validEdgeCount - 2) {
                    val l1 = edgeFittedLines[validEdges[i1]] ?: continue
                    val c1 = intersectLines(l0, l1) ?: continue
                    for (i2 in i1 + 1 until validEdgeCount - 1) {
                        val l2 = edgeFittedLines[validEdges[i2]] ?: continue
                        val c2 = intersectLines(l1, l2) ?: continue
                        for (i3 in i2 + 1 until validEdgeCount) {
                            val l3 = edgeFittedLines[validEdges[i3]] ?: continue
                            val c3 = intersectLines(l2, l3) ?: continue
                            val c0 = intersectLines(l3, l0) ?: continue
                            considerCandidate(arrayOf(c0, c1, c2, c3))
                        }
                    }
                }
            }
        }

        // 2. 4-Vertex DP Polygon subsets + local orthogonal line refinement along each chord
        val vertexCount = min(m, 12)
        for (v0 in 0 until vertexCount - 3) {
            val p0 = dpPoly[v0]
            val pt0 = PointF(unpackX(p0).toFloat(), unpackY(p0).toFloat())
            for (v1 in v0 + 1 until vertexCount - 2) {
                val p1 = dpPoly[v1]
                val pt1 = PointF(unpackX(p1).toFloat(), unpackY(p1).toFloat())
                for (v2 in v1 + 1 until vertexCount - 1) {
                    val p2 = dpPoly[v2]
                    val pt2 = PointF(unpackX(p2).toFloat(), unpackY(p2).toFloat())
                    for (v3 in v2 + 1 until vertexCount) {
                        val p3 = dpPoly[v3]
                        val pt3 = PointF(unpackX(p3).toFloat(), unpackY(p3).toFloat())
                        val rawCorners = arrayOf(pt0, pt1, pt2, pt3)
                        considerCandidate(rawCorners)

                        val refined = refineQuadCornersByContourLineFit(rawCorners, contourPacked)
                        if (refined != null) {
                            considerCandidate(refined)
                        }
                    }
                }
            }
        }

        return bestEval
    }

    private fun refineQuadCornersByContourLineFit(
        corners: Array<PointF>,
        contourPacked: IntArray
    ): Array<PointF>? {
        val lines = Array(4) { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            fitSideLineFromContour(a.x, a.y, b.x, b.y, contourPacked) ?: return null
        }
        val c0 = intersectLines(lines[3], lines[0]) ?: return null
        val c1 = intersectLines(lines[0], lines[1]) ?: return null
        val c2 = intersectLines(lines[1], lines[2]) ?: return null
        val c3 = intersectLines(lines[2], lines[3]) ?: return null
        return arrayOf(c0, c1, c2, c3)
    }

    /**
     * Fits a straight line using Orthogonal Total Least Squares (2D PCA) to all points in
     * [contourPacked] lying near the middle 76% (`t in 0.12..0.88`) of chord `(ax, ay) -> (bx, by)`.
     */
    private fun fitSideLineFromContour(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        contourPacked: IntArray
    ): FittedLine2D? {
        val dx = bx - ax
        val dy = by - ay
        val len = hypot(dx, dy)
        if (len < 6f) return null
        val ux = dx / len
        val uy = dy / len
        val nx0 = -uy
        val ny0 = ux

        var sumX = 0.0
        var sumY = 0.0
        var count = 0

        for (i in contourPacked.indices) {
            val px = unpackX(contourPacked[i]).toFloat()
            val py = unpackY(contourPacked[i]).toFloat()
            val relX = px - ax
            val relY = py - ay
            val proj = (relX * ux + relY * uy) / len
            if (proj in 0.12f..0.88f) {
                val orthoDist = abs(relX * nx0 + relY * ny0)
                if (orthoDist <= 2.4f) {
                    sumX += px.toDouble()
                    sumY += py.toDouble()
                    count++
                }
            }
        }

        if (count < 5) {
            val c = -(nx0 * ax + ny0 * ay)
            return FittedLine2D(nx0, ny0, c)
        }

        val meanX = sumX / count.toDouble()
        val meanY = sumY / count.toDouble()
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0

        for (i in contourPacked.indices) {
            val px = unpackX(contourPacked[i]).toFloat()
            val py = unpackY(contourPacked[i]).toFloat()
            val relX = px - ax
            val relY = py - ay
            val proj = (relX * ux + relY * uy) / len
            if (proj in 0.12f..0.88f) {
                val orthoDist = abs(relX * nx0 + relY * ny0)
                if (orthoDist <= 2.4f) {
                    val vx = px.toDouble() - meanX
                    val vy = py.toDouble() - meanY
                    sxx += vx * vx
                    syy += vy * vy
                    sxy += vx * vy
                }
            }
        }

        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        val tx = cos(theta).toFloat()
        val ty = sin(theta).toFloat()
        val nx = -ty
        val ny = tx
        val c = -(nx * meanX.toFloat() + ny * meanY.toFloat())
        return FittedLine2D(nx, ny, c)
    }

    private fun intersectLines(l1: FittedLine2D, l2: FittedLine2D): PointF? {
        val det = l1.nx * l2.ny - l1.ny * l2.nx
        if (abs(det) < 0.18f) return null
        val x = (l1.ny * l2.c - l2.ny * l1.c) / det
        val y = (l2.nx * l1.c - l1.nx * l2.c) / det
        if (!x.isFinite() || !y.isFinite()) return null
        return PointF(x, y)
    }

    /**
     * Evaluates how accurately the candidate 4-corner polygon [cornersGrid] approximates the
     * traced outer contour [contourPacked] and convex hull [hullPacked].
     */
    private fun evaluateContourFitToQuad(
        cornersGrid: Array<PointF>,
        contourPacked: IntArray,
        hullPacked: IntArray,
        w: Int,
        h: Int
    ): ContourQuadFitEvaluation? {
        if (cornersGrid.size != 4) return null

        val marginX = w * 0.08f
        val marginY = h * 0.08f
        for (i in 0 until 4) {
            val p = cornersGrid[i]
            if (p.x < -marginX || p.x > (w - 1) + marginX || p.y < -marginY || p.y > (h - 1) + marginY) {
                return null
            }
        }

        // Verify strict clockwise convexity and minimum edge lengths in grid pixels
        val minGridEdge = min(w, h) * 0.10f
        for (i in 0 until 4) {
            val a = cornersGrid[i]
            val b = cornersGrid[(i + 1) % 4]
            val c = cornersGrid[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross <= 4f) return null
            val edgeLen = hypot(b.x - a.x, b.y - a.y)
            if (edgeLen < minGridEdge) return null
        }

        val quadAreaPx = 0.5f * abs(
            (cornersGrid[0].x * cornersGrid[1].y - cornersGrid[1].x * cornersGrid[0].y) +
                (cornersGrid[1].x * cornersGrid[2].y - cornersGrid[2].x * cornersGrid[1].y) +
                (cornersGrid[2].x * cornersGrid[3].y - cornersGrid[3].x * cornersGrid[2].y) +
                (cornersGrid[3].x * cornersGrid[0].y - cornersGrid[0].x * cornersGrid[3].y)
        )
        val charScalePx = sqrt(quadAreaPx).coerceAtLeast(16f)

        // 1. Measure perpendicular distance from every outer contour point to the 4 quad edges
        val contourLen = contourPacked.size
        val dists = FloatArray(contourLen)
        var distSum = 0f

        for (i in 0 until contourLen) {
            val px = unpackX(contourPacked[i]).toFloat()
            val py = unpackY(contourPacked[i]).toFloat()
            var minDist = Float.MAX_VALUE
            for (e in 0 until 4) {
                val a = cornersGrid[e]
                val b = cornersGrid[(e + 1) % 4]
                val d = pointToSegmentDistance(px, py, a.x, a.y, b.x, b.y)
                if (d < minDist) minDist = d
            }
            dists[i] = minDist
            distSum += minDist
        }

        dists.sort()
        val meanContourErrorNorm = (distSum / contourLen.toFloat()) / charScalePx
        val p90ContourErrorNorm = dists[(contourLen * 0.90).toInt().coerceIn(0, contourLen - 1)] / charScalePx

        // 2. Measure maximum hull vertex distance to the 4 quad edges
        var maxHullDistPx = 0f
        for (i in hullPacked.indices) {
            val px = unpackX(hullPacked[i]).toFloat()
            val py = unpackY(hullPacked[i]).toFloat()
            var minDist = Float.MAX_VALUE
            for (e in 0 until 4) {
                val a = cornersGrid[e]
                val b = cornersGrid[(e + 1) % 4]
                val d = pointToSegmentDistance(px, py, a.x, a.y, b.x, b.y)
                if (d < minDist) minDist = d
            }
            if (minDist > maxHullDistPx) maxHullDistPx = minDist
        }
        val maxHullErrorNorm = maxHullDistPx / charScalePx

        // 3. Measure distance from each of the 4 quad corners to the nearest actual contour point
        //    (Allows rounded corners up to ~8% radius where corner-to-arc is ~3%, while rejecting
        //    obscured or invented corners far from any real boundary)
        var maxCornerDistPx = 0f
        for (cIdx in 0 until 4) {
            val c = cornersGrid[cIdx]
            var minDistSq = Float.MAX_VALUE
            for (i in 0 until contourLen) {
                val dx = unpackX(contourPacked[i]).toFloat() - c.x
                val dy = unpackY(contourPacked[i]).toFloat() - c.y
                val dSq = dx * dx + dy * dy
                if (dSq < minDistSq) minDistSq = dSq
            }
            val d = sqrt(minDistSq)
            if (d > maxCornerDistPx) maxCornerDistPx = d
        }
        val maxCornerToContourDistNorm = maxCornerDistPx / charScalePx

        // 4. Measure per-side contour coverage across 16 bins along the middle 80% (t in 0.10..0.90)
        val binsPerSide = 16
        val sideCoverages = FloatArray(4)
        val inlierTolPx = max(2.4f, charScalePx * 0.032f)

        for (e in 0 until 4) {
            val a = cornersGrid[e]
            val b = cornersGrid[(e + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val ux = dx / len
            val uy = dy / len
            val nx = -uy
            val ny = ux
            var coveredBins = 0

            for (i in 0 until contourLen) {
                val relX = unpackX(contourPacked[i]).toFloat() - a.x
                val relY = unpackY(contourPacked[i]).toFloat() - a.y
                val ortho = abs(relX * nx + relY * ny)
                if (ortho <= inlierTolPx) {
                    val t = (relX * ux + relY * uy) / len
                    if (t in 0.10f..0.90f) {
                        val bin = (((t - 0.10f) / 0.80f) * binsPerSide).toInt().coerceIn(0, binsPerSide - 1)
                        val bit = 1 shl bin
                        coveredBins = coveredBins or bit
                    }
                }
            }
            sideCoverages[e] = Integer.bitCount(coveredBins).toFloat() / binsPerSide.toFloat()
        }

        val minSideCoverage = sideCoverages.minOrNull() ?: 0f
        val meanSideCoverage = sideCoverages.average().toFloat()

        val isApproximationValid = p90ContourErrorNorm <= MAX_P90_CONTOUR_ERROR_NORM &&
            meanContourErrorNorm <= MAX_MEAN_CONTOUR_ERROR_NORM &&
            maxCornerToContourDistNorm <= MAX_CORNER_TO_CONTOUR_DIST_NORM &&
            minSideCoverage >= MIN_CONTOUR_SIDE_COVERAGE

        val fitScore = meanSideCoverage * 0.45f +
            minSideCoverage * 0.35f -
            p90ContourErrorNorm * 2.5f -
            maxHullErrorNorm * 1.4f -
            maxCornerToContourDistNorm * 1.2f

        return ContourQuadFitEvaluation(
            cornersGrid = cornersGrid,
            meanContourErrorNorm = meanContourErrorNorm,
            p90ContourErrorNorm = p90ContourErrorNorm,
            maxHullErrorNorm = maxHullErrorNorm,
            maxCornerToContourDistNorm = maxCornerToContourDistNorm,
            minSideContourCoverage = minSideCoverage,
            meanSideContourCoverage = meanSideCoverage,
            fitScore = fitScore,
            isApproximationValid = isApproximationValid
        )
    }

    /**
     * Orders the 4 quadrilateral corners into canonical clockwise order
     * (`topLeft`, `topRight`, `bottomRight`, `bottomLeft`) by sorting clockwise around
     * the centroid and selecting the cyclic shift best aligned with the 4 canonical quadrants.
     */
    private fun orderQuadCornersClockwise(corners: Array<PointF>): Array<PointF> {
        val cx = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) * 0.25f
        val cy = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) * 0.25f

        val cw = corners.sortedBy { pt ->
            atan2((pt.y - cy).toDouble(), (pt.x - cx).toDouble())
        }

        var bestShift = 0
        var bestScore = -Float.MAX_VALUE
        for (shift in 0 until 4) {
            var score = 0f
            for (k in 0 until 4) {
                val p = cw[(shift + k) % 4]
                val vx = p.x - cx
                val vy = p.y - cy
                val len = hypot(vx, vy).coerceAtLeast(1e-5f)
                score += (vx / len) * CANONICAL_DIR_X[k] + (vy / len) * CANONICAL_DIR_Y[k]
            }
            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
        }

        return Array(4) { k -> cw[(bestShift + k) % 4] }
    }

    private fun isValidConvexQuadNorm(tl: PointF, tr: PointF, br: PointF, bl: PointF): Boolean {
        val pts = arrayOf(tl, tr, br, bl)
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross <= 0.001f) return false
            if (hypot(b.x - a.x, b.y - a.y) < 0.10f) return false
        }
        return !segmentsProperlyIntersect(tl, tr, br, bl) && !segmentsProperlyIntersect(tr, br, bl, tl)
    }

    private fun segmentsProperlyIntersect(a: PointF, b: PointF, c: PointF, d: PointF): Boolean {
        fun ccw(p1: PointF, p2: PointF, p3: PointF): Float =
            (p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x)

        val d1 = ccw(a, b, c)
        val d2 = ccw(a, b, d)
        val d3 = ccw(c, d, a)
        val d4 = ccw(c, d, b)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    private fun pointToSegmentDistance(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return hypot(px - projX, py - projY)
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

            // Sample middle ~80% of each side (s in 3 until steps - 2) so straight sides
            // with slightly rounded corners are evaluated along their true straight span
            for (s in 3 until steps - 2) {
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
            for (s in 3 until steps - 2) {
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
