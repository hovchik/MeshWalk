package com.meshwalk.app.crypto

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Tests for AES-256-GCM encryption/decryption used in message envelopes.
 * These test the crypto primitives directly without Android dependencies.
 */
class AesGcmTest {

    private val secureRandom = SecureRandom()

    @Test
    fun `encrypt and decrypt round trip succeeds`() {
        val key = generateAesKey()
        val plaintext = "Hello, MeshWalk!".toByteArray()
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "sender|recipient|0".toByteArray()

        val ciphertext = encryptGcm(plaintext, key, nonce, aad)
        val decrypted = decryptGcm(ciphertext, key, nonce, aad)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `wrong key fails decryption`() {
        val key1 = generateAesKey()
        val key2 = generateAesKey()
        val plaintext = "Secret message".toByteArray()
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "test-aad".toByteArray()

        val ciphertext = encryptGcm(plaintext, key1, nonce, aad)

        assertThrows(Exception::class.java) {
            decryptGcm(ciphertext, key2, nonce, aad)
        }
    }

    @Test
    fun `wrong AAD fails authentication`() {
        val key = generateAesKey()
        val plaintext = "Authenticated data".toByteArray()
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }

        val ciphertext = encryptGcm(plaintext, key, nonce, "correct-aad".toByteArray())

        assertThrows(Exception::class.java) {
            decryptGcm(ciphertext, key, nonce, "wrong-aad".toByteArray())
        }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val key = generateAesKey()
        val plaintext = "Don't tamper with me".toByteArray()
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "aad".toByteArray()

        val ciphertext = encryptGcm(plaintext, key, nonce, aad)

        // Flip a bit
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()

        assertThrows(Exception::class.java) {
            decryptGcm(ciphertext, key, nonce, aad)
        }
    }

    @Test
    fun `empty plaintext works`() {
        val key = generateAesKey()
        val plaintext = ByteArray(0)
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "aad".toByteArray()

        val ciphertext = encryptGcm(plaintext, key, nonce, aad)
        val decrypted = decryptGcm(ciphertext, key, nonce, aad)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `large payload encrypts correctly`() {
        val key = generateAesKey()
        val plaintext = ByteArray(50_000).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "large-payload".toByteArray()

        val ciphertext = encryptGcm(plaintext, key, nonce, aad)
        val decrypted = decryptGcm(ciphertext, key, nonce, aad)

        assertArrayEquals(plaintext, decrypted)
    }

    // -- Helpers --

    private fun generateAesKey(): javax.crypto.SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    private fun encryptGcm(
        plaintext: ByteArray,
        key: javax.crypto.SecretKey,
        nonce: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun decryptGcm(
        ciphertext: ByteArray,
        key: javax.crypto.SecretKey,
        nonce: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
