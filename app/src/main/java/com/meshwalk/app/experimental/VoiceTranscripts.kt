package com.meshwalk.app.experimental

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 12 — Voice-note transcripts only.
 *
 * Ships short text transcripts of voice notes instead of audio, keeping the
 * audio on the speaker's device. This is a UI-layer helper — it normalizes
 * a transcript, tags it with a marker, and exposes a simple format so the
 * receiver's chat bubble can render it differently.
 */
data class VoiceTranscript(
    val transcript: String,
    val durationSeconds: Int,
    val recordedAt: Long
) {
    /** Wire-friendly serialization that fits in a plain text message. */
    fun toMessageText(): String =
        "[voice:${durationSeconds}s] ${transcript.trim()}"
}

@Singleton
class VoiceTranscripts @Inject constructor() {
    /** Parse out a voice transcript marker from a message text. Returns null
     *  if the message isn't a voice transcript. */
    fun parse(messageText: String): VoiceTranscript? {
        val regex = Regex("^\\[voice:(\\d+)s]\\s+(.*)$")
        val match = regex.find(messageText) ?: return null
        val seconds = match.groupValues[1].toIntOrNull() ?: return null
        return VoiceTranscript(
            transcript = match.groupValues[2],
            durationSeconds = seconds,
            recordedAt = System.currentTimeMillis()
        )
    }

    fun build(transcript: String, durationSeconds: Int): VoiceTranscript =
        VoiceTranscript(
            transcript = transcript.trim(),
            durationSeconds = durationSeconds.coerceIn(1, 600),
            recordedAt = System.currentTimeMillis()
        )
}
