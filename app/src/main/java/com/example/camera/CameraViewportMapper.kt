package com.example.camera

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Accurate coordinate transformation utility between CameraX [androidx.camera.view.PreviewView]
 * (`ScaleType.FILL_CENTER`) and the captured full-sensor image buffer.
 *
 * Enforces the Phase 0.4B Single-Transform Invariant across three explicit coordinate spaces:
 * 1. `RawSensorSpace`: Unrotated pixel coordinates `(0..rawW, 0..rawH)` as stored in the encoded JPEG stream.
 * 2. `UprightBitmapSpace`: Post-EXIF pixel coordinates `(0..uprightW, 0..uprightH)` returned by
 *    [UnifiedMediaInput.decodeBitmapWithExif].
 * 3. `PreviewViewSpace`: On-screen layout pixel coordinates `(0..previewW, 0..previewH)` of the
 *    live `PreviewView` (`ScaleType.FILL_CENTER`).
 *
 * Because [UnifiedMediaInput.resolveBitmap] and [UnifiedMediaInput.toBitmap] already return a bitmap in
 * `UprightBitmapSpace`, `mappedSensorCropRectNorm` is always defined in `UprightBitmapSpace` (`[0..1]`)
 * and must NEVER be chained with [mapUprightNormalizedRectToRawSensorRect].
 */
object CameraViewportMapper {

    /**
     * Resolves upright post-EXIF `(width, height)` for all 8 standard EXIF orientation tags.
     */
    fun resolveUprightDimensions(rawWidth: Int, rawHeight: Int, exifOrientation: Int): Pair<Int, Int> {
        return when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> rawHeight to rawWidth
            else -> rawWidth to rawHeight
        }
    }

    /**
     * Converts an EXIF orientation tag (`ORIENTATION_*`) to its clockwise rotation angle in degrees
     * (`0`, `90`, `180`, `270`).
     */
    fun exifOrientationToDegrees(exifOrientation: Int): Int {
        return when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    }

    data class ViewportMappingConfig(
        val rawSensorWidth: Int,
        val rawSensorHeight: Int,
        val rotationDegrees: Int = 0,
        val previewWidth: Int,
        val previewHeight: Int,
        val isFrontCamera: Boolean = false,
        val isSavedOutputMirrored: Boolean = false,
        val exifOrientation: Int = ExifInterface.ORIENTATION_UNDEFINED
    ) {
        val normalizedRotation: Int
            get() = ((rotationDegrees % 360) + 360) % 360

        /**
         * Upright image width after EXIF / target rotation is applied (`UprightBitmapSpace`).
         */
        val uprightImageWidth: Int
            get() = if (exifOrientation != ExifInterface.ORIENTATION_UNDEFINED) {
                resolveUprightDimensions(rawSensorWidth, rawSensorHeight, exifOrientation).first
            } else {
                if (normalizedRotation == 90 || normalizedRotation == 270) rawSensorHeight else rawSensorWidth
            }

        /**
         * Upright image height after EXIF / target rotation is applied (`UprightBitmapSpace`).
         */
        val uprightImageHeight: Int
            get() = if (exifOrientation != ExifInterface.ORIENTATION_UNDEFINED) {
                resolveUprightDimensions(rawSensorWidth, rawSensorHeight, exifOrientation).second
            } else {
                if (normalizedRotation == 90 || normalizedRotation == 270) rawSensorWidth else rawSensorHeight
            }
    }

    /**
     * Computes the visible sensor crop rectangle (in `UprightBitmapSpace` pixel coordinates)
     * that is displayed inside a `PreviewView` using `ScaleType.FILL_CENTER`.
     * Works for both portrait and landscape `PreviewView` and sensor dimensions.
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
     * Maps a normalized rectangle `[0..1]` defined in `PreviewViewSpace`
     * (such as the measured passport oval, signature cutout box, or document frame) into the
     * normalized `[0..1]` coordinate space of `UprightBitmapSpace` (the EXIF-corrected image).
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
     * in `UprightBitmapSpace`.
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
     * Standalone utility mapping an upright post-EXIF normalized rectangle (`UprightBitmapSpace`)
     * into the unrotated raw sensor buffer normalized coordinate space (`RawSensorSpace`) for
     * rotation degrees (`0°`, `90°`, `180°`, `270°`) or EXIF orientation constants (`1..8`).
     *
     * WARNING: Never chain this output with [UnifiedMediaInput.decodeBitmapWithExif], which
     * already returns a bitmap in `UprightBitmapSpace`.
     */
    fun mapUprightNormalizedRectToRawSensorRect(
        uprightRectNorm: RectF,
        rotationDegrees: Int
    ): RectF {
        if (rotationDegrees in 1..8) {
            return mapUprightNormalizedRectToRawSensorRectByExif(uprightRectNorm, rotationDegrees)
        }
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
     * Maps an upright post-EXIF normalized rectangle (`UprightBitmapSpace`) into `RawSensorSpace`
     * for all 8 standard EXIF orientation tags (`ORIENTATION_NORMAL` .. `ORIENTATION_ROTATE_270`).
     */
    fun mapUprightNormalizedRectToRawSensorRectByExif(
        uprightRectNorm: RectF,
        exifOrientation: Int
    ): RectF {
        val l = uprightRectNorm.left.coerceIn(0f, 1f)
        val t = uprightRectNorm.top.coerceIn(0f, 1f)
        val r = uprightRectNorm.right.coerceIn(l, 1f)
        val b = uprightRectNorm.bottom.coerceIn(t, 1f)

        return when (exifOrientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> RectF(1f - r, t, 1f - l, b)
            ExifInterface.ORIENTATION_ROTATE_180 -> RectF(1f - r, 1f - b, 1f - l, 1f - t)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> RectF(l, 1f - b, r, 1f - t)
            ExifInterface.ORIENTATION_TRANSPOSE -> RectF(t, l, b, r)
            ExifInterface.ORIENTATION_ROTATE_90 -> RectF(t, 1f - r, b, 1f - l)
            ExifInterface.ORIENTATION_TRANSVERSE -> RectF(1f - b, 1f - r, 1f - t, 1f - l)
            ExifInterface.ORIENTATION_ROTATE_270 -> RectF(1f - b, l, 1f - t, r)
            else -> RectF(l, t, r, b)
        }
    }

    /**
     * Crops an upright EXIF-normalized sensor [Bitmap] (`UprightBitmapSpace`) to the exact region
     * visible inside `previewRectNorm` on the `PreviewView` (`FILL_CENTER`), preserving the caller's
     * actual `previewWidth` and `previewHeight` in both portrait and landscape orientations.
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
            rotationDegrees = 0, // Bitmap is already EXIF-normalized in UprightBitmapSpace
            previewWidth = previewWidth.coerceAtLeast(1),
            previewHeight = previewHeight.coerceAtLeast(1),
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
