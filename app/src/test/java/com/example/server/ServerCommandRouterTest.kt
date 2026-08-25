package com.example.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCommandRouterTest {
    @Test
    fun commandsAlwaysGoToExactUuid() {
        val router = ServerCommandRouter()
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        router.attach("server-a", "session-a", a::add)
        router.attach("server-b", "session-b", b::add)

        assertTrue(router.send("server-b", "stop"))
        assertEquals(emptyList<String>(), a)
        assertEquals(listOf("stop"), b)
    }

    @Test
    fun staleSessionCannotDetachNewWriter() {
        val router = ServerCommandRouter()
        val delivered = mutableListOf<String>()
        router.attach("server-a", "old", {})
        router.attach("server-a", "new", delivered::add)

        assertFalse(router.detach("server-a", "old"))
        assertTrue(router.send("server-a", "list"))
        assertEquals(listOf("list"), delivered)
    }
}
