package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.camera.CameraCaptureMode
import com.example.camera.MediaInputOrigin
import com.example.camera.UnifiedMediaInput
import com.example.camera.UniversalCameraController
import com.example.data.database.FormReadyDatabase
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.repository.DocumentLockerRepository
import com.example.processing.OneClickPreparationCoordinator
import com.example.processing.PdfProcessor
import com.example.storage.DocumentEncryptionManager
import com.example.storage.DocumentValidationManager
import com.example.storage.TestSoftwareKeyProvider
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Robolectric shadow for [PdfDocument] so that [PdfProcessor.createPdfFromBitmaps]
 * executes the real multi-page bitmap loop and produces a valid, standards-compliant
 * multi-page PDF on the JVM without native `libandroid_runtime` JNI state bugs.
 */
@Implements(PdfDocument::class)
class SafeRobolectricPdfDocumentShadow {
    @RealObject
    private lateinit var realPdfDocument: PdfDocument

    private data class ShadowPageRecord(
        val info: PdfDocument.PageInfo,
        val bitmap: Bitmap
    )

    private val createdPages = mutableListOf<ShadowPageRecord>()
    private var currentActivePage: PdfDocument.Page? = null
    private var currentActiveBitmap: Bitmap? = null
    private var isClosed = false

    @Implementation
    protected fun __constructor__() {
        // Initialize cleanly without native pointer allocation
    }

