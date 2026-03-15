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
 * posts per-conversation notifications with sender name and message preview.
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
     * Show a notification for a received message.
     *
     * @param conversationId Used to generate a stable notification ID per conversation.
     * @param senderName Display name of the sender (or truncated nodeId).
     * @param messagePreview Text preview of the message.
     * @param isGroupMessage Whether this is a group message.
     */
    fun showMessageNotification(
        conversationId: String,
        senderName: String,
        messagePreview: String,
        isGroupMessage: Boolean = false
    ) {
        if (!hasNotificationPermission()) {
            Timber.d("Notification permission not granted, skipping message notification")
            return
        }

        val notificationId = BASE_NOTIFICATION_ID + conversationId.hashCode().and(0x7FFF)

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
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(messagePreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messagePreview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            Timber.d("Posted message notification for conversation ${conversationId.take(8)}")
        } catch (e: SecurityException) {
            Timber.w(e, "SecurityException posting notification")
        }

        // Post summary notification for grouping when multiple conversations have messages
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
