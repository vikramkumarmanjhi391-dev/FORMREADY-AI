package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Represents the exact rendered image rectangle inside a Canvas when displayed using
 * [ContentScale.Fit] and [Alignment.Center].
 */
data class OverlayImageFitTransform(
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val scale: Float,
    val displayedWidth: Float,
    val displayedHeight: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    val left: Float get() = offsetX
    val top: Float get() = offsetY
    val right: Float get() = offsetX + displayedWidth
    val bottom: Float get() = offsetY + displayedHeight

    val hasHorizontalLetterbox: Boolean
        get() = offsetX > 0.5f

    val hasVerticalLetterbox: Boolean
        get() = offsetY > 0.5f

    /**
     * Returns true when `(canvasX, canvasY)` falls within the actual displayed image area
     * (optionally expanded by [tolerancePx] for corner handle hit-testing at image edges).
     */
    fun isInsideDisplayedImage(
        canvasX: Float,
        canvasY: Float,
        tolerancePx: Float = 0f
    ): Boolean {
        if (displayedWidth <= 0f || displayedHeight <= 0f) return false
        return canvasX in (left - tolerancePx)..(right + tolerancePx) &&
            canvasY in (top - tolerancePx)..(bottom + tolerancePx)
    }

    /**
     * Converts a bitmap-normalized point `(normX, normY)` in `[0, 1]` to Canvas pixel coordinates
     * within the fitted image rectangle.
     */
    fun bitmapNormalizedToCanvas(
        normPoint: PointF,
        clampNormalized: Boolean = true
    ): Offset {
        val nx = if (clampNormalized) normPoint.x.coerceIn(0f, 1f) else normPoint.x
        val ny = if (clampNormalized) normPoint.y.coerceIn(0f, 1f) else normPoint.y
        return Offset(
            x = offsetX + nx * displayedWidth,
            y = offsetY + ny * displayedHeight
        )
    }

    /**
     * Converts a Canvas pointer coordinate to a bitmap-normalized point `[0, 1]` relative to
     * the actual displayed image rectangle. Letterboxed coordinates outside the image area are
     * clamped to `[minNorm, maxNorm]`.
     */
    fun canvasToBitmapNormalized(
        canvasOffset: Offset,
        minNorm: Float = 0f,
        maxNorm: Float = 1f
    ): PointF {
        val lo = minNorm.coerceIn(0f, 1f)
        val hi = maxNorm.coerceIn(lo, 1f)
        if (displayedWidth <= 0f || displayedHeight <= 0f) {
            return PointF(lo, lo)
        }
        val rawNormX = (canvasOffset.x - offsetX) / displayedWidth
        val rawNormY = (canvasOffset.y - offsetY) / displayedHeight
        return PointF(
            rawNormX.coerceIn(lo, hi),
            rawNormY.coerceIn(lo, hi)
        )
    }
}

enum class ManualQuadInvalidReason {
    WRONG_CORNER_COUNT,
    OUT_OF_BOUNDS,
    DISTINCT_CORNERS_REQUIRED,
    EDGE_TOO_SHORT,
    SELF_INTERSECTING_EDGES,
    NON_CONVEX_OR_REVERSED_WINDING,
    INVALID_INTERIOR_ANGLE,
    AREA_TOO_SMALL,
    INVALID_CLOCKWISE_ORDERING
}

data class ManualQuadValidationResult(
    val isValid: Boolean,
    val invalidReason: ManualQuadInvalidReason? = null,
    val errorMessage: String? = null,
    val areaNorm: Float = 0f,
    val minEdgeLengthNorm: Float = 0f
)

/**
 * Shared, deterministic coordinate-mapping and manual quadrilateral validation helper
 * for [InteractiveCornerCropOverlay].
 */
object CornerOverlayCoordinateMapper {

    const val MIN_MANUAL_EDGE_LENGTH_NORM = 0.08f
    const val MIN_MANUAL_QUAD_AREA_NORM = 0.02f
    const val MIN_MANUAL_INTERIOR_ANGLE_DEG = 25f
    const val MAX_MANUAL_INTERIOR_ANGLE_DEG = 160f
    const val DEFAULT_HANDLE_HIT_RADIUS_PX = 48f