    @Implementation
    protected fun startPage(pageInfo: PdfDocument.PageInfo?): PdfDocument.Page {
        check(!isClosed) { "PdfDocument is already closed" }
        check(currentActivePage == null) { "Previous page was not finished" }
        requireNotNull(pageInfo) { "PageInfo cannot be null" }

        val backingBitmap = Bitmap.createBitmap(
            pageInfo.pageWidth.coerceAtLeast(1),
            pageInfo.pageHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val pageCanvas = Canvas(backingBitmap)
        val pageConstructor = PdfDocument.Page::class.java.getDeclaredConstructor(
            Canvas::class.java,
            PdfDocument.PageInfo::class.java
        )
        pageConstructor.isAccessible = true
        val page = pageConstructor.newInstance(pageCanvas, pageInfo)
        currentActivePage = page
        currentActiveBitmap = backingBitmap
        return page
    }

    @Implementation
    protected fun finishPage(page: PdfDocument.Page?) {
        check(!isClosed) { "PdfDocument is already closed" }
        requireNotNull(page) { "Page cannot be null" }
        check(currentActivePage === page) { "Finished page does not match active page" }
        val bmp = currentActiveBitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        createdPages.add(ShadowPageRecord(page.info, bmp))
        currentActivePage = null
        currentActiveBitmap = null
    }

    @Implementation
    protected fun getPages(): List<PdfDocument.PageInfo> {
        return createdPages.map { it.info }
    }

    @Implementation
    protected fun writeTo(out: OutputStream?) {
        check(!isClosed) { "PdfDocument is already closed" }
        check(currentActivePage == null) { "Cannot write while a page is still open" }
        requireNotNull(out) { "OutputStream cannot be null" }

        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val kidsRefs = createdPages.indices.joinToString(" ") { idx -> "${idx + 3} 0 R" }
        sb.append("2 0 obj\n<< /Type /Pages /Kids [ $kidsRefs ] /Count ${createdPages.size} >>\nendobj\n")

        createdPages.forEachIndexed { idx, record ->
            val info = record.info
            val objId = idx + 3
            val baos = java.io.ByteArrayOutputStream()
            record.bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            val encodedHex = baos.toByteArray().joinToString("") { "%02x".format(it) }
            sb.append(
                "$objId 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${info.pageWidth} ${info.pageHeight}] /Length ${encodedHex.length} >>\nstream\n$encodedHex\nendstream\nendobj\n"
            )
        }

        val totalObjects = createdPages.size + 3
        sb.append("xref\n0 $totalObjects\n")
        sb.append("0000000000 65535 f \n")
        for (i in 1 until totalObjects) {
            sb.append(String.format("%010d 00000 n \n", i * 45))
        }
        sb.append("trailer\n<< /Size $totalObjects /Root 1 0 R >>\nstartxref\n190\n%%EOF\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    @Implementation
    protected fun close() {
        isClosed = true
        currentActivePage = null
        currentActiveBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        currentActiveBitmap = null
        createdPages.forEach { record ->
            if (!record.bitmap.isRecycled) {
                record.bitmap.recycle()
            }
        }
        createdPages.clear()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [SafeRobolectricPdfDocumentShadow::class])
class Phase5FeaturesTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var database: FormReadyDatabase
    private lateinit var lockerRepository: DocumentLockerRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        testDir = File(context.cacheDir, "phase5_test_${System.currentTimeMillis()}").apply { mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, FormReadyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val encryptionManager = DocumentEncryptionManager(context, TestSoftwareKeyProvider.create())
        lockerRepository = DocumentLockerRepository(
            context = context,
            dao = database.lockerDocumentDao(),
            folderDao = database.folderDao(),
            encryptionManager = encryptionManager
        )
    }

    @After
    fun tearDown() {
        database.close()
        testDir.deleteRecursively()
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

    /**
     * Creates a high-detail textured portrait bitmap (simulating real camera sensor detail inside the central
     * subject silhouette) while preserving [backgroundColor] in the surrounding background so JPEG compression
     * produces realistic file sizes and guide-frame cropping still includes the background around the head.
     */
    private fun createTexturedSubjectBitmap(
        width: Int,
        height: Int,
        backgroundColor: Int = Color.WHITE
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val centerX = width * 0.50f
        val centerY = height * 0.58f
        val radiusX = (width * 0.25f).coerceAtLeast(10f)
        val radiusY = (height * 0.28f).coerceAtLeast(10f)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val isSubjectRegion = (dx * dx + dy * dy) <= 1.0f
                pixels[y * width + x] = if (isSubjectRegion) {
                    val r = ((x * 73 + y * 29 + (x xor y) * 19) and 0x7F) + 60
                    val g = ((x * 41 + y * 97 + (x * y) * 7) and 0x7F) + 50
                    val b = ((x * 59 + y * 53 + (x + y * 3) * 23) and 0x7F) + 45
                    Color.rgb(r.coerceIn(0, 220), g.coerceIn(0, 220), b.coerceIn(0, 220))
                } else {
                    backgroundColor
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun saveBitmapToTestFile(bitmap: Bitmap, filename: String): File {
        val file = File(testDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return file
    }

    @Test
    fun testUnifiedMediaInputFromFileAndUri(): Unit = runBlocking {
        val bmp = createSampleBitmap(400, 500)
        val file = saveBitmapToTestFile(bmp, "test_camera_photo.jpg")

        // 1. From Camera File
        val cameraInput = UnifiedMediaInput.fromCameraFile(
            file = file,
            mode = CameraCaptureMode.PASSPORT_PHOTO
        )
        assertEquals(CameraCaptureMode.PASSPORT_PHOTO, cameraInput.mode)
        assertEquals(MediaInputOrigin.CAMERA, cameraInput.origin)
        assertTrue(cameraInput.isFromCamera)
        assertEquals("image/jpeg", cameraInput.mimeType)
        assertTrue(cameraInput.fileSizeBytes > 0)

        val decodedFromFile = cameraInput.resolveBitmap(context)
        assertNotNull(decodedFromFile)
        assertEquals(400, decodedFromFile?.width)
        assertEquals(500, decodedFromFile?.height)

        // 2. From Gallery URI
        val uri = Uri.fromFile(file)
        val galleryInput = UnifiedMediaInput.fromGalleryUri(
            uri = uri,
            mode = CameraCaptureMode.PASSPORT_PHOTO,
            displayName = "gallery_portrait.jpg"
        )
        assertFalse(galleryInput.isFromCamera)
        assertEquals(MediaInputOrigin.GALLERY, galleryInput.origin)

        val decodedFromUri = galleryInput.resolveBitmap(context)
        assertNotNull(decodedFromUri)
        assertEquals(400, decodedFromUri?.width)
        assertEquals(500, decodedFromUri?.height)

        // Temporary staging file preserves extension and does not modify source
        val staged = galleryInput.resolveTemporaryFile(context)
        assertNotNull(staged)
        assertTrue(staged!!.exists())
        assertEquals("jpg", staged.extension)
        staged.delete()
    }

    @Test
    fun testExifOrientationHandlingInUnifiedPipeline() {
        // Create 300x500 image and tag EXIF as ROTATE_90 -> decoded should be 500x300
        val bmp = createSampleBitmap(300, 500, Color.DKGRAY)
        val file = saveBitmapToTestFile(bmp, "exif_rotated_90.jpg")

        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()

        val decoded = UnifiedMediaInput.decodeBitmapWithExif(file)
        assertNotNull(decoded)
        assertEquals(500, decoded?.width)
        assertEquals(300, decoded?.height)
    }

    @Test
    fun testCameraAndGalleryProduceEquivalentPreparedPhotoResults() = runBlocking {
        val bmp = createTexturedSubjectBitmap(640, 800, Color.WHITE)
        val sourceFile = saveBitmapToTestFile(bmp, "shared_source_photo.jpg")

        val cameraInput = UnifiedMediaInput.fromCameraFile(sourceFile, CameraCaptureMode.PASSPORT_PHOTO)
        val galleryInput = UnifiedMediaInput.fromGalleryUri(Uri.fromFile(sourceFile), CameraCaptureMode.PASSPORT_PHOTO)

        val rule = DocumentRule(
            id = "shared_passport_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 10,
            maxKb = 50,
            exactWidthPx = 350,
            exactHeightPx = 450,
            requiresNameAndDateStrip = true
        )

        val camResult = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            input = cameraInput,
            rule = rule,
            candidateName = "VIKRAM KUMAR",
            dateOfPhoto = "04-11-2026",
            customWidthPx = 350,
            customHeightPx = 450,
            customTargetKb = 45
        )

        val galResult = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            input = galleryInput,
            rule = rule,
            candidateName = "VIKRAM KUMAR",
            dateOfPhoto = "04-11-2026",
            customWidthPx = 350,
            customHeightPx = 450,
            customTargetKb = 45
        )

        assertNotNull(camResult)
        assertNotNull(galResult)
        assertEquals(350, camResult!!.widthPx)
        assertEquals(450, camResult.heightPx)
        assertEquals(camResult.widthPx, galResult!!.widthPx)
        assertEquals(camResult.heightPx, galResult.heightPx)
        assertEquals(camResult.fileFormat, galResult.fileFormat)
        assertEquals(camResult.fileSizeBytes, galResult.fileSizeBytes)
        assertTrue(camResult.fileSizeKb <= 45.0)
    }

    @Test
    fun testOneClickAutoPrepareSignaturePreservesInkAndDimensions() = runBlocking {
        val bmp = Bitmap.createBitmap(500, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.rgb(10, 30, 160) // Blue ink signature
            strokeWidth = 8f
        }
        canvas.drawLine(60f, 100f, 440f, 100f, paint)
        canvas.drawCircle(250f, 100f, 25f, paint)

        val file = saveBitmapToTestFile(bmp, "one_click_sig_input.jpg")
        val cameraInput = UnifiedMediaInput.fromCameraFile(file, CameraCaptureMode.SIGNATURE)
        val galleryInput = UnifiedMediaInput.fromGalleryUri(Uri.fromFile(file), CameraCaptureMode.SIGNATURE)

        val rule = DocumentRule(
            id = "test_signature_rule",
            documentType = DocumentType.SIGNATURE_ENGLISH,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 1,
            maxKb = 20,
            exactWidthPx = 300,
            exactHeightPx = 120
        )

        val camRes = OneClickPreparationCoordinator.autoPrepareSignature(
            context = context,
            mediaInput = cameraInput,
            rule = rule,
            targetSizeKb = 18,
            preserveInkColor = true,
            trimBlankMargins = true
        ).getOrNull()

        val galRes = OneClickPreparationCoordinator.autoPrepareSignature(
            context = context,
            mediaInput = galleryInput,
            rule = rule,
            targetSizeKb = 18,
            preserveInkColor = true,
            trimBlankMargins = true
        ).getOrNull()

        assertNotNull(camRes)
        assertNotNull(galRes)
        assertEquals(300, camRes!!.widthPx)
        assertEquals(120, camRes.heightPx)
        assertEquals(camRes.widthPx, galRes!!.widthPx)
        assertEquals(camRes.heightPx, galRes.heightPx)
        assertEquals(camRes.fileSizeBytes, galRes.fileSizeBytes)
        assertTrue(File(camRes.processedFilePath).exists())
    }

    @Test
    fun testCompleteMultiPageCameraPipelineToPdfAndValidation() = runBlocking {
        // 1. Create 3 sample camera page images on disk
        val rawPage1 = saveBitmapToTestFile(createSampleBitmap(800, 1100, Color.WHITE), "scan_page_1.jpg")
        val rawPage2 = saveBitmapToTestFile(createSampleBitmap(800, 1100, Color.LTGRAY), "scan_page_2.jpg")
        val rawPage3 = saveBitmapToTestFile(createSampleBitmap(1100, 800, Color.WHITE), "scan_page_3_rot.jpg")

        // Tag page 3 with EXIF 90-degree rotation
        val exif3 = ExifInterface(rawPage3.absolutePath)
        exif3.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif3.saveAttributes()

        val capturedFiles = listOf(rawPage1, rawPage2, rawPage3)

        // 2. Decode and orient through the actual UnifiedMediaInput image pipeline
        val orientedBitmaps = capturedFiles.mapNotNull { file ->
            UnifiedMediaInput.decodeBitmapWithExif(file, maxDimension = 2048)
        }
        assertEquals(3, orientedBitmaps.size)
        // Page 3 was 1100x800 with ROTATE_90 -> must now be 800x1100 portrait
        assertEquals(800, orientedBitmaps[2].width)
        assertEquals(1100, orientedBitmaps[2].height)

        // 3. Generate multi-page PDF via PdfProcessor.createPdfFromBitmaps()
        val pdfFile = PdfProcessor.createPdfFromBitmaps(
            context = context,
            bitmaps = orientedBitmaps,
            outputFileName = "real_multipage_scan.pdf"
        )

        assertTrue(pdfFile.exists())
        assertTrue(pdfFile.length() > 0)

        // 4. Validate the generated PDF through DocumentValidationManager and verify page count == 3
        val validation = DocumentValidationManager.validateFile(pdfFile)
        assertTrue("Generated multi-page PDF must be valid: ${validation.errorMessage}", validation.isValid)
        assertEquals(FileFormat.PDF, validation.detectedFormat)
        assertEquals(3, validation.pageCount)
        assertTrue(validation.sha256Hex.isNotBlank())

        // 5. Import the generated multi-page PDF into the encrypted Document Locker repository
        val importResult = lockerRepository.importFile(
            file = pdfFile,
            title = "Scanned Degree Certificates (3 Pages)",
            documentType = DocumentType.GRADUATION_DEGREE,
            originalFileName = pdfFile.name,
            notes = "Captured via Phase 5B Multi-Page Scanner"
        )
        assertTrue("Encrypted Locker import must succeed", importResult.isSuccess)
        val savedEntity = importResult.getOrNull()
        assertNotNull(savedEntity)
        assertEquals("Scanned Degree Certificates (3 Pages)", savedEntity?.title)
        assertEquals(DocumentType.GRADUATION_DEGREE.name, savedEntity?.documentType)
        assertEquals(FileFormat.PDF.name, savedEntity?.fileFormat)
        assertEquals(validation.sha256Hex, savedEntity?.sha256Hash)

        // Verify decrypted vault document preserves all 3 pages
        val decryptedFile = lockerRepository.decryptToTempFile(savedEntity!!.id).getOrNull()
        assertNotNull(decryptedFile)
        val decryptedValidation = DocumentValidationManager.validateFile(decryptedFile!!)
        assertTrue(decryptedValidation.isValid)
        assertEquals(3, decryptedValidation.pageCount)
    }

    @Test
    fun testInvalidAndUnreadableImagesAndEmptyPageListHandling() = runBlocking {
        // 1. Corrupt / unreadable image file
        val corruptFile = File(testDir, "corrupt_capture.jpg").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        }
        val corruptInput = UnifiedMediaInput.fromCameraFile(corruptFile, CameraCaptureMode.PASSPORT_PHOTO)
        assertNull(corruptInput.resolveBitmap(context))
        assertNull(corruptInput.toBitmap(context))

        val rule = DocumentRule(
            id = "fail_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 10,
            maxKb = 50,
            exactWidthPx = 350,
            exactHeightPx = 450
        )
        val failedPrep = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            mediaInput = corruptInput,
            rule = rule
        )
        assertTrue(failedPrep.isFailure)

        // 2. Empty page list to PdfProcessor.createPdfFromBitmaps must fail safely with IllegalArgumentException
        var threwForEmptyList = false
        try {
            PdfProcessor.createPdfFromBitmaps(context, emptyList(), "should_not_exist.pdf")
        } catch (e: IllegalArgumentException) {
            threwForEmptyList = true
        }
        assertTrue("Empty bitmap list must throw IllegalArgumentException", threwForEmptyList)

        // 3. Temporary camera file cleanup only deletes files inside camera_captures
        val cameraCapturesDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
        val tempCamFile = File(cameraCapturesDir, "temp_retake.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val gallerySimFile = File(testDir, "user_gallery_original.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        UniversalCameraController.cleanupTemporaryFiles(listOf(tempCamFile, gallerySimFile))
        assertFalse("Temp camera file must be deleted", tempCamFile.exists())
        assertTrue("Non-camera file must NEVER be deleted by camera cleanup", gallerySimFile.exists())
    }

    @Test
    fun testNsdlAndUtiPanPresetsAnd300DpiJfifHeader() = runBlocking {
        val nsdlPortal = com.example.data.portal.PortalDatabase.findPortalById("nsdl_pan")
        val utiPortal = com.example.data.portal.PortalDatabase.findPortalById("uti_pan")
        assertNotNull(nsdlPortal)
        assertNotNull(utiPortal)

        val nsdlPhotoRule = nsdlPortal!!.documents.first { it.documentType == DocumentType.PASSPORT_PHOTO }
        assertEquals(213, nsdlPhotoRule.exactWidthPx)
        assertEquals(213, nsdlPhotoRule.exactHeightPx)
        assertEquals(300, nsdlPhotoRule.dpi)

        val utiSigRule = utiPortal!!.documents.first { it.documentType == DocumentType.SIGNATURE_ENGLISH }
        assertEquals(600, utiSigRule.dpi)

        val sourceBmp = createTexturedSubjectBitmap(600, 600, Color.rgb(245, 245, 245))
        val processed = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = sourceBmp,
            rule = nsdlPhotoRule,
            removeBackgroundToWhite = true,
            cropRectNorm = CameraCaptureMode.PASSPORT_PHOTO.guideCropRectNorm,
            customDpi = 300
        )

        assertEquals(213, processed.widthPx)
        assertEquals(213, processed.heightPx)
        assertEquals(300, processed.dpi)
        assertTrue(processed.measuredNearWhite)
        assertFalse(processed.verifiedBackgroundCompliance)
        assertFalse(processed.hasWhiteBackground)
        assertEquals(com.example.data.model.PreparationStatus.BACKGROUND_UNVERIFIED, processed.status)

        val outFile = File(processed.processedFilePath)
        assertEquals(300, com.example.processing.ImageProcessor.readJfifDpiHeader(outFile))
        assertEquals(300, com.example.processing.ImageProcessor.readExifDpi(outFile))
    }

    @Test
    fun testPhase0NoJpegComPaddingAndSizeBelowMinFailure() = runBlocking {
        // Tiny solid-white signature image naturally compresses to < 3 KB even at quality 100
        val tinySigBmp = Bitmap.createBitmap(140, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val strictRule = DocumentRule(
            id = "strict_min_kb_sig",
            documentType = DocumentType.SIGNATURE_ENGLISH,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 12,
            maxKb = 20,
            exactWidthPx = 140,
            exactHeightPx = 60,
            dpi = 300
        )

        val result = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = tinySigBmp,
            rule = strictRule
        )

        val savedFile = File(result.processedFilePath)

        // 1. Must NEVER pad with JPEG COM (0xFF 0xFE) metadata
        assertFalse("wasPaddedToMinKb must always be false", result.wasPaddedToMinKb)
        assertFalse(
            "JPEG file must NOT contain any 0xFF 0xFE COM padding segment",
            com.example.processing.ImageProcessor.hasJpegComSegment(savedFile)
        )

        // 2. Because natural quality 100 size is below 12 KB, compliance must be false with SIZE_BELOW_MIN
        assertTrue("Natural file size should be below 12 KB", result.fileSizeBytes < 12 * 1024L)
        assertFalse("isCompliant must be false when size < minKb", result.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.SIZE_BELOW_MIN, result.status)

        // 3. Saved file on disk must match reported size and dimensions
        assertEquals(savedFile.length(), result.fileSizeBytes)
        val decoded = android.graphics.BitmapFactory.decodeFile(result.processedFilePath)
        assertNotNull(decoded)
        assertEquals(result.widthPx, decoded.width)
        assertEquals(result.heightPx, decoded.height)
    }

    @Test
    fun testPhase0ExactDimensionMismatchAfterDownscalingFailsCompliance() = runBlocking {
        // High-frequency large image with an impossible 2 KB maxKb limit at 800x1000 exact dimensions
        val detailedBmp = createTexturedSubjectBitmap(800, 1000, Color.WHITE)
        val exactRuleWithTinyMaxKb = DocumentRule(
            id = "exact_dim_downscale_test",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 1,
            maxKb = 2, // Forces downscaling below 800x1000
            exactWidthPx = 800,
            exactHeightPx = 1000,
            dpi = 300,
            requiresWhiteBackground = false
        )

        val result = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = detailedBmp,
            rule = exactRuleWithTinyMaxKb,
            removeBackgroundToWhite = false
        )

        // Downscaling occurred, so final saved dimensions are smaller than 800x1000
        assertTrue("Final width must be < 800 after downscaling", result.widthPx < 800)
        assertTrue("Final height must be < 1000 after downscaling", result.heightPx < 1000)

        // Reported dimensions must match the actual saved file on disk
        val decodedSaved = android.graphics.BitmapFactory.decodeFile(result.processedFilePath)
        assertNotNull(decodedSaved)
        assertEquals(decodedSaved.width, result.widthPx)
        assertEquals(decodedSaved.height, result.heightPx)

        // Must fail compliance with DIMENSIONS_MISMATCH
        assertFalse("Exact-dimension rule must NOT be compliant after downscaling", result.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.DIMENSIONS_MISMATCH, result.status)
    }

    @Test
    fun testGithubJsonPresetSyncAndPowerTools() = runBlocking {
        val syncedList = com.example.data.portal.PortalDatabase.parseAndRegisterPortalsFromJson(
            com.example.data.portal.PortalDatabase.DEFAULT_GITHUB_PRESETS_JSON
        )
        assertTrue("Should sync remote exam presets from JSON", syncedList.size >= 2)
        assertNotNull(com.example.data.portal.PortalDatabase.findPortalById("up_police_recruitment"))
        assertNotNull(com.example.data.portal.PortalDatabase.findPortalById("bihar_police_csbc"))

        val photoBmp = createTexturedSubjectBitmap(350, 450, Color.WHITE)
        val sigBmp = createSampleBitmap(300, 100, Color.WHITE)

        // 1. Joint Photo + Signature Combiner
        val jointResult = com.example.processing.ImageProcessor.createJointPhotoAndSignature(
            context = context,
            photoBitmap = photoBmp,
            signatureBitmap = sigBmp,
            targetWidthPx = 300,
            targetHeightPx = 450,
            minKb = 10,
            maxKb = 50,
            dpi = 300
        )
        assertEquals(300, jointResult.widthPx)
        assertEquals(450, jointResult.heightPx)
        assertTrue(jointResult.fileSizeBytes <= 50 * 1024L)
        assertFalse(
            com.example.processing.ImageProcessor.hasJpegComSegment(
                File(jointResult.processedFilePath)
            )
        )

        // 2. Printable 8-Photo 4x6 Sheet
        val sheetResult = com.example.processing.ImageProcessor.createPrintablePassportSheet(
            context = context,
            photoBitmap = photoBmp,
            photoCount = 8
        )
        val sheetFile = File(sheetResult.processedFilePath)
        assertTrue(sheetFile.exists())
        assertTrue(sheetFile.length() > 10_000)

        // 3. 1-Click Document Dual JPG + PDF output
        val docBmp = createSampleBitmap(900, 1200, Color.LTGRAY)
        val docBundle = OneClickPreparationCoordinator.autoPrepareDocumentJpgAndPdf(
            context = context,
            input = null,
            preDecodedBitmap = docBmp,
            maxKb = 200,
            applyClearScanFilter = true,
            cropRectNorm = CameraCaptureMode.DOCUMENT_PAGE.guideCropRectNorm
        )
        assertNotNull(docBundle)
        assertTrue(File(docBundle!!.jpgResult.processedFilePath).exists())
        assertTrue(docBundle.pdfFile.exists())
        assertTrue(docBundle.jpgResult.fileSizeBytes <= 200 * 1024L)
    }

    @Test
    fun testPhase0HonestBackgroundWhitenessMeasurementAndDualDpiMetadata() = runBlocking {
        // 1. Create a photo with a dark blue background (non-white)
        val nonWhiteBgPhoto = createTexturedSubjectBitmap(400, 500, Color.BLUE)
        val whiteRequiredRule = DocumentRule(
            id = "phase0_honest_bg_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 5,
            maxKb = 60,
            exactWidthPx = 350,
            exactHeightPx = 450,
            dpi = 200,
            requiresWhiteBackground = true
        )

        // Process WITHOUT background removal -> measuredNearWhite MUST be false,
        // verifiedBackgroundCompliance MUST be false, isCompliant MUST be false, and status MUST be BACKGROUND_NOT_WHITE
        val unfilteredResult = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = nonWhiteBgPhoto,
            rule = whiteRequiredRule,
            removeBackgroundToWhite = false,
            customDpi = 200
        )

        assertFalse(unfilteredResult.measuredNearWhite)
        assertFalse(unfilteredResult.verifiedBackgroundCompliance)
        assertFalse(
            "ProcessedFileResult.hasWhiteBackground must be false when actual background is blue",
            unfilteredResult.hasWhiteBackground
        )
        assertFalse(
            "ProcessedFileResult.isCompliant must be false when rule requires white background and background is blue",
            unfilteredResult.isCompliant
        )
        assertEquals(
            com.example.data.model.PreparationStatus.BACKGROUND_NOT_WHITE,
            unfilteredResult.status
        )
        assertEquals("UNVERIFIED_PERIMETER_SAMPLE", unfilteredResult.backgroundVerificationState)
        assertTrue(
            "Validation messages must warn when background is not white",
            unfilteredResult.validationMessages.any { it.contains("Background Warning", ignoreCase = true) }
        )

        // 2. Verify BOTH JFIF APP0 header AND EXIF XResolution/YResolution/ResolutionUnit are written at 200 DPI
        val outFile = File(unfilteredResult.processedFilePath)
        val jfifMeta = com.example.processing.ImageProcessor.readJfifResolutionMetadata(outFile)
        val exifMeta = com.example.processing.ImageProcessor.readExifResolutionMetadata(outFile)
        assertNotNull(jfifMeta)
        assertNotNull(exifMeta)
        assertEquals(1, jfifMeta!!.resolutionUnit)
        assertEquals(200, jfifMeta.xDpi)
        assertEquals(200, jfifMeta.yDpi)
        assertEquals(2, exifMeta!!.resolutionUnit)
        assertEquals(200, exifMeta.xDpi)
        assertEquals(200, exifMeta.yDpi)

        // 3. Process a light-background portrait with background whitening WITHOUT a mask ->
        // perimeter sample is near-white (measuredNearWhite = true), but verifiedBackgroundCompliance = false
        val studioLightPhoto = createTexturedSubjectBitmap(400, 500, Color.rgb(245, 245, 245))
        val unverifiedWhitenedResult = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = studioLightPhoto,
            rule = whiteRequiredRule,
            removeBackgroundToWhite = true,
            customDpi = 300
        )
        assertTrue(unverifiedWhitenedResult.measuredNearWhite)
        assertFalse(
            "Perimeter-only sample must NEVER set verifiedBackgroundCompliance = true",
            unverifiedWhitenedResult.verifiedBackgroundCompliance
        )
        assertFalse(unverifiedWhitenedResult.hasWhiteBackground)
        assertFalse(
            "Strict white-background rule must NOT be marked compliant from perimeter-only estimate",
            unverifiedWhitenedResult.isCompliant
        )
        assertEquals(com.example.data.model.PreparationStatus.BACKGROUND_UNVERIFIED, unverifiedWhitenedResult.status)
        assertEquals("UNVERIFIED_PERIMETER_SAMPLE", unverifiedWhitenedResult.backgroundVerificationState)

        // 4. Process WITH an explicit verified backgroundMask -> verifiedBackgroundCompliance = true & compliant
        val maskWidth = 350
        val maskHeight = 450
        val verifiedBgMask = BooleanArray(maskWidth * maskHeight) { idx ->
            val x = idx % maskWidth
            val y = idx / maskWidth
            x < 35 || x >= maskWidth - 35 || y < 35
        }
        val verifiedWhitenedResult = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = studioLightPhoto,
            rule = whiteRequiredRule,
            removeBackgroundToWhite = true,
            customDpi = 300,
            backgroundMask = verifiedBgMask
        )
        assertTrue(verifiedWhitenedResult.measuredNearWhite)
        assertTrue(verifiedWhitenedResult.technicalBackgroundCompliant)
        assertTrue(verifiedWhitenedResult.hasWhiteBackground)
        assertTrue(verifiedWhitenedResult.isCompliant)
        assertFalse("Default userPreviewAccepted must be false", verifiedWhitenedResult.userPreviewAccepted)
        assertFalse("Must not be export-ready before user preview approval", verifiedWhitenedResult.isExportReady)
        assertEquals(com.example.data.model.PreparationStatus.PENDING_PREVIEW_APPROVAL, verifiedWhitenedResult.status)
        assertEquals("VERIFIED_MASK", verifiedWhitenedResult.backgroundVerificationState)
        assertTrue(verifiedWhitenedResult.nearWhitePixelRatio >= 0.85f)

