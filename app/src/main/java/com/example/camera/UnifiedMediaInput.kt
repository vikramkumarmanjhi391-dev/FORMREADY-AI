package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.processing.ImageProcessor
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

enum class CameraCaptureMode(
    val title: String,
    val subtitle: String,
    val instruction: String,
    val isMultiPageAllowed: Boolean = false,
    val defaultToFrontCamera: Boolean = false,
    val guideCropRectNorm: RectF = RectF(0f, 0f, 1f, 1f)
) {
    PASSPORT_PHOTO(
        title = "Passport & Photo Capture",
        subtitle = "Align face & shoulders within the guide",
        instruction = "Look straight at the camera. Keep face in oval & shoulders on line.",
        isMultiPageAllowed = false,
        defaultToFrontCamera = true,
        guideCropRectNorm = RectF(0.14f, 0.14f, 0.86f, 0.84f)
    ),
    SIGNATURE(
        title = "Signature Cutout Capture",
        subtitle = "Align signature inside horizontal cutout box",
        instruction = "Place signature inside the clear horizontal box. Auto-crops in 1-Click.",
        isMultiPageAllowed = false,
        defaultToFrontCamera = false,
        guideCropRectNorm = RectF(0.08f, 0.36f, 0.92f, 0.64f)
    ),
    THUMB_IMPRESSION(
        title = "Thumb Impression Capture",
        subtitle = "Align ink impression inside focus box",
        instruction = "Ensure ridge patterns are clearly visible and paper is well illuminated.",
        isMultiPageAllowed = false,
        defaultToFrontCamera = false,
        guideCropRectNorm = RectF(0.24f, 0.30f, 0.76f, 0.70f)
    ),
    DOCUMENT_PAGE(
        title = "Document & ID Scanner",
        subtitle = "Fit ID Card or A4 document inside frame",
        instruction = "Align all 4 corners inside the frame for 1-Click Clear Scan.",
        isMultiPageAllowed = false,
        defaultToFrontCamera = false,
        guideCropRectNorm = RectF(0.07f, 0.12f, 0.93f, 0.88f)
    ),
    MULTI_PAGE_DOCUMENT(
        title = "Multi-Page Document Scanner",
        subtitle = "Capture pages sequentially",
        instruction = "Capture each page clearly. You can review, reorder, or add more pages.",
        isMultiPageAllowed = true,
        defaultToFrontCamera = false,
        guideCropRectNorm = RectF(0.07f, 0.12f, 0.93f, 0.88f)
    )
}

enum class MediaInputOrigin {
    CAMERA,
    GALLERY,
    DOCUMENT_PICKER,
    IN_APP_CAMERA,
    SYSTEM_PHOTO_PICKER
}

/**
 * Unified model representing media from either Camera or Gallery.
 * Guarantees a single common input pipeline across Photo Studio, Signature Studio,
 * PDF Master, and Document Locker.
 */
