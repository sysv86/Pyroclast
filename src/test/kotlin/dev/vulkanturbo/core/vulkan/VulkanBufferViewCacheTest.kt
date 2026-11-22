package dev.vulkanturbo.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VulkanBufferViewCacheTest {
    @Test
    fun `finds matching entry across offset range format and device`() {
        val registry = BufferViewRegistry(maxEntries = 16)
        assertEquals(0L, registry.find(1L, 100L, 0L, 64L, 42))

        val evicted = registry.insert(1L, 100L, 0L, 64L, 42, 9001L)
        assertEquals(0L, evicted)
        assertEquals(9001L, registry.find(1L, 100L, 0L, 64L, 42))
        assertEquals(0L, registry.find(1L, 100L, 64L, 64L, 42))
        assertEquals(0L, registry.find(1L, 100L, 0L, 128L, 42))
        assertEquals(0L, registry.find(1L, 100L, 0L, 64L, 43))
        assertEquals(0L, registry.find(2L, 100L, 0L, 64L, 42))
    }

    @Test
    fun `evicts least recently used entry past capacity`() {
        val registry = BufferViewRegistry(maxEntries = 2)
        assertEquals(0L, registry.insert(1L, 10L, 0L, 16L, 1, 101L))
        assertEquals(0L, registry.insert(1L, 10L, 16L, 16L, 1, 102L))
        // Touch 101 so 102 becomes LRU.
        assertEquals(101L, registry.find(1L, 10L, 0L, 16L, 1))

        val evicted = registry.insert(1L, 10L, 32L, 16L, 1, 103L)
        assertEquals(102L, evicted)
        assertEquals(2, registry.size)
        assertEquals(101L, registry.find(1L, 10L, 0L, 16L, 1))
        assertEquals(0L, registry.find(1L, 10L, 16L, 16L, 1))
        assertEquals(103L, registry.find(1L, 10L, 32L, 16L, 1))
    }

    @Test
    fun `removeBuffer and removeDevice destroy only matching views`() {
        val registry = BufferViewRegistry(maxEntries = 8)
        registry.insert(1L, 10L, 0L, 16L, 1, 101L)
        registry.insert(1L, 10L, 16L, 16L, 1, 102L)
        registry.insert(1L, 20L, 0L, 16L, 1, 201L)
        registry.insert(2L, 10L, 0L, 16L, 1, 301L)

        val removedBuf = mutableListOf<Long>()
        registry.removeBuffer(1L, 10L) { removedBuf.add(it) }
        assertEquals(listOf(101L, 102L), removedBuf)
        assertEquals(2, registry.size)
        assertEquals(201L, registry.find(1L, 20L, 0L, 16L, 1))
        assertEquals(301L, registry.find(2L, 10L, 0L, 16L, 1))

        val removedDev = mutableListOf<Long>()
        registry.removeDevice(1L) { removedDev.add(it) }
        assertEquals(listOf(201L), removedDev)
        assertEquals(1, registry.size)
        assertEquals(301L, registry.find(2L, 10L, 0L, 16L, 1))
    }
}
