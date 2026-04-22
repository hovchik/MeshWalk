package com.meshwalk.app.experimental

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 7 — Compression + dictionary codes for common phrases.
 *
 * BLE payloads are tiny. When two peers share the same phrasebook version,
 * a message like "Need help, injured" can be encoded as a pair of 1-byte
 * opcodes (2 bytes on the wire) instead of 20+ UTF-8 bytes.
 *
 * Wire format (one byte vs UTF-8):
 *   - First byte 0x01 == phrasebook frame, followed by 1-byte version, then
 *     N * 1-byte phrase opcodes.
 *   - Any other first byte == plain UTF-8 text (backwards-compatible).
 */
@Singleton
class Phrasebook @Inject constructor() {

    companion object {
        const val VERSION: Byte = 1
        private const val FRAME_MARKER: Byte = 0x01
        // Opcodes are 1 byte; reserve 0x00 for "literal fallback" separator.
        val PHRASES: List<String> = listOf(
            "OK",
            "On my way",
            "Need help",
            "Injured",
            "Out of water",
            "Low battery",
            "Found shelter",
            "Lost",
            "Safe",
            "At the meeting point",
            "Moving north",
            "Moving south",
            "Moving east",
            "Moving west",
            "Stop",
            "Wait here",
            "Can you hear me?",
            "Yes",
            "No",
            "Repeat",
            "Unknown"
        )

        private val BY_PHRASE: Map<String, Int> = PHRASES
            .mapIndexed { index, phrase -> phrase.lowercase() to index }
            .toMap()
    }

    /** Try to encode [text] with the phrasebook. Returns null if any token cannot be encoded. */
    fun encode(text: String): ByteArray? {
        val tokens = text.split(",", ";", ".")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val opcodes = tokens.map { BY_PHRASE[it.lowercase()] ?: return null }
        val out = ByteArray(2 + opcodes.size)
        out[0] = FRAME_MARKER
        out[1] = VERSION
        opcodes.forEachIndexed { i, op -> out[2 + i] = op.toByte() }
        return out
    }

    /** Decode a phrasebook frame. Returns null if [data] is not a phrasebook frame. */
    fun decode(data: ByteArray): String? {
        if (data.size < 2 || data[0] != FRAME_MARKER) return null
        if (data[1] != VERSION) return null
        return data.drop(2).joinToString(", ") { byte ->
            val idx = byte.toInt() and 0xFF
            PHRASES.getOrNull(idx) ?: "?"
        }
    }

    /** Convenience: try [encode], otherwise return the plain UTF-8 bytes. */
    fun encodeOrFallback(text: String): ByteArray =
        encode(text) ?: text.toByteArray(Charsets.UTF_8)

    /** Convenience: try [decode], otherwise interpret as UTF-8. */
    fun decodeOrFallback(data: ByteArray): String =
        decode(data) ?: String(data, Charsets.UTF_8)

    /** Compression ratio for display in UI, useful when the user types canned phrases. */
    fun compressionRatio(text: String): Double? {
        val encoded = encode(text) ?: return null
        val plain = text.toByteArray(Charsets.UTF_8).size
        if (plain == 0) return null
        return encoded.size.toDouble() / plain.toDouble()
    }
}
