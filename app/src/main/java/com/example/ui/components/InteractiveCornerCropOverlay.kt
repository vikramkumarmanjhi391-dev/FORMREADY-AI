package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Interactive 4-corner quadrilateral adjustment overlay for Phase 3B Document & ID Card perspective warp.
 * Allows users to drag the 4 detected corners (Top-Left, Top-Right, Bottom-Right, Bottom-Left)
 * when auto-detection confidence is below 0.75 or when manual refinement is requested.
 */
@Composable
fun InteractiveCornerCropOverlay(
    bitmap: Bitmap,
    initialCornersNorm: List<PointF>,
    confidence: Float,
    onApplyCorners: (List<PointF>) -> Unit,
    onResetCorners: () -> Unit,
    modifier: Modifier = Modifier
) {
    val corners = remember(initialCornersNorm) {
        val list = if (initialCornersNorm.size == 4) {
            initialCornersNorm.map { PointF(it.x.coerceIn(0.02f, 0.98f), it.y.coerceIn(0.02f, 0.98f)) }
        } else {
            listOf(
                PointF(0.08f, 0.08f),
                PointF(0.92f, 0.08f),
                PointF(0.92f, 0.92f),
                PointF(0.08f, 0.92f)
            )
        }
        mutableStateListOf(list[0], list[1], list[2], list[3])
    }

    var activeHandleIndex by remember { mutableIntStateOf(-1) }

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
                    color = if (confidence >= 0.75f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF10141C))
            ) {
                Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = "Document Corner Adjustment Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                val primaryColor = MaterialTheme.colorScheme.primary

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("corner_crop_canvas")
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    var bestIdx = -1
                                    var bestDist = Float.MAX_VALUE
                                    corners.forEachIndexed { index, pt ->
                                        val px = pt.x * w
                                        val py = pt.y * h
                                        val dist = hypot(startOffset.x - px, startOffset.y - py)
                                        if (dist < bestDist) {
                                            bestDist = dist
                                            bestIdx = index
                                        }
                                    }
                                    activeHandleIndex = if (bestDist <= 96f) bestIdx else -1
                                },
                                onDrag = { change, dragAmount ->
                                    if (activeHandleIndex in 0..3) {
                                        change.consume()
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val h = size.height.toFloat().coerceAtLeast(1f)
                                        val cur = corners[activeHandleIndex]
                                        val nx = (cur.x + dragAmount.x / w).coerceIn(0.02f, 0.98f)
                                        val ny = (cur.y + dragAmount.y / h).coerceIn(0.02f, 0.98f)
                                        corners[activeHandleIndex] = PointF(nx, ny)
                                    }
                                },
                                onDragEnd = { activeHandleIndex = -1 },
                                onDragCancel = { activeHandleIndex = -1 }
                            )
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val pts = corners.map { Offset(it.x * w, it.y * h) }

                    val quadPath = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        lineTo(pts[1].x, pts[1].y)
                        lineTo(pts[2].x, pts[2].y)
                        lineTo(pts[3].x, pts[3].y)
                        close()
                    }

                    drawPath(
                        path = quadPath,
                        color = primaryColor.copy(alpha = 0.18f)
                    )
                    drawPath(
                        path = quadPath,
                        color = Color(0xFF00E676),
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
                            color = if (isSelected) primaryColor else Color(0xFF00C853),
                            radius = if (isSelected) 9.dp.toPx() else 6.dp.toPx(),
                            center = offset
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        corners[0] = PointF(0.06f, 0.06f)
                        corners[1] = PointF(0.94f, 0.06f)
                        corners[2] = PointF(0.94f, 0.94f)
                        corners[3] = PointF(0.06f, 0.94f)
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
                    onClick = { onApplyCorners(corners.toList()) },
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
