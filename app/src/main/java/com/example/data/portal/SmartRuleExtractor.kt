package com.example.data.portal

import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.PortalSpec
import com.example.data.model.VerificationStatus
import java.util.UUID

object SmartRuleExtractor {

    /**
     * Parses freeform notification text, official advertisement snippets,
     * or OCR'd instruction text to extract structured document rules.
     */
    fun extractRulesFromNotificationText(
        portalName: String,
        examName: String,
        rawText: String,
        sourceUrl: String = ""
    ): PortalSpec {
        val rules = mutableListOf<DocumentRule>()
        val lower = rawText.lowercase()

        // 1. Photo rules detection
        if (lower.contains("photo") || lower.contains("photograph") || lower.contains("picture")) {
            val photoSnippet = extractRelevantSection(rawText, listOf("photo", "photograph", "image"))
            val (minKb, maxKb) = extractKbRange(photoSnippet, defaultMin = 20, defaultMax = 50)
            val (w, h) = extractDimensions(photoSnippet)
            val requiresWhite = photoSnippet.contains("white", ignoreCase = true) || photoSnippet.contains("light background", ignoreCase = true)
            val requiresDate = photoSnippet.contains("date", ignoreCase = true) && photoSnippet.contains("name", ignoreCase = true)

            rules.add(
                DocumentRule(
                    id = "extracted_photo_${UUID.randomUUID().toString().take(6)}",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = minKb,
                    maxKb = maxKb,
                    exactWidthPx = w,
                    exactHeightPx = h,
                    targetAspectRatio = if (w != null && h != null && h > 0) w.toFloat() / h.toFloat() else (3.5f / 4.5f),
                    requiresWhiteBackground = requiresWhite,
                    requiresNameAndDateStrip = requiresDate,
                    instructions = "Extracted: Photo size $minKb-$maxKb KB${if (w != null && h != null) ", $w x $h px" else ""}${if (requiresDate) ", Name & Date mandatory" else ""}"
                )
            )
        }

        // 2. Signature rules detection
        if (lower.contains("signature") || lower.contains("sign") || lower.contains("hastakshar")) {
            val signSnippet = extractRelevantSection(rawText, listOf("signature", "sign", "ink", "running hand"))
            val (minKb, maxKb) = extractKbRange(signSnippet, defaultMin = 10, defaultMax = 20)
            val (w, h) = extractDimensions(signSnippet)
            val isHindi = signSnippet.contains("hindi", ignoreCase = true)

            rules.add(
                DocumentRule(
                    id = "extracted_sign_${UUID.randomUUID().toString().take(6)}",
                    documentType = if (isHindi) DocumentType.SIGNATURE_HINDI else DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = minKb,
                    maxKb = maxKb,
                    exactWidthPx = w,
                    exactHeightPx = h,
                    requiresWhiteBackground = true,
                    instructions = "Extracted: Signature size $minKb-$maxKb KB${if (w != null && h != null) ", $w x $h px" else ""}"
                )
            )
        }

        // 3. Thumb impression detection
        if (lower.contains("thumb") || lower.contains("anguthe") || lower.contains("lti")) {
            val thumbSnippet = extractRelevantSection(rawText, listOf("thumb", "impression", "lti"))
            val (minKb, maxKb) = extractKbRange(thumbSnippet, defaultMin = 10, defaultMax = 50)
            rules.add(
                DocumentRule(
                    id = "extracted_thumb_${UUID.randomUUID().toString().take(6)}",
                    documentType = DocumentType.THUMB_IMPRESSION_LEFT,
                    isRequired = false,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = minKb,
                    maxKb = maxKb,
                    exactWidthPx = 240,
                    exactHeightPx = 240,
                    instructions = "Extracted: Thumb Impression $minKb-$maxKb KB"
                )
            )
        }

        // 4. Certificate / Marksheet / PDF rules detection
        if (lower.contains("certificate") || lower.contains("marksheet") || lower.contains("pdf") || lower.contains("document")) {
            val docSnippet = extractRelevantSection(rawText, listOf("certificate", "marksheet", "pdf", "category", "caste"))
            val (minKb, maxKb) = extractKbRange(docSnippet, defaultMin = 50, defaultMax = 300)
            rules.add(
                DocumentRule(
                    id = "extracted_cert_${UUID.randomUUID().toString().take(6)}",
                    documentType = DocumentType.CLASS_10_CERTIFICATE,
                    isRequired = false,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = minKb,
                    maxKb = maxKb,
                    maxPages = 2,
                    instructions = "Extracted: Educational/Category Certificate in PDF format ($minKb-$maxKb KB)"
                )
            )
        }

        // Fallback default rules if text was sparse
        if (rules.isEmpty()) {
            rules.add(
                DocumentRule(
                    id = "default_photo_${UUID.randomUUID().toString().take(6)}",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    minKb = 20,
                    maxKb = 50,
                    exactWidthPx = 200,
                    exactHeightPx = 230,
                    instructions = "Standard passport photo (20-50 KB, 200x230 px)"
                )
            )
            rules.add(
                DocumentRule(
                    id = "default_sign_${UUID.randomUUID().toString().take(6)}",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    minKb = 10,
                    maxKb = 20,
                    exactWidthPx = 140,
                    exactHeightPx = 60,
                    instructions = "Standard signature (10-20 KB, 140x60 px)"
                )
            )
        }

        return PortalSpec(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            portalName = portalName.ifBlank { "User Defined Portal" },
            examOrServiceName = examName.ifBlank { "Online Application" },
            department = "Extracted from Notification",
            stateOrCentral = "Custom",
            year = "2025",
            category = "Recruitment",
            officialUrl = sourceUrl.ifBlank { "https://example.gov.in" },
            sourceDocumentTitle = "Extracted Notification Requirements",
            verificationStatus = VerificationStatus.USER_SUPPLIED,
            lastVerifiedDate = "Today",
            version = "v1.0 (Auto-extracted)",
            documents = rules
        )
    }