    /**
     * Computes the exact fitted image rectangle inside a Canvas of `(canvasWidth, canvasHeight)`
     * using [ContentScale.Fit] and center alignment.
     */
    fun computeImageFitTransform(
        bitmapWidth: Int,
        bitmapHeight: Int,
        canvasWidth: Float,
        canvasHeight: Float
    ): OverlayImageFitTransform {
        val safeBmpW = bitmapWidth.coerceAtLeast(1)
        val safeBmpH = bitmapHeight.coerceAtLeast(1)
        val safeCanvasW = canvasWidth.coerceAtLeast(1f)
        val safeCanvasH = canvasHeight.coerceAtLeast(1f)

        val scaleX = safeCanvasW / safeBmpW.toFloat()
        val scaleY = safeCanvasH / safeBmpH.toFloat()
        val scale = min(scaleX, scaleY)

        val displayedW = safeBmpW.toFloat() * scale
        val displayedH = safeBmpH.toFloat() * scale
        val offsetX = (safeCanvasW - displayedW) * 0.5f
        val offsetY = (safeCanvasH - displayedH) * 0.5f

        return OverlayImageFitTransform(
            bitmapWidth = safeBmpW,
            bitmapHeight = safeBmpH,
            canvasWidth = safeCanvasW,
            canvasHeight = safeCanvasH,
            scale = scale,
            displayedWidth = displayedW,
            displayedHeight = displayedH,
            offsetX = offsetX,
            offsetY = offsetY
        )
    }

    /**
     * Finds the index `0..3` of the nearest corner handle to [pointerCanvas] using the actual
     * displayed image coordinates. Returns `-1` if [pointerCanvas] is in the letterbox outside
     * the handle hit radius or farther than [hitRadiusPx] from all handles.
     */
    fun findNearestCornerHandleIndex(
        pointerCanvas: Offset,
        cornersNorm: List<PointF>,
        fitTransform: OverlayImageFitTransform,
        hitRadiusPx: Float = DEFAULT_HANDLE_HIT_RADIUS_PX
    ): Int {
        if (cornersNorm.size != 4) return -1
        if (!fitTransform.isInsideDisplayedImage(pointerCanvas.x, pointerCanvas.y, tolerancePx = hitRadiusPx)) {
            return -1
        }
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until 4) {
            val handleCanvas = fitTransform.bitmapNormalizedToCanvas(cornersNorm[i])
            val dist = hypot(pointerCanvas.x - handleCanvas.x, pointerCanvas.y - handleCanvas.y)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return if (bestDist <= hitRadiusPx) bestIdx else -1
    }

    /**
     * Maps a corner drag from its initial normalized coordinate [startCornerNorm] and total
     * Canvas displacement [totalDragCanvasDelta] into a new bitmap-normalized coordinate in `[0, 1]`
     * without cumulative floating-point or clamping drift.
     */
    fun dragCornerHandle(
        startCornerNorm: PointF,
        totalDragCanvasDelta: Offset,
        fitTransform: OverlayImageFitTransform
    ): PointF {
        val startCanvas = fitTransform.bitmapNormalizedToCanvas(startCornerNorm)
        val targetCanvas = Offset(
            x = startCanvas.x + totalDragCanvasDelta.x,
            y = startCanvas.y + totalDragCanvasDelta.y
        )
        return fitTransform.canvasToBitmapNormalized(targetCanvas, minNorm = 0f, maxNorm = 1f)
    }

