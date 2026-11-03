package com.example

import com.example.data.model.FileFormat
import com.example.storage.DocumentValidationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class DocumentValidationTest {

    @Test
    fun validateStream_detectsPdfMagicBytes() {
        val dummyPdfContent = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF" + "padding".repeat(20)
        val bytes = dummyPdfContent.toByteArray(Charsets.US_ASCII)

        val result = DocumentValidationManager.validateStream(
            inputStream = ByteArrayInputStream(bytes),
            knownSize = bytes.size.toLong(),
            fileNameHint = "marksheet.pdf"
        )

        assertTrue(result.isValid)
        assertEquals(FileFormat.PDF, result.detectedFormat)
        assertEquals("application/pdf", result.mimeType)
        assertTrue(result.sha256Hex.isNotBlank())
    }

    @Test
    fun validateStream_detectsJpegMagicBytes() {
        val dummyJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
        ) + ByteArray(150) { 0x55 }

        val result = DocumentValidationManager.validateStream(
            inputStream = ByteArrayInputStream(dummyJpeg),
            knownSize = dummyJpeg.size.toLong(),
            fileNameHint = "photo.jpg"
        )

        assertTrue(result.isValid)
        assertEquals(FileFormat.JPG, result.detectedFormat)
        assertEquals("image/jpeg", result.mimeType)
    }

    @Test
    fun validateStream_detectsPngMagicBytes() {
        val dummyPng = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        ) + ByteArray(150) { 0x33 }

        val result = DocumentValidationManager.validateStream(
            inputStream = ByteArrayInputStream(dummyPng),
            knownSize = dummyPng.size.toLong(),
            fileNameHint = "signature.png"
        )

        assertTrue(result.isValid)
        assertEquals(FileFormat.PNG, result.detectedFormat)
        assertEquals("image/png", result.mimeType)
    }

    @Test
    fun validateStream_rejectsCorruptOrUnsupportedFiles() {
        val randomBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05) + ByteArray(120)

        val result = DocumentValidationManager.validateStream(
            inputStream = ByteArrayInputStream(randomBytes),
            knownSize = randomBytes.size.toLong(),
            fileNameHint = "file.unknown"
        )

        assertFalse(result.isValid)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun validateStream_computesAccurateSha256Digest() {
        val content = "%PDF-1.5 test content for hash computation" + "x".repeat(100)
        val bytes = content.toByteArray(Charsets.UTF_8)

        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val result = DocumentValidationManager.validateStream(
            inputStream = ByteArrayInputStream(bytes),
            knownSize = bytes.size.toLong(),
            fileNameHint = "doc.pdf"
        )

        assertTrue(result.isValid)
        assertEquals(expectedHash, result.sha256Hex)
    }
}