    private fun extractRelevantSection(text: String, keywords: List<String>): String {
        val lines = text.split("\n", ".")
        val matches = lines.filter { line ->
            keywords.any { k -> line.contains(k, ignoreCase = true) }
        }
        return if (matches.isNotEmpty()) matches.joinToString(" ") else text
    }

    private fun extractKbRange(text: String, defaultMin: Int, defaultMax: Int): Pair<Int, Int> {
        // Look for patterns like "20 kb to 50 kb", "between 20-50 kb", "max 50kb", "not more than 100 kb"
        val kbRegex = Regex("""(\d+)\s*(?:kb|kilobytes)?\s*(?:to|-|and)\s*(\d+)\s*(?:kb|kilobytes)""", RegexOption.IGNORE_CASE)
        val match = kbRegex.find(text)
        if (match != null) {
            val min = match.groupValues[1].toIntOrNull() ?: defaultMin
            val max = match.groupValues[2].toIntOrNull() ?: defaultMax
            return Pair(min, max)
        }

        val maxKbRegex = Regex("""(?:max|maximum|under|less than|up to|upto|not exceed)\s*(\d+)\s*kb""", RegexOption.IGNORE_CASE)
        val maxMatch = maxKbRegex.find(text)
        if (maxMatch != null) {
            val max = maxMatch.groupValues[1].toIntOrNull() ?: defaultMax
            return Pair((max * 0.2).toInt().coerceAtLeast(5), max)
        }

        val singleKb = Regex("""(\d+)\s*kb""", RegexOption.IGNORE_CASE).find(text)
        if (singleKb != null) {
            val size = singleKb.groupValues[1].toIntOrNull() ?: defaultMax
            return Pair(10, size)
        }

        return Pair(defaultMin, defaultMax)
    }

    private fun extractDimensions(text: String): Pair<Int?, Int?> {
        // Look for e.g. "200 x 230", "200*230", "140 x 60 px"
        val dimRegex = Regex("""(\d{2,4})\s*(?:x|\*|by)\s*(\d{2,4})\s*(?:px|pixel|pixels)?""", RegexOption.IGNORE_CASE)
        val match = dimRegex.find(text)
        if (match != null) {
            val w = match.groupValues[1].toIntOrNull()
            val h = match.groupValues[2].toIntOrNull()
            return Pair(w, h)
        }
        return Pair(null, null)
    }
}
