package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.FormReadyDatabase
import com.example.data.database.LockerDocumentDao
import com.example.data.database.LockerDocumentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentLockerDatabaseTest {

    private lateinit var database: FormReadyDatabase
    private lateinit var dao: LockerDocumentDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FormReadyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.lockerDocumentDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    @Test
    fun insertAndRetrieveDocument() = runBlocking {
        val doc = LockerDocumentEntity(
            id = "doc_test_1",
            title = "Class 10 Passing Certificate",
            documentType = "CLASS_10_CERTIFICATE",
            category = "Education",
            originalFileName = "matric_cert.pdf",
            storedEncryptedFileName = "doc_test_1.vault",
            mimeType = "application/pdf",
            fileFormat = "PDF",
            fileSizeBytes = 125400L,
            sha256Hash = "abc123hash456",
            notes = "UPSC 2025 verified"
        )

        dao.insertDocument(doc)

        val retrieved = dao.getDocumentByIdSync("doc_test_1")
        assertNotNull(retrieved)
        assertEquals("Class 10 Passing Certificate", retrieved?.title)
        assertEquals("Education", retrieved?.category)
        assertEquals(125400L, retrieved?.fileSizeBytes)
        assertEquals("AVAILABLE", retrieved?.integrityStatus)
    }

    @Test
    fun searchDocuments_matchesTitleAndNotes() = runBlocking {
        val doc1 = LockerDocumentEntity(
            id = "doc_1",
            title = "Aadhaar Card Front and Back",
            documentType = "AADHAAR_CARD",
            category = "Identity",
            originalFileName = "aadhaar.pdf",
            storedEncryptedFileName = "doc_1.vault",
            mimeType = "application/pdf",
            fileFormat = "PDF",
            fileSizeBytes = 50000L,
            sha256Hash = "hash1"
        )
        val doc2 = LockerDocumentEntity(
            id = "doc_2",
            title = "OBC Certificate Central",
            documentType = "CASTE_OBC_NCL_CENTRAL",
            category = "Reservation",
            originalFileName = "caste.jpg",
            storedEncryptedFileName = "doc_2.vault",
            mimeType = "image/jpeg",
            fileFormat = "JPG",
            fileSizeBytes = 65000L,
            sha256Hash = "hash2",
            notes = "Valid till 2026 for BPSC"
        )

        dao.insertDocument(doc1)
        dao.insertDocument(doc2)

        val searchAadhaar = dao.searchDocuments("Aadhaar").first()
        assertEquals(1, searchAadhaar.size)
        assertEquals("doc_1", searchAadhaar[0].id)

        val searchBpsc = dao.searchDocuments("BPSC").first()
        assertEquals(1, searchBpsc.size)
        assertEquals("doc_2", searchBpsc[0].id)
    }

    @Test
    fun filterByCategory() = runBlocking {
        val docEdu = LockerDocumentEntity(
            id = "doc_edu",
            title = "Graduation Degree",
            documentType = "GRADUATION_DEGREE",
            category = "Education",
            originalFileName = "degree.pdf",
            storedEncryptedFileName = "doc_edu.vault",
            mimeType = "application/pdf",
            fileFormat = "PDF",
            fileSizeBytes = 80000L,
            sha256Hash = "hashEdu"
        )
        val docId = LockerDocumentEntity(
            id = "doc_id",
            title = "PAN Card",
            documentType = "PAN_CARD",
            category = "Identity",
            originalFileName = "pan.jpg",
            storedEncryptedFileName = "doc_id.vault",
            mimeType = "image/jpeg",
            fileFormat = "JPG",
            fileSizeBytes = 45000L,
            sha256Hash = "hashId"
        )

        dao.insertDocument(docEdu)
        dao.insertDocument(docId)

        val eduDocs = dao.getDocumentsByCategory("Education").first()
        assertEquals(1, eduDocs.size)
        assertEquals("doc_edu", eduDocs[0].id)

        val idDocs = dao.getDocumentsByCategory("Identity").first()
        assertEquals(1, idDocs.size)
        assertEquals("doc_id", idDocs[0].id)
    }

    @Test
    fun updateMetadata() = runBlocking {
        val doc = LockerDocumentEntity(
            id = "doc_upd",
            title = "Initial Title",
            documentType = "OTHER_SUPPORTING",
            category = "General",
            originalFileName = "doc.pdf",
            storedEncryptedFileName = "doc_upd.vault",
            mimeType = "application/pdf",
            fileFormat = "PDF",
            fileSizeBytes = 10000L,
            sha256Hash = "hashUpd"
        )
        dao.insertDocument(doc)

        dao.updateMetadata(
            id = "doc_upd",
            title = "Updated Certificate Title",
            documentType = "CLASS_12_MARKSHEET",
            category = "Education",
            notes = "Corrected classification",
            timestamp = 1700000000000L
        )

        val updated = dao.getDocumentByIdSync("doc_upd")
        assertNotNull(updated)
        assertEquals("Updated Certificate Title", updated?.title)
        assertEquals("CLASS_12_MARKSHEET", updated?.documentType)
        assertEquals("Education", updated?.category)
        assertEquals("Corrected classification", updated?.notes)
    }

    @Test
    fun getDocumentByHash_detectsDuplicates() = runBlocking {
        val doc = LockerDocumentEntity(
            id = "doc_hash",
            title = "Original Certificate",
            documentType = "DOMICILE_RESIDENCE",
            category = "Civil",
            originalFileName = "domicile.pdf",
            storedEncryptedFileName = "doc_hash.vault",
            mimeType = "application/pdf",
            fileFormat = "PDF",
            fileSizeBytes = 20000L,
            sha256Hash = "unique_sha256_hex_value"
        )
        dao.insertDocument(doc)

        val existing = dao.getDocumentByHash("unique_sha256_hex_value")
        assertNotNull(existing)
        assertEquals("doc_hash", existing?.id)

        val nonexistent = dao.getDocumentByHash("different_hash")
        assertNull(nonexistent)
    }

    @Test
    fun deleteDocumentById() = runBlocking {
        val doc = LockerDocumentEntity(
            id = "doc_del",
            title = "Document to Delete",
            documentType = "OTHER_SUPPORTING",
            category = "General",
            originalFileName = "temp.pdf",
            storedEncryptedFileName = "doc_del.vault",
            mimeType = "application/pdf",
            fileFormat = "PDF",
            fileSizeBytes = 15000L,
            sha256Hash = "hashDel"
        )
        dao.insertDocument(doc)
        assertEquals(1, dao.getDocumentCount())

        dao.deleteDocumentById("doc_del")
        assertEquals(0, dao.getDocumentCount())
        assertNull(dao.getDocumentByIdSync("doc_del"))
    }
}
