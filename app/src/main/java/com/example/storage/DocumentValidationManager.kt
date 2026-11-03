package com.example.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.data.model.FileFormat
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

data class DocumentValidationResult(
    val isValid: Boolean,
    val detectedFormat: FileFormat?,
    val mimeType: String,
    val fileSizeBytes: Long,
    val sha256Hex: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val pageCount: Int = 1,
    val errorMessage: String? = null
)

/**
 * Validates genuine document files using magic bytes, format sniffing, size limits,
 * and calculates streaming SHA-256 hashes without loading complete documents into memory.
 */
object DocumentValidationManager {

    private const val MIN_FILE_SIZE_BYTES = 100L
    private const val MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L // 50 MB safety ceiling

    // Magic Bytes signatures
    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // \x89PNG
    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // JPEG start

    fun validateFile(file: File): DocumentValidationResult {
        if (!file.exists() || !file.canRead()) {
            return DocumentValidationResult(
                isValid = false,
                detectedFormat = null,
                mimeType = "application/octet-stream",
                fileSizeBytes = 0L,
                sha256Hex = "",
                errorMessage = "Source file does not exist or cannot be read"
            )
        }
        val size = file.length()
        if (size < MIN_FILE_SIZE_BYTES) {
            return DocumentValidationResult(
                isValid = false,
                detectedFormat = null,
                mimeType = "application/octet-stream",
                fileSizeBytes = size,
                sha256Hex = "",
                errorMessage = "File is empty or too small (< 100 bytes) to be a valid document"
            )
        }
        if (size > MAX_FILE_SIZE_BYTES) {
            return DocumentValidationResult(
                isValid = false,
                detectedFormat = null,
                mimeType = "application/octet-stream",
                fileSizeBytes = size,
                sha256Hex = "",
                errorMessage = "File exceeds maximum permitted size of 50 MB"
            )
        }

        FileInputStream(file).use { stream ->
            return validateStream(stream, size, file.name)
        }
    }

    fun validateUri(context: Context, uri: Uri): DocumentValidationResult {
        val contentResolver = context.contentResolver
        val size = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }

        val inputStream = try {
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return DocumentValidationResult(
            isValid = false,
            detectedFormat = null,
            mimeType = "application/octet-stream",
            fileSizeBytes = 0L,
            sha256Hex = "",
            errorMessage = "Unable to open document URI"
        )

        inputStream.use { stream ->
            return validateStream(stream, size, uri.lastPathSegment ?: "document")
        }
    }

    fun validateStream(inputStream: InputStream, knownSize: Long, fileNameHint: String): DocumentValidationResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16384)
        var totalBytesRead = 0L

        // Read first 16 bytes for magic bytes sniffing
        val header = ByteArray(16)
        val initialRead = inputStream.read(header)
        if (initialRead < 4) {
            return DocumentValidationResult(
                isValid = false,
                detectedFormat = null,
                mimeType = "application/octet-stream",
                fileSizeBytes = initialRead.toLong(),
                sha256Hex = "",
                errorMessage = "Corrupt or truncated file (< 4 bytes)"
            )
        }
        digest.update(header, 0, initialRead)
        totalBytesRead += initialRead

        // Sniff format
        val format = sniffFormat(header)
        if (format == null) {
            return DocumentValidationResult(
                isValid = false,
                detectedFormat = null,
                mimeType = "application/octet-stream",
                fileSizeBytes = initialRead.toLong(),
                sha256Hex = "",
                errorMessage = "Unsupported file format. Please upload genuine PDF, JPG, or PNG documents."
            )
        }

        // Stream remaining content to compute SHA-256 hash and total size
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            if (totalBytesRead > MAX_FILE_SIZE_BYTES) {
                return DocumentValidationResult(
                    isValid = false,
                    detectedFormat = format,
                    mimeType = format.mimeType,
                    fileSizeBytes = totalBytesRead,
                    sha256Hex = "",
                    errorMessage = "File exceeds 50 MB limit during streaming validation"
                )
            }
        }

        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }

        return DocumentValidationResult(
            isValid = true,
            detectedFormat = format,
            mimeType = format.mimeType,
            fileSizeBytes = totalBytesRead,
            sha256Hex = sha256Hex,
            pageCount = if (format == FileFormat.PDF) 1 else 1,
            errorMessage = null
        )
    }

    private fun sniffFormat(header: ByteArray): FileFormat? {
        if (header.size >= 4) {
            // PDF: %PDF
            if (header[0] == PDF_MAGIC[0] && header[1] == PDF_MAGIC[1] &&
                header[2] == PDF_MAGIC[2] && header[3] == PDF_MAGIC[3]
            ) {
                return FileFormat.PDF
            }
            // PNG: \x89PNG
            if (header[0] == PNG_MAGIC[0] && header[1] == PNG_MAGIC[1] &&
                header[2] == PNG_MAGIC[2] && header[3] == PNG_MAGIC[3]
            ) {
                return FileFormat.PNG
            }
        }
        if (header.size >= 3) {
            // JPEG: 0xFF, 0xD8, 0xFF
            if (header[0] == JPEG_SOI[0] && header[1] == JPEG_SOI[1] && header[2] == JPEG_SOI[2]) {
                return FileFormat.JPG
            }
        }
        return null
    }

    fun inspectImageDimensions(file: File): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                Pair(options.outWidth, options.outHeight)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
