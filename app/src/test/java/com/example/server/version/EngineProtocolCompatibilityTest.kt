package com.example.server.version

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineProtocolCompatibilityTest {
    @Test
    fun matchingPowerNukkitXAdvertisementIsVerified() {
        val result = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = "1.26.30",
            advertisedBedrockVersion = "1.26.30",
            advertisedProtocol = 1001,
            expectedProtocols = listOf(1001),
            advertisedEdition = "MCPE",
        )
        assertEquals(EngineProtocolCompatibilityState.VERIFIED, result.state)
    }

    @Test
    fun newerProtocolIsReportedInsteadOfPretendingCompatible() {
        val result = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = "1.26.30",
            advertisedBedrockVersion = "1.26.33",
            advertisedProtocol = 1002,
            expectedProtocols = listOf(1001),
            advertisedEdition = "MCPE",
        )
        assertEquals(EngineProtocolCompatibilityState.PROTOCOL_MISMATCH, result.state)
    }

    @Test
    fun nonBedrockEditionIsRejected() {
        val result = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = "1.26.30",
            advertisedBedrockVersion = "1.26.30",
            advertisedProtocol = 1001,
            expectedProtocols = listOf(1001),
            advertisedEdition = "MCEE",
        )
        assertEquals(EngineProtocolCompatibilityState.PROTOCOL_MISMATCH, result.state)
    }

    @Test
    fun missingMetadataRemainsUnknown() {
        val result = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = "AUTO",
            advertisedBedrockVersion = null,
            advertisedProtocol = null,
            expectedProtocols = emptyList(),
            advertisedEdition = null,
        )
        assertEquals(EngineProtocolCompatibilityState.UNKNOWN, result.state)
    }
}
