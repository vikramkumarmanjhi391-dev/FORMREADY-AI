package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.FileFormat
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
import java.util.UUID

object ImageProcessor {

    suspend fun decodeBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        try {
            var input: InputStream? = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            input?.close()

            var inSampleSize = 1
            while (options.outWidth / inSampleSize > maxDimension || options.outHeight / inSampleSize > maxDimension) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            input = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(input, null, decodeOptions)
            input?.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Resizes, applies portal formatting (Name & Date stamp, White BG, Grayscale),
     * and iteratively compresses to strictly fit the target KB bounds.
     */
    suspend fun processImageForRule(
        context: Context,
        originalBitmap: Bitmap,
        rule: DocumentRule,
        candidateName: String = "",
        dateOfPhoto: String = "",
        customTargetKb: Int? = null,
        autoCropMargins: Boolean = false
    ): ProcessedFileResult = withContext(Dispatchers.Default) {
        var workingBitmap = originalBitmap

        // 1. Signature / Thumb border crop if requested
        if (autoCropMargins) {
            workingBitmap = cropBlankMargins(workingBitmap)
        }

        // 2. Aspect Ratio / Dimensions
        val targetWidth: Int
        val targetHeight: Int

        if (rule.exactWidthPx != null && rule.exactHeightPx != null) {
            targetWidth = rule.exactWidthPx
            targetHeight = rule.exactHeightPx
        } else if (rule.targetAspectRatio != null && rule.targetAspectRatio > 0) {
            val baseWidth = (rule.maxWidthPx ?: 600).coerceIn(200, 1200)
            targetWidth = baseWidth
            targetHeight = (baseWidth / rule.targetAspectRatio).toInt().coerceIn(150, 1600)
        } else {
            val maxBound = rule.maxWidthPx ?: 800
            if (workingBitmap.width > maxBound || workingBitmap.height > maxBound) {
                val ratio = workingBitmap.width.toFloat() / workingBitmap.height.toFloat()
                if (ratio >= 1.0f) {
                    targetWidth = maxBound
                    targetHeight = (maxBound / ratio).toInt()
                } else {
                    targetHeight = maxBound
                    targetWidth = (maxBound * ratio).toInt()
                }
            } else {
                targetWidth = workingBitmap.width
                targetHeight = workingBitmap.height
            }
        }

        // Resize bitmap to target dimensions
        var scaled = Bitmap.createScaledBitmap(workingBitmap, targetWidth, targetHeight, true)

        // 3. Optional Name & Date of Photo Strip (Mandatory for UPSC, NTA, SSC special rules)
        if (rule.requiresNameAndDateStrip && (candidateName.isNotBlank() || dateOfPhoto.isNotBlank())) {
            scaled = addNameAndDateStrip(scaled, candidateName, dateOfPhoto)
        }

        // 4. Color Mode adjustments
        when (rule.colorMode) {
            ColorMode.GRAYSCALE -> {
                scaled = applyGrayscale(scaled)
            }
            ColorMode.BLACK_AND_WHITE -> {
                scaled = applyHighContrastMonochrome(scaled)
            }
            else -> {}
        }

        // 5. Target KB compression
        val targetMaxKb = customTargetKb ?: rule.maxKb
        val targetMinKb = rule.minKb.coerceAtMost(targetMaxKb)

        val outputFormat = if (rule.allowedFormats.contains(FileFormat.PNG)) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }

        val compressedBytes = compressToTargetKb(scaled, targetMinKb, targetMaxKb, outputFormat)

        // 6. Save to cache directory
        val fileName = "doc_${rule.documentType.name.lowercase()}_${System.currentTimeMillis()}.${if (outputFormat == Bitmap.CompressFormat.PNG) "png" else "jpg"}"
        val docsDir = File(context.cacheDir, "prepared_docs").apply { mkdirs() }
        val outputFile = File(docsDir, fileName)

        withContext(Dispatchers.IO) {
            FileOutputStream(outputFile).use { it.write(compressedBytes) }
        }

        val actualSizeKb = outputFile.length() / 1024.0
        val isCompliant = actualSizeKb in (rule.minKb * 0.95)..(rule.maxKb * 1.05)

        val validationMessages = mutableListOf<String>()
        if (actualSizeKb > rule.maxKb) {
            validationMessages.add("File size (${String.format(Locale.US, "%.1f", actualSizeKb)} KB) exceeds portal limit of ${rule.maxKb} KB.")
        } else if (actualSizeKb < rule.minKb) {
            validationMessages.add("File size (${String.format(Locale.US, "%.1f", actualSizeKb)} KB) is below portal minimum requirement of ${rule.minKb} KB.")
        } else {
            validationMessages.add("File size is strictly compliant: ${String.format(Locale.US, "%.1f", actualSizeKb)} KB (Allowed: ${rule.minKb}-${rule.maxKb} KB)")
        }

        if (rule.exactWidthPx != null && rule.exactHeightPx != null) {
            validationMessages.add("Resolution matched: ${scaled.width} x ${scaled.height} px (Required: ${rule.exactWidthPx} x ${rule.exactHeightPx} px)")
        } else {
            validationMessages.add("Dimensions: ${scaled.width} x ${scaled.height} px")
        }

        ProcessedFileResult(
            ruleId = rule.id,
            documentType = rule.documentType,
            originalFileName = fileName,
            processedFilePath = outputFile.absolutePath,
            fileFormat = if (outputFormat == Bitmap.CompressFormat.PNG) FileFormat.PNG else FileFormat.JPG,
            fileSizeBytes = outputFile.length(),
            fileSizeKb = actualSizeKb,
            widthPx = scaled.width,
            heightPx = scaled.height,
            isCompliant = isCompliant,
            validationMessages = validationMessages
        )
    }

