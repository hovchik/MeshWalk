package com.meshwalk.app.mesh.group

import com.meshwalk.app.crypto.keys.KeyStorage
import com.meshwalk.app.crypto.keys.MeshKeyManager
import com.meshwalk.app.data.local.dao.GroupArchiveDao
import com.meshwalk.app.data.local.entity.GroupArchiveEntity
import com.meshwalk.app.domain.model.ConversationType
import com.meshwalk.app.domain.model.GroupInfo
import com.meshwalk.app.domain.model.MessageContent
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.GroupRepository
import com.meshwalk.app.domain.repository.MessageRepository
import com.meshwalk.app.domain.repository.PeerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of temporary groups.
 *
 * Periodically checks for expired temporary groups and:
 * 1. Collects all messages from the group
 * 2. Encrypts the archive using ECIES (ECDH + AES-GCM) with the admin's public key
 * 3. Stores the encrypted archive in the database
 * 4. Deletes the group, conversation, and messages
 *
 * Only the admin can decrypt the archive using their private exchange key.
 */
@Singleton
class GroupLifecycleManager @Inject constructor(
    private val groupRepo: GroupRepository,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val peerRepo: PeerRepository,
    private val keyStorage: KeyStorage,
    private val keyManager: MeshKeyManager,
    private val groupArchiveDao: GroupArchiveDao
) {

    companion object {
        /** Check for expired groups every 60 seconds. */
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val EC_CURVE = "secp256r1"
    }

    private val secureRandom = SecureRandom()

    /**
     * Start the lifecycle manager. Periodically checks for expired temporary
     * groups and archives them.
     */
    fun start(ourNodeId: String, scope: CoroutineScope) {
        scope.launch {
            Timber.d("GroupLifecycleManager started for node $ourNodeId")
            while (true) {
                try {
                    checkExpiredGroups(ourNodeId)
                } catch (e: Exception) {
                    Timber.e(e, "Error checking expired groups")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Check for expired temporary groups and archive them.
     */
    private suspend fun checkExpiredGroups(ourNodeId: String) {
        val now = System.currentTimeMillis()
        val groups = groupRepo.observeGroups().first()

        for (group in groups) {
            if (group.groupType != ConversationType.TEMPORARY_GROUP) continue
            val expiresAt = group.expiresAt ?: continue
            if (now < expiresAt) continue

            // Group has expired — archive and delete
            Timber.d("Temporary group '${group.name}' (${group.groupId.take(8)}) has expired, archiving...")
            try {
                archiveAndDeleteGroup(group, ourNodeId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to archive expired group ${group.groupId.take(8)}")
            }
        }
    }

    /**
     * Archive a group: collect messages, encrypt with admin's public key, store,
     * then delete the group.
     */
    private suspend fun archiveAndDeleteGroup(group: GroupInfo, ourNodeId: String) {
        // Collect all messages from the group
        val messages = collectGroupMessages(group.groupId)

        // Build the archive JSON
        val archiveJson = buildArchiveJson(group, messages)
        val archiveBytes = archiveJson.toString().toByteArray(Charsets.UTF_8)

        // Get the admin's public exchange key for encryption
        val adminNodeId = group.creatorNodeId
        val adminPublicKeyBytes = getAdminPublicExchangeKey(adminNodeId, ourNodeId)

        if (adminPublicKeyBytes == null) {
            Timber.w("Cannot archive group ${group.groupId.take(8)}: admin public key not available")
            // Still delete the group since it's expired, just can't create an encrypted archive
            deleteGroup(group.groupId)
            return
        }

        // Encrypt the archive using ECIES (ECDH + AES-GCM)
        val encrypted = encryptWithEcies(archiveBytes, adminPublicKeyBytes)

        // Store the encrypted archive
        val archiveEntity = GroupArchiveEntity(
            groupId = group.groupId,
            groupName = group.name,
            adminNodeId = adminNodeId,
            encryptedData = encrypted.ciphertext,
            ephemeralPublicKey = encrypted.ephemeralPublicKey,
            nonce = encrypted.nonce,
            memberCount = group.members.size,
            messageCount = messages.size,
            createdAt = group.createdAt,
            archivedAt = System.currentTimeMillis(),
            expiresAt = group.expiresAt ?: group.createdAt
        )
        groupArchiveDao.insert(archiveEntity)
        Timber.d("Stored encrypted archive for group '${group.name}' (${messages.size} messages)")

        // Delete the group, conversation, and messages
        deleteGroup(group.groupId)
        Timber.d("Deleted expired group '${group.name}' after archiving")
    }

    /**
     * Collect all messages from a group conversation.
     */
    private suspend fun collectGroupMessages(groupId: String): List<MessageData> {
        val messages = messageRepo.observeMessages(groupId).first()
        return messages.map { msg ->
            val text = when (val c = msg.content) {
                is MessageContent.SystemEvent -> "[system] ${c.event}"
                else -> c.previewText
            }
            MessageData(
                messageId = msg.messageId,
                senderNodeId = msg.senderNodeId,
                text = text,
                timestamp = msg.timestamp,
                isIncoming = msg.isIncoming
            )
        }
    }

    private data class MessageData(
        val messageId: String,
        val senderNodeId: String,
        val text: String,
        val timestamp: Long,
        val isIncoming: Boolean
    )

    /**
     * Build a JSON object containing the full group archive.
     */
    private fun buildArchiveJson(group: GroupInfo, messages: List<MessageData>): JSONObject {
        val json = JSONObject()
        json.put("groupId", group.groupId)
        json.put("groupName", group.name)
        json.put("creatorNodeId", group.creatorNodeId)
        json.put("createdAt", group.createdAt)
        json.put("expiresAt", group.expiresAt)
        json.put("archivedAt", System.currentTimeMillis())

        val membersArray = JSONArray()
        for (member in group.members) {
            val memberJson = JSONObject()
            memberJson.put("nodeId", member.nodeId)
            memberJson.put("displayName", member.displayName ?: "")
            memberJson.put("role", member.role.name)
            membersArray.put(memberJson)
        }
        json.put("members", membersArray)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgJson = JSONObject()
            msgJson.put("messageId", msg.messageId)
            msgJson.put("senderNodeId", msg.senderNodeId)
            msgJson.put("text", msg.text)
            msgJson.put("timestamp", msg.timestamp)
            messagesArray.put(msgJson)
        }
        json.put("messages", messagesArray)

        return json
    }

    /**
     * Get the admin's public exchange key.
     * If we are the admin, use our own key from KeyStorage.
     * Otherwise, try to get it from the peer repository.
     */
    private suspend fun getAdminPublicExchangeKey(adminNodeId: String, ourNodeId: String): ByteArray? {
        return if (adminNodeId == ourNodeId) {
            keyStorage.getExchangePublicKey(ourNodeId)
        } else {
            peerRepo.getPeer(adminNodeId)?.publicExchangeKey
        }
    }

    /**
     * ECIES encryption: generate an ephemeral EC key pair, perform ECDH with the
     * recipient's public key, derive an AES-256 key, and encrypt with AES-GCM.
     *
     * Returns the ciphertext, ephemeral public key, and nonce needed for decryption.
     */
    private fun encryptWithEcies(plaintext: ByteArray, recipientPublicKeyBytes: ByteArray): EciesResult {
        // Parse recipient's public key
        val keyFactory = KeyFactory.getInstance("EC")
        val recipientPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(recipientPublicKeyBytes))

        // Generate ephemeral key pair
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec(EC_CURVE))
        val ephemeralKeyPair = keyPairGen.generateKeyPair()

        // ECDH key agreement
        val sharedSecret = keyManager.performKeyAgreement(ephemeralKeyPair.private, recipientPublicKey)

        // Derive AES-256 key from shared secret using HKDF-like derivation
        val aesKey = keyManager.deriveSessionKey(
            sharedSecret = sharedSecret,
            info = "group-archive-ecies".toByteArray(Charsets.UTF_8)
        )

        // Encrypt with AES-256-GCM
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        return EciesResult(
            ciphertext = ciphertext,
            ephemeralPublicKey = ephemeralKeyPair.public.encoded,
            nonce = nonce
        )
    }

    private data class EciesResult(
        val ciphertext: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val nonce: ByteArray
    )

    /**
     * Decrypt an archived group. Only the admin (with their private exchange key) can do this.
     *
     * @return The decrypted archive JSON string, or null if decryption fails.
     */
    suspend fun decryptArchive(archive: GroupArchiveEntity, ourNodeId: String): String? {
        if (archive.adminNodeId != ourNodeId) {
            Timber.w("Cannot decrypt archive: not the admin (admin=${archive.adminNodeId.take(8)}, us=${ourNodeId.take(8)})")
            return null
        }

        val ourExchangePrivateKeyBytes = keyStorage.getExchangePrivateKey(ourNodeId)
        if (ourExchangePrivateKeyBytes == null) {
            Timber.w("Cannot decrypt archive: private exchange key not found")
            return null
        }

        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val ourPrivateKey = keyFactory.generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(ourExchangePrivateKeyBytes)
            )
            val ephemeralPublicKey = keyFactory.generatePublic(
                X509EncodedKeySpec(archive.ephemeralPublicKey)
            )

            // ECDH key agreement with the ephemeral public key
            val sharedSecret = keyManager.performKeyAgreement(ourPrivateKey, ephemeralPublicKey)

            // Derive the same AES key
            val aesKey = keyManager.deriveSessionKey(
                sharedSecret = sharedSecret,
                info = "group-archive-ecies".toByteArray(Charsets.UTF_8)
            )

            // Decrypt with AES-256-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, archive.nonce))
            val plaintext = cipher.doFinal(archive.encryptedData)

            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt group archive ${archive.groupId.take(8)}")
            null
        }
    }

    /**
     * Delete a group and all associated data (conversation, messages, sender keys).
     */
    private suspend fun deleteGroup(groupId: String) {
        messageRepo.deleteMessagesByConversation(groupId)
        conversationRepo.deleteConversation(groupId)
        groupRepo.deleteGroup(groupId)
    }
}
