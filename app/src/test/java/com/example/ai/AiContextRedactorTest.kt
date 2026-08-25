package com.example.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextRedactorTest {
    @Test
    fun redactsSensitiveIdentifiersAndPlayerNames() {
        val input = "player=Steve joined the server from 192.168.1.10 email steve@example.com uuid 123e4567-e89b-12d3-a456-426614174000"
        val output = AiContextRedactor.redactLine(input)

        assertFalse(output.contains("Steve"))
        assertFalse(output.contains("192.168.1.10"))
        assertFalse(output.contains("steve@example.com"))
        assertFalse(output.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(output.contains("[REDACTED_PLAYER]"))
        assertTrue(output.contains("[REDACTED_ADDRESS]"))
        assertTrue(output.contains("[REDACTED_EMAIL]"))
        assertTrue(output.contains("[REDACTED_UUID]"))
    }

    @Test
    fun redactsBearerAndSecretAssignments() {
        val output = AiContextRedactor.redactLine(
            "Authorization=secret-token Bearer abc.def.ghi api_key=my-secret"
        )
        assertFalse(output.contains("secret-token"))
        assertFalse(output.contains("my-secret"))
        assertTrue(output.contains("[REDACTED]"))
    }
}