    /**
     * Cleans up scanned signatures by finding the inked area and removing empty whitespace margins.
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
                // If pixel is darker than off-white/light gray (brightness < 225)
                val brightness = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                if (brightness < 220) {
                    foundContent = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (!foundContent || minX >= maxX || minY >= maxY) return src

        val padX = ((maxX - minX) * paddingPercent).toInt().coerceAtLeast(8)
        val padY = ((maxY - minY) * paddingPercent).toInt().coerceAtLeast(8)

        val cropLeft = (minX - padX).coerceAtLeast(0)
        val cropTop = (minY - padY).coerceAtLeast(0)
        val cropRight = (maxX + padX).coerceAtMost(width)
        val cropBottom = (maxY + padY).coerceAtMost(height)

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop

        return if (cropW > 10 && cropH > 10) {
            Bitmap.createBitmap(src, cropLeft, cropTop, cropW, cropH)
        } else src
    }

    /**
     * Adds official Candidate Name & Date of Photo capture strip at the bottom of the photo.
     */
    fun addNameAndDateStrip(src: Bitmap, name: String, date: String): Bitmap {
        val width = src.width
        val height = src.height
        val stripHeight = (height * 0.18f).toInt().coerceAtLeast(36)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw original photo
        canvas.drawBitmap(src, 0f, 0f, null)

        // Draw solid white strip at bottom
        val stripPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, (height - stripHeight).toFloat(), width.toFloat(), height.toFloat(), stripPaint)

        // Text paint
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
            canvas.drawText(name.uppercase(), (width / 2).toFloat(), nameY, textPaint)

            val dateText = "DOP: $formattedDate"
            val dateY = nameY + textSize + (stripHeight * 0.08f)
            textPaint.textSize = (textSize * 0.85f).coerceIn(10f, 28f)
            canvas.drawText(dateText, (width / 2).toFloat(), dateY, textPaint)
        } else {
            val text = if (name.isNotBlank()) name.uppercase() else "DOP: $formattedDate"
            val textSize = (stripHeight * 0.45f).coerceIn(12f, 34f)
            textPaint.textSize = textSize
            val textY = height - (stripHeight / 2) + (textSize / 3)
            canvas.drawText(text, (width / 2).toFloat(), textY, textPaint)
        }

        return result
    }

    /**
     * Converts to Grayscale preserving luminance.
     */
    fun applyGrayscale(src: Bitmap): Bitmap {
        val bmpGrayscale = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmpGrayscale)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmpGrayscale
    }

    /**
     * High-contrast monochrome ideal for signatures and thumb impressions.
     */
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

    /**
     * Magic scan filter: increases contrast, whites out light gray paper, sharpens text.
     */
    fun applyMagicDocFilter(src: Bitmap): Bitmap {
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

            // Dynamic curve: stretch highlights to pure white, deepen shadows
            val newLum = when {
                lum > 200 -> 255
                lum < 60 -> (lum * 0.6).toInt()
                else -> ((lum - 60) * 1.8).toInt().coerceIn(0, 255)
            }
            // Preserve subtle color tone or monochrome
            val factor = if (lum > 0) newLum.toFloat() / lum.toFloat() else 1f
            val newR = (r * factor).toInt().coerceIn(0, 255)
            val newG = (g * factor).toInt().coerceIn(0, 255)
            val newB = (b * factor).toInt().coerceIn(0, 255)

            pixels[i] = Color.rgb(newR, newG, newB)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Iteratively searches for the optimal JPEG quality (and downscales slightly if necessary)
     * so that output size is strictly <= targetMaxKb and as close as possible to target.
     */
    private fun compressToTargetKb(
        src: Bitmap,
        targetMinKb: Int,
        targetMaxKb: Int,
        format: Bitmap.CompressFormat
    ): ByteArray {
        var currentBitmap = src
        var quality = 95
        var stream = ByteArrayOutputStream()
        currentBitmap.compress(format, quality, stream)
        var bytes = stream.toByteArray()
        var currentKb = bytes.size / 1024

        // If PNG is requested and already fits
        if (format == Bitmap.CompressFormat.PNG) {
            if (currentKb <= targetMaxKb) return bytes
            // If PNG is too big, fall back to high-quality JPEG
            stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            bytes = stream.toByteArray()
            currentKb = bytes.size / 1024
        }

        // Binary-like stepped reduction
        var lowQuality = 15
        var highQuality = 98

        while (lowQuality <= highQuality) {
            val midQuality = (lowQuality + highQuality) / 2
            stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, midQuality, stream)
            bytes = stream.toByteArray()
            currentKb = bytes.size / 1024

            if (currentKb > targetMaxKb) {
                highQuality = midQuality - 3
            } else if (currentKb < targetMinKb && midQuality < 95) {
                lowQuality = midQuality + 3
            } else {
                // Inside target window or optimal
                break
            }
        }

        // If still too large even at quality 15, scale down dimensions iteratively
        var scale = 0.9f
        while (currentKb > targetMaxKb && currentBitmap.width > 120 && currentBitmap.height > 120) {
            val newW = (currentBitmap.width * scale).toInt()
            val newH = (currentBitmap.height * scale).toInt()
            currentBitmap = Bitmap.createScaledBitmap(currentBitmap, newW, newH, true)

            stream = ByteArrayOutputStream()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            bytes = stream.toByteArray()
            currentKb = bytes.size / 1024
        }

        return bytes
    }
}
