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

    // Regression: catalog stale but server functional — verify states are
    // returned correctly (the fatal/non-fatal decision is in
    // BedrockJavaEngineBase, not here; these tests confirm the evaluate
    // function still returns the right state for each scenario).

    @Test
    fun versionMismatchReturnsMismatchState() {
        // Cloudburst advertises 1.26.40 but catalog expects 1.20.70
        val result = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = "1.20.70",
            advertisedBedrockVersion = "1.26.40",
            advertisedProtocol = null,
            expectedProtocols = emptyList(),
            advertisedEdition = "MCPE",
        )
        assertEquals(EngineProtocolCompatibilityState.VERSION_MISMATCH, result.state)
    }

    @Test
    fun protocolMismatchReturnsMismatchState() {
        // Server advertises protocol 2169 but catalog expects 1001
        val result = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = "AUTO",
            advertisedBedrockVersion = "1.26.30",
            advertisedProtocol = 2169,
            expectedProtocols = listOf(1001),
            advertisedEdition = "MCPE",
        )
        assertEquals(EngineProtocolCompatibilityState.PROTOCOL_MISMATCH, result.state)
    }

    @Test
    fun unknownWithExpectedEvidenceReturnsUnknown() {
        // Server responds but no metadata to match — catalog has expected
        // protocols but server doesn't advertise them
        val result = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = "1.26.30",
            advertisedBedrockVersion = null,
            advertisedProtocol = null,
            expectedProtocols = listOf(1001),
            advertisedEdition = null,
        )
        assertEquals(EngineProtocolCompatibilityState.UNKNOWN, result.state)
    }

    @Test
    fun autoVersionWithNoAdvertisedDataReturnsUnknown() {
        // Nukkit-MOT with AUTO and no expected protocols — clean UNKNOWN
        val result = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = "AUTO",
            advertisedBedrockVersion = "1.26.30",
            advertisedProtocol = 2169,
            expectedProtocols = emptyList(),
            advertisedEdition = "MCPE",
        )
        assertEquals(EngineProtocolCompatibilityState.UNKNOWN, result.state)
    }
}
