package com.example.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Key provider interface.
 * Production uses [AndroidKeystoreKeyProvider], which fails closed on hardware/keystore failures.
 * Tests can explicitly inject a test key provider.
 */
fun interface VaultKeyProvider {
    fun getSecretKey(): SecretKey
}

/**
 * Production key provider backed by Android Keystore.
 * Fails closed without falling back to process-memory keys.
 */
class AndroidKeystoreKeyProvider : VaultKeyProvider {
    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "FormReady_DocVault_Key_v1"
        const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
    }

    @Synchronized
    override fun getSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(BLOCK_MODE)
                    .setEncryptionPaddings(PADDING)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(false) // 12-byte random IV explicitly managed per encryption
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: throw KeyStoreException("Key alias '$KEY_ALIAS' exists in Keystore but is not a SecretKeyEntry")
            entry.secretKey
        } catch (e: Exception) {
            // Strictly FAIL CLOSED - never fall back to temporary software keys in production
            throw SecurityException("Android Keystore error: unable to access hardware encryption key: ${e.message}", e)
        }
    }
}

/**
 * Explicit test-only key provider for local JVM testing where AndroidKeyStore is absent.
 */
class TestSoftwareKeyProvider(private val secretKey: SecretKey) : VaultKeyProvider {
    override fun getSecretKey(): SecretKey = secretKey

    companion object {
        fun create(): TestSoftwareKeyProvider {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256, SecureRandom())
            return TestSoftwareKeyProvider(keyGen.generateKey())
        }
    }
}

/**
 * Manages authenticated AES-256-GCM encryption and decryption of documents at rest.
 * Uses Android Keystore for hardware-backed key protection.
 * Format on disk:
 *   - 8 bytes magic header: "FRVAULT1"
 *   - 12 bytes IV
 *   - AES-GCM Ciphertext + 16-byte Auth Tag (128-bit)
 */