    /**
     * Normalizes an initial corner list to valid `[0, 1]` bitmap coordinates, falling back
     * to a default inset rectangle if fewer than 4 corners are supplied.
     */
    fun sanitizeInitialCorners(initialCornersNorm: List<PointF>): List<PointF> {
        return if (initialCornersNorm.size == 4 &&
            initialCornersNorm.all { it.x.isFinite() && it.y.isFinite() }
        ) {
            initialCornersNorm.map { PointF(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
        } else {
            defaultResetCorners()
        }
    }

    fun defaultResetCorners(): List<PointF> = listOf(
        PointF(0.06f, 0.06f),
        PointF(0.94f, 0.06f),
        PointF(0.94f, 0.94f),
        PointF(0.06f, 0.94f)
    )

    /**
     * Validates a manual 4-corner quadrilateral before running perspective homography warp:
     * - Exactly 4 finite corners inside `[0, 1]`
     * - Distinct vertices and minimum edge lengths (`>= 0.08`)
     * - No crossed or self-intersecting edges
     * - Strictly convex clockwise geometry (supporting rotated quadrilaterals without brittle
     *   corner-by-corner axis-aligned inequalities)
     * - Valid interior angles (`25°..160°`)
     * - Minimum polygon area (`>= 0.02`)
     * - Valid top/right/bottom/left cyclic edge orientation (`topMid.y < bottomMid.y` and `leftMid.x < rightMid.x`)
     */
    fun validateManualDocumentQuad(cornersNorm: List<PointF>): ManualQuadValidationResult {
        if (cornersNorm.size != 4) {
            return ManualQuadValidationResult(
                isValid = false,
                invalidReason = ManualQuadInvalidReason.WRONG_CORNER_COUNT,
                errorMessage = "Exactly 4 document corners are required."
            )
        }

        val eps = 1e-4f
        for (pt in cornersNorm) {
            if (!pt.x.isFinite() || !pt.y.isFinite() ||
                pt.x < -eps || pt.x > 1f + eps ||
                pt.y < -eps || pt.y > 1f + eps
            ) {
                return ManualQuadValidationResult(
                    isValid = false,
                    invalidReason = ManualQuadInvalidReason.OUT_OF_BOUNDS,
                    errorMessage = "All 4 corners must remain inside the image bounds [0, 1]."
                )
            }
        }

        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                val dist = hypot(cornersNorm[i].x - cornersNorm[j].x, cornersNorm[i].y - cornersNorm[j].y)
                if (dist < 0.04f) {
                    return ManualQuadValidationResult(
                        isValid = false,
                        invalidReason = ManualQuadInvalidReason.DISTINCT_CORNERS_REQUIRED,
                        errorMessage = "All 4 corners must be distinct and separated."
                    )
                }
            }
        }

        var minEdge = Float.MAX_VALUE
        for (i in 0 until 4) {
            val a = cornersNorm[i]
            val b = cornersNorm[(i + 1) % 4]
            val edgeLen = hypot(b.x - a.x, b.y - a.y)
            if (edgeLen < minEdge) minEdge = edgeLen
        }
        if (minEdge < MIN_MANUAL_EDGE_LENGTH_NORM) {
            return ManualQuadValidationResult(
                isValid = false,
                invalidReason = ManualQuadInvalidReason.EDGE_TOO_SHORT,
                errorMessage = "Document edges are too short. Drag corners farther apart.",
                minEdgeLengthNorm = minEdge
            )
        }

        val p0 = cornersNorm[0]
        val p1 = cornersNorm[1]
        val p2 = cornersNorm[2]
        val p3 = cornersNorm[3]

        if (segmentsIntersectOrTouch(p0, p1, p2, p3) || segmentsIntersectOrTouch(p1, p2, p3, p0)) {
            return ManualQuadValidationResult(
                isValid = false,
                invalidReason = ManualQuadInvalidReason.SELF_INTERSECTING_EDGES,
                errorMessage = "Document edges cannot cross or self-intersect. Please uncross the corners.",
                minEdgeLengthNorm = minEdge
            )
        }

        for (i in 0 until 4) {
            val a = cornersNorm[i]
            val b = cornersNorm[(i + 1) % 4]
            val c = cornersNorm[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross <= 0.0005f) {
                return ManualQuadValidationResult(
                    isValid = false,
                    invalidReason = ManualQuadInvalidReason.NON_CONVEX_OR_REVERSED_WINDING,
                    errorMessage = "Corners must form a convex clockwise quadrilateral without inward folds.",
                    minEdgeLengthNorm = minEdge
                )
            }
        }

        for (i in 0 until 4) {
            val prev = cornersNorm[(i + 3) % 4]
            val curr = cornersNorm[i]
            val next = cornersNorm[(i + 1) % 4]
            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val len1 = hypot(v1x, v1y)
            val len2 = hypot(v2x, v2y)
            val cosTheta = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1f, 1f)
            val deg = Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
            if (deg < MIN_MANUAL_INTERIOR_ANGLE_DEG || deg > MAX_MANUAL_INTERIOR_ANGLE_DEG) {
                return ManualQuadValidationResult(
                    isValid = false,
                    invalidReason = ManualQuadInvalidReason.INVALID_INTERIOR_ANGLE,
                    errorMessage = "Corner angles must be between 25° and 160°. Please adjust the corners.",
                    minEdgeLengthNorm = minEdge
                )
            }
        }

        val areaNorm = 0.5f * abs(
            (p0.x * p1.y - p1.x * p0.y) +
                (p1.x * p2.y - p2.x * p1.y) +
                (p2.x * p3.y - p3.x * p2.y) +
                (p3.x * p0.y - p0.x * p3.y)
        )
        if (areaNorm < MIN_MANUAL_QUAD_AREA_NORM) {
            return ManualQuadValidationResult(
                isValid = false,
                invalidReason = ManualQuadInvalidReason.AREA_TOO_SMALL,
                errorMessage = "Selected document region is too small. Expand the 4 corners.",
                areaNorm = areaNorm,
                minEdgeLengthNorm = minEdge
            )
        }

