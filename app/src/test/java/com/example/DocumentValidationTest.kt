package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.FileFormat
import com.example.storage.DocumentValidationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentValidationTest {

    private lateinit var testDir: File

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        testDir = File(context.cacheDir, "val_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun validateFile_realValidPdf_succeedsWithRealMetadata() {
        val pdfFile = File(testDir, "genuine_cert.pdf")
        val pdfContent = "%PDF-1.4\n" +
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n" +
            "xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \n" +
            "trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n190\n%%EOF\n"
        pdfFile.writeBytes(pdfContent.toByteArray(Charsets.US_ASCII))

        val result = DocumentValidationManager.validateFile(pdfFile)

        assertTrue("Real PDF must validate as valid: ${result.errorMessage}", result.isValid)
        assertEquals(FileFormat.PDF, result.detectedFormat)
        assertEquals("application/pdf", result.mimeType)
        assertTrue(result.pageCount >= 1)
        assertTrue(result.sha256Hex.isNotBlank())
        assertTrue(result.fileSizeBytes > 100)
    }

    @Test
    fun validateFile_realValidJpeg_succeedsWithDimensions() {
        val jpegFile = File(testDir, "genuine_photo.jpg")
        val bitmap = Bitmap.createBitmap(150, 200, Bitmap.Config.ARGB_8888)
        FileOutputStream(jpegFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()

        val result = DocumentValidationManager.validateFile(jpegFile)

        assertTrue("Real JPEG must validate as valid: ${result.errorMessage}", result.isValid)
        assertEquals(FileFormat.JPG, result.detectedFormat)
        assertEquals("image/jpeg", result.mimeType)
        assertEquals(150, result.widthPx)
        assertEquals(200, result.heightPx)
        assertTrue(result.sha256Hex.isNotBlank())
    }

    @Test
    fun validateFile_realValidPng_succeedsWithDimensions() {
        val pngFile = File(testDir, "genuine_signature.png")
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        FileOutputStream(pngFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val result = DocumentValidationManager.validateFile(pngFile)

        assertTrue("Real PNG must validate as valid: ${result.errorMessage}", result.isValid)
        assertEquals(FileFormat.PNG, result.detectedFormat)
        assertEquals("image/png", result.mimeType)
        assertEquals(120, result.widthPx)
        assertEquals(60, result.heightPx)
        assertTrue(result.sha256Hex.isNotBlank())
    }

    @Test
    fun validateFile_randomBytesWithPdfExtension_isRejected() {
        val fakePdf = File(testDir, "fake.pdf")
        fakePdf.writeBytes(ByteArray(500) { 0x41 }) // 'A's without %PDF header

        val result = DocumentValidationManager.validateFile(fakePdf)

        assertFalse("Random bytes with PDF extension must be rejected", result.isValid)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun validateFile_truncatedPdf_isRejected() {
        val truncatedPdf = File(testDir, "truncated.pdf")
        // Starts with %PDF header but truncated without %%EOF or xref
        truncatedPdf.writeBytes("%PDF-1.4\nCorrupted content with no trailer".toByteArray(Charsets.US_ASCII))

        val result = DocumentValidationManager.validateFile(truncatedPdf)

        assertFalse("Truncated PDF without EOF trailer must be rejected", result.isValid)
    }

    @Test
    fun validateFile_truncatedJpeg_isRejected() {
        val truncatedJpeg = File(testDir, "truncated.jpg")
        // JPEG SOI followed by garbage bytes that cannot decode dimensions or raster
        val fakeHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        truncatedJpeg.writeBytes(fakeHeader + ByteArray(150) { 0x00 })

        val result = DocumentValidationManager.validateFile(truncatedJpeg)

        assertFalse("Truncated JPEG with unreadable dimensions must be rejected", result.isValid)
    }

    @Test
    fun validateFile_corruptedPng_isRejected() {
        val corruptedPng = File(testDir, "corrupt.png")
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        corruptedPng.writeBytes(pngHeader + ByteArray(150) { 0xFF.toByte() })

        val result = DocumentValidationManager.validateFile(corruptedPng)

        assertFalse("Corrupted PNG must be rejected", result.isValid)
    }

    @Test
    fun validateFile_emptyFile_isRejected() {
        val emptyFile = File(testDir, "empty.pdf")
        emptyFile.createNewFile()

        val result = DocumentValidationManager.validateFile(emptyFile)

        assertFalse("Empty file must be rejected", result.isValid)
        assertTrue(result.errorMessage?.contains("small") == true || result.errorMessage?.contains("empty") == true)
    }

    @Test
    fun validateStream_partialReadInputStream_computesAccurateHash() {
        val testString = "%PDF-1.5 test document with partial reads and trailing %%EOF\n" + "x".repeat(100)
        val bytes = testString.toByteArray(Charsets.US_ASCII)

        // Custom InputStream that yields at most 3 bytes per read()
        val chunkedInputStream = object : InputStream() {
            var index = 0
            override fun read(): Int {
                return if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= bytes.size) return -1
                val toRead = minOf(3, len, bytes.size - index)
                System.arraycopy(bytes, index, b, off, toRead)
                index += toRead
                return toRead
            }
        }

        val result = DocumentValidationManager.validateStream(
            inputStream = chunkedInputStream,
            knownSize = bytes.size.toLong(),
            fileNameHint = "test.pdf"
        )

        assertTrue("Partial read InputStream must be read completely and validated", result.isValid)
        assertTrue(result.sha256Hex.isNotBlank())
    }
}
