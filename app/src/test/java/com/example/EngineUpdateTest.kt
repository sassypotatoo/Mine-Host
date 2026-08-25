package com.example

import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineUpdateTest {
    @Test
    fun mockWebServerStartsOnARealPort() {
        val server = MockWebServer()
        server.start()

        try {
            assertTrue(
                "MockWebServer must bind a positive local port",
                server.port > 0,
            )
        } finally {
            server.shutdown()
        }
    }
}
