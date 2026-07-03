package com.meshwalk.app.transport.manager

import timber.log.Timber
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport-level fragmentation and reassembly.
 *
 * Nearby Connections BYTES payloads are capped at ~32 KB, but MeshPacket
 * allows payloads up to [com.meshwalk.app.domain.model.MeshPacket.MAX_PAYLOAD_SIZE]
 * (needed for image attachments). Any serialized packet larger than
 * [MAX_FRAME_DATA] is split into numbered fragment frames that are
 * reassembled on the receiving side.
 *
 * Frame format:
 *   [FRAGMENT_MAGIC:1][fragmentId:16][index:4][total:4][chunk...]
 *
 * The magic byte cannot collide with other wire formats:
 * - serialized MeshPackets start with the high byte of a 4-byte length (0x00)
 * - advertisement payloads start with [TransportManager.ADVERTISEMENT_MAGIC] (0x4D)
 *
 * Incomplete assemblies are dropped after [ASSEMBLY_TIMEOUT_MS] so lost
 * fragments can't leak memory.
 */
@Singleton
class PacketFramer @Inject constructor() {

    companion object {
        const val FRAGMENT_MAGIC: Byte = 0x46 // 'F'

        /** Max chunk bytes per frame; total frame stays safely under Nearby's 32 KB cap. */
        const val MAX_FRAME_DATA = 27_000

        private const val FRAGMENT_ID_SIZE = 16
        private const val HEADER_SIZE = 1 + FRAGMENT_ID_SIZE + 4 + 4

        private const val ASSEMBLY_TIMEOUT_MS = 60_000L

        /** Upper bound on fragments per packet — rejects absurd/malicious totals. */
        private const val MAX_FRAGMENTS = 64
    }

    private val secureRandom = SecureRandom()

    private class PartialAssembly(total: Int) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var receivedCount = 0
        val firstSeenAt = System.currentTimeMillis()

        val isExpired: Boolean
            get() = System.currentTimeMillis() - firstSeenAt > ASSEMBLY_TIMEOUT_MS
    }

    // Keyed by "endpointId:fragmentIdHex" so different senders can't interfere.
    private val assemblies = ConcurrentHashMap<String, PartialAssembly>()

    /** True when [data] needs to be split before sending. */
    fun needsFragmentation(data: ByteArray): Boolean = data.size > MAX_FRAME_DATA

    /** True when [data] is a fragment frame produced by [split]. */
    fun isFragmentFrame(data: ByteArray): Boolean =
        data.size > HEADER_SIZE && data[0] == FRAGMENT_MAGIC

    /**
     * Split [data] into fragment frames ready to send in order.
     */
    fun split(data: ByteArray): List<ByteArray> {
        val fragmentId = ByteArray(FRAGMENT_ID_SIZE).also { secureRandom.nextBytes(it) }
        val total = (data.size + MAX_FRAME_DATA - 1) / MAX_FRAME_DATA
        require(total <= MAX_FRAGMENTS) {
            "Packet too large to fragment: ${data.size} bytes needs $total frames (max $MAX_FRAGMENTS)"
        }

        return (0 until total).map { index ->
            val start = index * MAX_FRAME_DATA
            val end = minOf(start + MAX_FRAME_DATA, data.size)
            ByteBuffer.allocate(HEADER_SIZE + (end - start))
                .put(FRAGMENT_MAGIC)
                .put(fragmentId)
                .putInt(index)
                .putInt(total)
                .put(data, start, end - start)
                .array()
        }
    }

    /**
     * Accept a fragment frame from [endpointId]. Returns the fully reassembled
     * payload when this frame completes the packet, or null while fragments
     * are still outstanding (or the frame is invalid).
     */
    fun accept(endpointId: String, frame: ByteArray): ByteArray? {
        if (!isFragmentFrame(frame)) return null
        pruneExpired()

        try {
            val buffer = ByteBuffer.wrap(frame)
            buffer.get() // magic
            val fragmentId = ByteArray(FRAGMENT_ID_SIZE).also { buffer.get(it) }
            val index = buffer.getInt()
            val total = buffer.getInt()
            if (total !in 1..MAX_FRAGMENTS || index !in 0 until total) {
                Timber.w("Dropping fragment with invalid index/total: $index/$total")
                return null
            }
            val chunk = ByteArray(buffer.remaining()).also { buffer.get(it) }

            val key = "$endpointId:${fragmentId.joinToString("") { "%02x".format(it) }}"
            val assembly = assemblies.getOrPut(key) { PartialAssembly(total) }
            if (assembly.chunks.size != total) {
                // Same fragmentId with a different total — corrupt or malicious. Reset.
                Timber.w("Fragment total mismatch for $key, discarding assembly")
                assemblies.remove(key)
                return null
            }

            synchronized(assembly) {
                if (assembly.chunks[index] == null) {
                    assembly.chunks[index] = chunk
                    assembly.receivedCount++
                }
                if (assembly.receivedCount < total) return null

                assemblies.remove(key)
                val out = ByteArray(assembly.chunks.sumOf { it!!.size })
                var offset = 0
                assembly.chunks.forEach { c ->
                    System.arraycopy(c!!, 0, out, offset, c.size)
                    offset += c.size
                }
                Timber.d("Reassembled ${out.size} bytes from $total fragments ($endpointId)")
                return out
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to process fragment frame from $endpointId")
            return null
        }
    }

    private fun pruneExpired() {
        val expired = assemblies.entries.filter { it.value.isExpired }.map { it.key }
        expired.forEach {
            assemblies.remove(it)
            Timber.d("Dropped expired fragment assembly $it")
        }
    }
}
