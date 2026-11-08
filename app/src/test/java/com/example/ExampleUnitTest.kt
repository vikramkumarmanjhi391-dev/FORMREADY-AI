package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.VerificationStatus
import com.example.data.portal.PortalDatabase
import com.example.data.portal.SmartRuleExtractor
import com.example.processing.ImageProcessor
import com.example.processing.QualityInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Test
    fun testPortalDatabase_verificationStatusesAndSources() {
        val portals = PortalDatabase.getAllPortals()
        assertTrue("Portals should not be empty", portals.isNotEmpty())

        val upsc = portals.find { it.id == "upsc_cse" }
        assertNotNull("UPSC CSE portal should exist", upsc)
        assertEquals(VerificationStatus.OFFICIAL_SOURCE_REVIEW, upsc?.verificationStatus)
        assertTrue(upsc?.sourceDocumentTitle?.isNotBlank() == true)
        assertTrue(upsc?.applicableWorkflow?.isNotBlank() == true)

        val universal = portals.find { it.id == "universal_passport" }
        assertNotNull("Universal preset should exist", universal)
        assertEquals(VerificationStatus.USER_SUPPLIED, universal?.verificationStatus)

        val bpsc = portals.find { it.id == "bpsc_cce" }
        assertNotNull("BPSC portal should exist", bpsc)
        assertEquals(VerificationStatus.OFFICIAL_SOURCE_REVIEW, bpsc?.verificationStatus)

        val sarathi = portals.find { it.id == "sarathi_parivahan" }
        assertNotNull("Sarathi portal should exist", sarathi)
        assertEquals(VerificationStatus.NEEDS_REVERIFICATION, sarathi?.verificationStatus)
    }

    @Test
    fun testSmartRuleExtractor_detectsPhotoAndSignRules() {
        val sampleNotice = """
            Candidate photograph must be between 20 kb to 50 kb, dimensions 200 x 230 pixels with plain white background.
            Signature in running hand must be between 10 kb to 20 kb.
            Class 10 certificate in PDF format not exceed 300 kb.
        """.trimIndent()

        val extracted = SmartRuleExtractor.extractRulesFromNotificationText(
            portalName = "SSC Test",
            examName = "Test Exam",
            rawText = sampleNotice
        )

        val photoRule = extracted.documents.find { it.documentType == DocumentType.PASSPORT_PHOTO }
        assertNotNull("Photo rule should be extracted", photoRule)
        assertEquals(20, photoRule?.minKb)
        assertEquals(50, photoRule?.maxKb)
        assertEquals(200, photoRule?.exactWidthPx)
        assertEquals(230, photoRule?.exactHeightPx)

        val signRule = extracted.documents.find { it.documentType == DocumentType.SIGNATURE_ENGLISH }
        assertNotNull("Signature rule should be extracted", signRule)
        assertEquals(10, signRule?.minKb)
        assertEquals(20, signRule?.maxKb)
    }

    @Test
    fun testCropToAspectRatio_producesAccurateDimensions() {
        val landscape = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val square = ImageProcessor.cropToAspectRatio(landscape, 1.0f)
        assertEquals(200, square.width)
        assertEquals(200, square.height)

        val portrait = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        val squareFromPortrait = ImageProcessor.cropToAspectRatio(portrait, 1.0f)
        assertEquals(200, squareFromPortrait.width)
        assertEquals(200, squareFromPortrait.height)
    }

    @Test
    fun testCropBlankMargins_preservesStrokeArea() {
        // 300x150 white canvas with black pixels in the center
        val canvas = Bitmap.createBitmap(300, 150, Bitmap.Config.ARGB_8888)
        canvas.eraseColor(Color.WHITE)

        // Draw inked region from x=100..200, y=60..90 directly into bitmap
        for (x in 100..200) {
            for (y in 60..90) {
                canvas.setPixel(x, y, Color.BLACK)
            }
        }

        val cropped = ImageProcessor.cropBlankMargins(canvas)
        // Cropped width and height must be strictly smaller than original 300x150, but larger than 90
        assertTrue("Cropped width (${cropped.width}) should be less than original 300", cropped.width < 300)
        assertTrue("Cropped height (${cropped.height}) should be less than original 150", cropped.height < 150)
        assertTrue("Cropped width should retain margin padding", cropped.width > 90)
    }

    @Test
    fun testQualityInspector_validatesActualDiskFile() {
        val tempFile = File.createTempFile("test_doc_", ".jpg")
        FileOutputStream(tempFile).use { it.write(ByteArray(25 * 1024)) } // 25 KB file

        val rule = DocumentRule(
            id = "rule_test",
            documentType = DocumentType.PASSPORT_PHOTO,
            minKb = 20,
            maxKb = 50,
            exactWidthPx = 200,
            exactHeightPx = 230
        )

        val summary = QualityInspector.validateFileAgainstRule(tempFile, rule, widthPx = 200, heightPx = 230)
        assertTrue("25 KB file should be valid for 20-50 KB rule", summary.isValid)
        assertEquals(100, summary.complianceScore)

        // Test over limit file (65 KB)
        FileOutputStream(tempFile).use { it.write(ByteArray(65 * 1024)) }
        val failSummary = QualityInspector.validateFileAgainstRule(tempFile, rule, widthPx = 200, heightPx = 230)
        assertFalse("65 KB file should fail 50 KB max limit", failSummary.isValid)

        tempFile.delete()
    }
}
