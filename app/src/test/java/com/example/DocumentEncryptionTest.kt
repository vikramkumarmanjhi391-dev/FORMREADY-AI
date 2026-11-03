package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.storage.AndroidKeystoreKeyProvider
import com.example.storage.DocumentEncryptionManager
import com.example.storage.TestSoftwareKeyProvider
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

    private lateinit var context: Context
    private lateinit var encryptionManager: DocumentEncryptionManager
    private lateinit var testKeyProvider: TestSoftwareKeyProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testKeyProvider = TestSoftwareKeyProvider.create()
        encryptionManager = DocumentEncryptionManager(context, testKeyProvider)
    }

    @Test
    fun keystoreProvider_failsClosedWhenHardwareKeystoreUnavailable() {
        val keystoreProvider = AndroidKeystoreKeyProvider()
        try {
            val key = keystoreProvider.getSecretKey()
            // If the environment supports hardware KeyStore, key must be non-null and AES
            assertEquals("AES", key.algorithm)
        } catch (e: SecurityException) {
            // In environments lacking KeyStore, it must FAIL CLOSED with SecurityException
            assertTrue(e.message?.contains("Keystore") == true || e.message?.contains("keystore") == true)
        }
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
    fun modifiedCiphertext_throwsSecurityException_andReleasesZeroPlaintext() {
        val testDir = File(context.cacheDir, "tamper_test").apply { mkdirs() }
        val sourceFile = File(testDir, "source.txt").apply { writeText("Secret User Identity Data") }
        val vaultFile = File(testDir, "doc.vault")
        val destinationPlain = File(testDir, "dest_plain.txt")

        try {
            encryptionManager.encryptFileAtomically(sourceFile, vaultFile)
            assertTrue(vaultFile.exists())

            // Tamper with bytes in ciphertext
            val vaultBytes = vaultFile.readBytes()
            vaultBytes[vaultBytes.size - 5] = (vaultBytes[vaultBytes.size - 5].toInt() xor 0xFF).toByte()
            vaultFile.writeBytes(vaultBytes)

            // Attempt authenticated decryption
            try {
                encryptionManager.decryptFile(vaultFile, destinationPlain)
                fail("Expected SecurityException on tampered ciphertext/auth tag")
            } catch (e: SecurityException) {
                // Verified exception thrown
            }

            // CRITICAL: Ensure destination plain file was NEVER created or written
            assertFalse("Tampered file must NEVER write plaintext to destination", destinationPlain.exists())

            // Test decryptAndStreamAuthenticated also releases ZERO bytes to destination stream
            val outputStream = ByteArrayOutputStream()
            try {
                encryptionManager.decryptAndStreamAuthenticated(vaultFile, outputStream)
                fail("Expected SecurityException from decryptAndStreamAuthenticated")
            } catch (e: SecurityException) {
                // Expected
            }
            assertEquals("Destination stream must have received 0 bytes on authentication failure", 0, outputStream.size())
        } finally {
            sourceFile.delete()
            vaultFile.delete()
            destinationPlain.delete()
        }
    }

    @Test
    fun truncatedHeader_throwsSecurityException() {
        val truncatedHeader = byteArrayOf(0x46, 0x52, 0x56) // Only "FRV" (3 bytes instead of 8)
        try {
            val decryptedOut = ByteArrayOutputStream()
            encryptionManager.decryptStream(ByteArrayInputStream(truncatedHeader), decryptedOut)
            fail("Expected SecurityException on truncated header")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("header") == true || e.message?.contains("vault") == true)
        }
    }

    @Test
    fun truncatedIV_throwsSecurityException() {
        val magicHeader = "FRVAULT1".toByteArray(Charsets.US_ASCII)
        val shortIv = ByteArray(5) { 0x01 } // Only 5 bytes instead of 12
        val malformedStream = magicHeader + shortIv

        try {
            val decryptedOut = ByteArrayOutputStream()
            encryptionManager.decryptStream(ByteArrayInputStream(malformedStream), decryptedOut)
            fail("Expected SecurityException on truncated IV")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("IV") == true || e.message?.contains("vault") == true)
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
