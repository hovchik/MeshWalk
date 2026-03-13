package com.meshwalk.app.util

import java.text.SimpleDateFormat
import java.util.*

object TimeUtils {
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    private val fullFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> timeFormat.format(Date(timestamp))
            diff < 7 * 86400_000 -> dateFormat.format(Date(timestamp))
            else -> fullFormat.format(Date(timestamp))
        }
    }

    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }
}

fun String.truncate(maxLen: Int, ellipsis: String = "..."): String {
    return if (length <= maxLen) this else take(maxLen - ellipsis.length) + ellipsis
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun ByteArray.toFingerprint(): String = take(8).joinToString(":") { "%02X".format(it) }
