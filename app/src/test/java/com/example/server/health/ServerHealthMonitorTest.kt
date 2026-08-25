package com.example.server.health

import com.example.server.ServerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerHealthMonitorTest {

    @Test
    fun testWorldIncompatibleDetection() {
        var status: ServerStatus = ServerStatus.STOPPED
        val monitor = ServerHealthMonitor(
            onLog = {},
            onStatusChange = { status = it }
        )

        val logLine = "[12:00:00] [Server thread/CRITICAL]: Invalid chunk serializer version: 100"
        val event = monitor.analyzeLogLine(logLine, listOf("Done"))
        
        assertEquals(HealthEvent.WORLD_INCOMPATIBLE, event)
    }

    @Test
    fun testPortDetection() {
        val monitor = ServerHealthMonitor(
            onLog = {},
            onStatusChange = {}
        )

        val logLine = "[12:00:00] [Server thread/INFO]: Opening server on 0.0.0.0:19133"
        val event = monitor.analyzeLogLine(logLine, listOf("Done"))
        
        assertEquals(HealthEvent.BOUND_PORT_DETECTED, event)
    }

    @Test
    fun testEngineReadyDetection() {
        val monitor = ServerHealthMonitor(
            onLog = {},
            onStatusChange = {}
        )

        val logLine = "[12:00:00] [Server thread/INFO]: Done (2.5s)!"
        val event = monitor.analyzeLogLine(logLine, listOf("Done"))
        
        assertEquals(HealthEvent.ENGINE_READY, event)
    }

    @Test
    fun testPortMismatchDetection() {
        val monitor = ServerHealthMonitor(
            onLog = {},
            onStatusChange = {}
        )

        val logLine = "[12:00:00] [Server thread/INFO]: Opening server on 0.0.0.0:19133"
        val event = monitor.analyzeLogLine(logLine, listOf("Done"))
        
        assertEquals(HealthEvent.BOUND_PORT_DETECTED, event)
    }
    @Test
    fun provisionalAndVerifiedProcessExitsKeepWorldTruth() {
        var status = ServerStatus.STOPPED
        val monitor = ServerHealthMonitor(onLog = {}, onStatusChange = { status = it })
        monitor.setStatus(ServerStatus.WORLD_PROVISIONALLY_LOADED)
        monitor.onProcessExit(exitCode = 1, intentional = false)
        assertEquals(ServerStatus.WORLD_LOAD_FAILED, status)

        monitor.setStatus(ServerStatus.WORLD_VERIFIED)
        monitor.onProcessExit(exitCode = 1, intentional = false)
        assertEquals(ServerStatus.CRASHED, status)
    }

    @Test
    fun allRequiredWorldCompatibilityErrorsFailClosed() {
        val monitor = ServerHealthMonitor(onLog = {}, onStatusChange = {})
        val lines = listOf(
            "Invalid chunk serializer version 42",
            "Failed to read chunk",
            "No runtime2legacy mapping for 11893",
            "Can not find legacyId! No runtime2legacy mapping for 11910",
            "Missing block runtime mapping",
            "Tried to create block entity that doesn't exists: Vault",
            "Unknown block entity: TrialSpawner",
            "Unsupported palette",
            "Failed to decode chunk",
            "Failed to load actor/entity data",
        )
        lines.forEach { line ->
            assertEquals(line, HealthEvent.WORLD_INCOMPATIBLE, monitor.analyzeLogLine(line, listOf("Done")))
        }
    }

}
