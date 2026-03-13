package com.meshwalk.app.routing

import com.meshwalk.app.routing.dedup.PacketDeduplicator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PacketDeduplicatorTest {

    private lateinit var deduplicator: PacketDeduplicator

    @Before
    fun setUp() {
        deduplicator = PacketDeduplicator()
    }

    @Test
    fun `new packet is not a duplicate`() {
        assertFalse(deduplicator.isDuplicate("packet-1"))
    }

    @Test
    fun `seen packet is a duplicate`() {
        deduplicator.markSeen("packet-1")
        assertTrue(deduplicator.isDuplicate("packet-1"))
    }

    @Test
    fun `different packet IDs are not duplicates`() {
        deduplicator.markSeen("packet-1")
        assertFalse(deduplicator.isDuplicate("packet-2"))
    }

    @Test
    fun `size tracks correctly`() {
        assertEquals(0, deduplicator.size())
        deduplicator.markSeen("a")
        deduplicator.markSeen("b")
        deduplicator.markSeen("c")
        assertEquals(3, deduplicator.size())
    }

    @Test
    fun `clear empties cache`() {
        deduplicator.markSeen("a")
        deduplicator.markSeen("b")
        deduplicator.clear()
        assertEquals(0, deduplicator.size())
        assertFalse(deduplicator.isDuplicate("a"))
    }

    @Test
    fun `marking same packet twice does not increase size`() {
        deduplicator.markSeen("packet-1")
        deduplicator.markSeen("packet-1")
        assertEquals(1, deduplicator.size())
    }
}
