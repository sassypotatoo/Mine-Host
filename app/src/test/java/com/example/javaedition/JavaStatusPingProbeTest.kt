package com.example.javaedition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JavaStatusPingProbeTest {

    @Test
    fun testVarIntEncodingAndDecoding() {
        val testValues = listOf(0, 1, 127, 128, 255, 2097151, 2147483647)
        for (value in testValues) {
            val out = ByteArrayOutputStream()
            JavaStatusPingProbe.writeVarInt(out, value)
            val bytes = out.toByteArray()
            val input = ByteArrayInputStream(bytes)
            val decoded = JavaStatusPingProbe.readVarInt(input)
            assertEquals("VarInt mismatch for $value", value, decoded)
        }
    }

    @Test
    fun testParseStatusJson() {
        val rawJson = """
            {
              "version": {
                "name": "1.20.4",
                "protocol": 765
              },
              "players": {
                "max": 20,
                "online": 5
              },
              "description": {
                "text": "A Paper Server"
              }
            }
        """.trimIndent()

        val result = JavaStatusPingProbe.parseStatusJson(rawJson)
        assertEquals("1.20.4", result.versionName)
        assertEquals(765, result.protocol)
        assertEquals(5, result.onlinePlayers)
        assertEquals(20, result.maxPlayers)
        assertEquals("A Paper Server", result.description)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseStatusJsonRejectsEmptyObject() {
        JavaStatusPingProbe.parseStatusJson("{}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseStatusJsonRejectsBlank() {
        JavaStatusPingProbe.parseStatusJson("   ")
    }
}