        // Validate clockwise cyclic assignment (top edge above bottom edge and left edge left of right edge
        // across rotations up to ±80°, without requiring axis-aligned corner inequalities)
        val topMidY = (p0.y + p1.y) * 0.5f
        val bottomMidY = (p2.y + p3.y) * 0.5f
        val leftMidX = (p3.x + p0.x) * 0.5f
        val rightMidX = (p1.x + p2.x) * 0.5f
        if (topMidY >= bottomMidY || leftMidX >= rightMidX) {
            return ManualQuadValidationResult(
                isValid = false,
                invalidReason = ManualQuadInvalidReason.INVALID_CLOCKWISE_ORDERING,
                errorMessage = "Corner ordering is inverted. Keep Top-Left, Top-Right, Bottom-Right, Bottom-Left in clockwise order.",
                areaNorm = areaNorm,
                minEdgeLengthNorm = minEdge
            )
        }

        return ManualQuadValidationResult(
            isValid = true,
            invalidReason = null,
            errorMessage = null,
            areaNorm = areaNorm,
            minEdgeLengthNorm = minEdge
        )
    }

    private fun segmentsIntersectOrTouch(a: PointF, b: PointF, c: PointF, d: PointF): Boolean {
        fun ccw(p1: PointF, p2: PointF, p3: PointF): Float =
            (p2.x - p1.x) * (p3.y - p1.y) - (p2.y - p1.y) * (p3.x - p1.x)

        fun onSegment(p: PointF, q: PointF, r: PointF): Boolean {
            return q.x <= max(p.x, r.x) + 1e-5f &&
                q.x >= min(p.x, r.x) - 1e-5f &&
                q.y <= max(p.y, r.y) + 1e-5f &&
                q.y >= min(p.y, r.y) - 1e-5f
        }

        val d1 = ccw(a, b, c)
        val d2 = ccw(a, b, d)
        val d3 = ccw(c, d, a)
        val d4 = ccw(c, d, b)

        if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
        ) {
            return true
        }
        if (abs(d1) <= 1e-5f && onSegment(a, c, b)) return true
        if (abs(d2) <= 1e-5f && onSegment(a, d, b)) return true
        if (abs(d3) <= 1e-5f && onSegment(c, a, d)) return true
        if (abs(d4) <= 1e-5f && onSegment(c, b, d)) return true
        return false
    }
}

/**
 * Interactive 4-corner quadrilateral adjustment overlay for Phase 3B Document & ID Card perspective warp.
 * Uses [CornerOverlayCoordinateMapper] so display, hit-testing, dragging, and applying corners operate
 * strictly within the [ContentScale.Fit] rendered image bounds rather than the full Canvas area.
 */
