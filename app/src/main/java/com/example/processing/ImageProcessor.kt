package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.FileFormat
import com.example.data.model.PreparationStatus
import com.example.data.model.ProcessedFileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageProcessor {

    /**
     * Decodes a Bitmap from Uri with memory protection and EXIF orientation correction.
     * Prevents sideways or upside-down camera photos.
     */
    suspend fun decodeBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 1. Read EXIF Orientation
            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (e: Exception) {
                // If EXIF reading fails, proceed with default orientation
                e.printStackTrace()
            }

            // 2. Sample size downscaling to prevent OOM on large camera images
            var input: InputStream? = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, boundsOptions)
            input?.close()

            var inSampleSize = 1
            while (boundsOptions.outWidth / inSampleSize > maxDimension || boundsOptions.outHeight / inSampleSize > maxDimension) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            input = context.contentResolver.openInputStream(uri)
            val decodedBitmap = BitmapFactory.decodeStream(input, null, decodeOptions)
            input?.close()

            if (decodedBitmap == null) return@withContext null

            // 3. Apply EXIF Rotation Matrix if needed
            val rotationMatrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotationMatrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotationMatrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotationMatrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> rotationMatrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> rotationMatrix.postScale(1f, -1f)
            }

            if (!rotationMatrix.isIdentity) {
                val rotated = Bitmap.createBitmap(
                    decodedBitmap, 0, 0,
                    decodedBitmap.width, decodedBitmap.height,
                    rotationMatrix, true
                )
                if (rotated != decodedBitmap) {
                    decodedBitmap.recycle()
                }
                rotated
            } else {
                decodedBitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Crops a bitmap to a normalized crop rectangle [0f..1f].
     */
    fun cropBitmap(src: Bitmap, cropRectNorm: RectF): Bitmap {
        val left = (cropRectNorm.left.coerceIn(0f, 1f) * src.width).toInt()
        val top = (cropRectNorm.top.coerceIn(0f, 1f) * src.height).toInt()
        val right = (cropRectNorm.right.coerceIn(0f, 1f) * src.width).toInt()
        val bottom = (cropRectNorm.bottom.coerceIn(0f, 1f) * src.height).toInt()

        val width = (right - left).coerceAtLeast(10)
        val height = (bottom - top).coerceAtLeast(10)

        val safeLeft = left.coerceIn(0, src.width - width)
        val safeTop = top.coerceIn(0, src.height - height)

        return Bitmap.createBitmap(src, safeLeft, safeTop, width, height)
    }

    /**
     * Center crops a bitmap to match a target aspect ratio (width / height)
     * without distorting or stretching.
     */
    fun cropToAspectRatio(src: Bitmap, targetRatio: Float): Bitmap {
        if (targetRatio <= 0f) return src
        val currentRatio = src.width.toFloat() / src.height.toFloat()

        return if (currentRatio > targetRatio) {
            // Image is wider than target ratio: crop sides
            val targetWidth = (src.height * targetRatio).toInt().coerceIn(10, src.width)
            val left = (src.width - targetWidth) / 2
            Bitmap.createBitmap(src, left, 0, targetWidth, src.height)
        } else if (currentRatio < targetRatio) {
            // Image is taller than target ratio: crop top/bottom
            val targetHeight = (src.width / targetRatio).toInt().coerceIn(10, src.height)
            val top = (src.height - targetHeight) / 2
            Bitmap.createBitmap(src, 0, top, src.width, targetHeight)
        } else {
            src
        }
    }

    /**
     * Comprehensive Image Preparation Engine:
     * - Respects aspect ratio cropping
     * - Resizes with proportional or exact scaling
     * - Applies official Name & Date of Photo strips
     * - Executes intelligent stepped compression
     * - Checks for requirement conflicts (e.g., impossible dimensions for KB limit)
     * - Validates actual written output file on disk
     */
    suspend fun processImageForRule(
        context: Context,
        originalBitmap: Bitmap,
        rule: DocumentRule,
        candidateName: String = "",
        dateOfPhoto: String = "",
        customTargetKb: Int? = null,
        customWidthPx: Int? = null,
        customHeightPx: Int? = null,
        enforceProportional: Boolean = true,
        cropRectNorm: RectF? = null,
        autoCropMargins: Boolean = false,
        contrastFactor: Float = 1.0f,
        brightnessOffset: Float = 0f,
        inkThreshold: Int = 180,
        preserveInkColor: Boolean = true
    ): ProcessedFileResult = withContext(Dispatchers.Default) {
        var workingBitmap = originalBitmap

        // 1. Manual Crop if specified
        if (cropRectNorm != null) {
            workingBitmap = cropBitmap(workingBitmap, cropRectNorm)
        }

        // 2. Auto-crop blank margins (Signature & Thumb)
        if (autoCropMargins) {
            workingBitmap = cropBlankMargins(workingBitmap)
        }

        // 3. Aspect ratio conforming (if aspect ratio is designated and no custom exact stretch was chosen)
        val desiredRatio = rule.targetAspectRatio
        if (desiredRatio != null && desiredRatio > 0f && cropRectNorm == null && enforceProportional) {
            workingBitmap = cropToAspectRatio(workingBitmap, desiredRatio)
        }

        // 4. Calculate target dimensions
        val targetWidth: Int
        val targetHeight: Int

        val reqExactW = customWidthPx ?: rule.exactWidthPx
        val reqExactH = customHeightPx ?: rule.exactHeightPx

        if (reqExactW != null && reqExactH != null && reqExactW > 0 && reqExactH > 0) {
            if (enforceProportional && cropRectNorm == null) {
                // Crop to match target ratio first so scaling does not squish
                val ratio = reqExactW.toFloat() / reqExactH.toFloat()
                workingBitmap = cropToAspectRatio(workingBitmap, ratio)
            }
            targetWidth = reqExactW
            targetHeight = reqExactH
        } else {
            val maxBound = rule.maxWidthPx ?: 800
            val curRatio = workingBitmap.width.toFloat() / workingBitmap.height.toFloat()
            if (workingBitmap.width > maxBound || workingBitmap.height > maxBound) {
                if (curRatio >= 1.0f) {
                    targetWidth = maxBound
                    targetHeight = (maxBound / curRatio).toInt().coerceAtLeast(50)
                } else {
                    targetHeight = maxBound
                    targetWidth = (maxBound * curRatio).toInt().coerceAtLeast(50)
                }
            } else {
                targetWidth = workingBitmap.width
                targetHeight = workingBitmap.height
            }
        }

        // 5. Scale Bitmap
        var scaled = Bitmap.createScaledBitmap(workingBitmap, targetWidth, targetHeight, true)

        // 6. Signature Enhancement & Background Whitening
        if (contrastFactor != 1.0f || brightnessOffset != 0f || inkThreshold != 180 || !preserveInkColor) {
            scaled = adjustSignatureLighting(scaled, contrastFactor, brightnessOffset, inkThreshold, preserveInkColor)
        }

        // 7. Name & Date Strip (UPSC, NTA, SSC requirements)
        if (rule.requiresNameAndDateStrip && (candidateName.isNotBlank() || dateOfPhoto.isNotBlank())) {
            scaled = addNameAndDateStrip(scaled, candidateName, dateOfPhoto)
        }

        // 8. Color Mode Transformations
        when (rule.colorMode) {
            ColorMode.GRAYSCALE -> scaled = applyGrayscale(scaled)
            ColorMode.BLACK_AND_WHITE -> scaled = applyHighContrastMonochrome(scaled, inkThreshold)
            else -> {}
        }

        // 9. Intelligent Compression & File Output
        val targetMaxKb = customTargetKb ?: rule.maxKb
        val targetMinKb = rule.minKb.coerceAtMost(targetMaxKb)

        val chosenFormat = if (rule.allowedFormats.contains(FileFormat.PNG) && !rule.allowedFormats.contains(FileFormat.JPG)) {
            FileFormat.PNG
        } else {
            FileFormat.JPG
        }

        val compressFormat = if (chosenFormat == FileFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

        // Detect conflicts: e.g. PNG with high resolution requested under 30 KB
        var hasConflict = false
        var conflictReason: String? = null

        if (compressFormat == Bitmap.CompressFormat.PNG && targetMaxKb < 35 && (scaled.width > 400 || scaled.height > 400)) {
            hasConflict = true
            conflictReason = "PNG is a lossless format and cannot compress ${scaled.width}x${scaled.height}px below $targetMaxKb KB. Consider JPG format."
        }

        val compressionResult = compressToTargetKbWithDetails(
            src = scaled,
            targetMinKb = targetMinKb,
            targetMaxKb = targetMaxKb,
            format = compressFormat,
            allowDownscale = !hasConflict
        )

        // 10. Write actual file to cache directory
        val fileName = "formready_${rule.documentType.name.lowercase()}_${System.currentTimeMillis()}.${chosenFormat.extension}"
        val docsDir = File(context.cacheDir, "prepared_docs").apply { mkdirs() }
        val outputFile = File(docsDir, fileName)

        withContext(Dispatchers.IO) {
            FileOutputStream(outputFile).use { it.write(compressionResult.bytes) }
        }

        // Measure actual disk file size
        val actualBytes = outputFile.length()
        val actualSizeKb = actualBytes / 1024.0
        val finalWidth = compressionResult.finalWidth
        val finalHeight = compressionResult.finalHeight
        val finalAspectRatio = if (finalHeight > 0) finalWidth.toFloat() / finalHeight.toFloat() else 1.0f

        // 11. Rigorous Validation against Rule
        val validationMessages = mutableListOf<String>()
        var isCompliant = true
        var status = PreparationStatus.SUCCESS_PREPARED

        if (actualSizeKb > targetMaxKb) {
            isCompliant = false
            status = PreparationStatus.SIZE_EXCEEDED
            validationMessages.add("File size (${String.format(Locale.US, "%.1f", actualSizeKb)} KB) exceeds the maximum allowed limit of $targetMaxKb KB.")
        } else if (actualSizeKb < targetMinKb) {
            // Note: some portals reject if file is too small (e.g. IBPS < 10 KB or < 20 KB)
            isCompliant = false
            status = PreparationStatus.SIZE_BELOW_MIN
            validationMessages.add("File size (${String.format(Locale.US, "%.1f", actualSizeKb)} KB) is below the required portal minimum of $targetMinKb KB.")
        } else {
            validationMessages.add("File size verified: ${String.format(Locale.US, "%.1f", actualSizeKb)} KB (Allowed: $targetMinKb - $targetMaxKb KB)")
        }

        if (reqExactW != null && reqExactH != null) {
            if (finalWidth == reqExactW && finalHeight == reqExactH) {
                validationMessages.add("Dimensions verified: $finalWidth x $finalHeight px (Exact match)")
            } else {
                if (compressionResult.wasDownscaled) {
                    validationMessages.add("Dimensions adjusted to $finalWidth x $finalHeight px to satisfy the strict $targetMaxKb KB limit.")
                } else {
                    isCompliant = false
                    status = PreparationStatus.DIMENSIONS_MISMATCH
                    validationMessages.add("Dimensions ($finalWidth x $finalHeight px) do not match required $reqExactW x $reqExactH px.")
                }
            }
        } else {
            validationMessages.add("Resolution: $finalWidth x $finalHeight px (Aspect Ratio: ${String.format(Locale.US, "%.2f", finalAspectRatio)})")
        }

        if (hasConflict && conflictReason != null) {
            status = PreparationStatus.CONFLICT_LIMITS
            validationMessages.add(conflictReason)
        }

        ProcessedFileResult(
            ruleId = rule.id,
            documentType = rule.documentType,
            originalFileName = fileName,
            processedFilePath = outputFile.absolutePath,
            fileFormat = chosenFormat,
            fileSizeBytes = actualBytes,
            fileSizeKb = actualSizeKb,
            widthPx = finalWidth,
            heightPx = finalHeight,
            pageCount = 1,
            isCompliant = isCompliant,
            validationMessages = validationMessages,
            status = status,
            aspectRatio = finalAspectRatio,
            hasConflict = hasConflict,
            conflictReason = conflictReason
        )
    }

    /**
     * Cleans up scanned signatures by detecting the bounding box of non-white pixels
     * and trimming away unnecessary paper borders while protecting thin ink strokes.
     */
    fun cropBlankMargins(src: Bitmap, paddingPercent: Float = 0.08f): Bitmap {
        val width = src.width
        val height = src.height
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        var foundContent = false
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

                // Consider pixel as ink if brightness is less than 225
                if (brightness < 225) {
                    foundContent = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (!foundContent || minX >= maxX || minY >= maxY) return src

        // Add comfortable breathing margin so strokes are never clipped
        val padX = ((maxX - minX) * paddingPercent).toInt().coerceAtLeast(10)
        val padY = ((maxY - minY) * paddingPercent).toInt().coerceAtLeast(10)

        val cropLeft = (minX - padX).coerceAtLeast(0)
        val cropTop = (minY - padY).coerceAtLeast(0)
        val cropRight = (maxX + padX).coerceAtMost(width)
        val cropBottom = (maxY + padY).coerceAtMost(height)

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop

        return if (cropW > 16 && cropH > 16) {
            Bitmap.createBitmap(src, cropLeft, cropTop, cropW, cropH)
        } else {
            src
        }
    }

    /**
     * User-controllable signature enhancer:
     * - Whitens grayish/yellowish paper background
     * - Darkens and deepens ink strokes without destroying thin lines
     * - Supports preserving original pen color (e.g. blue or black ink)
     */
    fun adjustSignatureLighting(
        src: Bitmap,
        contrastFactor: Float,
        brightnessOffset: Float,
        inkThreshold: Int,
        preserveInkColor: Boolean
    ): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val paperWhitePoint = (inkThreshold + 15).coerceAtMost(245)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = (r * 0.299 + g * 0.587 + b * 0.114).toInt()

            if (lum >= paperWhitePoint) {
                // Pure clean white paper background
                pixels[i] = Color.WHITE
            } else {
                // Ink stroke: deepen according to contrast and inkThreshold
                if (preserveInkColor) {
                    val inkRatio = (lum.toFloat() / paperWhitePoint.toFloat()).coerceIn(0f, 1f)
                    val darkenedRatio = Math.pow(inkRatio.toDouble(), contrastFactor.toDouble().coerceAtLeast(0.5)).toFloat()

                    val newR = ((r * darkenedRatio) + brightnessOffset).toInt().coerceIn(0, 255)
                    val newG = ((g * darkenedRatio) + brightnessOffset).toInt().coerceIn(0, 255)
                    val newB = ((b * darkenedRatio) + brightnessOffset).toInt().coerceIn(0, 255)
                    pixels[i] = Color.rgb(newR, newG, newB)
                } else {
                    // High-contrast clean black ink
                    val newLum = ((lum * (1f / contrastFactor)) + brightnessOffset).toInt().coerceIn(0, 255)
                    val finalVal = if (newLum < inkThreshold) (newLum * 0.7f).toInt().coerceIn(0, 255) else 255
                    pixels[i] = Color.rgb(finalVal, finalVal, finalVal)
                }
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Adds candidate name and DOP (Date of Photo) strip at the bottom of the photo.
     */
    fun addNameAndDateStrip(src: Bitmap, name: String, date: String): Bitmap {
        val width = src.width
        val height = src.height
        val stripHeight = (height * 0.18f).toInt().coerceAtLeast(36)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(src, 0f, 0f, null)

        val stripPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, (height - stripHeight).toFloat(), width.toFloat(), height.toFloat(), stripPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val formattedDate = if (date.isNotBlank()) date else SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())

        if (name.isNotBlank() && date.isNotBlank()) {
            val textSize = (stripHeight * 0.38f).coerceIn(11f, 32f)
            textPaint.textSize = textSize
            val nameY = height - stripHeight + textSize + (stripHeight * 0.12f)
            canvas.drawText(name.uppercase(Locale.US), (width / 2).toFloat(), nameY, textPaint)

            val dateText = "DOP: $formattedDate"
            val dateY = nameY + textSize + (stripHeight * 0.08f)
            textPaint.textSize = (textSize * 0.85f).coerceIn(10f, 28f)
            canvas.drawText(dateText, (width / 2).toFloat(), dateY, textPaint)
        } else {
            val text = if (name.isNotBlank()) name.uppercase(Locale.US) else "DOP: $formattedDate"
            val textSize = (stripHeight * 0.45f).coerceIn(12f, 34f)
            textPaint.textSize = textSize
            val textY = height - (stripHeight / 2) + (textSize / 3)
            canvas.drawText(text, (width / 2).toFloat(), textY, textPaint)
        }

        return result
    }

    fun applyGrayscale(src: Bitmap): Bitmap {
        val bmpGrayscale = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmpGrayscale)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmpGrayscale
    }

    fun applyHighContrastMonochrome(src: Bitmap, threshold: Int = 180): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            pixels[i] = if (lum > threshold) Color.WHITE else Color.BLACK
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    fun applyMagicDocFilter(src: Bitmap): Bitmap {
        return adjustSignatureLighting(
            src = src,
            contrastFactor = 1.3f,
            brightnessOffset = 0f,
            inkThreshold = 185,
            preserveInkColor = true
        )
    }

    data class CompressionDetails(
        val bytes: ByteArray,
        val finalWidth: Int,
        val finalHeight: Int,
        val finalQuality: Int,
        val wasDownscaled: Boolean
    )

    /**
     * Stepped compression algorithm that adjusts quality gradually (98 down to 15),
     * and applies controlled downscaling only when necessary to reach the target KB.
     */
    private fun compressToTargetKbWithDetails(
        src: Bitmap,
        targetMinKb: Int,
        targetMaxKb: Int,
        format: Bitmap.CompressFormat,
        allowDownscale: Boolean
    ): CompressionDetails {
        var currentBitmap = src
        var quality = 95
        var stream = ByteArrayOutputStream()
        currentBitmap.compress(format, quality, stream)
        var bytes = stream.toByteArray()
        var currentKb = bytes.size / 1024
        var wasDownscaled = false

        if (format == Bitmap.CompressFormat.PNG) {
            // PNG is lossless; quality param is ignored by encoder.
            // If PNG fits target, return immediately.
            if (currentKb <= targetMaxKb || !allowDownscale) {
                return CompressionDetails(bytes, currentBitmap.width, currentBitmap.height, 100, false)
            }
            // If PNG is too large, downscale dimensions gradually
            var scale = 0.92f
            while (currentKb > targetMaxKb && currentBitmap.width > 80 && currentBitmap.height > 80) {
                val newW = (currentBitmap.width * scale).toInt().coerceAtLeast(60)
                val newH = (currentBitmap.height * scale).toInt().coerceAtLeast(60)
                currentBitmap = Bitmap.createScaledBitmap(currentBitmap, newW, newH, true)
                wasDownscaled = true

                stream = ByteArrayOutputStream()
                currentBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bytes = stream.toByteArray()
                currentKb = bytes.size / 1024
            }
            return CompressionDetails(bytes, currentBitmap.width, currentBitmap.height, 100, wasDownscaled)
        }

        // Stepped search for optimal JPEG quality
        var lowQuality = 15
        var highQuality = 98
        var bestQuality = 85

        while (lowQuality <= highQuality) {
            val midQuality = (lowQuality + highQuality) / 2
            stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, midQuality, stream)
            bytes = stream.toByteArray()
            currentKb = bytes.size / 1024

            if (currentKb > targetMaxKb) {
                highQuality = midQuality - 2
            } else if (currentKb < targetMinKb && midQuality < 96) {
                bestQuality = midQuality
                lowQuality = midQuality + 2
            } else {
                bestQuality = midQuality
                break
            }
        }

        // If at lowest quality (15) it still exceeds targetMaxKb and downscale is allowed:
        if (currentKb > targetMaxKb && allowDownscale) {
            var scale = 0.90f
            while (currentKb > targetMaxKb && currentBitmap.width > 80 && currentBitmap.height > 80) {
                val newW = (currentBitmap.width * scale).toInt().coerceAtLeast(60)
                val newH = (currentBitmap.height * scale).toInt().coerceAtLeast(60)
                currentBitmap = Bitmap.createScaledBitmap(currentBitmap, newW, newH, true)
                wasDownscaled = true

                stream = ByteArrayOutputStream()
                currentBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                bytes = stream.toByteArray()
                currentKb = bytes.size / 1024
            }
        }

        return CompressionDetails(
            bytes = bytes,
            finalWidth = currentBitmap.width,
            finalHeight = currentBitmap.height,
            finalQuality = bestQuality,
            wasDownscaled = wasDownscaled
        )
    }
}
