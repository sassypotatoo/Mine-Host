package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldAdapterRegistryTest {
    @Test fun everySupportedNukkitFamilyEngineHasAnAdapter() {
        val expected = mapOf(
            "nukkit-mot" to NukkitMotWorldAdapter,
            "bedrock_power_nukkit_x" to PowerNukkitXWorldAdapter,
            "bedrock_power_nukkit_x_experimental" to PowerNukkitXExperimentalWorldAdapter,
            "bedrock_power_nukkit" to PowerNukkitWorldAdapter,
            "bedrock_nukkit" to Pm1eWorldAdapter,
            "bedrock_cloudburst_nukkit" to CloudburstWorldAdapter,
        )
        expected.forEach { (engineId, adapter) ->
            assertEquals(adapter, EngineWorldAdapterRegistry.forEngine(engineId))
            assertEquals(adapter, EngineWorldAdapterRegistry.forEngine("  ${engineId.uppercase()}  "))
        }
        assertNull(EngineWorldAdapterRegistry.forEngine("unknown"))
    }
}
