package com.example.server

import java.net.DatagramSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortAllocatorTest {
    @Test
    fun rejectsInvalidPortsAndSuggestsAUsablePort() {
        val result = PortAllocator.validate(80, emptySet())
        assertFalse(result.available)
        assertTrue(result.suggestedPort in 1024..65535)
    }

    @Test
    fun rejectsMineHostOwnedPort() {
        val result = PortAllocator.validate(19132, setOf(19132))
        assertFalse(result.available)
        assertNotEquals(19132, result.suggestedPort)
    }

    @Test
    fun rejectsPortBoundByAnotherProcess() {
        DatagramSocket(0).use { socket ->
            val result = PortAllocator.validate(socket.localPort, emptySet())
            assertFalse(result.available)
            assertNotEquals(socket.localPort, result.suggestedPort)
        }
    }
}
