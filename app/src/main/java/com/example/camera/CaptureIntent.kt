package com.example.camera

import android.graphics.RectF
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType

/**
 * Strongly typed capture context passed into [UniversalCameraScreen] so the camera
 * understands the exact document task, target aspect ratio, lens preference, and
 * downstream processing pipeline.
 */
sealed class CaptureIntent {

    abstract val initialMode: CameraCaptureMode
    abstract val showTaskSelector: Boolean
    abstract val targetAspectRatio: Float?
    abstract val requiresWhiteBackground: Boolean
    abstract val contextLabel: String

    /**
     * Launched from a specific portal's [DocumentRule] inside the Portal Preparation workflow.
     */
    data class ForPortalRule(
        val portalId: String,
        val portalName: String,
        val rule: DocumentRule
    ) : CaptureIntent() {
        override val initialMode: CameraCaptureMode
            get() = modeForDocumentType(rule.documentType, rule.maxPages > 1)

        override val showTaskSelector: Boolean = false

        override val targetAspectRatio: Float?
            get() = when {
                rule.exactWidthPx != null && rule.exactHeightPx != null && rule.exactHeightPx > 0 ->
                    rule.exactWidthPx.toFloat() / rule.exactHeightPx.toFloat()
                rule.targetAspectRatio != null && rule.targetAspectRatio > 0f ->
                    rule.targetAspectRatio
                rule.physicalWidthMm != null && rule.physicalHeightMm != null && rule.physicalHeightMm > 0f ->
                    rule.physicalWidthMm / rule.physicalHeightMm
                else -> null
            }

        override val requiresWhiteBackground: Boolean
            get() = rule.requiresWhiteBackground

        override val contextLabel: String
            get() = "$portalName • ${rule.documentType.displayName}"
    }

    /**
     * Launched from Photo Studio, Signature Studio, Thumb Studio, or PDF Master with known mode.
     */
    data class ForStudio(
        override val initialMode: CameraCaptureMode,
        override val targetAspectRatio: Float? = null,
        override val requiresWhiteBackground: Boolean = false,
        val studioTitle: String = initialMode.title
    ) : CaptureIntent() {
        override val showTaskSelector: Boolean = false
        override val contextLabel: String = studioTitle
    }

    /**
     * Launched from a general-purpose entry point without a predetermined document rule.
     * Displays a compact task selector bar on the camera overlay instead of guessing blindly.
     */
    data class Generic(
        override val initialMode: CameraCaptureMode = CameraCaptureMode.PASSPORT_PHOTO
    ) : CaptureIntent() {
        override val showTaskSelector: Boolean = true
        override val targetAspectRatio: Float? = null
        override val requiresWhiteBackground: Boolean = initialMode == CameraCaptureMode.PASSPORT_PHOTO
        override val contextLabel: String = initialMode.title
    }

    companion object {
        fun modeForDocumentType(documentType: DocumentType, isMultiPage: Boolean = false): CameraCaptureMode {
            return when (documentType) {
                DocumentType.PASSPORT_PHOTO, DocumentType.POSTCARD_PHOTO ->
                    CameraCaptureMode.PASSPORT_PHOTO
                DocumentType.SIGNATURE_ENGLISH,
                DocumentType.SIGNATURE_HINDI,
                DocumentType.DECLARATION_HANDWRITTEN ->
                    CameraCaptureMode.SIGNATURE
                DocumentType.THUMB_IMPRESSION_LEFT,
                DocumentType.THUMB_IMPRESSION_RIGHT ->
                    CameraCaptureMode.THUMB_IMPRESSION
                else -> if (isMultiPage) {
                    CameraCaptureMode.MULTI_PAGE_DOCUMENT
                } else {
                    CameraCaptureMode.DOCUMENT_PAGE
                }
            }
        }

        fun resolveGuideRectNorm(mode: CameraCaptureMode, customAspectRatio: Float? = null): RectF {
            val base = mode.guideCropRectNorm
            if (customAspectRatio == null || customAspectRatio <= 0f) return RectF(base)
            val centerX = (base.left + base.right) * 0.5f
            val centerY = (base.top + base.bottom) * 0.5f
            val baseW = base.width().coerceIn(0.25f, 0.90f)
            val baseH = base.height().coerceIn(0.20f, 0.85f)

            val currentRatio = baseW / baseH
            val (newW, newH) = if (customAspectRatio > currentRatio) {
                val w = baseW
                val h = (w / customAspectRatio).coerceIn(0.18f, 0.86f)
                w to h
            } else {
                val h = baseH
                val w = (h * customAspectRatio).coerceIn(0.24f, 0.90f)
                w to h
            }
            val left = (centerX - newW * 0.5f).coerceIn(0.04f, 0.96f)
            val top = (centerY - newH * 0.5f).coerceIn(0.06f, 0.94f)
            val right = (centerX + newW * 0.5f).coerceIn(left + 0.10f, 0.96f)
            val bottom = (centerY + newH * 0.5f).coerceIn(top + 0.10f, 0.94f)
            return RectF(left, top, right, bottom)
        }
    }
}