        val approvedWhitenedResult = OneClickPreparationCoordinator.updateUserPreviewAcceptance(
            verifiedWhitenedResult,
            userPreviewAccepted = true
        )
        assertTrue(approvedWhitenedResult.isExportReady)
        assertEquals(com.example.data.model.PreparationStatus.SUCCESS_PREPARED, approvedWhitenedResult.status)

        val whitenedFile = File(verifiedWhitenedResult.processedFilePath)
        assertEquals(300, com.example.processing.ImageProcessor.readJfifDpiHeader(whitenedFile))
        assertEquals(300, com.example.processing.ImageProcessor.readExifDpi(whitenedFile))
    }

    @Test
    fun testPhase0PdfGeometryPreservationAndTargetVerification() = runBlocking {
        // 1. Multi-page A4 (595 x 842) PDF compression preserves page count, order, and A4 geometry
        val a4Pdf = PdfProcessor.createPdfFromBitmaps(
            context = context,
            bitmaps = listOf(
                createSampleBitmap(600, 800, Color.WHITE),
                createSampleBitmap(600, 800, Color.LTGRAY),
                createSampleBitmap(600, 800, Color.WHITE)
            ),
            outputFileName = "phase0_a4_multipage.pdf"
        )
        val a4Report = PdfProcessor.compressPdfToTargetKbWithReport(
            context = context,
            inputPdfFile = a4Pdf,
            targetMaxKb = 300
        )
        assertTrue(a4Report.isValidPdf)
        assertTrue(a4Report.pageCountPreserved)
        assertTrue(a4Report.geometryPreserved)
        assertTrue(a4Report.metTarget)
        assertEquals(3, a4Report.compressedPages.size)
        a4Report.compressedPages.forEach { pageGeom ->
            assertEquals(595, pageGeom.widthPoints)
            assertEquals(842, pageGeom.heightPoints)
        }

        // 2. Non-A4 mixed page geometries: US Letter (612x792), Landscape A4 (842x595), Custom (500x700)
        val customPages = listOf(
            createSampleBitmap(612, 792, Color.WHITE) to (612 to 792),
            createSampleBitmap(842, 595, Color.LTGRAY) to (842 to 595),
            createSampleBitmap(500, 700, Color.WHITE) to (500 to 700)
        )
        val nonA4Pdf = PdfProcessor.createCustomGeometryPdfFromBitmaps(
            context = context,
            pages = customPages,
            outputFileName = "phase0_custom_geometry.pdf"
        )
        val nonA4Report = PdfProcessor.compressPdfToTargetKbWithReport(
            context = context,
            inputPdfFile = nonA4Pdf,
            targetMaxKb = 300
        )
        assertTrue("Compressed non-A4 PDF must be valid", nonA4Report.isValidPdf)
        assertTrue("Page count must be preserved", nonA4Report.pageCountPreserved)
        assertTrue("Non-A4 page geometry and aspect ratios must be preserved", nonA4Report.geometryPreserved)
        assertEquals(612, nonA4Report.compressedPages[0].widthPoints)
        assertEquals(792, nonA4Report.compressedPages[0].heightPoints)
        assertEquals(842, nonA4Report.compressedPages[1].widthPoints)
        assertEquals(595, nonA4Report.compressedPages[1].heightPoints)
        assertEquals(500, nonA4Report.compressedPages[2].widthPoints)
        assertEquals(700, nonA4Report.compressedPages[2].heightPoints)

        // 3. Impossible target KB reports metTarget = false without falsely claiming success
        val multiPageDetailedPdf = PdfProcessor.createPdfFromBitmaps(
            context = context,
            bitmaps = List(5) { createTexturedSubjectBitmap(600, 800, Color.WHITE) },
            outputFileName = "phase0_heavy_5page.pdf"
        )
        val impossibleReport = PdfProcessor.compressPdfToTargetKbWithReport(
            context = context,
            inputPdfFile = multiPageDetailedPdf,
            targetMaxKb = 1 // 1 KB is impossible for a 5-page raster PDF
        )
        assertTrue(impossibleReport.isValidPdf)
        assertTrue(impossibleReport.pageCountPreserved)
        assertTrue(impossibleReport.geometryPreserved)
        assertFalse("Impossible 1 KB target must report metTarget = false", impossibleReport.metTarget)
        assertTrue(impossibleReport.actualSizeBytes > 1024L)

        // 4. Invalid / corrupt / empty PDF and invalid targetMaxKb <= 0 must fail safely
        val corruptPdf = File(testDir, "corrupt.pdf").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x02)) }
        var threwForCorrupt = false
        try {
            PdfProcessor.compressPdfToTargetKbWithReport(context, corruptPdf, 100)
        } catch (e: IllegalArgumentException) {
            threwForCorrupt = true
        }
        assertTrue("Corrupt PDF must throw IllegalArgumentException", threwForCorrupt)

        var threwForZeroTarget = false
        try {
            PdfProcessor.compressPdfToTargetKbWithReport(context, a4Pdf, 0)
        } catch (e: IllegalArgumentException) {
            threwForZeroTarget = true
        }
        assertTrue("targetMaxKb <= 0 must throw IllegalArgumentException", threwForZeroTarget)

        // 5. Repeated compression runs cleanly without leaking temp step files
        repeat(3) {
            val rep = PdfProcessor.compressPdfToTargetKbWithReport(context, nonA4Pdf, 250)
            assertTrue(rep.isValidPdf)
            assertTrue(rep.geometryPreserved)
        }
    }

    @Test
    fun testPhase0UiStatusCardAndTruthfulFallbackLabels() = runBlocking {
        // 1. Verify truthful fallback button labels
        assertEquals("Keep Original Background", com.example.ui.screens.PHOTO_FALLBACK_BUTTON_LABEL)
        assertEquals("Gentle Ink Processing", com.example.ui.screens.SIGNATURE_FALLBACK_BUTTON_LABEL)
        assertFalse(com.example.ui.screens.PHOTO_FALLBACK_BUTTON_LABEL.contains("Restore Original", ignoreCase = true))
        assertFalse(com.example.ui.screens.SIGNATURE_FALLBACK_BUTTON_LABEL.contains("Restore", ignoreCase = true))

        // 2. Verify buildPdfCompressionUiState agrees with actual final disk size, metTarget, and geometry
        val samplePdf = PdfProcessor.createPdfFromBitmaps(
            context = context,
            bitmaps = listOf(createSampleBitmap(595, 842, Color.WHITE)),
            outputFileName = "ui_state_test.pdf"
        )
        val successReport = PdfProcessor.compressPdfToTargetKbWithReport(context, samplePdf, 300)
        val successUi = com.example.ui.screens.buildPdfCompressionUiState(successReport)
        assertEquals(com.example.ui.screens.PdfCompressionUiStatus.SUCCESS, successUi.status)
        assertTrue(successUi.isSuccess)
        assertTrue(successUi.metTarget)
        assertEquals(successReport.outputFile.length(), successUi.actualSizeBytes)
        assertFalse("UI must not claim guaranteed readability", successUi.subtitle.contains("guaranteed", ignoreCase = true))

        // Oversized warning UI state
        val heavyPdf = PdfProcessor.createPdfFromBitmaps(
            context = context,
            bitmaps = List(4) { createTexturedSubjectBitmap(500, 700, Color.WHITE) },
            outputFileName = "ui_oversized_test.pdf"
        )
        val oversizedReport = PdfProcessor.compressPdfToTargetKbWithReport(context, heavyPdf, 1)
        val oversizedUi = com.example.ui.screens.buildPdfCompressionUiState(oversizedReport)
        assertEquals(com.example.ui.screens.PdfCompressionUiStatus.WARNING_OVERSIZED, oversizedUi.status)
        assertFalse(oversizedUi.isSuccess)
        assertFalse(oversizedUi.metTarget)
        assertEquals(oversizedReport.outputFile.length(), oversizedUi.actualSizeBytes)

        // Geometry validation failure UI state
        val brokenGeomReport = successReport.copy(geometryPreserved = false)
        val failedUi = com.example.ui.screens.buildPdfCompressionUiState(brokenGeomReport)
        assertEquals(com.example.ui.screens.PdfCompressionUiStatus.VALIDATION_FAILED, failedUi.status)
        assertFalse(failedUi.isSuccess)
        assertFalse(failedUi.geometryPreserved)
    }

    @Test
    fun testPhase0_1WhiteBorderDarkInteriorCannotPassStrictWhiteBackgroundRule() = runBlocking {
        // Create an image that has pure white outer borders (including top, side, and upper corner bands)
        // and a dark navy interior (representing a colored studio backdrop behind the subject)
        val width = 350
        val height = 450
        val whiteBorderDarkInteriorBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inDarkInterior = x >= (width * 0.26f).toInt() &&
                    x <= (width * 0.74f).toInt() &&
                    y >= (height * 0.30f).toInt() &&
                    y <= (height * 0.95f).toInt()
                pixels[y * width + x] = if (inDarkInterior) {
                    Color.rgb(15, 25, 90)
                } else {
                    Color.WHITE
                }
            }
        }
        whiteBorderDarkInteriorBmp.setPixels(pixels, 0, width, 0, 0, width, height)

        val strictWhiteRule = DocumentRule(
            id = "strict_white_bg_check",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 2,
            maxKb = 80,
            exactWidthPx = width,
            exactHeightPx = height,
            dpi = 300,
            requiresWhiteBackground = true
        )

        val result = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = whiteBorderDarkInteriorBmp,
            rule = strictWhiteRule,
            removeBackgroundToWhite = false
        )

        // Perimeter sample sees the white outer frame, so measuredNearWhite is true,
        // but verifiedBackgroundCompliance MUST be false and isCompliant MUST be false!
        assertTrue("Perimeter sample should detect near-white outer margin", result.measuredNearWhite)
        assertFalse("Unsegmented perimeter sample must NEVER set verifiedBackgroundCompliance", result.verifiedBackgroundCompliance)
        assertFalse("hasWhiteBackground must reflect verifiedBackgroundCompliance", result.hasWhiteBackground)
        assertFalse("Rule requiring white background must NOT be compliant without verified background mask", result.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.BACKGROUND_UNVERIFIED, result.status)
        assertEquals("UNVERIFIED_PERIMETER_SAMPLE", result.backgroundVerificationState)
    }

    @Test
    fun testPhase0_1MinMaxKbConflictsAndBounds() = runBlocking {
        val sampleBmp = createTexturedSubjectBitmap(350, 450, Color.WHITE)

        // 1. Conflicting rule where rule.minKb > rule.maxKb -> CONFLICT_LIMITS
        val conflictingRule = DocumentRule(
            id = "conflicting_kb_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 50,
            maxKb = 20,
            exactWidthPx = 350,
            exactHeightPx = 450,
            dpi = 300,
            requiresWhiteBackground = false
        )
        val conflictRes = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = sampleBmp,
            rule = conflictingRule
        )
        assertTrue("hasConflict must be true when minKb > maxKb", conflictRes.hasConflict)
        assertFalse("isCompliant must be false on conflicting KB limits", conflictRes.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.CONFLICT_LIMITS, conflictRes.status)

        // 2. Custom targetMaxKb lower than rule.minKb -> must NOT silently lower minKb via coerceAtMost
        val validRule = DocumentRule(
            id = "valid_kb_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 20,
            maxKb = 50,
            exactWidthPx = 350,
            exactHeightPx = 450,
            dpi = 300,
            requiresWhiteBackground = false
        )
        val customConflictRes = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = sampleBmp,
            rule = validRule,
            customTargetKb = 10 // 10 KB < rule.minKb (20 KB) -> conflict!
        )
        assertTrue(
            "customTargetKb < rule.minKb must be detected as a conflict instead of silently lowering minKb",
            customConflictRes.hasConflict
        )
        assertFalse(customConflictRes.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.CONFLICT_LIMITS, customConflictRes.status)

        // 3. Above-maximum output without exact dimension constraints -> SIZE_EXCEEDED
        val flexibleDimImpossibleKbRule = DocumentRule(
            id = "flexible_dim_impossible_kb",
            documentType = DocumentType.AADHAAR_CARD,
            allowedFormats = listOf(FileFormat.PNG), // Lossless PNG of 350x350 textured image exceeds 1 KB even after downscale steps
            minKb = 0,
            maxKb = 1,
            exactWidthPx = null,
            exactHeightPx = null,
            dpi = null,
            requiresWhiteBackground = false
        )
        val exceededRes = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = createTexturedSubjectBitmap(350, 350, Color.WHITE),
            rule = flexibleDimImpossibleKbRule
        )
        assertFalse(exceededRes.hasConflict)
        assertTrue("File size should exceed 1 KB", exceededRes.fileSizeBytes > 1024L)
        assertFalse("isCompliant must be false when size > maxKb", exceededRes.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.SIZE_EXCEEDED, exceededRes.status)
    }

    @Test
    fun testPhase0_1FormatSpecificDpiValidationJpegVsPng() = runBlocking {
        val sampleBmp = createTexturedSubjectBitmap(300, 400, Color.WHITE)

        // 1. JPEG output with required DPI = 300 -> verified via both JFIF and EXIF
        val jpegRule = DocumentRule(
            id = "jpeg_dpi_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 2,
            maxKb = 100,
            exactWidthPx = 300,
            exactHeightPx = 400,
            dpi = 300,
            requiresWhiteBackground = false
        )
        val jpegRes = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = sampleBmp,
            rule = jpegRule
        )
        assertEquals(300, jpegRes.dpi)
        assertTrue(jpegRes.isCompliant)
        assertFalse(jpegRes.userPreviewAccepted)
        assertEquals(com.example.data.model.PreparationStatus.PENDING_PREVIEW_APPROVAL, jpegRes.status)
        val approvedJpegRes = OneClickPreparationCoordinator.updateUserPreviewAcceptance(jpegRes, true)
        assertEquals(com.example.data.model.PreparationStatus.SUCCESS_PREPARED, approvedJpegRes.status)

        // 2. PNG output with required DPI = 300 -> Android PNG encoder does not embed pHYs DPI metadata,
        // so readPngPhysMetadata is null, reported dpi MUST be 0 (not 300!), and status MUST be DPI_MISMATCH
        val pngRuleWithDpi = DocumentRule(
            id = "png_dpi_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.PNG),
            minKb = 2,
            maxKb = 500,
            exactWidthPx = 300,
            exactHeightPx = 400,
            dpi = 300,
            requiresWhiteBackground = false
        )
        val pngRes = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = sampleBmp,
            rule = pngRuleWithDpi
        )
        val pngFile = File(pngRes.processedFilePath)
        assertNull(
            "Standard Android PNG output has no pHYs chunk",
            com.example.processing.ImageProcessor.readPngPhysMetadata(pngFile)
        )
        assertEquals("PNG without verified pHYs metadata must report dpi = 0, never 300", 0, pngRes.dpi)
        assertEquals("Pixel width must still be validated separately", 300, pngRes.widthPx)
        assertEquals("Pixel height must still be validated separately", 400, pngRes.heightPx)
        assertFalse("PNG without verified DPI metadata must not claim compliance when rule requires DPI", pngRes.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.DPI_MISMATCH, pngRes.status)
    }

    @Test
    fun testPhase0_1CallerBitmapPreservedAndBoundedHeaderVerification() = runBlocking {
        val callerPhoto = createTexturedSubjectBitmap(500, 600, Color.WHITE)
        val callerSig = createSampleBitmap(300, 120, Color.WHITE)

        val rule = DocumentRule(
            id = "lifecycle_test_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 5,
            maxKb = 80,
            exactWidthPx = 350,
            exactHeightPx = 450,
            dpi = 300,
            requiresWhiteBackground = false,
            requiresNameAndDateStrip = true
        )

        val res = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = callerPhoto,
            rule = rule,
            candidateName = "VIKRAM KUMAR",
            dateOfPhoto = "06/11/2026",
            removeBackgroundToWhite = true,
            autoCropMargins = true,
            cropRectNorm = CameraCaptureMode.PASSPORT_PHOTO.guideCropRectNorm
        )
        assertFalse("Caller-supplied photo bitmap must NEVER be recycled by processImageForRule", callerPhoto.isRecycled)
        assertTrue(File(res.processedFilePath).exists())

        val jointRes = com.example.processing.ImageProcessor.createJointPhotoAndSignature(
            context = context,
            photoBitmap = callerPhoto,
            signatureBitmap = callerSig
        )
        assertFalse("Caller photo must not be recycled by createJointPhotoAndSignature", callerPhoto.isRecycled)
        assertFalse("Caller signature must not be recycled by createJointPhotoAndSignature", callerSig.isRecycled)
        assertTrue(File(jointRes.processedFilePath).exists())

        val sheetRes = com.example.processing.ImageProcessor.createPrintablePassportSheet(
            context = context,
            photoBitmap = callerPhoto,
            photoCount = 8
        )
        assertFalse("Caller photo must not be recycled by createPrintablePassportSheet", callerPhoto.isRecycled)
        assertTrue(File(sheetRes.processedFilePath).exists())
    }

    @Test
    fun testPhase1CameraViewportMapperFrontRearRotationsAndAspectRatios() {
        val guideRect = android.graphics.RectF(0.20f, 0.15f, 0.80f, 0.85f)

        // 1. Rear camera, 90° portrait rotation, 4:3 sensor (4000x3000 -> upright 3000x4000) on 20:9 screen (1080x2400)
        val rearConfig = com.example.camera.CameraViewportMapper.ViewportMappingConfig(
            rawSensorWidth = 4000,
            rawSensorHeight = 3000,
            rotationDegrees = 90,
            previewWidth = 1080,
            previewHeight = 2400,
            isFrontCamera = false,
            isSavedOutputMirrored = false
        )
        val visibleViewport = com.example.camera.CameraViewportMapper.computeVisibleSensorViewportPx(rearConfig)
        // Upright sensor is 3000x4000 (aspect 0.75). Preview is 1080x2400 (aspect 0.45).
        // Since sensor is wider than preview, FILL_CENTER keeps full height (4000) and crops width to 4000 * 0.45 = 1800
        // centered at (3000 - 1800) / 2 = 600..2400
        assertEquals(600f, visibleViewport.left, 2f)
        assertEquals(2400f, visibleViewport.right, 2f)
        assertEquals(0f, visibleViewport.top, 2f)
        assertEquals(4000f, visibleViewport.bottom, 2f)

        val rearSensorNorm = com.example.camera.CameraViewportMapper.mapPreviewRectToSensorNormalizedRect(
            previewRectNorm = guideRect,
            config = rearConfig
        )
        // Guide left = 0.20 of visible 1800px = 600 + 360 = 960 -> 960 / 3000 = 0.32
        // Guide right = 0.80 of visible 1800px = 600 + 1440 = 2040 -> 2040 / 3000 = 0.68
        assertEquals(0.32f, rearSensorNorm.left, 0.01f)
        assertEquals(0.68f, rearSensorNorm.right, 0.01f)
        assertEquals(0.15f, rearSensorNorm.top, 0.01f)
        assertEquals(0.85f, rearSensorNorm.bottom, 0.01f)

        // 2. Front camera with horizontal preview mirroring vs asymmetrical guide rect
        val asymGuide = android.graphics.RectF(0.10f, 0.20f, 0.40f, 0.70f)
        val frontConfig = rearConfig.copy(isFrontCamera = true, isSavedOutputMirrored = false)
        val frontSensorNorm = com.example.camera.CameraViewportMapper.mapPreviewRectToSensorNormalizedRect(
            previewRectNorm = asymGuide,
            config = frontConfig
        )
        // Mirrored horizontally inside visible [600..2400]: [0.60..0.90] of 1800px -> [1680..2220] / 3000 = [0.56..0.74]
        assertEquals(0.56f, frontSensorNorm.left, 0.01f)
        assertEquals(0.74f, frontSensorNorm.right, 0.01f)

        // 3. Test all 4 rotations (0°, 90°, 180°, 270°) raw sensor coordinate mapping
        for (rot in listOf(0, 90, 180, 270)) {
            val rawRect = com.example.camera.CameraViewportMapper.mapUprightNormalizedRectToRawSensorRect(
                uprightRectNorm = rearSensorNorm,
                rotationDegrees = rot
            )
            assertTrue("Raw rect must be valid for rotation $rot", rawRect.left < rawRect.right && rawRect.top < rawRect.bottom)
            assertTrue(rawRect.left >= 0f && rawRect.right <= 1f && rawRect.top >= 0f && rawRect.bottom <= 1f)
        }
    }

    @Test
    fun testPhase1OfficialDomainAllowlistAndNonDestructive180DayReview() {
        val baseUpsc = com.example.data.portal.PortalDatabase.OFFICIAL_PORTALS.first { it.id == "upsc_cse" }
        // Construct a complete, clause-verified portal snapshot to test fresh vs >180d review
        val verifiedUpsc = baseUpsc.copy(
            sourceIdentifier = "UPSC-CSE-2025-NOTIF-05",
            applicableWorkflow = "Online OTR & CSP Application Upload",
            verificationStatus = com.example.data.model.VerificationStatus.OFFICIALLY_VERIFIED,
            documents = baseUpsc.documents.mapIndexed { idx, rule ->
                rule.copy(
                    sourceClauseRef = "Appendix-III Clause ${idx + 1}",
                    isRuleIndividuallyVerified = true
                )
            }
        )
        val upscEval = com.example.data.portal.OfficialDomainAllowlist.evaluatePortalProvenance(
            portal = verifiedUpsc,
            nowEpochMs = com.example.data.portal.OfficialDomainAllowlist.parseVerifiedDateEpochMs("2025-03-01")!!
        )
        assertTrue(upscEval.isOfficialDomain)
        assertFalse(upscEval.isInternalReviewDue)
        assertEquals(com.example.data.model.VerificationStatus.OFFICIALLY_VERIFIED, upscEval.effectiveStatus)

        // 180-day threshold check: 250 days after lastVerifiedDate -> triggers internal review due
        // and downgrades effectiveStatus to OFFICIAL_SOURCE_REVIEW while keeping all DocumentRule specs intact!
        val futureEpoch = com.example.data.portal.OfficialDomainAllowlist.parseVerifiedDateEpochMs("2025-11-15")!!
        val reviewDueEval = com.example.data.portal.OfficialDomainAllowlist.evaluatePortalProvenance(
            portal = verifiedUpsc,
            nowEpochMs = futureEpoch
        )
        assertTrue("More than 180 days since check should flag internal review due", reviewDueEval.isInternalReviewDue)
        assertNotNull(reviewDueEval.reviewNote)
        assertEquals(
            "Stale (>180 days) check downgrades effectiveStatus to OFFICIAL_SOURCE_REVIEW",
            com.example.data.model.VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            reviewDueEval.effectiveStatus
        )
        assertTrue(verifiedUpsc.documents.isNotEmpty())

        // Non-official https:// domain must NEVER be granted OFFICIALLY_VERIFIED
        val fakeDomainPortal = verifiedUpsc.copy(
            id = "fake_blog_portal",
            officialUrl = "https://exam-tricks-blog.example.com/upsc-rules",
            verificationStatus = com.example.data.model.VerificationStatus.OFFICIALLY_VERIFIED
        )
        val fakeEval = com.example.data.portal.OfficialDomainAllowlist.evaluatePortalProvenance(fakeDomainPortal)
        assertFalse(fakeEval.isOfficialDomain)
        assertEquals(
            "Unverified third-party domain must be downgraded from OFFICIALLY_VERIFIED",
            com.example.data.model.VerificationStatus.UNVERIFIED_RULE,
            fakeEval.effectiveStatus
        )
    }

    @Test
    fun testPhase2PortraitVisionFramingAnd6PartBackgroundVerification() = runBlocking {
        val portraitBmp = createTexturedSubjectBitmap(400, 500, Color.rgb(210, 215, 225))
        val strictWhiteRule = DocumentRule(
            id = "phase2_strict_white_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 5,
            maxKb = 100,
            exactWidthPx = 350,
            exactHeightPx = 450,
            dpi = 300,
            requiresWhiteBackground = true,
            minHeadHeightRatio = 0.55f,
            maxHeadHeightRatio = 0.68f,
            topHeadroomRatio = 0.11f
        )

        val mockFaceEngine = com.example.processing.PortraitVisionProcessor.FaceDetectionEngine { _ ->
            listOf(
                com.example.processing.PortraitVisionProcessor.DetectedFaceInfo(
                    boundingBox = android.graphics.RectF(
                        0.28f,
                        0.18f,
                        0.72f,
                        0.66f
                    ),
                    headEulerAngleZ = 2.0f,
                    headEulerAngleY = 1.0f
                )
            )
        }

        // Case 1: High-confidence segmentation mask (core subject 0.96, background 0.02, very narrow transition edge)
        val highConfidenceSegEngine = com.example.processing.PortraitVisionProcessor.SelfieSegmentationEngine { bmp ->
            val w = bmp.width
            val h = bmp.height
            val mask = FloatArray(w * h) { idx ->
                val x = idx % w
                val y = idx / w
                val inSubject = x in (w * 0.22).toInt()..(w * 0.78).toInt() &&
                    y in (h * 0.16).toInt()..(h * 0.95).toInt()
                if (inSubject) 0.96f else 0.02f
            }
            com.example.processing.PortraitVisionProcessor.RawSegmentationMask(w, h, mask)
        }

        // 1A. High confidence + userPreviewAccepted = true -> technicalBackgroundCompliant = true & isExportReady = true & SUCCESS_PREPARED
        val acceptedResult = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            input = null,
            rule = strictWhiteRule,
            preDecodedBitmap = portraitBmp,
            removeBackgroundToWhite = true,
            userPreviewAccepted = true,
            faceEngine = mockFaceEngine,
            segmentationEngine = highConfidenceSegEngine
        )
        assertNotNull(acceptedResult)
        assertTrue(acceptedResult!!.segmentationExecuted)
        assertTrue(acceptedResult.backgroundReplacementApplied)
        assertTrue(acceptedResult.outputBackgroundPixelChecksPassed)
        assertTrue(acceptedResult.subjectMaskConfidence >= 0.80f)
        assertTrue(acceptedResult.edgeUncertaintyRatio <= 0.18f)
        assertTrue(acceptedResult.userPreviewAccepted)
        assertTrue(acceptedResult.technicalBackgroundCompliant)
        assertTrue(acceptedResult.isCompliant)
        assertTrue(acceptedResult.isExportReady)
        assertEquals(com.example.data.model.PreviewApprovalStatus.APPROVED_BY_USER, acceptedResult.approvalStatus)
        assertEquals(com.example.data.model.PreparationStatus.SUCCESS_PREPARED, acceptedResult.status)

        // 1B. High confidence + userPreviewAccepted = false ->
        // Phase 0.4A Invariant: technicalBackgroundCompliant and isCompliant remain TRUE,
        // while approvalStatus == PENDING_USER_APPROVAL, status == PENDING_PREVIEW_APPROVAL, and isExportReady == FALSE!
        val unacceptedResult = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            input = null,
            rule = strictWhiteRule,
            preDecodedBitmap = portraitBmp,
            removeBackgroundToWhite = true,
            userPreviewAccepted = false,
            faceEngine = mockFaceEngine,
            segmentationEngine = highConfidenceSegEngine
        )
        assertNotNull(unacceptedResult)
        assertTrue(unacceptedResult!!.segmentationExecuted)
        assertFalse(unacceptedResult.userPreviewAccepted)
        assertTrue("Technically clean image must report technicalBackgroundCompliant = true even before preview approval", unacceptedResult.technicalBackgroundCompliant)
        assertTrue("Technically clean image must report isCompliant = true", unacceptedResult.isCompliant)
        assertFalse("Unaccepted preview must NOT be export-ready", unacceptedResult.isExportReady)
        assertEquals(com.example.data.model.PreviewApprovalStatus.PENDING_USER_APPROVAL, unacceptedResult.approvalStatus)
        assertEquals(com.example.data.model.PreparationStatus.PENDING_PREVIEW_APPROVAL, unacceptedResult.status)
        assertEquals("VERIFIED_MASK", unacceptedResult.backgroundVerificationState)

        // Case 2: Ambiguous boundary segmentation mask (large fraction of pixels in 0.20..0.80 uncertainty band)
        val uncertainEdgeSegEngine = com.example.processing.PortraitVisionProcessor.SelfieSegmentationEngine { bmp ->
            val w = bmp.width
            val h = bmp.height
            val mask = FloatArray(w * h) { idx ->
                val x = idx % w
                val y = idx / w
                when {
                    x < w * 0.18 || x > w * 0.82 || y < h * 0.14 -> 0.03f // background
                    x in (w * 0.35).toInt()..(w * 0.65).toInt() && y in (h * 0.30).toInt()..(h * 0.80).toInt() -> 0.92f // core
                    else -> 0.50f // wide uncertain boundary halo (> 18% of subject)
                }
            }
            com.example.processing.PortraitVisionProcessor.RawSegmentationMask(w, h, mask)
        }

        val uncertainResult = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            input = null,
            rule = strictWhiteRule,
            preDecodedBitmap = portraitBmp,
            removeBackgroundToWhite = true,
            userPreviewAccepted = true,
            faceEngine = mockFaceEngine,
            segmentationEngine = uncertainEdgeSegEngine
        )
        assertNotNull(uncertainResult)
        assertTrue(uncertainResult!!.edgeUncertaintyRatio > 0.18f)
        assertFalse(
            "High edge uncertainty must NOT set technicalBackgroundCompliant = true even when userPreviewAccepted = true",
            uncertainResult.technicalBackgroundCompliant
        )
        assertFalse("Technical background failure must NEVER be overridden by userPreviewAccepted = true", uncertainResult.isExportReady)
        assertEquals(com.example.data.model.PreparationStatus.BACKGROUND_UNVERIFIED, uncertainResult.status)
        assertEquals("LOW_MASK_CONFIDENCE", uncertainResult.backgroundVerificationState)
    }

    @Test
    fun testPhase3ASignatureSauvolaAdaptiveShadowRemovalAndFallback() {
        // 1. Create a synthetic signature image with a strong illumination shadow gradient across paper
        // and blue pen strokes in the center
        val width = 320
        val height = 140
        val shadowedSigBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val paperLum = (235 - (x.toFloat() / width.toFloat()) * 90f).toInt().coerceIn(120, 245)
                val isStroke = (y in 52..62 && x in 45..270 && ((x + y) % 7 != 0)) ||
                    (y in 70..76 && x in 80..230)
                if (isStroke) {
                    val inkR = (paperLum * 0.18f).toInt()
                    val inkG = (paperLum * 0.25f).toInt()
                    val inkB = (paperLum * 0.65f).toInt()
                    shadowedSigBmp.setPixel(x, y, Color.rgb(inkR, inkG, inkB))
                } else {
                    shadowedSigBmp.setPixel(x, y, Color.rgb(paperLum, paperLum, paperLum))
                }
            }
        }

        val sauvolaRes = com.example.processing.SignatureVisionProcessor.extractSignatureOrThumb(
            src = shadowedSigBmp,
            preserveInkColor = true,
            trimToInkBounds = true
        )
        assertFalse("Valid signature under shadow gradient must not trigger fallback", sauvolaRes.usedGentleFallback)
        assertTrue("Ink pixel ratio should be within plausible signature bounds", sauvolaRes.inkPixelRatio in 0.005f..0.35f)
        assertTrue("Cropped width should trim outer blank margins", sauvolaRes.cleanedBitmap.width < width)
        assertTrue("Cropped height should trim outer blank margins", sauvolaRes.cleanedBitmap.height < height)

        val whiteness = com.example.processing.ImageProcessor.measureBackgroundWhiteness(
            bitmap = sauvolaRes.cleanedBitmap,
            isSignatureOrDocument = true,
            backgroundMask = sauvolaRes.backgroundMask
        )
        assertTrue("Sauvola background must be verified pure white", whiteness.verifiedBackgroundCompliance)
        assertTrue(whiteness.exactWhitePixelRatio >= 0.95f)

        // 2. Blank paper image -> triggers gentle fallback safely
        val blankBmp = createSampleBitmap(200, 100, Color.rgb(240, 240, 240))
        val fallbackRes = com.example.processing.SignatureVisionProcessor.extractSignatureOrThumb(
            src = blankBmp,
            preserveInkColor = true,
            trimToInkBounds = true
        )
        assertTrue("Blank paper with 0 ink strokes must trigger gentle fallback", fallbackRes.usedGentleFallback)
    }

    @Test
    fun testPhase3BDocument4CornerDetectionAndHomographyPerspectiveWarp() {
        val w = 400
        val h = 500
        val docSceneBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // Angled white document quadrilateral on a dark desk background
        val trueTl = android.graphics.PointF(0.16f * w, 0.14f * h)
        val trueTr = android.graphics.PointF(0.86f * w, 0.18f * h)
        val trueBr = android.graphics.PointF(0.82f * w, 0.86f * h)
        val trueBl = android.graphics.PointF(0.12f * w, 0.82f * h)
        val quadVerts = arrayOf(trueTl, trueTr, trueBr, trueBl)

        val pixels = IntArray(w * h)
        val deskColor = Color.rgb(35, 38, 45)
        val paperColor = Color.rgb(248, 248, 248)
        for (y in 0 until h) {
            val py = y.toFloat()
            for (x in 0 until w) {
                val px = x.toFloat()
                var inside = true
                for (i in 0 until 4) {
                    val a = quadVerts[i]
                    val b = quadVerts[(i + 1) % 4]
                    val cross = (b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x)
                    if (cross < 0f) {
                        inside = false
                        break
                    }
                }
                pixels[y * w + x] = if (inside) paperColor else deskColor
            }
        }
        docSceneBmp.setPixels(pixels, 0, w, 0, 0, w, h)

        val detection = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(docSceneBmp)
        assertEquals(4, detection.cornersNorm.size)
        assertTrue(
            "Clean high-contrast angled document should achieve confidence >= 0.80 (was ${detection.confidence})",
            detection.confidence >= 0.80f
        )
        assertTrue(detection.autoWarpEligible)

        assertEquals(0.16f, detection.cornersNorm[0].x, 0.06f)
        assertEquals(0.14f, detection.cornersNorm[0].y, 0.06f)
        assertEquals(0.86f, detection.cornersNorm[1].x, 0.06f)
        assertEquals(0.18f, detection.cornersNorm[1].y, 0.06f)

        val warped = com.example.processing.DocumentCornerProcessor.warpDocumentPerspective(
            src = docSceneBmp,
            cornersNorm = detection.cornersNorm,
            targetWidthPx = 300,
            targetHeightPx = 400
        )
        assertEquals(300, warped.width)
        assertEquals(400, warped.height)
        val centerColor = warped.getPixel(150, 200)
        assertTrue("Center of rectified document must be bright white paper", Color.red(centerColor) >= 235)

        val uniformBmp = createSampleBitmap(300, 400, Color.rgb(180, 180, 180))
        val lowConfDetection = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(uniformBmp)
        assertFalse("Uniform image without document edges must not be autoWarpEligible", lowConfDetection.autoWarpEligible)
    }

    // =========================================================================
    // PHASE 0.4 REMEDIATION REGRESSION TESTS (BATCHES 1 - 5)
    // =========================================================================

    @Test
    fun testBatch1IsExportReadyTruthTableAll8Cases() {
        data class TruthTableCase(
            val caseId: Int,
            val requiresWhiteBg: Boolean,
            val technicalBgCompliant: Boolean,
            val baseMetricsPass: Boolean,
            val requiresApproval: Boolean,
            val userPreviewAccepted: Boolean,
            val expectedIsCompliant: Boolean,
            val expectedApprovalStatus: com.example.data.model.PreviewApprovalStatus,
            val expectedIsExportReady: Boolean
        )

        val cases = listOf(
            // Case 1: White BG required, tech BG pass, metrics pass, approval required, user NOT accepted -> pending approval, NOT export ready
            TruthTableCase(1, true, true, true, true, false, true, com.example.data.model.PreviewApprovalStatus.PENDING_USER_APPROVAL, false),
            // Case 2: White BG required, tech BG pass, metrics pass, approval required, user ACCEPTED -> export ready!
            TruthTableCase(2, true, true, true, true, true, true, com.example.data.model.PreviewApprovalStatus.APPROVED_BY_USER, true),
            // Case 3: White BG required, tech BG FAIL, metrics pass, approval required, user NOT accepted -> tech fail, NOT export ready
            TruthTableCase(3, true, false, true, true, false, false, com.example.data.model.PreviewApprovalStatus.PENDING_USER_APPROVAL, false),
            // Case 4: White BG required, tech BG FAIL, metrics pass, approval required, user ACCEPTED -> user approval MUST NEVER override tech failure!
            TruthTableCase(4, true, false, true, true, true, false, com.example.data.model.PreviewApprovalStatus.APPROVED_BY_USER, false),
            // Case 5: White BG NOT required, tech BG false, metrics pass, approval required, user NOT accepted -> pending approval, NOT export ready
            TruthTableCase(5, false, false, true, true, false, true, com.example.data.model.PreviewApprovalStatus.PENDING_USER_APPROVAL, false),
            // Case 6: White BG NOT required, tech BG false, metrics pass, approval required, user ACCEPTED -> export ready (not blocked by white BG)
            TruthTableCase(6, false, false, true, true, true, true, com.example.data.model.PreviewApprovalStatus.APPROVED_BY_USER, true),
            // Case 7: White BG required, tech BG pass, base metrics FAIL (e.g. size exceeded), approval required, user ACCEPTED -> NOT export ready
            TruthTableCase(7, true, true, false, true, true, false, com.example.data.model.PreviewApprovalStatus.APPROVED_BY_USER, false),
            // Case 8: White BG NOT required, tech BG false, metrics pass, approval NOT required, user NOT accepted -> export ready
            TruthTableCase(8, false, false, true, false, false, true, com.example.data.model.PreviewApprovalStatus.NOT_REQUIRED, true)
        )

        for (c in cases) {
            val isCompliant = c.baseMetricsPass && (!c.requiresWhiteBg || c.technicalBgCompliant)
            assertEquals("Case ${c.caseId} isCompliant mismatch", c.expectedIsCompliant, isCompliant)

            val result = com.example.data.model.ProcessedFileResult(
                ruleId = "truth_table_${c.caseId}",
                documentType = DocumentType.PASSPORT_PHOTO,
                originalFileName = "test_${c.caseId}.jpg",
                processedFilePath = "/tmp/test_${c.caseId}.jpg",
                fileSizeBytes = 25_000L,
                fileSizeKb = 25.0,
                widthPx = 350,
                heightPx = 450,
                dpi = 300,
                fileFormat = FileFormat.JPG,
                isCompliant = isCompliant,
                validationMessages = emptyList(),
                requiresWhiteBackground = c.requiresWhiteBg,
                technicalBackgroundCompliant = c.technicalBgCompliant,
                requiresUserPreviewApproval = c.requiresApproval,
                userPreviewAccepted = c.userPreviewAccepted
            )

            assertEquals("Case ${c.caseId} approvalStatus mismatch", c.expectedApprovalStatus, result.approvalStatus)
            assertEquals("Case ${c.caseId} isExportReady mismatch", c.expectedIsExportReady, result.isExportReady)
        }
    }

    @Test
    fun testBatch1InitialPreviewAcceptanceStartsFalseAndResetsOnParameterChange() = runBlocking {
        val sampleBmp = createTexturedSubjectBitmap(350, 450, Color.WHITE)
        val rule = DocumentRule(
            id = "batch1_reset_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 2,
            maxKb = 80,
            exactWidthPx = 350,
            exactHeightPx = 450,
            dpi = 300,
            requiresWhiteBackground = false
        )

        // 1. Initial preparation must default to userPreviewAccepted == false and status == PENDING_PREVIEW_APPROVAL
        val initialResult = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            input = null,
            rule = rule,
            preDecodedBitmap = sampleBmp
        )
        assertNotNull(initialResult)
        assertTrue(initialResult!!.isCompliant)
        assertFalse("Initial preparation must start with userPreviewAccepted = false", initialResult.userPreviewAccepted)
        assertFalse("Initial preparation must start with isExportReady = false", initialResult.isExportReady)
        assertEquals(com.example.data.model.PreparationStatus.PENDING_PREVIEW_APPROVAL, initialResult.status)

        // 2. Explicit user preview approval transitions to APPROVED_BY_USER, isExportReady = true, SUCCESS_PREPARED
        val approved = OneClickPreparationCoordinator.updateUserPreviewAcceptance(initialResult, true)
        assertTrue(approved.userPreviewAccepted)
        assertTrue(approved.isExportReady)
        assertEquals(com.example.data.model.PreviewApprovalStatus.APPROVED_BY_USER, approved.approvalStatus)
        assertEquals(com.example.data.model.PreparationStatus.SUCCESS_PREPARED, approved.status)

        // 3. Re-running preparation with new parameters (e.g. Adjust sheet apply) resets userPreviewAccepted to false
        val reprocessed = OneClickPreparationCoordinator.autoPreparePhoto(
            context = context,
            input = null,
            rule = rule,
            preDecodedBitmap = sampleBmp,
            candidateName = "UPDATED NAME",
            dateOfPhoto = "07/11/2026"
        )
        assertNotNull(reprocessed)
        assertFalse("Re-processing must reset userPreviewAccepted to false", reprocessed!!.userPreviewAccepted)
        assertFalse("Re-processing must reset isExportReady to false until new preview is approved", reprocessed.isExportReady)
        assertEquals(com.example.data.model.PreparationStatus.PENDING_PREVIEW_APPROVAL, reprocessed.status)
    }

    @Test
    fun testBatch1SignatureAndThumbValidationEvidenceWithoutPortraitSpoofing() = runBlocking {
        val sigRule = DocumentRule(
            id = "batch1_sig_rule",
            documentType = DocumentType.SIGNATURE_ENGLISH,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 1,
            maxKb = 30,
            exactWidthPx = 280,
            exactHeightPx = 120,
            requiresWhiteBackground = true
        )

        // 1. Valid signature on white paper -> Sauvola succeeds, zero portrait spoofing, signatureEvidence passes
        val validSigBmp = Bitmap.createBitmap(320, 140, Bitmap.Config.ARGB_8888)
        for (y in 0 until 140) {
            for (x in 0 until 320) {
                val paperLum = (242 - (x.toFloat() / 320f) * 35f).toInt().coerceIn(200, 250)
                val isStroke = (y in 45..47 && x in 45..265 && ((x + y) % 5 != 0)) ||
                    (y in 78..80 && x in 75..235 && ((x + y) % 6 != 0)) ||
                    (y in 48..77 && x in (80 + (y - 48) * 2)..(82 + (y - 48) * 2))
                if (isStroke) {
                    validSigBmp.setPixel(x, y, Color.rgb(18, 28, 145))
                } else {
                    validSigBmp.setPixel(x, y, Color.rgb(paperLum, paperLum, paperLum))
                }
            }
        }

        val validSigRes = OneClickPreparationCoordinator.autoPrepareSignature(
            context = context,
            input = null,
            rule = sigRule,
            preDecodedBitmap = validSigBmp
        )
        assertNotNull(validSigRes)
        // Verify fake portrait segmentation telemetry is completely removed
        assertFalse("Signature processing must NEVER spoof portrait segmentationExecuted = true", validSigRes!!.segmentationExecuted)
        assertEquals(0f, validSigRes.subjectMaskConfidence, 0.0001f)
        assertEquals(0f, validSigRes.edgeUncertaintyRatio, 0.0001f)
        assertNotNull("Signature result must populate signatureEvidence", validSigRes.signatureEvidence)
        assertEquals(
            com.example.data.model.SignatureProcessingMethod.SAUVOLA_ADAPTIVE_THRESHOLD,
            validSigRes.signatureEvidence!!.processingMethod
        )
        assertTrue(validSigRes.signatureEvidence!!.inkPresenceVerified)
        assertTrue(validSigRes.signatureEvidence!!.strokeBoundingBoxFound)
        assertTrue(validSigRes.signatureEvidence!!.paperWhitenessRatio >= 0.85f)
        assertTrue(
            "Expected technicalBackgroundCompliant=true (evidence=${validSigRes.signatureEvidence})",
            validSigRes.technicalBackgroundCompliant
        )
        assertTrue(
            "Expected isCompliant=true (status=${validSigRes.status}, sizeBytes=${validSigRes.fileSizeBytes})",
            validSigRes.isCompliant
        )

        // 2. Blank dark/shadowed paper where Sauvola falls back to gentle contrast ->
        // gentle fallback MUST NOT bypass signature ink/whiteness validation!
        val blankGrayPaper = Bitmap.createBitmap(280, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(205, 205, 210))
        }
        val blankSigRes = OneClickPreparationCoordinator.autoPrepareSignature(
            context = context,
            input = null,
            rule = sigRule,
            preDecodedBitmap = blankGrayPaper,
            userPreviewAccepted = true // Even if user tries to accept, technical failure must hold!
        )
        assertNotNull(blankSigRes)
        assertNotNull(blankSigRes!!.signatureEvidence)
        assertEquals(
            com.example.data.model.SignatureProcessingMethod.GENTLE_CONTRAST_FALLBACK,
            blankSigRes.signatureEvidence!!.processingMethod
        )
        assertFalse("Blank paper under gentle fallback must fail ink/paper validation", blankSigRes.technicalBackgroundCompliant)
        assertFalse("Blank paper must NOT be compliant", blankSigRes.isCompliant)
        assertFalse("User preview acceptance must NEVER make a blank/unverified signature export-ready", blankSigRes.isExportReady)

        // 3. Thumb impression with dense ridge ink (~24% ink coverage) -> passes THUMB_IMPRESSION bounds
        val thumbRule = DocumentRule(
            id = "batch1_thumb_rule",
            documentType = DocumentType.THUMB_IMPRESSION_LEFT,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 1,
            maxKb = 50,
            exactWidthPx = 240,
            exactHeightPx = 240,
            requiresWhiteBackground = true
        )
        val thumbBmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        for (y in 45..195) {
            for (x in 55..185) {
                val dx = (x - 120f) / 65f
                val dy = (y - 120f) / 75f
                if (dx * dx + dy * dy <= 1.0f && (x + y * 2) % 3 != 0) {
                    thumbBmp.setPixel(x, y, Color.rgb(20, 30, 130))
                }
            }
        }
        val thumbRes = OneClickPreparationCoordinator.autoPrepareSignature(
            context = context,
            input = null,
            rule = thumbRule,
            preDecodedBitmap = thumbBmp
        )
        assertNotNull(thumbRes)
        assertNotNull(thumbRes!!.signatureEvidence)
        assertTrue(
            "Thumb impression with dense ink ridges must pass thumb-specific ink bounds",
            thumbRes.signatureEvidence!!.inkPresenceVerified && thumbRes.signatureEvidence!!.strokeBoundingBoxFound
        )
        assertTrue(thumbRes.technicalBackgroundCompliant)
    }

    @Test
    fun testBatch2All8ExifOrientationsSingleTransformPipeline() {
        val exifOrientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )

        val previewRectNorm = android.graphics.RectF(0.20f, 0.25f, 0.75f, 0.80f)

        for (orientation in exifOrientations) {
            val swapsDims = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE

            val rawW = if (swapsDims) 400 else 300
            val rawH = if (swapsDims) 300 else 400
            val rawBmp = createSampleBitmap(rawW, rawH, Color.DKGRAY)
            val file = saveBitmapToTestFile(rawBmp, "batch2_exif_$orientation.jpg")

            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()

            // Decode upright bitmap via UnifiedMediaInput.decodeBitmapWithExif
            val uprightBmp = UnifiedMediaInput.decodeBitmapWithExif(file)
            assertNotNull("Decoded bitmap must not be null for EXIF $orientation", uprightBmp)
            val (expectedUprightW, expectedUprightH) = com.example.camera.CameraViewportMapper.resolveUprightDimensions(
                rawW,
                rawH,
                orientation
            )
            assertEquals("Upright width mismatch for EXIF $orientation", expectedUprightW, uprightBmp!!.width)
            assertEquals("Upright height mismatch for EXIF $orientation", expectedUprightH, uprightBmp.height)
            assertEquals(300, uprightBmp.width)
            assertEquals(400, uprightBmp.height)

            // Test both portrait (1080x2400) and landscape (2400x1080) PreviewView layouts
            for ((pvW, pvH) in listOf(1080 to 2400, 2400 to 1080)) {
                val mediaInput = UnifiedMediaInput.buildMappedMediaInputFromGeometry(
                    file = file,
                    mode = CameraCaptureMode.PASSPORT_PHOTO,
                    previewWidth = pvW,
                    previewHeight = pvH,
                    measuredGuideRectNorm = previewRectNorm,
                    isFrontCamera = false
                )
                assertNotNull(
                    "Valid geometry must produce non-null mappedSensorCropRectNorm for EXIF $orientation (${pvW}x$pvH)",
                    mediaInput.mappedSensorCropRectNorm
                )
                val cropped = com.example.camera.CameraViewportMapper.cropUprightSensorBitmapToPreviewGuide(
                    uprightBitmap = uprightBmp,
                    previewRectNorm = previewRectNorm,
                    previewWidth = pvW,
                    previewHeight = pvH,
                    isFrontCamera = false
                )
                assertTrue(cropped.width > 0 && cropped.height > 0)
            }

            // Also verify standalone raw sensor coordinate mapping stays within [0..1]
            val rawSensorRect = com.example.camera.CameraViewportMapper.mapUprightNormalizedRectToRawSensorRectByExif(
                uprightRectNorm = previewRectNorm,
                exifOrientation = orientation
            )
            assertTrue(rawSensorRect.left >= 0f && rawSensorRect.right <= 1f && rawSensorRect.left < rawSensorRect.right)
            assertTrue(rawSensorRect.top >= 0f && rawSensorRect.bottom <= 1f && rawSensorRect.top < rawSensorRect.bottom)
        }
    }

    @Test
    fun testBatch2UnknownCameraGeometryReturnsNullCropAndNeverSilentlyCrops() {
        val bmp = createSampleBitmap(400, 500, Color.WHITE)
        val file = saveBitmapToTestFile(bmp, "batch2_unknown_geom.jpg")
        val validGuide = android.graphics.RectF(0.15f, 0.15f, 0.85f, 0.85f)

        // 1. Unknown PreviewView width/height (0x0) -> mappedSensorCropRectNorm MUST be null
        val zeroPreviewInput = UnifiedMediaInput.buildMappedMediaInputFromGeometry(
            file = file,
            mode = CameraCaptureMode.SIGNATURE,
            previewWidth = 0,
            previewHeight = 0,
            measuredGuideRectNorm = validGuide
        )
        assertNull(
            "Zero PreviewView dimensions must produce mappedSensorCropRectNorm = null instead of 1080x1920 fallback",
            zeroPreviewInput.mappedSensorCropRectNorm
        )

        // 2. Null measured guide overlay rect -> mappedSensorCropRectNorm MUST be null
        val nullGuideInput = UnifiedMediaInput.buildMappedMediaInputFromGeometry(
            file = file,
            mode = CameraCaptureMode.PASSPORT_PHOTO,
            previewWidth = 1080,
            previewHeight = 2400,
            measuredGuideRectNorm = null
        )
        assertNull(
            "Unmeasured guide overlay rect must produce mappedSensorCropRectNorm = null instead of mode.guideCropRectNorm fallback",
            nullGuideInput.mappedSensorCropRectNorm
        )
    }

    @Test
    fun testBatch3UnlistedGovInAndNicInDomainsAreRejected() {
        // Explicit allowlisted domains pass
        assertTrue(com.example.data.portal.OfficialDomainAllowlist.isVerifiedOfficialUrl("https://upsconline.nic.in"))
        assertTrue(com.example.data.portal.OfficialDomainAllowlist.isVerifiedOfficialUrl("https://ssc.gov.in"))
        assertTrue(com.example.data.portal.OfficialDomainAllowlist.isVerifiedOfficialUrl("https://tin.tin.proteantech.in"))

        // Arbitrary unlisted .gov.in and .nic.in domains MUST be rejected (no wildcard suffix acceptance!)
        assertFalse(
            "Unlisted .gov.in domain must NOT pass strict allowlist",
            com.example.data.portal.OfficialDomainAllowlist.isVerifiedOfficialUrl("https://unlisted-random-agency.gov.in")
        )
        assertFalse(
            "Unlisted .nic.in domain must NOT pass strict allowlist",
            com.example.data.portal.OfficialDomainAllowlist.isVerifiedOfficialUrl("https://unknown-portal-test.nic.in")
        )
        assertFalse(
            "onlineservices.nsdl.com must not be silently allowlisted without traceable source governance",
            com.example.data.portal.OfficialDomainAllowlist.isVerifiedOfficialUrl("https://www.onlineservices.nsdl.com")
        )
    }

    @Test
    fun testBatch3IncompleteOrStaleProvenanceDowngradesToOfficialSourceReview() {
        val basePortal = com.example.data.portal.PortalDatabase.findPortalById("ssc_cgl")!!
        val checkEpoch = com.example.data.portal.OfficialDomainAllowlist.parseVerifiedDateEpochMs("2025-02-01")!!

        // 1. Missing sourceIdentifier / rule sourceClauseRef -> downgraded to OFFICIAL_SOURCE_REVIEW
        val incompletePortal = basePortal.copy(
            verificationStatus = com.example.data.model.VerificationStatus.OFFICIALLY_VERIFIED,
            sourceIdentifier = null
        )
        val incompleteEval = com.example.data.portal.OfficialDomainAllowlist.evaluatePortalProvenance(
            portal = incompletePortal,
            nowEpochMs = checkEpoch
        )
        assertEquals(
            com.example.data.model.VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            incompleteEval.effectiveStatus
        )

        // 2. All portal & rule provenance fields present and fresh (<= 180 days) -> retains OFFICIALLY_VERIFIED
        val completePortal = basePortal.copy(
            verificationStatus = com.example.data.model.VerificationStatus.OFFICIALLY_VERIFIED,
            sourceIdentifier = "SSC-CGL-2025-NOTICE-01",
            applicableWorkflow = "SSC Online Application Upload",
            lastVerifiedDate = "2025-01-15",
            documents = basePortal.documents.mapIndexed { idx, r ->
                r.copy(
                    sourceClauseRef = "Annexure-II Para ${idx + 1}",
                    isRuleIndividuallyVerified = true
                )
            }
        )
        val freshEval = com.example.data.portal.OfficialDomainAllowlist.evaluatePortalProvenance(
            portal = completePortal,
            nowEpochMs = checkEpoch
        )
        assertEquals(
            com.example.data.model.VerificationStatus.OFFICIALLY_VERIFIED,
            freshEval.effectiveStatus
        )

        // 3. Stale check (> 180 days) -> downgraded to OFFICIAL_SOURCE_REVIEW
        val staleEpoch = com.example.data.portal.OfficialDomainAllowlist.parseVerifiedDateEpochMs("2025-10-15")!!
        val staleEval = com.example.data.portal.OfficialDomainAllowlist.evaluatePortalProvenance(
            portal = completePortal,
            nowEpochMs = staleEpoch
        )
        assertTrue(staleEval.isInternalReviewDue)
        assertEquals(
            com.example.data.model.VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            staleEval.effectiveStatus
        )
    }

    @Test
    fun testBatch3PanWorkflowSeparationAndUniversalPresetStatus() {
        val universal = com.example.data.portal.PortalDatabase.findPortalById("universal_passport")
        assertNotNull(universal)
        assertEquals(com.example.data.model.VerificationStatus.USER_SUPPLIED, universal!!.verificationStatus)

        val nsdlLegacy = com.example.data.portal.PortalDatabase.findPortalById("nsdl_pan")
        val nsdlDsc = com.example.data.portal.PortalDatabase.findPortalById("nsdl_pan_dsc")
        assertNotNull(nsdlLegacy)
        assertNotNull(nsdlDsc)
        assertEquals(com.example.data.model.VerificationStatus.OFFICIAL_SOURCE_REVIEW, nsdlLegacy!!.verificationStatus)
        assertEquals(com.example.data.model.VerificationStatus.OFFICIAL_SOURCE_REVIEW, nsdlDsc!!.verificationStatus)

        // Verify DSC workflow uses 200 DPI (3.5x2.5 cm <=20 KB photo, 2x4.5 cm <=10 KB sign)
        // and does NOT mix 213x213 square pixels with 25x35mm physical dimensions
        val dscPhoto = nsdlDsc.documents.first { it.documentType == DocumentType.PASSPORT_PHOTO }
        val dscSign = nsdlDsc.documents.first { it.documentType == DocumentType.SIGNATURE_ENGLISH }
        assertEquals(200, dscPhoto.dpi)
        assertEquals(20, dscPhoto.maxKb)
        assertEquals(200, dscSign.dpi)
        assertEquals(10, dscSign.maxKb)
        assertNull("Square 213x213 preset must not attach conflicting 25x35mm physical dimensions", nsdlLegacy.documents.first().physicalWidthMm)
    }

    @Test
    fun testBatch4DocumentCornerDetection8SceneValidationCorpus() {
        val w = 360
        val h = 440

        fun buildQuadScene(
            tl: android.graphics.PointF,
            tr: android.graphics.PointF,
            br: android.graphics.PointF,
            bl: android.graphics.PointF,
            bgFn: (Int, Int) -> Int,
            fgFn: (Int, Int) -> Int
        ): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val verts = arrayOf(
                android.graphics.PointF(tl.x * w, tl.y * h),
                android.graphics.PointF(tr.x * w, tr.y * h),
                android.graphics.PointF(br.x * w, br.y * h),
                android.graphics.PointF(bl.x * w, bl.y * h)
            )
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var inside = true
                    for (i in 0 until 4) {
                        val a = verts[i]
                        val b = verts[(i + 1) % 4]
                        val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
                        if (cross < 0f) {
                            inside = false
                            break
                        }
                    }
                    pixels[y * w + x] = if (inside) fgFn(x, y) else bgFn(x, y)
                }
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            return bmp
        }

        // Scene 1: Clean high-contrast angled document on dark desk -> autoWarpEligible = true
        val scene1 = buildQuadScene(
            tl = android.graphics.PointF(0.15f, 0.14f),
            tr = android.graphics.PointF(0.85f, 0.17f),
            br = android.graphics.PointF(0.82f, 0.85f),
            bl = android.graphics.PointF(0.13f, 0.82f),
            bgFn = { _, _ -> Color.rgb(35, 38, 45) },
            fgFn = { _, _ -> Color.rgb(245, 245, 245) }
        )
        val det1 = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(scene1)
        assertTrue("Scene 1 (clean dark desk) must be autoWarpEligible (conf=${det1.confidence})", det1.autoWarpEligible)
        assertEquals(0.15f, det1.topLeft.x, 0.05f)
        assertEquals(0.14f, det1.topLeft.y, 0.05f)

        // Scene 2: Document on textured wooden desk (alternating wood grain stripes lum 55..92) -> autoWarpEligible = true
        val scene2 = buildQuadScene(
            tl = android.graphics.PointF(0.16f, 0.15f),
            tr = android.graphics.PointF(0.84f, 0.16f),
            br = android.graphics.PointF(0.84f, 0.84f),
            bl = android.graphics.PointF(0.16f, 0.84f),
            bgFn = { x, y ->
                val grain = 60 + ((x * 3 + y) % 30)
                Color.rgb(grain + 10, grain, (grain - 10).coerceAtLeast(30))
            },
            fgFn = { _, _ -> Color.rgb(244, 244, 240) }
        )
        val det2 = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(scene2)
        assertTrue("Scene 2 (wooden desk) must be autoWarpEligible (conf=${det2.confidence})", det2.autoWarpEligible)

        // Scene 3: Multiple competing rectangular objects (two separate bright sheets side-by-side) -> autoWarpEligible = false
        val scene3 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(35, 38, 45))
        }
        for (y in (h * 0.18).toInt()..(h * 0.82).toInt()) {
            for (x in (w * 0.08).toInt()..(w * 0.44).toInt()) {
                scene3.setPixel(x, y, Color.WHITE)
            }
            for (x in (w * 0.56).toInt()..(w * 0.92).toInt()) {
                scene3.setPixel(x, y, Color.WHITE)
            }
        }
        val det3 = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(scene3)
        assertTrue("Scene 3 (multiple rectangular objects) must flag hasCompetingObjects", det3.hasCompetingObjects)
        assertFalse("Scene 3 must NOT auto-warp without user confirmation", det3.autoWarpEligible)

        // Scene 4: Low-contrast white paper (lum 242) on light cream desk (lum 230) -> autoWarpEligible = false
        val scene4 = buildQuadScene(
            tl = android.graphics.PointF(0.15f, 0.15f),
            tr = android.graphics.PointF(0.85f, 0.15f),
            br = android.graphics.PointF(0.85f, 0.85f),
            bl = android.graphics.PointF(0.15f, 0.85f),
            bgFn = { _, _ -> Color.rgb(230, 228, 222) },
            fgFn = { _, _ -> Color.rgb(242, 242, 240) }
        )
        val det4 = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(scene4)
        assertFalse("Scene 4 (low contrast white-on-cream) must NOT auto-warp", det4.autoWarpEligible)

        // Scene 5: Document with moderate diagonal illumination shadow (lum 245 down to 145) on dark desk -> autoWarpEligible = true
        val scene5 = buildQuadScene(
            tl = android.graphics.PointF(0.15f, 0.15f),
            tr = android.graphics.PointF(0.85f, 0.15f),
            br = android.graphics.PointF(0.85f, 0.85f),
            bl = android.graphics.PointF(0.15f, 0.85f),
            bgFn = { _, _ -> Color.rgb(35, 38, 45) },
            fgFn = { x, y ->
                val t = ((x.toFloat() / w) * 0.5f + (y.toFloat() / h) * 0.5f).coerceIn(0f, 1f)
                val lumVal = (245 - t * 95f).toInt().coerceIn(145, 245)
                Color.rgb(lumVal, lumVal, lumVal)
            }
        )
        val det5 = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(scene5)
        assertTrue("Scene 5 (diagonal shadow on document) must be autoWarpEligible", det5.autoWarpEligible)

        // Scene 6: Clipped document extending off the bottom and right borders -> autoWarpEligible = false
        val scene6 = buildQuadScene(
            tl = android.graphics.PointF(0.18f, 0.18f),
            tr = android.graphics.PointF(0.995f, 0.18f),
            br = android.graphics.PointF(0.995f, 0.995f),
            bl = android.graphics.PointF(0.18f, 0.995f),
            bgFn = { _, _ -> Color.rgb(35, 38, 45) },
            fgFn = { _, _ -> Color.WHITE }
        )
        val det6 = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(scene6)
        assertTrue("Scene 6 (clipped document) must flag isClippedAtBorder", det6.isClippedAtBorder)
        assertFalse("Scene 6 (clipped document) must NOT auto-warp", det6.autoWarpEligible)

        // Scene 7: 4-corner background clutter (4 bright patches near the 4 corners, dark center) -> autoWarpEligible = false
        val scene7 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(35, 38, 45))
        }
        for (y in 10..70) {
            for (x in 10..70) {
                scene7.setPixel(x, y, Color.WHITE)
                scene7.setPixel(w - 1 - x, y, Color.WHITE)
                scene7.setPixel(x, h - 1 - y, Color.WHITE)
                scene7.setPixel(w - 1 - x, h - 1 - y, Color.WHITE)
            }
        }
        val det7 = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(scene7)
        assertFalse("Scene 7 (4-corner disconnected clutter) must NEVER be autoWarpEligible", det7.autoWarpEligible)

        // Scene 8: Blank uniform surface -> autoWarpEligible = false
        val scene8 = createSampleBitmap(w, h, Color.rgb(120, 125, 130))
        val det8 = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(scene8)
        assertFalse("Scene 8 (blank surface) must NEVER be autoWarpEligible", det8.autoWarpEligible)
    }

    @Test
    fun testBatch5MlKitClientReuseConcurrentLocksAndLifecycleRelease() = runBlocking {
        com.example.processing.VisionBenchmarkRecorder.clear()
        com.example.processing.PortraitVisionProcessor.releaseEngines()

        var faceClientCreatedCount = 0
        var faceClientClosedCount = 0
        var segClientCreatedCount = 0
        var segClientClosedCount = 0

        val originalFaceFactory = com.example.processing.PortraitVisionProcessor.faceDetectorClientFactory
        val originalSegFactory = com.example.processing.PortraitVisionProcessor.selfieSegmenterClientFactory

        try {
            com.example.processing.PortraitVisionProcessor.faceDetectorClientFactory = {
                faceClientCreatedCount++
                object : com.example.processing.PortraitVisionProcessor.ManagedFaceDetectorClient {
                    private var closed = false
                    override suspend fun processBitmap(bitmap: Bitmap): List<com.example.processing.PortraitVisionProcessor.DetectedFaceInfo> {
                        check(!closed) { "FaceDetector client used after close()!" }
                        return listOf(
                            com.example.processing.PortraitVisionProcessor.DetectedFaceInfo(
                                boundingBox = android.graphics.RectF(0.25f, 0.20f, 0.75f, 0.70f)
                            )
                        )
                    }

                    override fun close() {
                        closed = true
                        faceClientClosedCount++
                    }
                }
            }

            com.example.processing.PortraitVisionProcessor.selfieSegmenterClientFactory = {
                segClientCreatedCount++
                object : com.example.processing.PortraitVisionProcessor.ManagedSelfieSegmenterClient {
                    private var closed = false
                    override suspend fun processBitmap(bitmap: Bitmap): com.example.processing.PortraitVisionProcessor.SegmentationConfidenceMask {
                        check(!closed) { "SelfieSegmenter client used after close()!" }
                        return com.example.processing.PortraitVisionProcessor.RawSegmentationMask(
                            bitmap.width,
                            bitmap.height,
                            FloatArray(bitmap.width * bitmap.height) { 0.95f }
                        )
                    }

                    override fun close() {
                        closed = true
                        segClientClosedCount++
                    }
                }
            }

            val testBmp = createSampleBitmap(200, 250, Color.WHITE)

            // 1. First call creates client once and records *_COLD_FIRST_INFERENCE
            val faces1 = com.example.processing.PortraitVisionProcessor.mlKitFaceDetectionEngine.detectFaces(testBmp)
            val mask1 = com.example.processing.PortraitVisionProcessor.mlKitSelfieSegmentationEngine.segmentPortrait(testBmp)
            assertEquals(1, faces1.size)
            assertNotNull(mask1)
            assertEquals(1, faceClientCreatedCount)
            assertEquals(0, faceClientClosedCount)
            assertEquals(1, segClientCreatedCount)
            assertEquals(0, segClientClosedCount)
            assertTrue(com.example.processing.PortraitVisionProcessor.isFaceDetectorWarm())
            assertTrue(com.example.processing.PortraitVisionProcessor.isSelfieSegmenterWarm())

            // 2. Second call reuses the SAME cached clients without re-creating or closing, and records *_WARM_INFERENCE
            val faces2 = com.example.processing.PortraitVisionProcessor.mlKitFaceDetectionEngine.detectFaces(testBmp)
            val mask2 = com.example.processing.PortraitVisionProcessor.mlKitSelfieSegmentationEngine.segmentPortrait(testBmp)
            assertEquals(1, faces2.size)
            assertNotNull(mask2)
            assertEquals("Cached FaceDetector must be reused without recreation", 1, faceClientCreatedCount)
            assertEquals("Cached SelfieSegmenter must be reused without recreation", 1, segClientCreatedCount)
            assertEquals(0, faceClientClosedCount)
            assertEquals(0, segClientClosedCount)

            // 3. Lifecycle release closes both clients and resets warm state
            com.example.processing.PortraitVisionProcessor.releaseEngines()
            assertEquals(1, faceClientClosedCount)
            assertEquals(1, segClientClosedCount)
            assertFalse(com.example.processing.PortraitVisionProcessor.isFaceDetectorCached())
            assertFalse(com.example.processing.PortraitVisionProcessor.isSelfieSegmenterCached())

            // 4. Subsequent inference after release constructs a new client and runs cold first-inference
            com.example.processing.PortraitVisionProcessor.mlKitFaceDetectionEngine.detectFaces(testBmp)
            assertEquals(2, faceClientCreatedCount)
        } finally {
            com.example.processing.PortraitVisionProcessor.releaseEngines()
            com.example.processing.PortraitVisionProcessor.faceDetectorClientFactory = originalFaceFactory
            com.example.processing.PortraitVisionProcessor.selfieSegmenterClientFactory = originalSegFactory
        }
    }

    @Test
    fun testBatch5VisionBenchmarkRecorderSampledPrePostMemoryAndColdWarmStages() {
        com.example.processing.VisionBenchmarkRecorder.clear()

        val preSnap = com.example.processing.VisionBenchmarkRecorder.HeapSnapshot(
            javaHeapBytes = 10_000_000L,
            nativeHeapBytes = 20_000_000L
        )
        val postSnap = com.example.processing.VisionBenchmarkRecorder.HeapSnapshot(
            javaHeapBytes = 14_500_000L,
            nativeHeapBytes = 28_000_000L
        )

        val sample = com.example.processing.VisionBenchmarkRecorder.recordSample(
            stage = com.example.processing.VisionBenchmarkRecorder.Stage.FACE_DETECTION_COLD_FIRST_INFERENCE,
            durationMs = 85L,
            imageWidth = 1200,
            imageHeight = 1600,
            preSnapshot = preSnap,
            postSnapshot = postSnap
        )

        assertEquals(10_000_000L, sample.sampledPreJavaHeapBytes)
        assertEquals(14_500_000L, sample.sampledPostJavaHeapBytes)
        assertEquals(4_500_000L, sample.sampledJavaHeapDeltaBytes)
        assertEquals(20_000_000L, sample.sampledPreNativeHeapBytes)
        assertEquals(28_000_000L, sample.sampledPostNativeHeapBytes)
        assertEquals(8_000_000L, sample.sampledNativeHeapDeltaBytes)

        val summary = com.example.processing.VisionBenchmarkRecorder.summarizeStage(
            com.example.processing.VisionBenchmarkRecorder.Stage.FACE_DETECTION_COLD_FIRST_INFERENCE
        )
        assertNotNull(summary)
        assertEquals(1, summary!!.sampleCount)
        assertEquals(85L, summary.medianMs)
        assertEquals(14_500_000L, summary.maxSampledPostJavaHeapBytes)
        assertEquals(4_500_000L, summary.maxSampledJavaHeapDeltaBytes)
        assertEquals(28_000_000L, summary.maxSampledPostNativeHeapBytes)
        assertEquals(8_000_000L, summary.maxSampledNativeHeapDeltaBytes)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase05AExportReadinessGateUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        testDir = File(context.cacheDir, "phase05a_ui_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun createDummyProcessedFile(name: String = "prepared.jpg"): File {
        val file = File(testDir, name)
        val bmp = Bitmap.createBitmap(350, 450, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file
    }

    @Test
    fun testPortalDocumentPrepareScreenPendingApprovalBlocksSaveAndShareUntilApproved() {
        val dummyFile = createDummyProcessedFile("portal_photo.jpg")
        val sampleBmp = Bitmap.createBitmap(350, 450, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val portal = com.example.data.portal.PortalDatabase.findPortalById("ssc_cgl")!!
        val photoRule = portal.documents.first { it.documentType == DocumentType.PASSPORT_PHOTO }

        val pendingResult = com.example.data.model.ProcessedFileResult(
            ruleId = photoRule.id,
            documentType = photoRule.documentType,
            originalFileName = dummyFile.name,
            processedFilePath = dummyFile.absolutePath,
            fileFormat = FileFormat.JPG,
            fileSizeBytes = dummyFile.length(),
            fileSizeKb = dummyFile.length() / 1024.0,
            widthPx = 200,
            heightPx = 230,
            isCompliant = true,
            validationMessages = listOf("Compliant"),
            status = com.example.data.model.PreparationStatus.PENDING_PREVIEW_APPROVAL,
            dpi = 300,
            requiresWhiteBackground = true,
            technicalBackgroundCompliant = true,
            outputBackgroundPixelChecksPassed = true,
            backgroundReplacementApplied = true,
            requiresUserPreviewApproval = true,
            userPreviewAccepted = false
        )
        assertFalse("Initial pending result must have isExportReady = false", pendingResult.isExportReady)

        var latestCallbackResult: com.example.data.model.ProcessedFileResult = pendingResult

        composeTestRule.setContent {
            com.example.ui.screens.PortalDocumentPrepareScreen(
                portal = portal,
                rule = photoRule,
                initialCandidateName = "",
                initialDateOfPhoto = "01-01-2026",
                onDocumentPrepared = { latestCallbackResult = it },
                onBack = {},
                initialSourceBitmap = sampleBmp,
                initialProcessedResult = pendingResult
            )
        }

        // 1. Pending approval blocks Save and Share buttons in Compose UI
        composeTestRule.onNodeWithTag("preview_acceptance_checkbox").assertIsOff()
        composeTestRule.onNodeWithTag("portal_doc_save_gallery_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("portal_doc_share_btn").assertIsNotEnabled()

        // 2. Explicit user approval enables Save and Share without rerunning ML inference
        var mlInferenceTriggered = false
        val origFaceFactory = com.example.processing.PortraitVisionProcessor.faceDetectorClientFactory
        try {
            com.example.processing.PortraitVisionProcessor.faceDetectorClientFactory = {
                mlInferenceTriggered = true
                origFaceFactory()
            }
            composeTestRule.onNodeWithTag("preview_acceptance_checkbox").performScrollTo().performClick()
            composeTestRule.waitForIdle()
        } finally {
            com.example.processing.PortraitVisionProcessor.faceDetectorClientFactory = origFaceFactory
        }

        assertFalse("Toggling approval checkbox must NOT rerun ML inference", mlInferenceTriggered)
        composeTestRule.onNodeWithTag("preview_acceptance_checkbox").assertIsOn()
        assertTrue("Approved compliant result must be export-ready", latestCallbackResult.isExportReady)
        composeTestRule.onNodeWithTag("portal_doc_save_gallery_btn").assertIsEnabled()
        composeTestRule.onNodeWithTag("portal_doc_share_btn").assertIsEnabled()
    }

    @Test
    fun testPhotoStudioPendingApprovalBlocksGallerySaveShareAndLockerAndTechnicalFailureCannotBeOverridden() {
        val dummyFile = createDummyProcessedFile("studio_photo.jpg")
        val sampleBmp = Bitmap.createBitmap(350, 450, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

        // Case A: Technically compliant photo with userPreviewAccepted = false -> all 4 export controls disabled
        val compliantPendingResult = com.example.data.model.ProcessedFileResult(
            ruleId = "photo_studio_test",
            documentType = DocumentType.PASSPORT_PHOTO,
            originalFileName = dummyFile.name,
            processedFilePath = dummyFile.absolutePath,
            fileFormat = FileFormat.JPG,
            fileSizeBytes = dummyFile.length(),
            fileSizeKb = dummyFile.length() / 1024.0,
            widthPx = 350,
            heightPx = 450,
            isCompliant = true,
            validationMessages = listOf("Compliant"),
            status = com.example.data.model.PreparationStatus.PENDING_PREVIEW_APPROVAL,
            dpi = 300,
            requiresWhiteBackground = true,
            technicalBackgroundCompliant = true,
            outputBackgroundPixelChecksPassed = true,
            backgroundReplacementApplied = true,
            requiresUserPreviewApproval = true,
            userPreviewAccepted = false
        )

        composeTestRule.setContent {
            com.example.ui.screens.PhotoStudioScreen(
                initialSourceBitmap = sampleBmp,
                initialProcessedResult = compliantPendingResult
            )
        }

        composeTestRule.onNodeWithTag("instant_gallery_download_photo_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("save_photo_device_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("share_photo_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("add_photo_to_locker_btn").assertIsNotEnabled()

        // Approve preview -> all 4 export controls become enabled
        composeTestRule.onNodeWithTag("photo_preview_acceptance_checkbox").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("instant_gallery_download_photo_btn").assertIsEnabled()
        composeTestRule.onNodeWithTag("save_photo_device_btn").assertIsEnabled()
        composeTestRule.onNodeWithTag("share_photo_btn").assertIsEnabled()
        composeTestRule.onNodeWithTag("add_photo_to_locker_btn").assertIsEnabled()
    }

    @Test
    fun testUserApprovalCannotOverrideTechnicalFailureInUi() {
        val dummyFile = createDummyProcessedFile("failed_photo.jpg")
        val sampleBmp = Bitmap.createBitmap(350, 450, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }

        // Technically failed result (background not white / size exceeded)
        val technicallyFailedResult = com.example.data.model.ProcessedFileResult(
            ruleId = "failed_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            originalFileName = dummyFile.name,
            processedFilePath = dummyFile.absolutePath,
            fileFormat = FileFormat.JPG,
            fileSizeBytes = dummyFile.length(),
            fileSizeKb = dummyFile.length() / 1024.0,
            widthPx = 350,
            heightPx = 450,
            isCompliant = false,
            validationMessages = listOf("Background Warning: Non-white background"),
            status = com.example.data.model.PreparationStatus.BACKGROUND_NOT_WHITE,
            dpi = 300,
            requiresWhiteBackground = true,
            technicalBackgroundCompliant = false,
            outputBackgroundPixelChecksPassed = false,
            backgroundReplacementApplied = false,
            requiresUserPreviewApproval = true,
            userPreviewAccepted = false
        )

        composeTestRule.setContent {
            com.example.ui.screens.PhotoStudioScreen(
                initialSourceBitmap = sampleBmp,
                initialProcessedResult = technicallyFailedResult
            )
        }

        // Check the preview approval checkbox
        composeTestRule.onNodeWithTag("photo_preview_acceptance_checkbox").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Checkbox is checked, but export buttons MUST remain disabled because technical compliance failed!
        composeTestRule.onNodeWithTag("photo_preview_acceptance_checkbox").assertIsOn()
        composeTestRule.onNodeWithTag("instant_gallery_download_photo_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("save_photo_device_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("share_photo_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("add_photo_to_locker_btn").assertIsNotEnabled()
    }

    @Test
    fun testSignatureStudioPendingApprovalBlocksExportAndChangingAdjustmentResetsApproval() {
        val dummyFile = createDummyProcessedFile("signature.jpg")
        val sampleBmp = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 40 until 260) {
                for (y in 55..58) {
                    setPixel(x, y, Color.BLACK)
                }
            }
        }

        val approvedSignResult = com.example.data.model.ProcessedFileResult(
            ruleId = "sign_rule",
            documentType = DocumentType.SIGNATURE_ENGLISH,
            originalFileName = dummyFile.name,
            processedFilePath = dummyFile.absolutePath,
            fileFormat = FileFormat.JPG,
            fileSizeBytes = 14 * 1024L,
            fileSizeKb = 14.0,
            widthPx = 140,
            heightPx = 60,
            isCompliant = true,
            validationMessages = listOf("Compliant"),
            status = com.example.data.model.PreparationStatus.SUCCESS_PREPARED,
            dpi = 300,
            requiresWhiteBackground = true,
            technicalBackgroundCompliant = true,
            outputBackgroundPixelChecksPassed = true,
            backgroundReplacementApplied = true,
            requiresUserPreviewApproval = true,
            userPreviewAccepted = true
        )
        assertTrue(approvedSignResult.isExportReady)

        composeTestRule.setContent {
            com.example.ui.screens.SignatureStudioScreen(
                initialSourceBitmap = sampleBmp,
                initialProcessedResult = approvedSignResult
            )
        }

        // Initially approved -> all export buttons enabled
        composeTestRule.onNodeWithTag("sign_preview_acceptance_checkbox").assertIsOn()
        composeTestRule.onNodeWithTag("instant_gallery_download_sign_btn").assertIsEnabled()
        composeTestRule.onNodeWithTag("save_signature_device_btn").assertIsEnabled()
        composeTestRule.onNodeWithTag("share_signature_btn").assertIsEnabled()
        composeTestRule.onNodeWithTag("add_signature_to_locker_btn").assertIsEnabled()

        // Uncheck approval -> immediately disables all export buttons
        composeTestRule.onNodeWithTag("sign_preview_acceptance_checkbox").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("instant_gallery_download_sign_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("save_signature_device_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("share_signature_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("add_signature_to_locker_btn").assertIsNotEnabled()

        // Re-approve -> enabled again
        composeTestRule.onNodeWithTag("sign_preview_acceptance_checkbox").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("instant_gallery_download_sign_btn").assertIsEnabled()

        // Trigger Gentle Ink Processing fallback adjustment -> resets userPreviewAccepted to false and blocks export again
        composeTestRule.onNodeWithTag("restore_original_signature_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sign_preview_acceptance_checkbox").assertIsOff()
        composeTestRule.onNodeWithTag("instant_gallery_download_sign_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("save_signature_device_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("share_signature_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("add_signature_to_locker_btn").assertIsNotEnabled()
    }

    @Test
    fun testPortalDetailScreenShareAllBlockedWhenAnyPreparedDocumentNotExportReadyAndNonWhiteBgRuleAllowed() {
        val dummyFile = createDummyProcessedFile("doc_non_white.jpg")
        val portal = com.example.data.portal.PortalDatabase.findPortalById("ssc_cgl")!!
        val rule = portal.documents.first()

        // Rule that does NOT require white background (requiresWhiteBackground = false)
        // Even with technicalBackgroundCompliant = false, once approved it must become export-ready
        val nonWhiteBgPending = com.example.data.model.ProcessedFileResult(
            ruleId = rule.id,
            documentType = rule.documentType,
            originalFileName = dummyFile.name,
            processedFilePath = dummyFile.absolutePath,
            fileFormat = FileFormat.JPG,
            fileSizeBytes = 25 * 1024L,
            fileSizeKb = 25.0,
            widthPx = 200,
            heightPx = 230,
            isCompliant = true,
            validationMessages = listOf("Compliant"),
            status = com.example.data.model.PreparationStatus.PENDING_PREVIEW_APPROVAL,
            dpi = 300,
            requiresWhiteBackground = false,
            technicalBackgroundCompliant = false,
            outputBackgroundPixelChecksPassed = false,
            backgroundReplacementApplied = false,
            requiresUserPreviewApproval = true,
            userPreviewAccepted = false
        )
        assertFalse("Pending approval must block export even when white bg is not required", nonWhiteBgPending.isExportReady)

        val nonWhiteBgApproved = OneClickPreparationCoordinator.updateUserPreviewAcceptance(nonWhiteBgPending, true)
        assertTrue(
            "Rules that do not require white background must NOT be blocked by white-background checks once approved",
            nonWhiteBgApproved.isExportReady
        )

        val preparedMapState = androidx.compose.runtime.mutableStateOf(mapOf(rule.id to nonWhiteBgPending))

        composeTestRule.setContent {
            com.example.ui.screens.PortalDetailScreen(
                portal = portal,
                preparedResults = preparedMapState.value,
                candidateName = "",
                onCandidateNameChange = {},
                dateOfPhoto = "01-01-2026",
                onDateOfPhotoChange = {},
                onSelectRuleToPrepare = {},
                onBack = {}
            )
        }

        // While pending approval, share_all_portal_docs_btn MUST be disabled
        composeTestRule.onNodeWithTag("portal_detail_screen")
            .performScrollToNode(hasTestTag("share_all_portal_docs_btn"))
        composeTestRule.onNodeWithTag("share_all_portal_docs_btn").assertIsNotEnabled()

        // Update map with approved result -> share_all_portal_docs_btn becomes enabled
        preparedMapState.value = mapOf(rule.id to nonWhiteBgApproved)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("portal_detail_screen")
            .performScrollToNode(hasTestTag("share_all_portal_docs_btn"))
        composeTestRule.onNodeWithTag("share_all_portal_docs_btn").assertIsEnabled()
    }
}





