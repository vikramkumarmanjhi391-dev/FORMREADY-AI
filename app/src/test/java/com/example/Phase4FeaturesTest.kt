package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.FolderDao
import com.example.data.database.FolderEntity
import com.example.data.database.FormReadyDatabase
import com.example.data.database.LockerDocumentDao
import com.example.data.database.LockerDocumentEntity
import com.example.data.database.VaultNoteDao
import com.example.data.database.VaultNoteEntity
import com.example.data.model.DocumentType
import com.example.data.repository.DocumentLockerRepository
import com.example.data.repository.VaultNotesRepository
import com.example.storage.DocumentEncryptionManager
import com.example.storage.TestSoftwareKeyProvider
import kotlinx.coroutines.flow.first
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
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase4FeaturesTest {

    private lateinit var context: Context
    private lateinit var database: FormReadyDatabase
    private lateinit var lockerDao: LockerDocumentDao
    private lateinit var folderDao: FolderDao
    private lateinit var noteDao: VaultNoteDao
    private lateinit var encryptionManager: DocumentEncryptionManager
    private lateinit var notesRepository: VaultNotesRepository
    private lateinit var lockerRepository: DocumentLockerRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FormReadyDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        lockerDao = database.lockerDocumentDao()
        folderDao = database.folderDao()
        noteDao = database.vaultNoteDao()
        val testKeyProvider = TestSoftwareKeyProvider.create()
        encryptionManager = DocumentEncryptionManager(context, testKeyProvider)

        notesRepository = VaultNotesRepository(
            context = context,
            dao = noteDao,
            encryptionManager = encryptionManager
        )

        lockerRepository = DocumentLockerRepository(
            context = context,
            dao = lockerDao,
            folderDao = folderDao,
            encryptionManager = encryptionManager
        )
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        database.close()
        // Clean test vault files
        File(context.noBackupFilesDir, "document_locker").deleteRecursively()
    }

    @Test
    fun testFolderCreationAndDocumentCount(): Unit {
        runBlocking {
            val folderId = "folder_upsc_2026"
            val folder = FolderEntity(
                id = folderId,
                name = "UPSC CSE 2026",
                colorHex = "#2563EB",
                iconName = "folder",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            folderDao.insertFolder(folder)

            val initialFoldersWithCount = folderDao.getAllFoldersWithCounts().first()
            assertEquals(1, initialFoldersWithCount.size)
            assertEquals("UPSC CSE 2026", initialFoldersWithCount[0].folder.name)
            assertEquals(0, initialFoldersWithCount[0].documentCount)

            // Insert 2 documents assigned to this folder
            val doc1 = LockerDocumentEntity(
                id = "doc_p4_1",
                title = "Aadhaar Card",
                documentType = DocumentType.AADHAAR_CARD.name,
                category = "Identity",
                originalFileName = "aadhaar.pdf",
                storedEncryptedFileName = "doc_p4_1.vault",
                mimeType = "application/pdf",
                fileFormat = "PDF",
                fileSizeBytes = 50000L,
                sha256Hash = "hash1",
                folderId = folderId,
                isImportant = true
            )
            val doc2 = LockerDocumentEntity(
                id = "doc_p4_2",
                title = "Degree Certificate",
                documentType = DocumentType.GRADUATION_DEGREE.name,
                category = "Education",
                originalFileName = "degree.pdf",
                storedEncryptedFileName = "doc_p4_2.vault",
                mimeType = "application/pdf",
                fileFormat = "PDF",
                fileSizeBytes = 75000L,
                sha256Hash = "hash2",
                folderId = folderId,
                isImportant = false
            )
            lockerDao.insertDocument(doc1)
            lockerDao.insertDocument(doc2)

            val updatedFoldersWithCount = folderDao.getAllFoldersWithCounts().first()
            assertEquals(2, updatedFoldersWithCount[0].documentCount)

            // Verify important docs query
            val importantDocs = lockerDao.getImportantDocuments().first()
            assertEquals(1, importantDocs.size)
            assertEquals("Aadhaar Card", importantDocs[0].title)

            // Verify category counts
            val categoryCounts = lockerDao.getCategoryCounts().first().associate { it.category to it.count }
            assertEquals(1, categoryCounts["Identity"])
            assertEquals(1, categoryCounts["Education"])
        }
    }

    @Test
    fun testFolderDeletionPreservesDocuments(): Unit {
        runBlocking {
            val folderId = "folder_to_delete"
            val folder = FolderEntity(
                id = folderId,
                name = "Temporary Forms",
                colorHex = "#10B981",
                iconName = "folder",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            folderDao.insertFolder(folder)

            val doc = LockerDocumentEntity(
                id = "doc_preserve_test",
                title = "Caste Certificate",
                documentType = DocumentType.CASTE_SC_ST.name,
                category = "Certificates",
                originalFileName = "caste.pdf",
                storedEncryptedFileName = "doc_preserve_test.vault",
                mimeType = "application/pdf",
                fileFormat = "PDF",
                fileSizeBytes = 60000L,
                sha256Hash = "hash_caste",
                folderId = folderId
            )
            lockerDao.insertDocument(doc)

            // Delete folder via repository (safe deletion: unassigns documents first)
            lockerRepository.deleteFolder(folderId)

            // Verify folder is gone
            val folders = folderDao.getAllFolders().first()
            assertTrue(folders.isEmpty())

            // Verify document still exists in database and folderId is null
            val preservedDoc = lockerDao.getDocumentByIdSync("doc_preserve_test")
            assertNotNull(preservedDoc)
            assertEquals("Caste Certificate", preservedDoc?.title)
            assertNull(preservedDoc?.folderId)
        }
    }

    @Test
    fun testEncryptedNotesCrudAndHardwareEncryptionAtRest(): Unit {
        runBlocking {
            val title = "SBI PO Credentials"
            val secretBody = "Registration No: 987654321, Password: SecretKey@2026"

            val createResult = notesRepository.createNote(
                title = title,
                content = secretBody,
                colorTag = "#2563EB",
                isPinned = true
            )
            createResult.exceptionOrNull()?.printStackTrace()
            assertTrue("Failed to create note: ${createResult.exceptionOrNull()?.message}", createResult.isSuccess)
            val noteEntity = createResult.getOrThrow()

            // Verify metadata in Room
            assertEquals("SBI PO Credentials", noteEntity.title)
            assertTrue(noteEntity.isPinned)
            assertTrue(noteEntity.previewSnippet.contains("Registration No: 987654321"))

            // Verify that the file stored on disk is ENCRYPTED (does NOT contain the secret plaintext)
            val notesDir = File(context.noBackupFilesDir, "document_locker/notes")
            val encryptedFile = File(notesDir, noteEntity.storedEncryptedFileName)
            assertTrue("Encrypted note file must exist on disk", encryptedFile.exists())
            val rawBytes = encryptedFile.readBytes()
            val rawString = String(rawBytes, Charsets.ISO_8859_1)
            assertFalse(
                "Plaintext secret must NEVER appear unencrypted on disk",
                rawString.contains("SecretKey@2026")
            )

            // Verify decryption produces exact original content
            val decryptResult = notesRepository.getDecryptedNoteContent(noteEntity.id)
            assertTrue(decryptResult.isSuccess)
            assertEquals(secretBody, decryptResult.getOrThrow())

            // Verify update note
            val updatedBody = "Registration No: 987654321, Password: UpdatedPassword#99"
            val updateResult = notesRepository.updateNote(
                noteId = noteEntity.id,
                title = "SBI PO Credentials Updated",
                content = updatedBody,
                colorTag = "#10B981",
                isPinned = false
            )
            assertTrue(updateResult.isSuccess)

            val updatedDecrypted = notesRepository.getDecryptedNoteContent(noteEntity.id).getOrThrow()
            assertEquals(updatedBody, updatedDecrypted)

            // Verify delete note removes database record and disk file
            val deleteResult = notesRepository.deleteNote(noteEntity.id)
            assertTrue(deleteResult.isSuccess)
            assertFalse(encryptedFile.exists())
            val allNotes = noteDao.getAllNotes().first()
            assertTrue(allNotes.isEmpty())
        }
    }

    @Test
    fun testToggleImportantDocument(): Unit {
        runBlocking {
            val doc = LockerDocumentEntity(
                id = "doc_star_test",
                title = "Passport Photo",
                documentType = DocumentType.PASSPORT_PHOTO.name,
                category = "Photo",
                originalFileName = "photo.jpg",
                storedEncryptedFileName = "doc_star_test.vault",
                mimeType = "image/jpeg",
                fileFormat = "JPEG",
                fileSizeBytes = 30000L,
                sha256Hash = "hash_photo",
                isImportant = false
            )
            lockerDao.insertDocument(doc)

            lockerRepository.toggleImportant("doc_star_test", true)
            val updatedDoc = lockerDao.getDocumentByIdSync("doc_star_test")
            assertNotNull(updatedDoc)
            assertTrue(updatedDoc!!.isImportant)

            lockerRepository.toggleImportant("doc_star_test", false)
            val unstarredDoc = lockerDao.getDocumentByIdSync("doc_star_test")
            assertNotNull(unstarredDoc)
            assertFalse(unstarredDoc!!.isImportant)
        }
    }
}