data class UnifiedMediaInput(
    val uri: Uri? = null,
    val privateFile: File? = null,
    val origin: MediaInputOrigin,
    val mode: CameraCaptureMode,
    val mimeType: String = "image/jpeg",
    val timestamp: Long = System.currentTimeMillis(),
    val rotationDegrees: Int = 0,
    val displayName: String = "media_${System.currentTimeMillis()}",
    val mappedSensorCropRectNorm: RectF? = null,
    val captureIntent: CaptureIntent? = null
) {
    val file: File? get() = privateFile
    val fileSizeBytes: Long get() = privateFile?.length() ?: 0L
    val isFromCamera: Boolean
        get() = origin == MediaInputOrigin.CAMERA || origin == MediaInputOrigin.IN_APP_CAMERA

    @Deprecated(
        "Use mappedSensorCropRectNorm directly; returns null when live PreviewView or sensor geometry was unknown so no silent guesswork crop occurs.",
        ReplaceWith("mappedSensorCropRectNorm")
    )
    val effectiveGuideCropRectNorm: RectF?
        get() = mappedSensorCropRectNorm

    /**
     * Resolves a working Bitmap safely with EXIF orientation correction and memory bounds protection.
     */
    fun toBitmap(context: Context, maxDimension: Int = 2048): Bitmap? {
        return try {
            if (privateFile != null && privateFile.exists() && privateFile.length() > 0L) {
                decodeBitmapWithExif(privateFile, maxDimension)
            } else if (uri != null) {
                ImageProcessor.decodeBitmapFromUriSync(context, uri, maxDimension)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Suspending resolution of a working Bitmap with orientation correction.
     * Both Camera and Gallery inputs apply identical EXIF orientation and sample-size logic.
     */
    suspend fun resolveBitmap(context: Context, maxDimension: Int = 2048): Bitmap? {
        return try {
            if (privateFile != null && privateFile.exists() && privateFile.length() > 0L) {
                decodeBitmapWithExif(privateFile, maxDimension)
            } else if (uri != null) {
                ImageProcessor.decodeBitmapFromUri(context, uri, maxDimension)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Obtains or materializes an isolated disk file in private cache for downstream processing.
     * Original gallery URI or camera capture is NEVER overwritten.
     */
    fun resolveTemporaryFile(context: Context): File? {
        return try {
            val ext = resolveExtension(context)
            if (privateFile != null && privateFile.exists() && privateFile.length() > 0L) {
                val copy = File(context.cacheDir, "unified_${UUID.randomUUID()}.$ext")
                privateFile.copyTo(copy, overwrite = true)
                copy
            } else if (uri != null) {
                val staging = File(context.cacheDir, "unified_${UUID.randomUUID()}.$ext")
                val copied = context.contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(staging).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                    true
                } ?: false
                if (copied && staging.exists() && staging.length() > 0L) staging else {
                    staging.delete()
                    null
                }
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Cleans up the underlying private camera file if this input originated from temporary camera capture.
     * Never deletes original Gallery documents.
     */
    fun cleanupPrivateCameraFileIfTemporary() {
        try {
            val f = privateFile ?: return
            if (isFromCamera && f.exists() && f.absolutePath.contains("camera_captures")) {
                f.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveExtension(context: Context): String {
        privateFile?.extension?.takeIf { it.isNotBlank() }?.let {
            return it.lowercase(Locale.US)
        }
        val resolvedMime = uri?.let { context.contentResolver.getType(it) } ?: mimeType
        return when (resolvedMime.lowercase(Locale.US)) {
            "image/png" -> "png"
            "application/pdf" -> "pdf"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime) ?: "jpg"
        }
    }

    companion object {
        fun fromGalleryUri(
            uri: Uri,
            mode: CameraCaptureMode,
            displayName: String = "gallery_image",
            mimeType: String = "image/jpeg",
            captureIntent: CaptureIntent? = null
        ): UnifiedMediaInput {
            return UnifiedMediaInput(
                uri = uri,
                privateFile = null,
                origin = MediaInputOrigin.GALLERY,
                mode = mode,
                mimeType = mimeType,
                displayName = displayName,
                captureIntent = captureIntent
            )
        }

        fun fromUri(
            uri: Uri,
            mode: CameraCaptureMode,
            origin: MediaInputOrigin = MediaInputOrigin.GALLERY,
            mimeType: String = "image/jpeg"
        ): UnifiedMediaInput {
            return UnifiedMediaInput(
                uri = uri,
                privateFile = null,
                origin = origin,
                mode = mode,
                mimeType = mimeType,
                displayName = uri.lastPathSegment ?: "image"
            )
        }

        fun fromCameraFile(
            file: File,
            mode: CameraCaptureMode,
            rotationDegrees: Int = 0,
            mappedSensorCropRectNorm: RectF? = null,
            captureIntent: CaptureIntent? = null
        ): UnifiedMediaInput {
            val ext = file.extension.lowercase(Locale.US)
            val detectedMime = when (ext) {
                "png" -> "image/png"
                "pdf" -> "application/pdf"
                else -> "image/jpeg"
            }
            return UnifiedMediaInput(
                uri = null,
                privateFile = file,
                origin = MediaInputOrigin.CAMERA,
                mode = mode,
                mimeType = detectedMime,
                rotationDegrees = rotationDegrees,
                displayName = file.name,
                mappedSensorCropRectNorm = mappedSensorCropRectNorm,
                captureIntent = captureIntent
            )
        }

        /**
         * Builds a camera [UnifiedMediaInput] using actual measured `PreviewView` dimensions,
         * measured guide overlay normalized bounds (`PreviewViewSpace`), and decoded sensor/EXIF
         * dimensions (`UprightBitmapSpace`).
         *
         * Strictly enforces the Unknown-Geometry Gate: if any dimension (`previewWidth`, `previewHeight`,
         * `rawWidth`, `rawHeight`) is `<= 0` or `measuredGuideRectNorm == null`, sets
         * `mappedSensorCropRectNorm = null` instead of guessing fallback constants.
         */
        fun buildMappedMediaInputFromGeometry(
            file: File,
            mode: CameraCaptureMode,
            previewWidth: Int,
            previewHeight: Int,
            measuredGuideRectNorm: RectF?,
            isFrontCamera: Boolean = false,
            captureIntent: CaptureIntent? = null
        ): UnifiedMediaInput {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (file.exists() && file.length() >= 4L) {
                BitmapFactory.decodeFile(file.absolutePath, bounds)
            }
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            val exifOrientation = try {
                if (file.exists()) {
                    val exif = ExifInterface(file.absolutePath)
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } else {
                    ExifInterface.ORIENTATION_NORMAL
                }
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val exifRotDegrees = CameraViewportMapper.exifOrientationToDegrees(exifOrientation)
            val (uprightW, uprightH) = CameraViewportMapper.resolveUprightDimensions(rawW, rawH, exifOrientation)

            val mappedRect: RectF? = if (
                previewWidth > 0 &&
                previewHeight > 0 &&
                uprightW > 0 &&
                uprightH > 0 &&
                measuredGuideRectNorm != null &&
                measuredGuideRectNorm.width() > 0f &&
                measuredGuideRectNorm.height() > 0f
            ) {
                CameraViewportMapper.mapPreviewRectToSensorNormalizedRect(
                    previewRectNorm = measuredGuideRectNorm,
                    config = CameraViewportMapper.ViewportMappingConfig(
                        rawSensorWidth = rawW,
                        rawSensorHeight = rawH,
                        rotationDegrees = exifRotDegrees,
                        previewWidth = previewWidth,
                        previewHeight = previewHeight,
                        isFrontCamera = isFrontCamera,
                        isSavedOutputMirrored = false,
                        exifOrientation = exifOrientation
                    )
                )
            } else {
                null
            }

            return fromCameraFile(
                file = file,
                mode = mode,
                rotationDegrees = exifRotDegrees,
                mappedSensorCropRectNorm = mappedRect,
                captureIntent = captureIntent
            )
        }

        fun fromFile(
            file: File,
            mode: CameraCaptureMode,
            origin: MediaInputOrigin = MediaInputOrigin.CAMERA
        ): UnifiedMediaInput {
            val ext = file.extension.lowercase(Locale.US)
            val detectedMime = when (ext) {
                "png" -> "image/png"
                "pdf" -> "application/pdf"
                else -> "image/jpeg"
            }
            return UnifiedMediaInput(
                uri = null,
                privateFile = file,
                origin = origin,
                mode = mode,
                mimeType = detectedMime,
                displayName = file.name
            )
        }

        fun hasValidImageHeader(header: ByteArray, readBytes: Int): Boolean {
            if (readBytes < 4) return false
            // JPEG: FF D8 FF
            if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                return true
            }
            // PNG: 89 50 4E 47
            if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
            ) {
                return true
            }
            // WEBP: RIFF....WEBP
            if (readBytes >= 12 &&
                header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
            ) {
                return true
            }
            // GIF: GIF8
            if (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte()
            ) {
                return true
            }
            // BMP: BM
            if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
                return true
            }
            return false
        }

        fun decodeBitmapWithExif(file: File, maxDimension: Int = 2048): Bitmap? {
            if (!file.exists() || file.length() < 4L) return null
            return try {
                val header = ByteArray(12)
                val readBytes = file.inputStream().use { it.read(header) }
                if (!hasValidImageHeader(header, readBytes)) return null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                var sampleSize = 1
                while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
                    sampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

                val orientation = try {
                    val exif = ExifInterface(file.absolutePath)
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (_: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }

                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1.0f, 1.0f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1.0f, -1.0f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        matrix.postRotate(90f)
                        matrix.postScale(-1.0f, 1.0f)
                    }
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        matrix.postRotate(270f)
                        matrix.postScale(-1.0f, 1.0f)
                    }
                    else -> return decoded
                }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated != decoded) decoded.recycle()
                rotated
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
