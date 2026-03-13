package com.meshwalk.app.routing

import com.meshwalk.app.domain.model.MeshPacket
import com.meshwalk.app.domain.model.PacketType
import com.meshwalk.app.routing.queue.OfflineQueue
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OfflineQueueTest {

    private lateinit var queue: OfflineQueue

    private fun createPacket(
        id: String = "pkt-${System.nanoTime()}",
        dest: String = "node-dest"
    ) = MeshPacket(
        packetId = id,
        sourceNodeId = "node-src",
        destinationNodeId = dest,
        packetType = PacketType.MESSAGE,
        encryptedPayload = "hello".toByteArray(),
        nonce = ByteArray(12),
        senderSignature = ByteArray(0)
    )

    @Before
    fun setUp() {
        queue = OfflineQueue()
    }

    @Test
    fun `enqueue adds packet`() {
        queue.enqueue(createPacket())
        assertEquals(1, queue.size())
    }

    @Test
    fun `duplicate packet not added twice`() {
        val packet = createPacket(id = "dup-1")
        queue.enqueue(packet)
        queue.enqueue(packet)
        assertEquals(1, queue.size())
    }

    @Test
    fun `getPacketsForDestination filters correctly`() {
        queue.enqueue(createPacket(dest = "node-a"))
        queue.enqueue(createPacket(dest = "node-b"))
        queue.enqueue(createPacket(dest = "node-a"))

        val forA = queue.getPacketsForDestination("node-a")
        assertEquals(2, forA.size)
        assertTrue(forA.all { it.destinationNodeId == "node-a" })
    }

    @Test
    fun `remove deletes packet`() {
        val packet = createPacket(id = "remove-me")
        queue.enqueue(packet)
        assertEquals(1, queue.size())

        queue.remove("remove-me")
        assertEquals(0, queue.size())
    }

    @Test
    fun `getAll returns all packets`() {
        queue.enqueue(createPacket())
        queue.enqueue(createPacket())
        queue.enqueue(createPacket())
        assertEquals(3, queue.getAll().size)
    }

    @Test
    fun `clear empties queue`() {
        queue.enqueue(createPacket())
        queue.enqueue(createPacket())
        queue.clear()
        assertEquals(0, queue.size())
    }
}
