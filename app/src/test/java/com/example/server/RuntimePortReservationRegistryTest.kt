package com.example.server

import com.example.data.PortTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePortReservationRegistryTest {
    @Test
    fun differentServersCannotReserveSamePort() {
        val registry = RuntimePortReservationRegistry()
        val transport = PortTransport.UDP
        assertTrue(registry.reserve("a", "a-1", 19132, transport) is RuntimePortReservationRegistry.ReserveResult.Acquired)
        val second = registry.reserve("b", "b-1", 19132, transport)
        assertTrue(second is RuntimePortReservationRegistry.ReserveResult.Rejected)
        assertEquals(setOf(RuntimePortReservationRegistry.PortKey(transport, 19132)), registry.reservedPorts())
    }

    @Test
    fun staleSessionCannotReleaseNewReservation() {
        val registry = RuntimePortReservationRegistry()
        val transport = PortTransport.UDP
        registry.reserve("a", "old", 19132, transport)
        assertTrue(registry.release("a", "old", 19132, transport))
        registry.reserve("a", "new", 19132, transport)

        assertFalse(registry.release("a", "old", 19132, transport))
        assertEquals("new", registry.reservationFor("a")?.sessionId)
    }

    @Test
    fun startingFlagChangesWithoutReleasingPort() {
        val registry = RuntimePortReservationRegistry()
        val transport = PortTransport.UDP
        registry.reserve("a", "a-1", 19132, transport)
        assertEquals(setOf("a"), registry.startingServerIds())

        assertTrue(registry.markStarted("a", "a-1"))
        assertTrue(registry.startingServerIds().isEmpty())
        assertEquals(setOf(RuntimePortReservationRegistry.PortKey(transport, 19132)), registry.reservedPorts())
    }
}
