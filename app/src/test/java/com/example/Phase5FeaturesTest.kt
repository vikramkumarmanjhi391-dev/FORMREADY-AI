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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
        assertTrue(verifiedWhitenedResult.verifiedBackgroundCompliance)
        assertTrue(verifiedWhitenedResult.hasWhiteBackground)
        assertTrue(verifiedWhitenedResult.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.SUCCESS_PREPARED, verifiedWhitenedResult.status)
        assertEquals("VERIFIED_MASK", verifiedWhitenedResult.backgroundVerificationState)
        assertTrue(verifiedWhitenedResult.nearWhitePixelRatio >= 0.85f)
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
        assertEquals(com.example.data.model.PreparationStatus.SUCCESS_PREPARED, jpegRes.status)

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
        val officialUpsc = com.example.data.portal.PortalDatabase.OFFICIAL_PORTALS.first { it.id == "upsc_cse" }
        val upscEval = com.example.data.portal.OfficialDomainAllowlist.evaluatePortalProvenance(
            portal = officialUpsc,
            nowEpochMs = com.example.data.portal.OfficialDomainAllowlist.parseVerifiedDateEpochMs("2025-03-01")!!
        )
        assertTrue(upscEval.isOfficialDomain)
        assertFalse(upscEval.isInternalReviewDue)
        assertEquals(com.example.data.model.VerificationStatus.OFFICIALLY_VERIFIED, upscEval.effectiveStatus)

        // 180-day threshold check: 250 days after lastVerifiedDate -> triggers internal review note ONLY,
        // preserving OFFICIALLY_VERIFIED status and keeping all DocumentRule specs intact!
        val futureEpoch = com.example.data.portal.OfficialDomainAllowlist.parseVerifiedDateEpochMs("2025-11-15")!!
        val reviewDueEval = com.example.data.portal.OfficialDomainAllowlist.evaluatePortalProvenance(
            portal = officialUpsc,
            nowEpochMs = futureEpoch
        )
        assertTrue("More than 180 days since check should flag internal review due", reviewDueEval.isInternalReviewDue)
        assertNotNull(reviewDueEval.reviewNote)
        assertEquals(
            "180-day internal review threshold must NOT invalidate an official portal's verified status",
            com.example.data.model.VerificationStatus.OFFICIALLY_VERIFIED,
            reviewDueEval.effectiveStatus
        )
        assertTrue(officialUpsc.documents.isNotEmpty())

        // Non-official https:// domain must NEVER be granted OFFICIALLY_VERIFIED
        val fakeDomainPortal = officialUpsc.copy(
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

        // 1A. High confidence + userPreviewAccepted = true -> verifiedBackgroundCompliance = true & SUCCESS_PREPARED
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
        assertTrue(acceptedResult.verifiedBackgroundCompliance)
        assertTrue(acceptedResult.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.SUCCESS_PREPARED, acceptedResult.status)

        // 1B. High confidence + userPreviewAccepted = false -> verifiedBackgroundCompliance = false & BACKGROUND_UNVERIFIED
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
        assertFalse("Unaccepted preview must NOT set verifiedBackgroundCompliance = true", unacceptedResult.verifiedBackgroundCompliance)
        assertFalse(unacceptedResult.isCompliant)
        assertEquals(com.example.data.model.PreparationStatus.BACKGROUND_UNVERIFIED, unacceptedResult.status)
        assertEquals("PREVIEW_NOT_ACCEPTED", unacceptedResult.backgroundVerificationState)

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
            "High edge uncertainty must NOT set verifiedBackgroundCompliance = true",
            uncertainResult.verifiedBackgroundCompliance
        )
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
                // Paper luminance drops from 235 on the left to 145 on the right (heavy phone shadow)
                val paperLum = (235 - (x.toFloat() / width.toFloat()) * 90f).toInt().coerceIn(120, 245)
                val isStroke = (y in 52..62 && x in 45..270 && ((x + y) % 7 != 0)) ||
                    (y in 70..76 && x in 80..230)
                if (isStroke) {
                    // Blue ballpoint ink darker than local paper
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

        // Verify background pixels in cleanedBitmap are pure white (#FFFFFF)
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
        assertTrue("Clean high-contrast angled document should achieve confidence >= 0.75 (was ${detection.confidence})", detection.confidence >= 0.75f)
        assertTrue(detection.autoWarpEligible)

        // Verify detected corners are close to the true quadrilateral corners
        assertEquals(0.16f, detection.cornersNorm[0].x, 0.06f)
        assertEquals(0.14f, detection.cornersNorm[0].y, 0.06f)
        assertEquals(0.86f, detection.cornersNorm[1].x, 0.06f)
        assertEquals(0.18f, detection.cornersNorm[1].y, 0.06f)

        // Warp document perspective and verify rectified output is bright document content
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

        // Uniform low-contrast image -> confidence < 0.75f (not auto-warp eligible)
        val uniformBmp = createSampleBitmap(300, 400, Color.rgb(180, 180, 180))
        val lowConfDetection = com.example.processing.DocumentCornerProcessor.detectDocumentCorners(uniformBmp)
        assertFalse("Uniform image without document edges must not be autoWarpEligible", lowConfDetection.autoWarpEligible)
    }
}



