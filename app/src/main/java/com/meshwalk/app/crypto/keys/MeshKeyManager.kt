package com.meshwalk.app.crypto.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.meshwalk.app.domain.usecase.CryptoManagerPort
import com.meshwalk.app.domain.usecase.IdentityKeyPair
import timber.log.Timber
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages cryptographic keys for the mesh network.
 *
 * Identity keys: Ed25519-like (using EC with Curve25519 emulated via P-256 on Android)
 * Session keys: X25519-like key agreement -> HKDF -> AES-256-GCM
 *
 * NOTE: Android's native Keystore doesn't support Curve25519 directly on all devices.
 * We use ECDH with P-256 as a practical alternative that's universally supported.
 * For production, consider using libsodium-jni for true Curve25519 support.
 *
 * Local data encryption key is stored in Android Keystore (hardware-backed when available).
 */
@Singleton
class MeshKeyManager @Inject constructor(
    private val keyStorage: KeyStorage
) : CryptoManagerPort {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LOCAL_ENCRYPTION_KEY_ALIAS = "meshwalk_local_key"
        private const val EC_CURVE = "secp256r1"
        private const val KEY_AGREEMENT_ALGO = "ECDH"
        private const val SIGNATURE_ALGO = "SHA256withECDSA"
    }

    /**
     * Generate a new identity key pair.
     * - Signing key pair for message authentication (ECDSA)
     * - Exchange key pair for key agreement (ECDH)
     */
    override fun generateIdentityKeyPair(): IdentityKeyPair {
        val signingPair = generateECKeyPair()
        val exchangePair = generateECKeyPair()

        return IdentityKeyPair(
            signingPublicKey = signingPair.public.encoded,
            signingPrivateKey = signingPair.private.encoded,
            exchangePublicKey = exchangePair.public.encoded,
            exchangePrivateKey = exchangePair.private.encoded
        )
    }

    /**
     * Perform ECDH key agreement to derive a shared secret.
     */
    fun performKeyAgreement(
        ourPrivateKey: PrivateKey,
        theirPublicKey: PublicKey
    ): ByteArray {
        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGO)
        keyAgreement.init(ourPrivateKey)
        keyAgreement.doPhase(theirPublicKey, true)
        return keyAgreement.generateSecret()
    }

    /**
     * Derive a session key from shared secret using HKDF-like derivation.
     * Uses SHA-256 based extraction and expansion.
     */
    fun deriveSessionKey(
        sharedSecret: ByteArray,
        info: ByteArray,
        salt: ByteArray = ByteArray(32)
    ): SecretKey {
        val hkdfResult = hkdfExpand(
            hkdfExtract(salt, sharedSecret),
            info,
            32 // AES-256
        )
        return SecretKeySpec(hkdfResult, "AES")
    }

    /**
     * Sign data with our identity signing key.
     */
    fun sign(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGO)
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    /**
     * Verify a signature against a public key.
     */
    fun verify(data: ByteArray, signatureBytes: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val signature = Signature.getInstance(SIGNATURE_ALGO)
            signature.initVerify(publicKey)
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            Timber.w(e, "Signature verification failed")
            false
        }
    }

    /**
     * Get or create the local data encryption key from Android Keystore.
     * This key never leaves the secure hardware (if TEE/StrongBox available).
     */
    fun getLocalEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        return if (keyStore.containsAlias(LOCAL_ENCRYPTION_KEY_ALIAS)) {
            (keyStore.getEntry(LOCAL_ENCRYPTION_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    LOCAL_ENCRYPTION_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false) // App handles auth
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    /**
     * Generate pre-keys for asynchronous key exchange.
     * Pre-keys are ephemeral key pairs published during discovery so that
     * other nodes can initiate encrypted sessions without direct contact.
     */
    fun generatePreKeys(count: Int = 10): List<PreKey> {
        return (0 until count).map { index ->
            val keyPair = generateECKeyPair()
            PreKey(
                preKeyId = index,
                publicKey = keyPair.public.encoded,
                privateKey = keyPair.private.encoded,
                createdAt = System.currentTimeMillis()
            )
        }
    }

    // -- Internal helpers --

    private fun generateECKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec(EC_CURVE))
        return keyGen.generateKeyPair()
    }

    /**
     * HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
     */
    private fun hkdfExtract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(inputKeyMaterial)
    }

    /**
     * HKDF-Expand: OKM = T(1) || T(2) || ... truncated to length
     * T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i: Byte = 1

        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(i))
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            i++
            mac.reset()
        }
        return result
    }
}

data class PreKey(
    val preKeyId: Int,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreKey) return false
        return preKeyId == other.preKeyId
    }

    override fun hashCode(): Int = preKeyId
}
