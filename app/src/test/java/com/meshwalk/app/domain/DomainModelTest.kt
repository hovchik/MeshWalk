package com.meshwalk.app.domain

import com.meshwalk.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class MeshPacketTest {

    private fun createPacket(
        ttl: Int = 7,
        hopCount: Int = 0,
        flags: Int = 0
    ) = MeshPacket(
        packetId = "test-pkt",
        sourceNodeId = "src",
        destinationNodeId = "dst",
        packetType = PacketType.MESSAGE,
        ttl = ttl,
        hopCount = hopCount,
        encryptedPayload = ByteArray(10),
        nonce = ByteArray(12),
        senderSignature = ByteArray(0),
        flags = flags
    )

    @Test
    fun `default TTL is 7`() {
        assertEquals(7, MeshPacket.DEFAULT_TTL)
    }

    @Test
    fun `packet with TTL 0 is expired`() {
        assertTrue(createPacket(ttl = 0).isExpired)
    }

    @Test
    fun `packet with positive TTL is not expired`() {
        assertFalse(createPacket(ttl = 3).isExpired)
    }

    @Test
    fun `forRelay decrements TTL and increments hop count`() {
        val original = createPacket(ttl = 5, hopCount = 1)
        val relayed = original.forRelay()

        assertEquals(4, relayed.ttl)
        assertEquals(2, relayed.hopCount)
        assertTrue(relayed.isRelay)
    }

    @Test
    fun `flags work correctly`() {
        val ack = createPacket(flags = MeshPacket.FLAG_IS_ACK or MeshPacket.FLAG_ACK_REQUESTED)
        assertTrue(ack.isAck)
        assertTrue(ack.isAckRequested)
        assertFalse(ack.isGroupMessage)
    }

    @Test
    fun `group message flag detected`() {
        val group = createPacket(flags = MeshPacket.FLAG_GROUP_MESSAGE)
        assertTrue(group.isGroupMessage)
        assertFalse(group.isAck)
    }

    @Test
    fun `key exchange flag detected`() {
        val kex = createPacket(flags = MeshPacket.FLAG_KEY_EXCHANGE)
        assertTrue(kex.isKeyExchange)
    }

    @Test
    fun `equality based on packetId`() {
        val a = createPacket().copy(packetId = "same-id")
        val b = createPacket().copy(packetId = "same-id", ttl = 3)
        assertEquals(a, b)
    }

    @Test
    fun `different packetIds are not equal`() {
        val a = createPacket().copy(packetId = "id-1")
        val b = createPacket().copy(packetId = "id-2")
        assertNotEquals(a, b)
    }
}

class NodeIdentityTest {

    @Test
    fun `named identity is not anonymous`() {
        val identity = NodeIdentity(
            displayName = "Alice",
            identityType = IdentityType.NAMED,
            publicSigningKey = ByteArray(32),
            publicExchangeKey = ByteArray(32)
        )
        assertFalse(identity.isAnonymous)
        assertFalse(identity.isTemporary)
    }

    @Test
    fun `anonymous identity detected`() {
        val identity = NodeIdentity(
            displayName = null,
            identityType = IdentityType.ANONYMOUS,
            publicSigningKey = ByteArray(32),
            publicExchangeKey = ByteArray(32)
        )
        assertTrue(identity.isAnonymous)
    }

    @Test
    fun `temporary identity detected`() {
        val identity = NodeIdentity(
            displayName = null,
            identityType = IdentityType.TEMPORARY,
            publicSigningKey = ByteArray(32),
            publicExchangeKey = ByteArray(32),
            expiresAt = System.currentTimeMillis() + 1000
        )
        assertTrue(identity.isTemporary)
        assertFalse(identity.isExpired)
    }

    @Test
    fun `expired identity detected`() {
        val identity = NodeIdentity(
            displayName = null,
            identityType = IdentityType.TEMPORARY,
            publicSigningKey = ByteArray(32),
            publicExchangeKey = ByteArray(32),
            expiresAt = System.currentTimeMillis() - 1000
        )
        assertTrue(identity.isExpired)
    }

    @Test
    fun `fingerprint format is correct`() {
        val identity = NodeIdentity(
            displayName = "Test",
            identityType = IdentityType.NAMED,
            publicSigningKey = ByteArray(32) { it.toByte() },
            publicExchangeKey = ByteArray(32)
        )
        val fp = identity.fingerprint
        // Should be "XX:XX:XX:XX:XX:XX:XX:XX" format
        assertEquals(8, fp.split(":").size)
    }
}
