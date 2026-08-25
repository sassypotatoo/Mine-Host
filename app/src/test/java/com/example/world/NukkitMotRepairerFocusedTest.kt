package com.example.world

import org.junit.Assert.*
import org.junit.Test

class NukkitMotRepairerFocusedTest {

    private fun makeObs(canonical: String, name: String, version: Int?): CanonicalBlockStateObservation {
        return CanonicalBlockStateObservation(
            canonicalIdentity = canonical,
            blockName = name,
            properties = emptyList(),
            blockStateVersion = version,
            originalRawNbtSha256 = "test_sha",
            sampleLocation = BlockStateSourceLocation(0, 0, 0, 0, 0),
            occurrenceCount = 1
        )
    }

    @Test
    fun `resolver returns NamespaceUnproven when runtime lookup namespace is UNKNOWN`() {
        val inspection = EngineArtifactInspection(
            engineId = "Nukkit-MOT",
            engineVersionId = "1361",
            artifactSha256 = "test_sha",
            status = CompatibilityStatus.SUPPORTED,
            persistentRuntimeIdsByCanonicalState = mapOf("minecraft:stone" to 0),
            protocolStatesByRuntimeId = mapOf(1001 to mapOf(0 to EnginePaletteBlockState(1001, 0, 1, 0, false, "minecraft:stone", "minecraft:stone", 17959425))),
            inspectedResources = emptySet(),
            warnings = emptyList(),
            errors = emptyList(),
            runtimeLookupNamespace = RuntimeLookupNamespace.UNKNOWN
        )

        val obs = makeObs("minecraft:stone", "minecraft:stone", 17959425)
        val res = NukkitMotRuntimeLookupResolver.resolve(obs, inspection, 1001)

        assertTrue("Expected NamespaceUnproven, got $res", res is RuntimeLookupResolution.NamespaceUnproven)
    }

    @Test
    fun `resolver detects ambiguous states when multiple candidates match semantic state`() {
        val inspection = EngineArtifactInspection(
            engineId = "Nukkit-MOT",
            engineVersionId = "1361",
            artifactSha256 = "test_sha",
            status = CompatibilityStatus.SUPPORTED,
            persistentRuntimeIdsByCanonicalState = mapOf("minecraft:stone|version=100" to 0, "minecraft:stone|version=200" to 1),
            protocolStatesByRuntimeId = mapOf(1001 to mapOf(0 to EnginePaletteBlockState(1001, 0, 1, 0, false, "minecraft:stone|version=100", "minecraft:stone", 17959425))),
            inspectedResources = emptySet(),
            warnings = emptyList(),
            errors = emptyList(),
            runtimeLookupNamespace = RuntimeLookupNamespace.PERSISTENT_PALETTE
        )

        val obs = makeObs("minecraft:stone", "minecraft:stone", 17959425)
        val res = NukkitMotRuntimeLookupResolver.resolve(obs, inspection, 1001)

        assertTrue("Expected Ambiguous resolution, got $res", res is RuntimeLookupResolution.Ambiguous)
    }

    @Test
    fun `resolver detects version translation requirement when block state versions mismatch`() {
        val inspection = EngineArtifactInspection(
            engineId = "Nukkit-MOT",
            engineVersionId = "1361",
            artifactSha256 = "test_sha",
            status = CompatibilityStatus.SUPPORTED,
            persistentRuntimeIdsByCanonicalState = mapOf("minecraft:stone" to 0),
            protocolStatesByRuntimeId = mapOf(1001 to mapOf(0 to EnginePaletteBlockState(1001, 0, 1, 0, false, "minecraft:stone", "minecraft:stone", 20000000))),
            inspectedResources = emptySet(),
            warnings = emptyList(),
            errors = emptyList(),
            runtimeLookupNamespace = RuntimeLookupNamespace.PERSISTENT_PALETTE
        )

        val obs = makeObs("minecraft:stone", "minecraft:stone", 17959425)
        val res = NukkitMotRuntimeLookupResolver.resolve(obs, inspection, 1001)

        assertTrue("Expected VersionTranslationRequired, got $res", res is RuntimeLookupResolution.VersionTranslationRequired)
    }

    @Test
    fun `resolver yields proven resolution when exact state and namespace match`() {
        val inspection = EngineArtifactInspection(
            engineId = "Nukkit-MOT",
            engineVersionId = "1361",
            artifactSha256 = "test_sha",
            status = CompatibilityStatus.SUPPORTED,
            persistentRuntimeIdsByCanonicalState = mapOf("minecraft:stone" to 0),
            protocolStatesByRuntimeId = mapOf(1001 to mapOf(0 to EnginePaletteBlockState(1001, 0, 1, 0, false, "minecraft:stone", "minecraft:stone", 17959425))),
            inspectedResources = emptySet(),
            warnings = emptyList(),
            errors = emptyList(),
            runtimeLookupNamespace = RuntimeLookupNamespace.PERSISTENT_PALETTE,
            legacyDataBits = 6,
            legacyDataMask = 63
        )

        val obs = makeObs("minecraft:stone", "minecraft:stone", 17959425)
        val res = NukkitMotRuntimeLookupResolver.resolve(obs, inspection, 1001)

        assertTrue("Expected Proven resolution, got $res", res is RuntimeLookupResolution.Proven)
        val proven = res as RuntimeLookupResolution.Proven
        assertEquals(0, proven.persistentId.value)
        assertEquals(0, proven.protocolRuntimeId?.value)
    }
}
