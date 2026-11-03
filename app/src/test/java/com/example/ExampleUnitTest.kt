package com.example

import com.example.data.portal.PortalDatabase
import com.example.data.portal.SmartRuleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testPortalDatabase_containsOfficialPortals() {
        val portals = PortalDatabase.getAllPortals()
        assertTrue("Portals should not be empty", portals.isNotEmpty())

        val upsc = portals.find { it.id == "upsc_cse" }
        assertNotNull("UPSC CSE portal should exist", upsc)
        assertEquals("Central", upsc?.stateOrCentral)

        val ssc = portals.find { it.id == "ssc_cgl" }
        assertNotNull("SSC CGL portal should exist", ssc)
    }

    @Test
    fun testSmartRuleExtractor_detectsPhotoAndSignRules() {
        val sampleNotice = """
            Important Instructions:
            Candidate's photograph must be between 20 kb to 50 kb, dimensions 200 x 230 pixels with plain white background.
            Signature in running hand must be between 10 kb to 20 kb.
            Class 10 certificate in PDF format not exceed 300 kb.
        """.trimIndent()

        val extracted = SmartRuleExtractor.extractRulesFromNotificationText(
            portalName = "SSC Test",
            examName = "Test Exam",
            rawText = sampleNotice
        )

        val photoRule = extracted.documents.find { it.documentType == com.example.data.model.DocumentType.PASSPORT_PHOTO }
        assertNotNull("Photo rule should be extracted", photoRule)
        assertEquals(20, photoRule?.minKb)
        assertEquals(50, photoRule?.maxKb)
        assertEquals(200, photoRule?.exactWidthPx)
        assertEquals(230, photoRule?.exactHeightPx)

        val signRule = extracted.documents.find { it.documentType == com.example.data.model.DocumentType.SIGNATURE_ENGLISH }
        assertNotNull("Signature rule should be extracted", signRule)
        assertEquals(10, signRule?.minKb)
        assertEquals(20, signRule?.maxKb)
    }
}
