package com.example.camera

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Accurate coordinate transformation utility between CameraX [androidx.camera.view.PreviewView]
 * (`ScaleType.FILL_CENTER`) and the captured full-sensor image buffer.
 *
 * Accounts for:
 * 1. Raw sensor dimensions vs. display/target rotation (`0°`, `90°`, `180°`, `270°`).
 * 2. `PreviewView.ScaleType.FILL_CENTER` center-crop scaling between the upright sensor aspect ratio
 *    (e.g., 4:3) and the device screen viewport aspect ratio (e.g., 20:9 on Motorola Moto G60).
 * 3. Front-camera (`LENS_FACING_FRONT`) horizontal preview mirroring versus unmirrored saved JPEGs.
 */
object CameraViewportMapper {

    data class ViewportMappingConfig(
        val rawSensorWidth: Int,
        val rawSensorHeight: Int,
        val rotationDegrees: Int = 0,
        val previewWidth: Int,
        val previewHeight: Int,
        val isFrontCamera: Boolean = false,
        val isSavedOutputMirrored: Boolean = false
    ) {
        val normalizedRotation: Int
            get() = ((rotationDegrees % 360) + 360) % 360

        /**
         * Upright image width after EXIF / target rotation is applied.
         */
        val uprightImageWidth: Int
            get() = if (normalizedRotation == 90 || normalizedRotation == 270) rawSensorHeight else rawSensorWidth

        /**
         * Upright image height after EXIF / target rotation is applied.
         */
        val uprightImageHeight: Int
            get() = if (normalizedRotation == 90 || normalizedRotation == 270) rawSensorWidth else rawSensorHeight
    }

    /**
     * Computes the visible sensor crop rectangle (in upright post-EXIF image pixel coordinates)
     * that is displayed inside a `PreviewView` using `ScaleType.FILL_CENTER`.
     */
    fun computeVisibleSensorViewportPx(config: ViewportMappingConfig): RectF {
        val imgW = config.uprightImageWidth.toFloat().coerceAtLeast(1f)
        val imgH = config.uprightImageHeight.toFloat().coerceAtLeast(1f)
        val prevW = config.previewWidth.toFloat().coerceAtLeast(1f)
        val prevH = config.previewHeight.toFloat().coerceAtLeast(1f)

        // FILL_CENTER scales the image uniformly so both dimensions are >= preview dimensions
        val scale = max(prevW / imgW, prevH / imgH)
        val scaledImgW = imgW * scale
        val scaledImgH = imgH * scale

        val offsetXInScaled = (scaledImgW - prevW) * 0.5f
        val offsetYInScaled = (scaledImgH - prevH) * 0.5f

        val visibleLeft = (offsetXInScaled / scale).coerceIn(0f, imgW)
        val visibleTop = (offsetYInScaled / scale).coerceIn(0f, imgH)
        val visibleRight = ((offsetXInScaled + prevW) / scale).coerceIn(visibleLeft, imgW)
        val visibleBottom = ((offsetYInScaled + prevH) / scale).coerceIn(visibleTop, imgH)

        return RectF(visibleLeft, visibleTop, visibleRight, visibleBottom)
    }

