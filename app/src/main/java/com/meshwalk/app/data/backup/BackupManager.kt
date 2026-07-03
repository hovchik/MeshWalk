package com.meshwalk.app.data.backup

import android.util.Base64
import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.domain.model.*
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.usecase.IdentityKeyPair
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passphrase-protected export/import of the user's identity and chat history.
 *
 * Identity private keys live only in DataStore, so a lost device means a lost
 * identity and all conversations. This produces a single encrypted blob the
 * user can save anywhere and restore on a new device.
 *
 * Bundle format (all one AES-256-GCM ciphertext):
 *   [MAGIC:4]["MWBK"][version:1][saltLen:1][salt][nonceLen:1][nonce][ciphertext]
 * Key derivation: PBKDF2-HMAC-SHA256 over the passphrase, 210k iterations.
 * Plaintext is a JSON document with identity, conversations, and messages.
 */
@Singleton
class BackupManager @Inject constructor(
    private val keyStorage: KeyStorage,
    private val identityRepo: IdentityRepository,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository
) {
    companion object {
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
        private const val VERSION: Byte = 1
        private const val PBKDF2_ITERATIONS = 210_000
        private const val SALT_LEN = 16
        private const val GCM_NONCE_LEN = 12
        private const val GCM_TAG_BITS = 128
        private const val AES_KEY_BITS = 256
    }

    class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val secureRandom = SecureRandom()

    /**
     * Build an encrypted backup of the active identity and all chat history.
     * @throws BackupException if there is no active identity or a key is missing.
     */
    suspend fun export(passphrase: String): ByteArray {
        require(passphrase.length >= 6) { "Passphrase must be at least 6 characters" }

        val identity = identityRepo.getActiveIdentity()
            ?: throw BackupException("No active identity to back up")

        val signingPriv = keyStorage.getSigningPrivateKey(identity.nodeId)
            ?: throw BackupException("Signing private key missing for active identity")
        val exchangePriv = keyStorage.getExchangePrivateKey(identity.nodeId)
            ?: throw BackupException("Exchange private key missing for active identity")

        val json = JSONObject().apply {
            put("v", VERSION.toInt())
            put("identity", JSONObject().apply {
                put("nodeId", identity.nodeId)
                put("displayName", identity.displayName ?: JSONObject.NULL)
                put("identityType", identity.identityType.name)
                put("signingPublicKey", identity.publicSigningKey.b64())
                put("signingPrivateKey", signingPriv.b64())
                put("exchangePublicKey", identity.publicExchangeKey.b64())
                put("exchangePrivateKey", exchangePriv.b64())
            })
            put("conversations", JSONArray().apply {
                conversationRepo.getAllConversations().forEach { put(it.toJson()) }
            })
            put("messages", JSONArray().apply {
                messageRepo.getAllMessages().forEach { put(it.toJson()) }
            })
        }

        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        return encrypt(plaintext, passphrase)
    }

    /**
     * Restore identity keys and chat history from an encrypted backup.
     * Existing conversations/messages with the same IDs are overwritten.
     * @throws BackupException on wrong passphrase or corrupt data.
     */
    suspend fun import(blob: ByteArray, passphrase: String) {
        val plaintext = decrypt(blob, passphrase)
        val json = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("Backup is corrupt or the passphrase is wrong", e)
        }

        val idJson = json.getJSONObject("identity")
        val nodeId = idJson.getString("nodeId")
        val keyPair = IdentityKeyPair(
            signingPublicKey = idJson.getString("signingPublicKey").unb64(),
            signingPrivateKey = idJson.getString("signingPrivateKey").unb64(),
            exchangePublicKey = idJson.getString("exchangePublicKey").unb64(),
            exchangePrivateKey = idJson.getString("exchangePrivateKey").unb64()
        )
        keyStorage.storeIdentityKeys(nodeId, keyPair)
        keyStorage.setActiveNodeId(nodeId)

        val convArray = json.optJSONArray("conversations") ?: JSONArray()
        for (i in 0 until convArray.length()) {
            conversationRepo.createConversation(convArray.getJSONObject(i).toConversation())
        }

        val msgArray = json.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgArray.length()) {
            messageRepo.saveMessage(msgArray.getJSONObject(i).toMessage())
        }

        Timber.d("Restored identity $nodeId with ${convArray.length()} conversations, ${msgArray.length()} messages")
    }

    // -- Crypto --

    private fun encrypt(plaintext: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(GCM_NONCE_LEN).also { secureRandom.nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        return ByteBuffer.allocate(MAGIC.size + 1 + 1 + salt.size + 1 + nonce.size + ciphertext.size)
            .put(MAGIC)
            .put(VERSION)
            .put(salt.size.toByte()).put(salt)
            .put(nonce.size.toByte()).put(nonce)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        try {
            val buffer = ByteBuffer.wrap(blob)
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) throw BackupException("Not a MeshWalk backup file")
            buffer.get() // version
            val salt = ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }
            val nonce = ByteArray(buffer.get().toInt() and 0xFF).also { buffer.get(it) }
            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            return cipher.doFinal(ciphertext)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            // GCM tag failure (wrong passphrase) or malformed buffer both land here.
            throw BackupException("Wrong passphrase or corrupt backup", e)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // -- JSON helpers --

    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unb64() = Base64.decode(this, Base64.NO_WRAP)

    private fun Conversation.toJson() = JSONObject().apply {
        put("conversationId", conversationId)
        put("type", type.name)
        put("title", title ?: JSONObject.NULL)
        put("participants", JSONArray(participants))
        put("peerDisplayName", peerDisplayName ?: JSONObject.NULL)
        put("createdAt", createdAt)
        put("nickname", nickname ?: JSONObject.NULL)
        put("isFavorite", isFavorite)
        put("messageTtlMs", messageTtlMs ?: JSONObject.NULL)
    }

    private fun JSONObject.toConversation() = Conversation(
        conversationId = getString("conversationId"),
        type = runCatching { ConversationType.valueOf(getString("type")) }.getOrDefault(ConversationType.DIRECT),
        title = optStringOrNull("title"),
        participants = optJSONArray("participants")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList(),
        peerDisplayName = optStringOrNull("peerDisplayName"),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        nickname = optStringOrNull("nickname"),
        isFavorite = optBoolean("isFavorite", false),
        messageTtlMs = if (isNull("messageTtlMs")) null else optLong("messageTtlMs")
    )

    private fun MeshMessage.toJson() = JSONObject().apply {
        put("messageId", messageId)
        put("conversationId", conversationId)
        put("senderNodeId", senderNodeId)
        put("contentType", when (content) {
            is MessageContent.Text -> "text"
            is MessageContent.SystemEvent -> "system"
            is MessageContent.Location -> "location"
            is MessageContent.Image -> "image"
        })
        put("contentText", when (val c = content) {
            is MessageContent.Text -> c.text
            is MessageContent.SystemEvent -> c.event
            is MessageContent.Location -> "${c.latitude},${c.longitude},${c.accuracyMeters ?: ""}"
            is MessageContent.Image -> "${c.width},${c.height}:${c.base64Jpeg}"
        })
        put("timestamp", timestamp)
        put("deliveryStatus", deliveryStatus.name)
        put("isIncoming", isIncoming)
        put("hopCount", hopCount)
        put("expiresAt", expiresAt ?: JSONObject.NULL)
    }

    private fun JSONObject.toMessage(): MeshMessage {
        val contentText = getString("contentText")
        val content: MessageContent = when (optString("contentType", "text")) {
            "system" -> MessageContent.SystemEvent(contentText)
            "location" -> {
                val f = contentText.split(",", limit = 3)
                val lat = f.getOrNull(0)?.toDoubleOrNull()
                val lng = f.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lng != null)
                    MessageContent.Location(lat, lng, f.getOrNull(2)?.toFloatOrNull())
                else MessageContent.Text(contentText)
            }
            "image" -> {
                val sep = contentText.indexOf(':')
                if (sep > 0) {
                    val dims = contentText.substring(0, sep).split(",", limit = 2)
                    MessageContent.Image(
                        base64Jpeg = contentText.substring(sep + 1),
                        width = dims.getOrNull(0)?.toIntOrNull() ?: 0,
                        height = dims.getOrNull(1)?.toIntOrNull() ?: 0
                    )
                } else MessageContent.Text(contentText)
            }
            else -> MessageContent.Text(contentText)
        }
        return MeshMessage(
            messageId = getString("messageId"),
            conversationId = getString("conversationId"),
            senderNodeId = getString("senderNodeId"),
            content = content,
            timestamp = optLong("timestamp", System.currentTimeMillis()),
            deliveryStatus = runCatching { DeliveryStatus.valueOf(getString("deliveryStatus")) }
                .getOrDefault(DeliveryStatus.DELIVERED),
            isIncoming = optBoolean("isIncoming", true),
            hopCount = optInt("hopCount", 0),
            expiresAt = if (isNull("expiresAt")) null else optLong("expiresAt")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
