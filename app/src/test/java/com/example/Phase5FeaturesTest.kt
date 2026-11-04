package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraCaptureMode
import com.example.camera.MediaInputOrigin
import com.example.camera.UnifiedMediaInput
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.processing.OneClickPreparationCoordinator
import com.example.processing.PdfProcessor
import com.example.storage.DocumentValidationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase5FeaturesTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    private fun createSampleBitmap(width: Int, height: Int, color: Int = Color.BLUE): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint().apply {
            this.color = Color.WHITE
            strokeWidth = 6f
        }
        canvas.drawLine(10f, 10f, (width - 10).toFloat(), (height - 10).toFloat(), paint)
        return bitmap
    }

    private fun saveBitmapToTestFile(bitmap: Bitmap, filename: String): File {
        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return file
    }

    @Test
    fun testUnifiedMediaInputFromFile() {
        val bmp = createSampleBitmap(400, 500)
        val file = saveBitmapToTestFile(bmp, "test_camera_photo.jpg")

        val input = UnifiedMediaInput.fromFile(
            file = file,
            mode = CameraCaptureMode.PASSPORT_PHOTO,
            origin = MediaInputOrigin.IN_APP_CAMERA
        )

        assertEquals(CameraCaptureMode.PASSPORT_PHOTO, input.mode)
        assertEquals(MediaInputOrigin.IN_APP_CAMERA, input.origin)
        assertEquals("test_camera_photo.jpg", input.displayName)
        assertTrue(input.fileSizeBytes > 0)

        val decoded = input.toBitmap(context)
        assertNotNull(decoded)
        assertEquals(400, decoded?.width)
        assertEquals(500, decoded?.height)
    }

    @Test
    fun testOneClickAutoPreparePhoto() = runBlocking {
        val bmp = createSampleBitmap(600, 600, Color.CYAN)
        val file = saveBitmapToTestFile(bmp, "one_click_photo_input.jpg")
        val input = UnifiedMediaInput.fromFile(file, CameraCaptureMode.PASSPORT_PHOTO)

        val rule = DocumentRule(
            id = "test_passport_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 10,
            maxKb = 50,
            exactWidthPx = 350,
            exactHeightPx = 450
        )

        val result = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            mediaInput = input,
            rule = rule,
            targetSizeKb = 40,
            customDpi = 300
        )

        assertTrue("Photo prep should succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val prepResult = result.getOrNull()
        assertNotNull(prepResult)
        assertEquals(350, prepResult?.widthPx)
        assertEquals(450, prepResult?.heightPx)
        val outputFile = File(prepResult?.processedFilePath ?: "")
        assertTrue(outputFile.exists())
        assertTrue(outputFile.length() > 0)
    }

    @Test
    fun testOneClickAutoPrepareSignature() = runBlocking {
        // Create signature bitmap with strokes on white canvas
        val bmp = Bitmap.createBitmap(500, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 8f
        }
        canvas.drawLine(50f, 100f, 450f, 100f, paint)
        canvas.drawCircle(250f, 100f, 30f, paint)

        val file = saveBitmapToTestFile(bmp, "one_click_sig_input.jpg")
        val input = UnifiedMediaInput.fromFile(file, CameraCaptureMode.SIGNATURE)

        val rule = DocumentRule(
            id = "test_signature_rule",
            documentType = DocumentType.SIGNATURE_ENGLISH,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 5,
            maxKb = 20,
            exactWidthPx = 300,
            exactHeightPx = 120
        )

        val result = OneClickPreparationCoordinator.autoPrepareSignature(
            context = context,
            mediaInput = input,
            rule = rule,
            targetSizeKb = 15
        )

        assertTrue("Signature prep should succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val prepResult = result.getOrNull()
        assertNotNull(prepResult)
        assertEquals(300, prepResult?.widthPx)
        assertEquals(120, prepResult?.heightPx)
        val outputFile = File(prepResult?.processedFilePath ?: "")
        assertTrue(outputFile.exists())
        assertTrue(outputFile.length() > 0)
    }

    @Test
    fun testMultiPageCameraScanToPdfAndValidation() {
        // Multi-page document scan validation test
        val pdfFile = File(context.cacheDir, "test_scanned_multipage.pdf")
        val pdfContent = "%PDF-1.4\n" +
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>\nendobj\n" +
            "4 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>\nendobj\n" +
            "xref\n0 5\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000123 00000 n \n0000000188 00000 n \n" +
            "trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n253\n%%EOF\n"
        pdfFile.writeBytes(pdfContent.toByteArray(Charsets.US_ASCII))

        assertTrue(pdfFile.exists())
        assertTrue(pdfFile.length() > 0)

        // Validate multi-page scan with DocumentValidationManager
        val validation = DocumentValidationManager.validateFile(pdfFile)
        assertTrue("Multi-page PDF validation should pass: ${validation.errorMessage}", validation.isValid)
        assertEquals(FileFormat.PDF, validation.detectedFormat)
        assertTrue(validation.pageCount >= 1)
        assertTrue(validation.sha256Hex.isNotBlank())

        val input = UnifiedMediaInput.fromFile(
            file = pdfFile,
            mode = CameraCaptureMode.MULTI_PAGE_DOCUMENT,
            origin = MediaInputOrigin.IN_APP_CAMERA
        )
        assertEquals(CameraCaptureMode.MULTI_PAGE_DOCUMENT, input.mode)
        assertTrue(input.mode.isMultiPageAllowed)
    }
}