    /**
     * Maps a normalized rectangle `[0..1]` defined in the `PreviewView` coordinate space
     * (such as the passport oval, signature cutout box, or document frame) into the
     * normalized `[0..1]` coordinate space of the upright EXIF-corrected captured image.
     *
     * Handles front-camera horizontal preview mirroring: Android's `PreviewView` mirrors the live
     * front-camera preview horizontally like a mirror, whereas `ImageCapture` saves an unmirrored
     * image by default unless `isSavedOutputMirrored` is true.
     */
    fun mapPreviewRectToSensorNormalizedRect(
        previewRectNorm: RectF,
        config: ViewportMappingConfig
    ): RectF {
        val clampedLeft = previewRectNorm.left.coerceIn(0f, 1f)
        val clampedTop = previewRectNorm.top.coerceIn(0f, 1f)
        val clampedRight = previewRectNorm.right.coerceIn(clampedLeft, 1f)
        val clampedBottom = previewRectNorm.bottom.coerceIn(clampedTop, 1f)

        // Account for horizontal mirroring difference between live front-camera preview and saved file
        val requiresHorizontalFlip = config.isFrontCamera && !config.isSavedOutputMirrored
        val effectivePreviewLeft = if (requiresHorizontalFlip) 1f - clampedRight else clampedLeft
        val effectivePreviewRight = if (requiresHorizontalFlip) 1f - clampedLeft else clampedRight

        val visibleViewportPx = computeVisibleSensorViewportPx(config)
        val imgW = config.uprightImageWidth.toFloat().coerceAtLeast(1f)
        val imgH = config.uprightImageHeight.toFloat().coerceAtLeast(1f)

        val mappedLeftPx = visibleViewportPx.left + effectivePreviewLeft * visibleViewportPx.width()
        val mappedRightPx = visibleViewportPx.left + effectivePreviewRight * visibleViewportPx.width()
        val mappedTopPx = visibleViewportPx.top + clampedTop * visibleViewportPx.height()
        val mappedBottomPx = visibleViewportPx.top + clampedBottom * visibleViewportPx.height()

        val normLeft = (mappedLeftPx / imgW).coerceIn(0f, 1f)
        val normTop = (mappedTopPx / imgH).coerceIn(0f, 1f)
        val normRight = (mappedRightPx / imgW).coerceIn(normLeft, 1f)
        val normBottom = (mappedBottomPx / imgH).coerceIn(normTop, 1f)

        return RectF(normLeft, normTop, normRight, normBottom)
    }

    /**
     * Maps a normalized preview rectangle `[0..1]` directly to integer pixel coordinates
     * in the upright EXIF-normalized captured image.
     */
    fun mapPreviewRectToUprightPixelRect(
        previewRectNorm: RectF,
        config: ViewportMappingConfig
    ): Rect {
        val norm = mapPreviewRectToSensorNormalizedRect(previewRectNorm, config)
        val imgW = config.uprightImageWidth.coerceAtLeast(1)
        val imgH = config.uprightImageHeight.coerceAtLeast(1)

        val left = (norm.left * imgW).roundToInt().coerceIn(0, imgW - 1)
        val top = (norm.top * imgH).roundToInt().coerceIn(0, imgH - 1)
        val right = (norm.right * imgW).roundToInt().coerceIn(left + 1, imgW)
        val bottom = (norm.bottom * imgH).roundToInt().coerceIn(top + 1, imgH)
        return Rect(left, top, right, bottom)
    }

    /**
     * Maps an upright post-EXIF normalized rectangle into the unrotated raw sensor buffer
     * normalized coordinate space (`0°`, `90°`, `180°`, `270°`).
     */
    fun mapUprightNormalizedRectToRawSensorRect(
        uprightRectNorm: RectF,
        rotationDegrees: Int
    ): RectF {
        val l = uprightRectNorm.left.coerceIn(0f, 1f)
        val t = uprightRectNorm.top.coerceIn(0f, 1f)
        val r = uprightRectNorm.right.coerceIn(l, 1f)
        val b = uprightRectNorm.bottom.coerceIn(t, 1f)

        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> RectF(t, 1f - r, b, 1f - l)
            180 -> RectF(1f - r, 1f - b, 1f - l, 1f - t)
            270 -> RectF(1f - b, l, 1f - t, r)
            else -> RectF(l, t, r, b)
        }
    }

    /**
     * Crops an upright EXIF-normalized sensor [Bitmap] to the exact region visible inside
     * `previewRectNorm` on the `PreviewView` (`FILL_CENTER`).
     */
    fun cropUprightSensorBitmapToPreviewGuide(
        uprightBitmap: Bitmap,
        previewRectNorm: RectF,
        previewWidth: Int,
        previewHeight: Int,
        isFrontCamera: Boolean = false,
        isSavedOutputMirrored: Boolean = false
    ): Bitmap {
        val config = ViewportMappingConfig(
            rawSensorWidth = uprightBitmap.width,
            rawSensorHeight = uprightBitmap.height,
            rotationDegrees = 0, // Bitmap is already EXIF-normalized upright
            previewWidth = min(previewWidth, previewHeight).coerceAtLeast(1),
            previewHeight = max(previewWidth, previewHeight).coerceAtLeast(1),
            isFrontCamera = isFrontCamera,
            isSavedOutputMirrored = isSavedOutputMirrored
        )
        val pixelRect = mapPreviewRectToUprightPixelRect(previewRectNorm, config)
        return Bitmap.createBitmap(
            uprightBitmap,
            pixelRect.left,
            pixelRect.top,
            pixelRect.width(),
            pixelRect.height()
        )
    }
}
