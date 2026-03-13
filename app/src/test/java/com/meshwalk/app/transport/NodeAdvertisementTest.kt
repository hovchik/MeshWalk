package com.meshwalk.app.transport

import com.meshwalk.app.transport.api.NodeAdvertisement
import org.junit.Assert.*
import org.junit.Test

class NodeAdvertisementTest {

    @Test
    fun `serialize and deserialize round trip`() {
        val original = NodeAdvertisement(
            nodeId = "test-node-123",
            displayName = "Alice",
            publicExchangeKey = ByteArray(32) { it.toByte() }
        )

        val serialized = original.serialize()
        val deserialized = NodeAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(original.nodeId, deserialized!!.nodeId)
        assertEquals(original.displayName, deserialized.displayName)
        assertArrayEquals(original.publicExchangeKey, deserialized.publicExchangeKey)
    }

    @Test
    fun `anonymous node serialization`() {
        val original = NodeAdvertisement(
            nodeId = "anon-456",
            displayName = null,
            publicExchangeKey = ByteArray(32) { 0xAB.toByte() }
        )

        val serialized = original.serialize()
        val deserialized = NodeAdvertisement.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals("anon-456", deserialized!!.nodeId)
        assertNull(deserialized.displayName)
    }

    @Test
    fun `invalid data returns null`() {
        val result = NodeAdvertisement.deserialize(ByteArray(3))
        assertNull(result)
    }

    @Test
    fun `empty data returns null`() {
        val result = NodeAdvertisement.deserialize(ByteArray(0))
        assertNull(result)
    }
}
