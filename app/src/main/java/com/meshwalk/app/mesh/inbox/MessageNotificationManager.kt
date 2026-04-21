package com.meshwalk.app.mesh.inbox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.meshwalk.app.MainActivity
import com.meshwalk.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages notifications for incoming mesh messages.
 *
 * Creates a dedicated high-importance notification channel for messages
 * (separate from the low-importance foreground service channel) and
 * posts per-conversation notifications with sender name, message preview,
 * quick-reply action, and predefined quick message buttons.
 */
@Singleton
class MessageNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val MESSAGE_CHANNEL_ID = "mesh_message_channel"
        private const val GROUP_KEY = "com.meshwalk.app.MESSAGES"
        private const val SUMMARY_NOTIFICATION_ID = 9000
        private const val BASE_NOTIFICATION_ID = 9001
    }

    init {
        createMessageChannel()
    }

    /**
     * Show a notification for a received message with quick reply action.
     */
    fun showMessageNotification(
        conversationId: String,
        senderName: String,
        messagePreview: String,
        isGroupMessage: Boolean = false,
        peerNodeId: String = ""
    ) {
        if (!hasNotificationPermission()) {
            Timber.d("Notification permission not granted, skipping message notification")
            return
        }

        val notificationId = BASE_NOTIFICATION_ID + conversationId.hashCode().and(0x7FFF)

        // Main tap intent - opens the chat
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isGroupMessage) "Group message from $senderName" else senderName

        val builder = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(messagePreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messagePreview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)

        // Quick reply action (inline reply from notification)
        val replyRemoteInput = RemoteInput.Builder(QuickReplyReceiver.KEY_QUICK_REPLY)
            .setLabel("Type a reply...")
            .build()

        val replyIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            action = QuickReplyReceiver.ACTION_QUICK_REPLY
            putExtra(QuickReplyReceiver.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(QuickReplyReceiver.EXTRA_PEER_NODE_ID, peerNodeId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 10000,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(replyRemoteInput).build()

        builder.addAction(replyAction)

        // Predefined quick messages
        addQuickMessageAction(builder, conversationId, peerNodeId, notificationId, "I'm OK", 1)
        addQuickMessageAction(builder, conversationId, peerNodeId, notificationId, "Need help", 2)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Timber.d("Posted message notification for conversation ${conversationId.take(8)}")
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException posting notification")
        }

        postSummaryNotification()
    }

    private fun addQuickMessageAction(
        builder: NotificationCompat.Builder,
        conversationId: String,
        peerNodeId: String,
        notificationId: Int,
        quickText: String,
        offset: Int
    ) {
        val quickIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            action = QuickReplyReceiver.ACTION_QUICK_REPLY
            putExtra(QuickReplyReceiver.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(QuickReplyReceiver.EXTRA_PEER_NODE_ID, peerNodeId)
            // Embed the quick message as a RemoteInput result
            putExtra("quick_text", quickText)
        }

        // For predefined messages, we use a direct PendingIntent that includes
        // the text in the bundle, simulating a RemoteInput result.
        val bundle = android.os.Bundle().apply {
            putCharSequence(QuickReplyReceiver.KEY_QUICK_REPLY, quickText)
        }
        val clipIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            action = QuickReplyReceiver.ACTION_QUICK_REPLY
            putExtra(QuickReplyReceiver.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(QuickReplyReceiver.EXTRA_PEER_NODE_ID, peerNodeId)
        }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(QuickReplyReceiver.KEY_QUICK_REPLY).build()),
            clipIntent,
            bundle
        )

        val quickPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 20000 + offset,
            clipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        builder.addAction(
            NotificationCompat.Action.Builder(
                R.mipmap.ic_launcher,
                quickText,
                quickPendingIntent
            ).build()
        )
    }

    /**
     * Show a notification when a peer reacts to one of our messages.
     */
    fun showReactionNotification(
        conversationId: String,
        senderName: String,
        emoji: String,
        messagePreview: String,
        isRemoval: Boolean
    ) {
        if (!hasNotificationPermission()) {
            Timber.d("Notification permission not granted, skipping reaction notification")
            return
        }

        // Offset by 40000 so reaction notifications don't collide with per-conversation message notifications.
        val notificationId = BASE_NOTIFICATION_ID + 40000 + conversationId.hashCode().and(0x7FFF)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isRemoval) {
            "$senderName removed $emoji"
        } else {
            "$senderName reacted $emoji"
        }
        val body = if (messagePreview.isNotBlank()) "to: $messagePreview" else "to your message"

        val builder = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Timber.d("Posted reaction notification for conversation ${conversationId.take(8)}")
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException posting reaction notification")
        }

        postSummaryNotification()
    }

    /**
     * Cancel notification for a conversation (e.g. when user opens the chat).
     */
    fun cancelNotification(conversationId: String) {
        val notificationId = BASE_NOTIFICATION_ID + conversationId.hashCode().and(0x7FFF)
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun postSummaryNotification() {
        if (!hasNotificationPermission()) return

        val summary = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MeshWalk")
            .setContentText("New messages")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, summary)
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException posting summary notification")
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun createMessageChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming mesh message notifications"
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