@Composable
fun InteractiveCornerCropOverlay(
    bitmap: Bitmap,
    initialCornersNorm: List<PointF>,
    confidence: Float,
    onApplyCorners: (List<PointF>) -> Unit,
    onResetCorners: () -> Unit,
    modifier: Modifier = Modifier,
    onInvalidCorners: ((String) -> Unit)? = null
) {
    val corners = remember(bitmap.width, bitmap.height, initialCornersNorm) {
        val sanitized = CornerOverlayCoordinateMapper.sanitizeInitialCorners(initialCornersNorm)
        mutableStateListOf(sanitized[0], sanitized[1], sanitized[2], sanitized[3])
    }

    var activeHandleIndex by remember { mutableIntStateOf(-1) }
    var validationErrorMessage by remember(bitmap.width, bitmap.height, initialCornersNorm) {
        mutableStateOf<String?>(null)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("interactive_corner_crop_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Adjust Document Corners",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Edge Score: ${(confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (confidence >= 0.80f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF10141C)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = "Document Corner Adjustment Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )

                val primaryColor = MaterialTheme.colorScheme.primary

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("corner_crop_canvas")
                        .pointerInput(bitmap.width, bitmap.height, initialCornersNorm) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val fitTransform = CornerOverlayCoordinateMapper.computeImageFitTransform(
                                    bitmapWidth = bitmap.width,
                                    bitmapHeight = bitmap.height,
                                    canvasWidth = size.width.toFloat(),
                                    canvasHeight = size.height.toFloat()
                                )
                                val hitRadius = max(48.dp.toPx(), CornerOverlayCoordinateMapper.DEFAULT_HANDLE_HIT_RADIUS_PX)
                                val hitIdx = CornerOverlayCoordinateMapper.findNearestCornerHandleIndex(
                                    pointerCanvas = down.position,
                                    cornersNorm = corners,
                                    fitTransform = fitTransform,
                                    hitRadiusPx = hitRadius
                                )
                                activeHandleIndex = hitIdx
                                if (hitIdx !in 0..3) {
                                    return@awaitEachGesture
                                }
                                down.consume()
                                val startCornerNorm = PointF(corners[hitIdx].x, corners[hitIdx].y)
                                val downPointerCanvas = down.position

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            change.consume()
                                            break
                                        }
                                        val currentFit = CornerOverlayCoordinateMapper.computeImageFitTransform(
                                            bitmapWidth = bitmap.width,
                                            bitmapHeight = bitmap.height,
                                            canvasWidth = size.width.toFloat(),
                                            canvasHeight = size.height.toFloat()
                                        )
                                        val totalDelta = change.position - downPointerCanvas
                                        corners[hitIdx] = CornerOverlayCoordinateMapper.dragCornerHandle(
                                            startCornerNorm = startCornerNorm,
                                            totalDragCanvasDelta = totalDelta,
                                            fitTransform = currentFit
                                        )
                                        if (validationErrorMessage != null &&
                                            CornerOverlayCoordinateMapper.validateManualDocumentQuad(corners).isValid
                                        ) {
                                            validationErrorMessage = null
                                        }
                                        change.consume()
                                    }
                                } finally {
                                    activeHandleIndex = -1
                                }
                            }
                        }
                ) {
                    val fitTransform = CornerOverlayCoordinateMapper.computeImageFitTransform(
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                        canvasWidth = size.width,
                        canvasHeight = size.height
                    )

                    // Subtle border around the actual fitted image area to distinguish it from letterbox bars
                    drawRect(
                        color = Color.White.copy(alpha = 0.22f),
                        topLeft = Offset(fitTransform.left, fitTransform.top),
                        size = Size(fitTransform.displayedWidth, fitTransform.displayedHeight),
                        style = Stroke(width = 1.dp.toPx())
                    )

                    val pts = corners.map { fitTransform.bitmapNormalizedToCanvas(it) }
                    val isQuadValid = CornerOverlayCoordinateMapper.validateManualDocumentQuad(corners).isValid
                    val strokeColor = if (isQuadValid) Color(0xFF00E676) else Color(0xFFFF5252)

                    val quadPath = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        lineTo(pts[1].x, pts[1].y)
                        lineTo(pts[2].x, pts[2].y)
                        lineTo(pts[3].x, pts[3].y)
                        close()
                    }

                    drawPath(
                        path = quadPath,
                        color = if (isQuadValid) {
                            primaryColor.copy(alpha = 0.18f)
                        } else {
                            Color(0xFFFF5252).copy(alpha = 0.20f)
                        }
                    )
                    drawPath(
                        path = quadPath,
                        color = strokeColor,
                        style = Stroke(width = 3.dp.toPx())
                    )

                    pts.forEachIndexed { idx, offset ->
                        val isSelected = idx == activeHandleIndex
                        drawCircle(
                            color = Color.White,
                            radius = if (isSelected) 12.dp.toPx() else 9.dp.toPx(),
                            center = offset
                        )
                        drawCircle(
                            color = when {
                                !isQuadValid -> Color(0xFFFF5252)
                                isSelected -> primaryColor
                                else -> Color(0xFF00C853)
                            },
                            radius = if (isSelected) 9.dp.toPx() else 6.dp.toPx(),
                            center = offset
                        )
                    }
                }
            }

            if (validationErrorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("corner_crop_validation_error")
                ) {
                    Text(
                        text = validationErrorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val resetPts = CornerOverlayCoordinateMapper.defaultResetCorners()
                        for (i in 0 until 4) {
                            corners[i] = resetPts[i]
                        }
                        validationErrorMessage = null
                        onResetCorners()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("reset_corners_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset")
                }

                Button(
                    onClick = {
                        val snapshot = corners.map { PointF(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                        val validation = CornerOverlayCoordinateMapper.validateManualDocumentQuad(snapshot)
                        if (!validation.isValid) {
                            val msg = validation.errorMessage
                                ?: "Invalid corner quadrilateral. Please adjust the 4 corners before applying."
                            validationErrorMessage = msg
                            onInvalidCorners?.invoke(msg)
                        } else {
                            validationErrorMessage = null
                            onApplyCorners(snapshot)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("apply_corners_btn")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Warp")
                }
            }
        }
    }
}
