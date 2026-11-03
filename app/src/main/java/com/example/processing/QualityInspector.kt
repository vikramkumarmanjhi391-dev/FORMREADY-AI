package com.example.processing

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.DocumentValidationSummary
import com.example.data.model.IssueSeverity
import com.example.data.model.ValidationIssue
import java.io.File
import java.util.Locale

object QualityInspector {

    data class QualityReport(
        val sharpnessScore: Int, // 0 - 100
        val isSharp: Boolean,
        val brightnessScore: Int, // 0 - 255
        val exposureStatus: String, // "Optimal", "Too Dark", "Glare / Overexposed"
        val contrastScore: Int, // 0 - 100
        val issues: List<String>,
        val suggestedDocType: DocumentType
    )

    /**
     * Inspects a bitmap for sharpness, lighting, contrast, and document characteristics.
     */
    fun inspectImageQuality(bitmap: Bitmap): QualityReport {
        val width = bitmap.width
        val height = bitmap.height

        // Downsample for fast inspection
        val sampleW = 200
        val sampleH = (200f * (height.toFloat() / width.toFloat())).toInt().coerceIn(100, 300)
        val sampled = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)

        val pixels = IntArray(sampleW * sampleH)
        sampled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

        var totalLum = 0L
        var minLum = 255
        var maxLum = 0

        // Laplacian variance proxy for blur detection
        var laplacianSum = 0.0
        var laplacianSqSum = 0.0
        var count = 0

        for (y in 1 until sampleH - 1) {
            for (x in 1 until sampleW - 1) {
                val c = pixels[y * sampleW + x]
                val lum = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114).toInt()

                totalLum += lum
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum

                // Simple 4-neighbor Laplacian filter: [0, 1, 0; 1, -4, 1; 0, 1, 0]
                val cUp = pixels[(y - 1) * sampleW + x]
                val cDown = pixels[(y + 1) * sampleW + x]
                val cLeft = pixels[y * sampleW + (x - 1)]
                val cRight = pixels[y * sampleW + (x + 1)]

                val lumUp = (Color.red(cUp) * 0.299 + Color.green(cUp) * 0.587 + Color.blue(cUp) * 0.114).toInt()
                val lumDown = (Color.red(cDown) * 0.299 + Color.green(cDown) * 0.587 + Color.blue(cDown) * 0.114).toInt()
                val lumLeft = (Color.red(cLeft) * 0.299 + Color.green(cLeft) * 0.587 + Color.blue(cLeft) * 0.114).toInt()
                val lumRight = (Color.red(cRight) * 0.299 + Color.green(cRight) * 0.587 + Color.blue(cRight) * 0.114).toInt()

                val laplacian = (lumUp + lumDown + lumLeft + lumRight - 4 * lum).toDouble()
                laplacianSum += laplacian
                laplacianSqSum += laplacian * laplacian
                count++
            }
        }

        val totalPixels = (sampleW * sampleH).coerceAtLeast(1)
        val avgBrightness = (totalLum / totalPixels).toInt()
        val variance = if (count > 0) (laplacianSqSum / count) - ((laplacianSum / count) * (laplacianSum / count)) else 0.0

        val sharpnessScore = (variance / 30.0 * 100).toInt().coerceIn(0, 100)
        val isSharp = sharpnessScore >= 35

        val exposureStatus = when {
            avgBrightness < 60 -> "Too Dark (Low Lighting)"
            avgBrightness > 220 -> "Overexposed (Glare / High Brightness)"
            else -> "Optimal Exposure"
        }

        val contrastRange = maxLum - minLum
        val contrastScore = (contrastRange / 255.0 * 100).toInt().coerceIn(0, 100)

        val issues = mutableListOf<String>()
        if (!isSharp) {
            issues.add("Warning: Image appears blurry. Hold camera steady or ensure proper document focus.")
        }
        if (avgBrightness < 60) {
            issues.add("Poor Lighting: Document or photo is too dark. Capture in better illuminated surroundings.")
        } else if (avgBrightness > 220) {
            issues.add("Glare / Overexposure: Bright reflections detected. Avoid flash on glossy certificate paper.")
        }
        if (contrastScore < 40) {
            issues.add("Low Contrast: Text or signature details might be faint for official portals.")
        }

        // Suggested Doc Type based on aspect ratio and lightness
        val ratio = width.toFloat() / height.toFloat()
        val suggestedDocType = when {
            ratio in 0.65f..0.9f && height >= width -> DocumentType.PASSPORT_PHOTO
            ratio in 1.8f..4.5f -> DocumentType.SIGNATURE_ENGLISH
            ratio in 0.9f..1.1f && width < 400 -> DocumentType.THUMB_IMPRESSION_LEFT
            ratio in 1.4f..1.75f -> DocumentType.AADHAAR_CARD
            else -> DocumentType.CLASS_10_MARKSHEET
        }

        return QualityReport(
            sharpnessScore = sharpnessScore,
            isSharp = isSharp,
            brightnessScore = avgBrightness,
            exposureStatus = exposureStatus,
            contrastScore = contrastScore,
            issues = issues,
            suggestedDocType = suggestedDocType
        )
    }

    /**
     * Comprehensive validator for a processed file against a specific DocumentRule.
     */
    fun validateFileAgainstRule(file: File, rule: DocumentRule, widthPx: Int, heightPx: Int): DocumentValidationSummary {
        val issues = mutableListOf<ValidationIssue>()
        var score = 100

        val sizeKb = file.length() / 1024.0

        // File size checks
        if (sizeKb > rule.maxKb) {
            issues.add(
                ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    message = "File size (${String.format(Locale.US, "%.1f", sizeKb)} KB) exceeds the maximum allowed ${rule.maxKb} KB."
                )
            )
            score -= 40
        } else if (sizeKb < rule.minKb) {
            issues.add(
                ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    message = "File size (${String.format(Locale.US, "%.1f", sizeKb)} KB) is below the minimum portal requirement ${rule.minKb} KB."
                )
            )
            score -= 30
        }

        // Exact pixel dimension checks
        if (rule.exactWidthPx != null && widthPx != rule.exactWidthPx) {
            issues.add(
                ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    message = "Width is $widthPx px (Rule recommends exact ${rule.exactWidthPx} px)."
                )
            )
            score -= 15
        }
        if (rule.exactHeightPx != null && heightPx != rule.exactHeightPx) {
            issues.add(
                ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    message = "Height is $heightPx px (Rule recommends exact ${rule.exactHeightPx} px)."
                )
            )
            score -= 15
        }

        // Bounded dimension checks
        if (rule.maxWidthPx != null && widthPx > rule.maxWidthPx) {
            issues.add(
                ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    message = "Width ($widthPx px) exceeds maximum allowed ${rule.maxWidthPx} px."
                )
            )
            score -= 20
        }
        if (rule.maxHeightPx != null && heightPx > rule.maxHeightPx) {
            issues.add(
                ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    message = "Height ($heightPx px) exceeds maximum allowed ${rule.maxHeightPx} px."
                )
            )
            score -= 20
        }

        val isValid = issues.none { it.severity == IssueSeverity.ERROR }
        return DocumentValidationSummary(
            isValid = isValid,
            issues = issues,
            complianceScore = score.coerceIn(0, 100)
        )
    }
}
