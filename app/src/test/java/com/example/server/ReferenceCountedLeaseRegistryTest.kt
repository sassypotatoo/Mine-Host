package com.example.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceCountedLeaseRegistryTest {
    @Test
    fun releasingOneLeaseKeepsOtherLeaseOwned() {
        val registry = ReferenceCountedLeaseRegistry()
        registry.acquire("server-a", "PowerNukkit")
        registry.acquire("server-b", "Nukkit-MOT")

        val afterFirstRelease = registry.release("server-a")

        assertEquals(1, afterFirstRelease.count)
        assertFalse(afterFirstRelease.isEmpty)
        assertTrue("server-b" in afterFirstRelease.ids)
    }

    @Test
    fun duplicateAcquireUpdatesSameOwnerInsteadOfIncreasingCount() {
        val registry = ReferenceCountedLeaseRegistry()
        registry.acquire("server-a", "Starting")
        val state = registry.acquire("server-a", "Online")

        assertEquals(1, state.count)
        assertEquals("Online", state.entries["server-a"])
    }

    @Test
    fun finalReleaseMakesRegistryEmpty() {
        val registry = ReferenceCountedLeaseRegistry()
        registry.acquire("server-a", "PowerNukkit")

        assertTrue(registry.release("server-a").isEmpty)
    }
}
