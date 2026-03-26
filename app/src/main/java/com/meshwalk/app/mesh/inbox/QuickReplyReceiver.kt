package com.meshwalk.app.mesh.inbox

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshwalk.app.domain.model.ConversationType
import com.meshwalk.app.domain.repository.ConversationRepository
import com.meshwalk.app.domain.repository.IdentityRepository
import com.meshwalk.app.domain.usecase.MeshOutboxPort
import com.meshwalk.app.domain.usecase.SendGroupMessageUseCase
import com.meshwalk.app.domain.usecase.SendMessageUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles quick-reply actions from message notifications.
 * The user can type a reply directly in the notification shade.
 */
@AndroidEntryPoint
class QuickReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var sendMessage: SendMessageUseCase
    @Inject lateinit var sendGroupMessage: SendGroupMessageUseCase
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var identityRepo: IdentityRepository
    @Inject lateinit var notificationManager: MessageNotificationManager

    companion object {
        const val KEY_QUICK_REPLY = "key_quick_reply"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_PEER_NODE_ID = "extra_peer_node_id"
        const val ACTION_QUICK_REPLY = "com.meshwalk.app.ACTION_QUICK_REPLY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = remoteInput.getCharSequence(KEY_QUICK_REPLY)?.toString()?.trim()
        if (replyText.isNullOrBlank()) return

        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val peerNodeId = intent.getStringExtra(EXTRA_PEER_NODE_ID) ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val identity = identityRepo.getActiveIdentity() ?: return@launch
                val conversation = conversationRepo.getConversation(conversationId) ?: return@launch

                when (conversation.type) {
                    ConversationType.GROUP, ConversationType.TEMPORARY_GROUP -> {
                        sendGroupMessage(
                            groupId = conversationId,
                            text = replyText,
                            senderNodeId = identity.nodeId
                        )
                    }
                    else -> {
                        sendMessage(
                            conversationId = conversationId,
                            recipientNodeId = peerNodeId,
                            text = replyText,
                            senderNodeId = identity.nodeId
                        )
                    }
                }

                // Cancel the notification after successful reply
                notificationManager.cancelNotification(conversationId)
                Timber.d("Quick reply sent to conversation ${conversationId.take(8)}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send quick reply")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