class DocumentEncryptionManager(
    private val context: Context,
    private val keyProvider: VaultKeyProvider = AndroidKeystoreKeyProvider()
) {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private val MAGIC_HEADER = "FRVAULT1".toByteArray(Charsets.US_ASCII)
    }

    private val secureRandom = SecureRandom()

    /**
     * Reads exact number of bytes into buffer, handling partial-read streams safely.
     * Returns false if EOF reached before buffer is filled.
     */
    private fun readExact(inputStream: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = inputStream.read(buffer, offset, buffer.size - offset)
            if (count == -1) return false
            offset += count
        }
        return true
    }

    /**
     * Encrypts data from an [InputStream] into an [OutputStream] using AES-GCM-128.
     * Writes magic header, 12-byte IV, and streamed ciphertext.
     */
    fun encryptStream(inputStream: InputStream, outputStream: OutputStream) {
        val secretKey = keyProvider.getSecretKey()
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)

        outputStream.write(MAGIC_HEADER)
        outputStream.write(iv)

        val buffer = ByteArray(16384)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val update = cipher.update(buffer, 0, bytesRead)
            if (update != null && update.isNotEmpty()) {
                outputStream.write(update)
            }
        }
        val finalBytes = cipher.doFinal()
        if (finalBytes != null && finalBytes.isNotEmpty()) {
            outputStream.write(finalBytes)
        }
        outputStream.flush()
    }

    /**
     * Encrypts source file atomically into target encrypted file via temporary file.
     */
    fun encryptFileAtomically(sourceFile: File, destinationEncryptedFile: File) {
        destinationEncryptedFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val tempFile = File(destinationEncryptedFile.parentFile, "${destinationEncryptedFile.name}_${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(sourceFile).use { inStream ->
                FileOutputStream(tempFile).use { outStream ->
                    encryptStream(inStream, outStream)
                }
            }
            if (destinationEncryptedFile.exists()) {
                destinationEncryptedFile.delete()
            }
            if (!tempFile.renameTo(destinationEncryptedFile)) {
                tempFile.copyTo(destinationEncryptedFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    /**
     * Authenticated decryption:
     * Decrypts into an isolated temporary staging file in private storage first.
     * Verifies the full AES-GCM authentication tag with `doFinal()`.
     * Only after authentication passes, safely copies/renames to [destinationPlainFile].
     * If authentication fails or file is corrupt, staging file is immediately deleted,
     * destination file is never created or touched, and [SecurityException] is thrown.
     */
    fun decryptFile(encryptedFile: File, destinationPlainFile: File) {
        val stagingFile = File(context.cacheDir, "dec_stage_${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(encryptedFile).use { inStream ->
                FileOutputStream(stagingFile).use { outStream ->
                    decryptStreamInternal(inStream, outStream)
                }
            }
            // Decryption authenticated successfully!
            if (destinationPlainFile.exists()) {
                destinationPlainFile.delete()
            }
            if (!stagingFile.renameTo(destinationPlainFile)) {
                stagingFile.copyTo(destinationPlainFile, overwrite = true)
                stagingFile.delete()
            }
        } catch (e: Exception) {
            if (stagingFile.exists()) stagingFile.delete()
            throw e
        }
    }

    /**
     * Decrypts encrypted stream to unencrypted output stream with full authentication verification.
     * Uses intermediate private staging to ensure zero plaintext bytes are leaked if auth tag verification fails.
     */
    fun decryptStream(encryptedInputStream: InputStream, unencryptedOutputStream: OutputStream) {
        val stagingFile = File(context.cacheDir, "dec_stage_stream_${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(stagingFile).use { stagingOut ->
                decryptStreamInternal(encryptedInputStream, stagingOut)
            }
            // Tag verified! Now safely stream verified plaintext to destination
            FileInputStream(stagingFile).use { stagingIn ->
                val buffer = ByteArray(16384)
                var read: Int
                while (stagingIn.read(buffer).also { read = it } != -1) {
                    unencryptedOutputStream.write(buffer, 0, read)
                }
                unencryptedOutputStream.flush()
            }
        } finally {
            if (stagingFile.exists()) stagingFile.delete()
        }
    }

    /**
     * Low-level stream decryption worker.
     * Reads magic header & IV using exact-length helper, initializes Cipher, writes intermediate updates.
     * Validates GCM auth tag via `cipher.doFinal()`.
     */
    private fun decryptStreamInternal(encryptedInputStream: InputStream, unencryptedOutputStream: OutputStream) {
        val secretKey = keyProvider.getSecretKey()

        // 1. Verify magic header with exact-length read
        val header = ByteArray(MAGIC_HEADER.size)
        if (!readExact(encryptedInputStream, header) || !header.contentEquals(MAGIC_HEADER)) {
            throw SecurityException("Invalid vault format or unencrypted file header")
        }

        // 2. Read 12-byte IV with exact-length read
        val iv = ByteArray(IV_LENGTH_BYTES)
        if (!readExact(encryptedInputStream, iv)) {
            throw SecurityException("Corrupted vault file: unable to read complete IV header")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

        val buffer = ByteArray(16384)
        var bytesRead: Int
        try {
            while (encryptedInputStream.read(buffer).also { bytesRead = it } != -1) {
                val update = cipher.update(buffer, 0, bytesRead)
                if (update != null && update.isNotEmpty()) {
                    unencryptedOutputStream.write(update)
                }
            }
            val finalBytes = cipher.doFinal()
            if (finalBytes != null && finalBytes.isNotEmpty()) {
                unencryptedOutputStream.write(finalBytes)
            }
            unencryptedOutputStream.flush()
        } catch (e: Exception) {
            throw SecurityException("Decryption authentication failed: file integrity compromised or modified", e)
        }
    }

    /**
     * Authenticated decryption directly into an external OutputStream (e.g. SAF content resolver),
     * guaranteeing no partial plaintext is released if authentication fails.
     */
    fun decryptAndStreamAuthenticated(encryptedFile: File, destinationOutputStream: OutputStream): Long {
        val stagingFile = File(context.cacheDir, "dec_export_${UUID.randomUUID()}.tmp")
        try {
            // First stage: decrypt and verify tag into isolated private staging
            decryptFile(encryptedFile, stagingFile)

            // Second stage: write authenticated verified plaintext to destination
            var totalBytesWritten = 0L
            FileInputStream(stagingFile).use { inStream ->
                val buffer = ByteArray(16384)
                var read: Int
                while (inStream.read(buffer).also { read = it } != -1) {
                    destinationOutputStream.write(buffer, 0, read)
                    totalBytesWritten += read
                }
                destinationOutputStream.flush()
            }
            return totalBytesWritten
        } finally {
            if (stagingFile.exists()) stagingFile.delete()
        }
    }
}
