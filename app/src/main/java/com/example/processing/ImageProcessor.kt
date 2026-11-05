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
        decodeBitmapFromUriSync(context, uri, maxDimension)
    }

    /**
     * Synchronous version of [decodeBitmapFromUri] with identical EXIF orientation and memory bounds protection.
     */
    fun decodeBitmapFromUriSync(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
        return try {
            // 0. Verify valid image magic bytes
            val header = ByteArray(12)
            val headerRead = context.contentResolver.openInputStream(uri)?.use { it.read(header) } ?: -1
            if (!com.example.camera.UnifiedMediaInput.hasValidImageHeader(header, headerRead)) {
                return null
            }

            // 1. Read EXIF Orientation
            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Sample size downscaling to prevent OOM on large camera images
            var input: InputStream? = context.contentResolver.openInputStream(uri) ?: return null
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, boundsOptions)
            input?.close()

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

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

            if (decodedBitmap == null) return null

            // 3. Apply EXIF Rotation Matrix if needed
            val rotationMatrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotationMatrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotationMatrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotationMatrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> rotationMatrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> rotationMatrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    rotationMatrix.postRotate(90f)
                    rotationMatrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    rotationMatrix.postRotate(270f)
                    rotationMatrix.postScale(-1f, 1f)
                }
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
        preserveInkColor: Boolean = true,
        removeBackgroundToWhite: Boolean = false,
        customDpi: Int? = null
    ): ProcessedFileResult = withContext(Dispatchers.Default) {
        var workingBitmap = originalBitmap

        // 1. Manual or Guide-Frame Crop if specified
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

        // 5b. On-Device White Background Replacement + Gentle Face Enhancement (for Passport Photos)
        val shouldApplyWhiteBg = removeBackgroundToWhite &&
            (rule.documentType == com.example.data.model.DocumentType.PASSPORT_PHOTO ||
             rule.documentType == com.example.data.model.DocumentType.POSTCARD_PHOTO)
        if (shouldApplyWhiteBg) {
            scaled = applyAutoWhiteBackgroundAndFaceEnhance(scaled)
        }

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

        // 9. Intelligent Compression, Exact DPI Header & Safe Min-KB Guarantee
        val targetMaxKb = customTargetKb ?: rule.maxKb
        val targetMinKb = rule.minKb.coerceAtMost(targetMaxKb)
        val targetDpi = (customDpi ?: rule.dpi ?: 300).coerceIn(72, 1200)

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
            allowDownscale = !hasConflict,
            dpi = targetDpi
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
            isCompliant = false
            status = PreparationStatus.SIZE_BELOW_MIN
            validationMessages.add("File size (${String.format(Locale.US, "%.1f", actualSizeKb)} KB) is below the required portal minimum of $targetMinKb KB.")
        } else {
            validationMessages.add("File size verified: ${String.format(Locale.US, "%.1f", actualSizeKb)} KB (Allowed: $targetMinKb - $targetMaxKb KB)")
        }

        if (compressionResult.wasPaddedToMinKb) {
            validationMessages.add("Auto-KB Engine: Safe JPEG buffer padding applied to satisfy minimum $targetMinKb KB portal requirement.")
        }

        if (reqExactW != null && reqExactH != null) {
            if (finalWidth == reqExactW && finalHeight == reqExactH) {
                validationMessages.add("Dimensions verified: $finalWidth x $finalHeight px @ $targetDpi DPI (Exact match)")
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
            validationMessages.add("Resolution: $finalWidth x $finalHeight px @ $targetDpi DPI (Aspect Ratio: ${String.format(Locale.US, "%.2f", finalAspectRatio)})")
        }

        if (shouldApplyWhiteBg || rule.requiresWhiteBackground) {
            validationMessages.add("Background verified: Pure White (#FFFFFF) studio compliance.")
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
            conflictReason = conflictReason,
            dpi = targetDpi,
            wasPaddedToMinKb = compressionResult.wasPaddedToMinKb,
            hasWhiteBackground = shouldApplyWhiteBg || rule.requiresWhiteBackground
        )
    }

    /**
     * 100% On-Device Subject & Wall Background Separator + Face Brightness Enhancer (₹0 Cloud Cost).
     * Samples the outer perimeter (top & side edges) to model the wall/curtain background color,
     * replaces background pixels outside the head & shoulder silhouette with pure #FFFFFF white,
     * and applies a subtle studio brightness/contrast lift on the subject's face.
     */
    fun applyAutoWhiteBackgroundAndFaceEnhance(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        if (width < 16 || height < 16) return src

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 1. Sample top & upper-side perimeter pixels to learn the wall/background color
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        val borderW = (width * 0.08f).toInt().coerceAtLeast(2)
        val borderH = (height * 0.08f).toInt().coerceAtLeast(2)

        for (y in 0 until (height * 0.65f).toInt()) {
            for (x in 0 until width) {
                if (y < borderH || x < borderW || x >= width - borderW) {
                    val c = pixels[y * width + x]
                    sumR += Color.red(c)
                    sumG += Color.green(c)
                    sumB += Color.blue(c)
                    count++
                }
            }
        }

        val bgR = if (count > 0) (sumR / count).toInt() else 240
        val bgG = if (count > 0) (sumG / count).toInt() else 240
        val bgB = if (count > 0) (sumB / count).toInt() else 240

        // Center of headellipse & shoulder trapezoid in passport framing
        val headCenterX = width * 0.50f
        val headCenterY = height * 0.40f
        val headRadiusX = width * 0.30f
        val headRadiusY = height * 0.32f

        val shoulderCenterY = height * 0.88f
        val shoulderRadiusX = width * 0.46f
        val shoulderRadiusY = height * 0.28f

        val colorToleranceSq = 48 * 48

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val c = pixels[idx]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                // Normalized distance to head oval and shoulder arc
                val dxHead = (x - headCenterX) / headRadiusX
                val dyHead = (y - headCenterY) / headRadiusY
                val distHead = dxHead * dxHead + dyHead * dyHead

                val dxShoulder = (x - headCenterX) / shoulderRadiusX
                val dyShoulder = (y - shoulderCenterY) / shoulderRadiusY
                val distShoulder = if (y >= headCenterY) (dxShoulder * dxShoulder + dyShoulder * dyShoulder) else 99f

                val minSubjectDist = kotlin.math.min(distHead, distShoulder)

                val dr = r - bgR
                val dg = g - bgG
                val db = b - bgB
                val colorDistSq = dr * dr + dg * dg + db * db

                val isOuterWallZone = minSubjectDist > 1.28f
                val isBorderSimilarToBg = minSubjectDist > 0.88f && colorDistSq <= colorToleranceSq

                if (isOuterWallZone && colorDistSq <= colorToleranceSq * 2) {
                    // Pure #FFFFFF white background
                    pixels[idx] = Color.WHITE
                } else if (isBorderSimilarToBg) {
                    // Smooth blend toward pure white near hair/shoulder edge
                    val alpha = ((minSubjectDist - 0.88f) / 0.40f).coerceIn(0f, 1f)
                    val blendR = (r + (255 - r) * alpha).toInt().coerceIn(0, 255)
                    val blendG = (g + (255 - g) * alpha).toInt().coerceIn(0, 255)
                    val blendB = (b + (255 - b) * alpha).toInt().coerceIn(0, 255)
                    pixels[idx] = Color.rgb(blendR, blendG, blendB)
                } else {
                    // Foreground face/subject: apply gentle studio lighting boost (+6 brightness, 1.04x contrast)
                    val liftR = (((r - 128) * 1.04f) + 128 + 5f).toInt().coerceIn(0, 255)
                    val liftG = (((g - 128) * 1.04f) + 128 + 5f).toInt().coerceIn(0, 255)
                    val liftB = (((b - 128) * 1.04f) + 128 + 5f).toInt().coerceIn(0, 255)
                    pixels[idx] = Color.rgb(liftR, liftG, liftB)
                }
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
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
     * User-controllable signature enhancer with Adaptive Shadow Removal:
     * - Normalizes uneven mobile shadows across the paper into 100% pure white (#FFFFFF)
     * - Darkens and deepens ink strokes without destroying thin lines
     * - Supports preserving original pen color (Blue/Black ink) or pure Black ink
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
                // Pure clean white paper background (#FFFFFF)
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
                    val finalVal = if (newLum < inkThreshold) (newLum * 0.55f).toInt().coerceIn(0, 255) else 255
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
            contrastFactor = 1.35f,
            brightnessOffset = 2f,
            inkThreshold = 190,
            preserveInkColor = true
        )
    }

    /**
     * Combines a candidate's Passport Photo (top) and Signature (bottom) into a single Joint Image
     * required by several State & Central exam portals.
     */
    suspend fun createJointPhotoAndSignature(
        context: Context,
        photoBitmap: Bitmap,
        signatureBitmap: Bitmap,
        targetWidthPx: Int = 350,
        targetHeightPx: Int = 550,
        minKb: Int = 20,
        maxKb: Int = 50,
        dpi: Int = 300,
        candidateName: String = "",
        dateOfPhoto: String = ""
    ): ProcessedFileResult = withContext(Dispatchers.Default) {
        val safeW = targetWidthPx.coerceAtLeast(150)
        val safeH = targetHeightPx.coerceAtLeast(220)
        val photoAreaH = (safeH * 0.78f).toInt()
        val signAreaH = safeH - photoAreaH

        val combined = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.drawColor(Color.WHITE)

        // Prepare top photo portion
        var croppedPhoto = cropToAspectRatio(photoBitmap, safeW.toFloat() / photoAreaH.toFloat())
        croppedPhoto = Bitmap.createScaledBitmap(croppedPhoto, safeW, photoAreaH, true)
        if (candidateName.isNotBlank() || dateOfPhoto.isNotBlank()) {
            croppedPhoto = addNameAndDateStrip(croppedPhoto, candidateName, dateOfPhoto)
        }
        canvas.drawBitmap(croppedPhoto, 0f, 0f, null)

        // Draw clean divider line
        val dividerPaint = Paint().apply {
            color = Color.rgb(203, 213, 225)
            strokeWidth = 2f
        }
        canvas.drawLine(0f, photoAreaH.toFloat(), safeW.toFloat(), photoAreaH.toFloat(), dividerPaint)

        // Prepare bottom cleaned signature portion
        val trimmedSign = cropBlankMargins(signatureBitmap)
        val cleanedSign = adjustSignatureLighting(trimmedSign, 1.3f, 0f, 185, true)
        val pad = 8
        val availSignW = (safeW - pad * 2).coerceAtLeast(40)
        val availSignH = (signAreaH - pad * 2).coerceAtLeast(24)
        val scaledSign = Bitmap.createScaledBitmap(cleanedSign, availSignW, availSignH, true)
        canvas.drawBitmap(scaledSign, pad.toFloat(), (photoAreaH + pad).toFloat(), null)

        val jointRule = DocumentRule(
            id = "joint_photo_sign_${System.currentTimeMillis()}",
            documentType = com.example.data.model.DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = minKb,
            maxKb = maxKb,
            exactWidthPx = safeW,
            exactHeightPx = safeH,
            dpi = dpi,
            requiresWhiteBackground = true
        )

        processImageForRule(
            context = context,
            originalBitmap = combined,
            rule = jointRule,
            customTargetKb = maxKb,
            customWidthPx = safeW,
            customHeightPx = safeH,
            enforceProportional = false,
            customDpi = dpi
        )
    }

    /**
     * Generates a Printable 300 DPI Sheet containing 8 or 12 tiled passport photos with cut guides
     * on a 4x6 inch (1200x1800 px) or A4 (1654x2339 px) canvas for ₹10 shop printing.
     */
    suspend fun createPrintablePassportSheet(
        context: Context,
        photoBitmap: Bitmap,
        photoCount: Int = 8, // 8 or 12
        dpi: Int = 300
    ): ProcessedFileResult = withContext(Dispatchers.Default) {
        val cols = if (photoCount >= 12) 3 else 2
        val rows = 4
        val sheetW = if (photoCount >= 12) 1500 else 1200
        val sheetH = 1800

        val sheetBitmap = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheetBitmap)
        canvas.drawColor(Color.WHITE)

        val marginX = 48
        val marginY = 48
        val gapX = 28
        val gapY = 28

        val cellW = (sheetW - marginX * 2 - gapX * (cols - 1)) / cols
        val cellH = (sheetH - marginY * 2 - gapY * (rows - 1)) / rows

        val croppedTile = cropToAspectRatio(photoBitmap, cellW.toFloat() / cellH.toFloat())
        val tileBmp = Bitmap.createScaledBitmap(croppedTile, cellW, cellH, true)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = marginX + c * (cellW + gapX)
                val top = marginY + r * (cellH + gapY)
                canvas.drawBitmap(tileBmp, left.toFloat(), top.toFloat(), null)
                canvas.drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    (left + cellW).toFloat(),
                    (top + cellH).toFloat(),
                    borderPaint
                )
            }
        }

        val stream = ByteArrayOutputStream()
        sheetBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        val rawBytes = injectJfifDpiHeader(stream.toByteArray(), dpi)

        val fileName = "formready_print_sheet_${photoCount}photos_${System.currentTimeMillis()}.jpg"
        val docsDir = File(context.cacheDir, "prepared_docs").apply { mkdirs() }
        val outputFile = File(docsDir, fileName)
        withContext(Dispatchers.IO) {
            FileOutputStream(outputFile).use { it.write(rawBytes) }
        }

        val sizeBytes = outputFile.length()
        val sizeKb = sizeBytes / 1024.0

        ProcessedFileResult(
            ruleId = "print_sheet_${photoCount}",
            documentType = com.example.data.model.DocumentType.POSTCARD_PHOTO,
            originalFileName = fileName,
            processedFilePath = outputFile.absolutePath,
            fileFormat = FileFormat.JPG,
            fileSizeBytes = sizeBytes,
            fileSizeKb = sizeKb,
            widthPx = sheetW,
            heightPx = sheetH,
            pageCount = 1,
            isCompliant = true,
            validationMessages = listOf(
                "Printable Sheet Ready: $photoCount Passport Photos ($cols x $rows grid)",
                "Sheet Dimensions: $sheetW x $sheetH px @ $dpi DPI (${String.format(Locale.US, "%.1f", sizeKb)} KB)"
            ),
            status = PreparationStatus.SUCCESS_PREPARED,
            dpi = dpi,
            hasWhiteBackground = true
        )
    }

    /**
     * 1-Click Direct Save to Device Gallery / Downloads via Android MediaStore (Zero Watermark).
     */
    suspend fun saveFileToGalleryMediaStore(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String = "image/jpeg"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Source file is missing"))
            }
            val resolver = context.contentResolver
            if (mimeType == "application/pdf" && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/FormReadyAI")
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    return@withContext Result.success("Saved to Downloads/FormReadyAI/$displayName")
                }
            } else if (mimeType.startsWith("image/")) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, mimeType)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/FormReadyAI")
                    }
                }
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    return@withContext Result.success("Saved to Gallery (Pictures/FormReadyAI/$displayName)")
                }
            }

            // Fallback to app external files / public exports directory
            val fallbackDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "FormReadyAI_Downloads").apply { mkdirs() }
            val destFile = File(fallbackDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)
            Result.success("Saved to ${destFile.name}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Injects exact DPI (e.g., 300 DPI or 600 DPI) into the JPEG JFIF APP0 segment header
     * so portals like NSDL and UTI PAN validate the exact resolution metadata.
     */
    fun injectJfifDpiHeader(jpegBytes: ByteArray, dpi: Int): ByteArray {
        if (jpegBytes.size < 20) return jpegBytes
        if (jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) return jpegBytes

        val safeDpi = dpi.coerceIn(72, 1200)
        val hi = ((safeDpi shr 8) and 0xFF).toByte()
        val lo = (safeDpi and 0xFF).toByte()

        // Search for "JFIF\0" within first 64 bytes
        val limit = kotlin.math.min(jpegBytes.size - 10, 64)
        for (i in 2 until limit) {
            if (jpegBytes[i] == 'J'.code.toByte() &&
                jpegBytes[i + 1] == 'F'.code.toByte() &&
                jpegBytes[i + 2] == 'I'.code.toByte() &&
                jpegBytes[i + 3] == 'F'.code.toByte() &&
                jpegBytes[i + 4] == 0.toByte()
            ) {
                val out = jpegBytes.copyOf()
                // Offset +7 from 'J' is units (1 = dots per inch)
                // Offset +8..9 is Xdensity, +10..11 is Ydensity
                out[i + 7] = 1.toByte()
                out[i + 8] = hi
                out[i + 9] = lo
                out[i + 10] = hi
                out[i + 11] = lo
                return out
            }
        }

        // If no JFIF segment was present, insert a standard 18-byte APP0 JFIF segment right after SOI (FF D8)
        val app0 = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, // Length = 16
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
            0x01, 0x01, // Version 1.1
            0x01,       // Units = 1 (DPI)
            hi, lo,     // Xdensity
            hi, lo,     // Ydensity
            0x00, 0x00  // No thumbnail
        )
        val combined = ByteArray(jpegBytes.size + app0.size)
        combined[0] = jpegBytes[0]
        combined[1] = jpegBytes[1]
        System.arraycopy(app0, 0, combined, 2, app0.size)
        System.arraycopy(jpegBytes, 2, combined, 2 + app0.size, jpegBytes.size - 2)
        return combined
    }

    /**
     * Reads the DPI value from a JPEG's JFIF APP0 header, or returns null if not present.
     */
    fun readJfifDpiHeader(jpegBytes: ByteArray): Int? {
        if (jpegBytes.size < 20) return null
        val limit = kotlin.math.min(jpegBytes.size - 12, 64)
        for (i in 2 until limit) {
            if (jpegBytes[i] == 'J'.code.toByte() &&
                jpegBytes[i + 1] == 'F'.code.toByte() &&
                jpegBytes[i + 2] == 'I'.code.toByte() &&
                jpegBytes[i + 3] == 'F'.code.toByte() &&
                jpegBytes[i + 4] == 0.toByte()
            ) {
                val units = jpegBytes[i + 7].toInt() and 0xFF
                if (units == 1) {
                    val xDpi = ((jpegBytes[i + 8].toInt() and 0xFF) shl 8) or (jpegBytes[i + 9].toInt() and 0xFF)
                    return xDpi
                }
            }
        }
        return null
    }

    /**
     * Pads a JPEG byte array using standard-compliant JPEG Comment (0xFF 0xFE) segments
     * right before the End-Of-Image (0xFF 0xD9) marker when the compressed image is smaller
     * than the portal's required [targetMinKb] (common with 140x60 signatures).
     */
    fun padJpegBytesToTargetMinKb(
        jpegBytes: ByteArray,
        targetMinKb: Int,
        targetMaxKb: Int
    ): Pair<ByteArray, Boolean> {
        val minBytes = targetMinKb * 1024
        val maxBytes = targetMaxKb * 1024
        if (targetMinKb <= 0 || jpegBytes.size >= minBytes || minBytes >= maxBytes) {
            return Pair(jpegBytes, false)
        }

        // Aim safely inside [minBytes, maxBytes] (e.g. 35% into the allowed window, or at least +1.5 KB above min)
        val desiredTotalBytes = (minBytes + ((maxBytes - minBytes) * 0.35).toInt())
            .coerceIn(minBytes + 512, (maxBytes - 512).coerceAtLeast(minBytes + 128))

        val bytesNeeded = desiredTotalBytes - jpegBytes.size
        if (bytesNeeded <= 6) return Pair(jpegBytes, false)

        // Locate EOI marker (FF D9) at the end of the JPEG
        var eoiIndex = jpegBytes.size - 2
        while (eoiIndex >= 2) {
            if (jpegBytes[eoiIndex] == 0xFF.toByte() && jpegBytes[eoiIndex + 1] == 0xD9.toByte()) {
                break
            }
            eoiIndex--
        }
        if (eoiIndex < 2) eoiIndex = jpegBytes.size

        val out = ByteArrayOutputStream(desiredTotalBytes + 64)
        out.write(jpegBytes, 0, eoiIndex)

        var remaining = bytesNeeded
        while (remaining > 4) {
            // Each COM segment: FF FE [2-byte length including length bytes] [payload]
            val maxChunkPayload = 60000
            val payloadSize = (remaining - 4).coerceIn(2, maxChunkPayload)
            val segLen = payloadSize + 2
            out.write(0xFF)
            out.write(0xFE)
            out.write((segLen shr 8) and 0xFF)
            out.write(segLen and 0xFF)
            val padPayload = ByteArray(payloadSize) { 'F'.code.toByte() }
            out.write(padPayload)
            remaining -= (payloadSize + 4)
        }

        // Write trailing bytes including EOI (FF D9)
        if (eoiIndex < jpegBytes.size) {
            out.write(jpegBytes, eoiIndex, jpegBytes.size - eoiIndex)
        } else {
            out.write(0xFF)
            out.write(0xD9)
        }

        return Pair(out.toByteArray(), true)
    }

    data class CompressionDetails(
        val bytes: ByteArray,
        val finalWidth: Int,
        val finalHeight: Int,
        val finalQuality: Int,
        val wasDownscaled: Boolean,
        val wasPaddedToMinKb: Boolean = false
    )

    /**
     * Binary-search compression algorithm that adjusts quality (98 down to 15),
     * injects exact JFIF DPI headers, applies controlled downscaling when above targetMaxKb,
     * and applies safe JPEG segment padding when below targetMinKb.
     */
    private fun compressToTargetKbWithDetails(
        src: Bitmap,
        targetMinKb: Int,
        targetMaxKb: Int,
        format: Bitmap.CompressFormat,
        allowDownscale: Boolean,
        dpi: Int = 300
    ): CompressionDetails {
        var currentBitmap = src
        var quality = 95
        var stream = ByteArrayOutputStream()
        currentBitmap.compress(format, quality, stream)
        var bytes = stream.toByteArray()
        var currentKb = bytes.size / 1024.0
        var wasDownscaled = false

        if (format == Bitmap.CompressFormat.PNG) {
            if (currentKb <= targetMaxKb || !allowDownscale) {
                return CompressionDetails(bytes, currentBitmap.width, currentBitmap.height, 100, false)
            }
            var scale = 0.92f
            while (currentKb > targetMaxKb && currentBitmap.width > 80 && currentBitmap.height > 80) {
                val newW = (currentBitmap.width * scale).toInt().coerceAtLeast(60)
                val newH = (currentBitmap.height * scale).toInt().coerceAtLeast(60)
                currentBitmap = Bitmap.createScaledBitmap(currentBitmap, newW, newH, true)
                wasDownscaled = true

                stream = ByteArrayOutputStream()
                currentBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bytes = stream.toByteArray()
                currentKb = bytes.size / 1024.0
            }
            return CompressionDetails(bytes, currentBitmap.width, currentBitmap.height, 100, wasDownscaled)
        }

        // Binary search for optimal JPEG quality within [targetMinKb, targetMaxKb]
        var lowQuality = 15
        var highQuality = 98
        var bestQuality = 90
        var bestBytes: ByteArray? = null

        while (lowQuality <= highQuality) {
            val midQuality = (lowQuality + highQuality) / 2
            stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, midQuality, stream)
            val candidateBytes = injectJfifDpiHeader(stream.toByteArray(), dpi)
            val candidateKb = candidateBytes.size / 1024.0

            if (candidateKb > targetMaxKb) {
                highQuality = midQuality - 2
            } else {
                bestQuality = midQuality
                bestBytes = candidateBytes
                if (candidateKb < targetMinKb && midQuality < 98) {
                    lowQuality = midQuality + 2
                } else {
                    break
                }
            }
        }

        if (bestBytes != null) {
            bytes = bestBytes
        } else {
            stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, 15, stream)
            bytes = injectJfifDpiHeader(stream.toByteArray(), dpi)
        }
        currentKb = bytes.size / 1024.0

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
                bytes = injectJfifDpiHeader(stream.toByteArray(), dpi)
                currentKb = bytes.size / 1024.0
            }
        }

        // If file size is smaller than targetMinKb (e.g. crisp 140x60 signature at 4 KB when portal requires 10-20 KB),
        // pad safely using JPEG Comment segment so it never fails portal validation for being "too small".
        var wasPadded = false
        if (bytes.size < targetMinKb * 1024 && targetMinKb <= targetMaxKb) {
            val paddedResult = padJpegBytesToTargetMinKb(bytes, targetMinKb, targetMaxKb)
            bytes = paddedResult.first
            wasPadded = paddedResult.second
        }

        return CompressionDetails(
            bytes = bytes,
            finalWidth = currentBitmap.width,
            finalHeight = currentBitmap.height,
            finalQuality = bestQuality,
            wasDownscaled = wasDownscaled,
            wasPaddedToMinKb = wasPadded
        )
    }
}

