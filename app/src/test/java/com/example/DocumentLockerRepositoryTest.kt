package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.FormReadyDatabase
import com.example.data.database.LockerDocumentDao
import com.example.data.database.LockerDocumentEntity
import com.example.data.model.DocumentType
import com.example.data.repository.DocumentLockerRepository
import com.example.storage.DocumentEncryptionManager
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentLockerRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: FormReadyDatabase
    private lateinit var dao: LockerDocumentDao
    private lateinit var encryptionManager: DocumentEncryptionManager
    private lateinit var repository: DocumentLockerRepository
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "repo_test_${System.currentTimeMillis()}").apply { mkdirs() }

        database = Room.inMemoryDatabaseBuilder(context, FormReadyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.lockerDocumentDao()

        val testKeyProvider = TestSoftwareKeyProvider.create()
        encryptionManager = DocumentEncryptionManager(context, testKeyProvider)
        repository = DocumentLockerRepository(context, dao, encryptionManager)
    }

    @After
    fun tearDown() {
        database.close()
        testDir.deleteRecursively()
    }

    private fun createValidImageFile(name: String): File {
        val file = File(testDir, name)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    @Test
    fun importFile_persistsEncryptedVaultAndRoomRecord() {
        runBlocking {
            val imageFile = createValidImageFile("my_photo.jpg")

            val result = repository.importFile(
                file = imageFile,
                title = "Passport Size Photo",
                documentType = DocumentType.PASSPORT_PHOTO,
                notes = "Test passport photo"
            )

            assertTrue("Import must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            val entity = result.getOrThrow()
            assertEquals("Passport Size Photo", entity.title)
            assertEquals("Photo", entity.category)
            assertEquals("AVAILABLE", entity.integrityStatus)

            // Verify encrypted file exists on disk in vaultDir
            val vaultFile = File(repository.vaultDir, entity.storedEncryptedFileName)
            assertTrue("Vault file must exist", vaultFile.exists())
            assertTrue("Vault file must be encrypted and larger than 20 bytes", vaultFile.length() > 20)

            // Verify Room database entry
            val dbRecord = dao.getDocumentByIdSync(entity.id)
            assertNotNull(dbRecord)
            assertEquals(entity.sha256Hash, dbRecord?.sha256Hash)
        }
    }

    @Test
    fun checkDuplicateFile_detectsExistingHash() {
        runBlocking {
            val imageFile = createValidImageFile("doc1.jpg")
            val importRes = repository.importFile(
                file = imageFile,
                title = "Aadhaar Card",
                documentType = DocumentType.AADHAAR_CARD
            )
            assertTrue(importRes.isSuccess)

            val duplicate = repository.checkDuplicateFile(imageFile)
            assertNotNull("Should detect duplicate document with identical content", duplicate)
            assertEquals(importRes.getOrThrow().id, duplicate?.id)
        }
    }

    @Test
    fun deleteDocument_removesVaultFileAndRoomRecord() {
        runBlocking {
            val imageFile = createValidImageFile("delete_me.jpg")
            val importRes = repository.importFile(
                file = imageFile,
                title = "Temporary Document",
                documentType = DocumentType.OTHER_SUPPORTING
            )
            val entity = importRes.getOrThrow()
            val vaultFile = File(repository.vaultDir, entity.storedEncryptedFileName)
            assertTrue(vaultFile.exists())

            val deleteRes = repository.deleteDocument(entity.id)
            assertTrue(deleteRes.isSuccess)

            // Verify file is gone
            assertFalse("Encrypted vault file must be deleted", vaultFile.exists())
            // Verify database entry is deleted
            assertNull("Database record must be deleted", dao.getDocumentByIdSync(entity.id))
        }
    }

    @Test
    fun exportDocumentToStream_authenticatedStreamExport() {
        runBlocking {
            val imageFile = createValidImageFile("export_source.jpg")
            val importRes = repository.importFile(
                file = imageFile,
                title = "Export Certificate",
                documentType = DocumentType.OTHER_SUPPORTING
            )
            val entity = importRes.getOrThrow()

            val outputStream = ByteArrayOutputStream()
            val exportRes = repository.exportDocumentToStream(entity.id, outputStream)

            assertTrue("Export must succeed: ${exportRes.exceptionOrNull()?.message}", exportRes.isSuccess)
            val exportedBytes = outputStream.toByteArray()
            assertEquals(imageFile.length(), exportedBytes.size.toLong())
        }
    }

    @Test
    fun tamperedVaultFile_marksIntegrityFailed_andRefusesExport() {
        runBlocking {
            val imageFile = createValidImageFile("tamper_test.jpg")
            val importRes = repository.importFile(
                file = imageFile,
                title = "Integrity Test Doc",
                documentType = DocumentType.OTHER_SUPPORTING
            )
            val entity = importRes.getOrThrow()
            val vaultFile = File(repository.vaultDir, entity.storedEncryptedFileName)

            // Tamper with bytes in vault file
            val bytes = vaultFile.readBytes()
            bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0xFF).toByte()
            vaultFile.writeBytes(bytes)

            // Attempt export
            val outStream = ByteArrayOutputStream()
            val exportRes = repository.exportDocumentToStream(entity.id, outStream)

            assertFalse("Export of tampered file must fail", exportRes.isSuccess)
            assertEquals("Zero bytes must be released from tampered file", 0, outStream.size())

            // Verify status in DB was updated to INTEGRITY_FAILED
            val updated = dao.getDocumentByIdSync(entity.id)
            assertEquals("INTEGRITY_FAILED", updated?.integrityStatus)
        }
    }

    @Test
    fun storageReconciliation_detectsMissingFilesAndOrphans() {
        runBlocking {
            // 1. Insert a document whose vault file is missing
            val missingDoc = LockerDocumentEntity(
                id = "doc_missing_1",
                title = "Missing Document",
                documentType = "OTHER_SUPPORTING",
                category = "General",
                originalFileName = "missing.pdf",
                storedEncryptedFileName = "non_existent_file.vault",
                mimeType = "application/pdf",
                fileFormat = "PDF",
                fileSizeBytes = 5000L,
                sha256Hash = "hashMissing"
            )
            dao.insertDocument(missingDoc)

            // 2. Create an orphaned vault file (file exists but no DB record)
            val orphanFile = File(repository.vaultDir, "orphan_${System.currentTimeMillis()}.vault")
            orphanFile.writeBytes(ByteArray(100) { 0x01 })

            // 3. Run reconciliation
            val report = repository.reconcileStorage()

            assertEquals(1, report.missingFilesCount)
            assertEquals(1, report.orphanedVaultFilesCount)
            assertTrue(report.orphanedVaultFileNames.contains(orphanFile.name))

            // Verify status updated in DB
            val updatedMissing = dao.getDocumentByIdSync("doc_missing_1")
            assertEquals("MISSING_FILE", updatedMissing?.integrityStatus)

            // Verify orphaned file was NOT deleted automatically
            assertTrue("Orphaned vault files must NOT be silently deleted", orphanFile.exists())
            orphanFile.delete()
        }
    }
}
