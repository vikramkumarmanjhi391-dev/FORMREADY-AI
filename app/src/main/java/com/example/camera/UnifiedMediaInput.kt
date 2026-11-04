package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.example.processing.ImageProcessor
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class CameraCaptureMode(
    val title: String,
    val subtitle: String,
    val instruction: String,
    val isMultiPageAllowed: Boolean = false,
    val defaultToFrontCamera: Boolean = false
) {
    PASSPORT_PHOTO(
        title = "Passport & Photo Capture",
        subtitle = "Align face within the framing guide",
        instruction = "Look straight at the camera. Keep head upright with plain background.",
        isMultiPageAllowed = false,
        defaultToFrontCamera = true
    ),
    SIGNATURE(
        title = "Signature Capture",
        subtitle = "Align handwriting within rectangular frame",
        instruction = "Keep paper flat with good lighting. Avoid shadows across ink strokes.",
        isMultiPageAllowed = false,
        defaultToFrontCamera = false
    ),
    THUMB_IMPRESSION(
        title = "Thumb Impression Capture",
        subtitle = "Align ink impression inside focus box",
        instruction = "Ensure ridge patterns are clearly visible and paper is well illuminated.",
        isMultiPageAllowed = false,
        defaultToFrontCamera = false
    ),
    DOCUMENT_PAGE(
        title = "Document & Certificate Page",
        subtitle = "Fit whole document inside the frame",
        instruction = "Hold device steady directly above document. Ensure all four borders and text are visible.",
        isMultiPageAllowed = false,
        defaultToFrontCamera = false
    ),
    MULTI_PAGE_DOCUMENT(
        title = "Multi-Page Document Scanner",
        subtitle = "Capture pages sequentially",
        instruction = "Capture each page clearly. You can review, reorder, or add more pages.",
        isMultiPageAllowed = true,
        defaultToFrontCamera = false
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
    val displayName: String = "media_${System.currentTimeMillis()}"
) {
    val file: File? get() = privateFile
    val fileSizeBytes: Long get() = privateFile?.length() ?: 0L

    /**
     * Resolves a working Bitmap safely with orientation correction synchronously.
     */
    fun toBitmap(context: Context): Bitmap? {
        return try {
            if (privateFile != null && privateFile.exists()) {
                decodeBitmapWithExif(privateFile)
            } else if (uri != null) {
                val currentUri = uri
                runCatching {
                    context.contentResolver.openInputStream(currentUri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }.getOrNull()
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Resolves a working Bitmap safely with orientation correction.
     */
    suspend fun resolveBitmap(context: Context): Bitmap? {
        return try {
            if (privateFile != null && privateFile.exists()) {
                decodeBitmapWithExif(privateFile)
            } else if (uri != null) {
                ImageProcessor.decodeBitmapFromUri(context, uri)
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
            if (privateFile != null && privateFile.exists()) {
                val copy = File(context.cacheDir, "unified_${UUID.randomUUID()}.jpg")
                privateFile.copyTo(copy, overwrite = true)
                copy
            } else if (uri != null) {
                val staging = File(context.cacheDir, "unified_${UUID.randomUUID()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(staging).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                staging
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        fun fromGalleryUri(uri: Uri, mode: CameraCaptureMode, displayName: String = "gallery_image"): UnifiedMediaInput {
            return UnifiedMediaInput(
                uri = uri,
                privateFile = null,
                origin = MediaInputOrigin.GALLERY,
                mode = mode,
                displayName = displayName
            )
        }

        fun fromUri(uri: Uri, mode: CameraCaptureMode, origin: MediaInputOrigin = MediaInputOrigin.GALLERY): UnifiedMediaInput {
            return UnifiedMediaInput(
                uri = uri,
                privateFile = null,
                origin = origin,
                mode = mode,
                displayName = uri.lastPathSegment ?: "image"
            )
        }

        fun fromCameraFile(file: File, mode: CameraCaptureMode, rotationDegrees: Int = 0): UnifiedMediaInput {
            return UnifiedMediaInput(
                uri = null,
                privateFile = file,
                origin = MediaInputOrigin.CAMERA,
                mode = mode,
                rotationDegrees = rotationDegrees,
                displayName = file.name
            )
        }

        fun fromFile(file: File, mode: CameraCaptureMode, origin: MediaInputOrigin = MediaInputOrigin.CAMERA): UnifiedMediaInput {
            return UnifiedMediaInput(
                uri = null,
                privateFile = file,
                origin = origin,
                mode = mode,
                displayName = file.name
            )
        }

        fun decodeBitmapWithExif(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val maxDimension = 3000
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

            return try {
                val exif = ExifInterface(file.absolutePath)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1.0f, -1.0f)
                    else -> return decoded
                }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated != decoded) decoded.recycle()
                rotated
            } catch (e: Exception) {
                decoded
            }
        }
    }
}
