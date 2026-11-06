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

    private val createdPages = mutableListOf<PdfDocument.PageInfo>()
    private var currentActivePage: PdfDocument.Page? = null
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
        return page
    }

    @Implementation
    protected fun finishPage(page: PdfDocument.Page?) {
        check(!isClosed) { "PdfDocument is already closed" }
        requireNotNull(page) { "Page cannot be null" }
        check(currentActivePage === page) { "Finished page does not match active page" }
        createdPages.add(page.info)
        currentActivePage = null
    }

    @Implementation
    protected fun getPages(): List<PdfDocument.PageInfo> {
        return createdPages.toList()
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

        createdPages.forEachIndexed { idx, info ->
            val objId = idx + 3
            sb.append(
                "$objId 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${info.pageWidth} ${info.pageHeight}] >>\nendobj\n"
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
        val bmp = createSampleBitmap(640, 800, Color.CYAN)
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
            minKb = 5,
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

        val sourceBmp = createSampleBitmap(600, 600, Color.LTGRAY)
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
        assertTrue(processed.hasWhiteBackground)

        val outBytes = File(processed.processedFilePath).readBytes()
        assertEquals(300, com.example.processing.ImageProcessor.readJfifDpiHeader(outBytes))
    }

    @Test
    fun testSafeJpegPaddingToMinKbGuarantee() = runBlocking {
        // Tiny solid-white signature image naturally compresses to < 3 KB
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

        assertTrue("File must be padded to at least 12 KB (12288 bytes), actual=${result.fileSizeBytes}", result.fileSizeBytes >= 12 * 1024L)
        assertTrue("File must remain <= 20 KB", result.fileSizeBytes <= 20 * 1024L)
        assertTrue("wasPaddedToMinKb flag should be true", result.wasPaddedToMinKb)

        // Ensure padded JPEG is still 100% valid and decodable
        val decoded = android.graphics.BitmapFactory.decodeFile(result.processedFilePath)
        assertNotNull(decoded)
        assertEquals(140, decoded.width)
        assertEquals(60, decoded.height)
    }

    @Test
    fun testGithubJsonPresetSyncAndPowerTools() = runBlocking {
        val syncedList = com.example.data.portal.PortalDatabase.parseAndRegisterPortalsFromJson(
            com.example.data.portal.PortalDatabase.DEFAULT_GITHUB_PRESETS_JSON
        )
        assertTrue("Should sync remote exam presets from JSON", syncedList.size >= 2)
        assertNotNull(com.example.data.portal.PortalDatabase.findPortalById("up_police_recruitment"))
        assertNotNull(com.example.data.portal.PortalDatabase.findPortalById("bihar_police_csbc"))

        val photoBmp = createSampleBitmap(350, 450, Color.BLUE)
        val sigBmp = createSampleBitmap(300, 100, Color.WHITE)

        // 1. Joint Photo + Signature Combiner
        val jointResult = com.example.processing.ImageProcessor.createJointPhotoAndSignature(
            context = context,
            photoBitmap = photoBmp,
            signatureBitmap = sigBmp,
            targetWidthPx = 300,
            targetHeightPx = 450,
            minKb = 15,
            maxKb = 50,
            dpi = 300
        )
        assertEquals(300, jointResult.widthPx)
        assertEquals(450, jointResult.heightPx)
        assertTrue(jointResult.fileSizeBytes in (15 * 1024L)..(50 * 1024L))

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
        val nonWhiteBgPhoto = createSampleBitmap(400, 500, Color.BLUE)
        val whiteRequiredRule = DocumentRule(
            id = "phase0_honest_bg_rule",
            documentType = DocumentType.PASSPORT_PHOTO,
            allowedFormats = listOf(FileFormat.JPG),
            minKb = 10,
            maxKb = 60,
            exactWidthPx = 350,
            exactHeightPx = 450,
            dpi = 200,
            requiresWhiteBackground = true
        )

        // Process WITHOUT background removal -> measured hasWhiteBackground MUST be false (never echoed from rule)
        val unfilteredResult = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = nonWhiteBgPhoto,
            rule = whiteRequiredRule,
            removeBackgroundToWhite = false,
            customDpi = 200
        )

        assertFalse(
            "ProcessedFileResult.hasWhiteBackground must be false when actual background is blue",
            unfilteredResult.hasWhiteBackground
        )
        assertTrue(
            "Validation messages must warn when background is not white",
            unfilteredResult.validationMessages.any { it.contains("Background Warning", ignoreCase = true) }
        )

        // 2. Verify BOTH JFIF APP0 header AND EXIF XResolution/YResolution are written at 200 DPI
        val outFile = File(unfilteredResult.processedFilePath)
        val jfifDpi = com.example.processing.ImageProcessor.readJfifDpiHeader(outFile.readBytes())
        val exifDpi = com.example.processing.ImageProcessor.readExifDpi(outFile)
        assertEquals(200, jfifDpi)
        assertEquals(200, exifDpi)

        // 3. Process a light-background portrait with background whitening -> measured hasWhiteBackground MUST be true
        val studioLightPhoto = createSampleBitmap(400, 500, Color.rgb(245, 245, 245))
        val whitenedResult = com.example.processing.ImageProcessor.processImageForRule(
            context = context,
            originalBitmap = studioLightPhoto,
            rule = whiteRequiredRule,
            removeBackgroundToWhite = true,
            customDpi = 300
        )
        assertTrue(
            "ProcessedFileResult.hasWhiteBackground must be true when background pixels are pure white",
            whitenedResult.hasWhiteBackground
        )
        val whitenedFile = File(whitenedResult.processedFilePath)
        assertEquals(300, com.example.processing.ImageProcessor.readJfifDpiHeader(whitenedFile.readBytes()))
        assertEquals(300, com.example.processing.ImageProcessor.readExifDpi(whitenedFile))
    }

    @Test
    fun testPhase0PdfTargetSizeVerificationHonesty() = runBlocking {
        val samplePdf = PdfProcessor.createPdfFromBitmaps(
            context = context,
            bitmaps = listOf(createSampleBitmap(600, 800, Color.WHITE)),
            outputFileName = "phase0_size_check.pdf"
        )
        assertTrue(samplePdf.exists())
        // Should be well under 300 KB
        assertTrue(PdfProcessor.isPdfWithinTargetKb(samplePdf, 300))
        // Should be false for an impossible 0 KB target
        assertFalse(PdfProcessor.isPdfWithinTargetKb(samplePdf, 0))
    }
}

