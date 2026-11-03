package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.storage.DocumentEncryptionManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentEncryptionTest {

    private lateinit var encryptionManager: DocumentEncryptionManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        encryptionManager = DocumentEncryptionManager(context)
    }

    @Test
    fun encryptAndDecryptStream_recoversExactPlaintext() {
        val originalText = "Confidential Government Application Certificate: Roll No 12345678"
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        val encryptedOut = ByteArrayOutputStream()
        encryptionManager.encryptStream(ByteArrayInputStream(originalBytes), encryptedOut)
        val encryptedData = encryptedOut.toByteArray()

        // 1. Verify encrypted data has header (8 bytes) + IV (12 bytes) + ciphertext + tag
        assertTrue(encryptedData.size > originalBytes.size)
        val header = String(encryptedData.sliceArray(0..7), Charsets.US_ASCII)
        assertEquals("FRVAULT1", header)

        // 2. Verify encrypted data does NOT contain plaintext
        val encryptedString = String(encryptedData, Charsets.ISO_8859_1)
        assertFalse(encryptedString.contains(originalText))

        // 3. Decrypt and verify exact match
        val decryptedOut = ByteArrayOutputStream()
        encryptionManager.decryptStream(ByteArrayInputStream(encryptedData), decryptedOut)
        val decryptedBytes = decryptedOut.toByteArray()

        assertArrayEquals(originalBytes, decryptedBytes)
        assertEquals(originalText, String(decryptedBytes, Charsets.UTF_8))
    }

    @Test
    fun separateEncryptions_useUniqueIVs() {
        val plaintext = "Same Plaintext For Multiple Encryptions".toByteArray(Charsets.UTF_8)

        val out1 = ByteArrayOutputStream()
        encryptionManager.encryptStream(ByteArrayInputStream(plaintext), out1)
        val enc1 = out1.toByteArray()

        val out2 = ByteArrayOutputStream()
        encryptionManager.encryptStream(ByteArrayInputStream(plaintext), out2)
        val enc2 = out2.toByteArray()

        // Extract 12-byte IVs (bytes 8..19)
        val iv1 = enc1.sliceArray(8..19)
        val iv2 = enc2.sliceArray(8..19)

        assertFalse("Unique random IVs must be generated for each file", iv1.contentEquals(iv2))
        assertFalse("Ciphertext must differ due to unique IVs", enc1.contentEquals(enc2))
    }

    @Test
    fun modifiedCiphertext_throwsSecurityException() {
        val original = "Tamper Detection Test Content".toByteArray(Charsets.UTF_8)
        val encryptedOut = ByteArrayOutputStream()
        encryptionManager.encryptStream(ByteArrayInputStream(original), encryptedOut)
        val encryptedBytes = encryptedOut.toByteArray()

        // Tamper with one byte in the ciphertext body
        val tamperedBytes = encryptedBytes.clone()
        val tamperIndex = tamperedBytes.size - 5
        tamperedBytes[tamperIndex] = (tamperedBytes[tamperIndex].toInt() xor 0xFF).toByte()

        try {
            val decryptedOut = ByteArrayOutputStream()
            encryptionManager.decryptStream(ByteArrayInputStream(tamperedBytes), decryptedOut)
            fail("Expected SecurityException on tampered ciphertext/auth tag")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("integrity") == true || e.message?.contains("authentication") == true)
        }
    }

    @Test
    fun fileEncryptionAtomicity() {
        val testDir = File(context.cacheDir, "encryption_test").apply { mkdirs() }
        val sourceFile = File(testDir, "test_source.txt").apply {
            writeText("Source document content for atomic file test")
        }
        val targetEncryptedFile = File(testDir, "test_target.vault")
        val decryptedFile = File(testDir, "test_decrypted.txt")

        try {
            encryptionManager.encryptFileAtomically(sourceFile, targetEncryptedFile)
            assertTrue(targetEncryptedFile.exists())
            assertTrue(targetEncryptedFile.length() > sourceFile.length())

            encryptionManager.decryptFile(targetEncryptedFile, decryptedFile)
            assertTrue(decryptedFile.exists())
            assertEquals(sourceFile.readText(), decryptedFile.readText())
        } finally {
            sourceFile.delete()
            targetEncryptedFile.delete()
            decryptedFile.delete()
        }
    }
}
