package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NukkitMotWorldAdapterTest {
    @Test fun extractsRuntimeIdAfterRuntime2LegacyText() {
        assertEquals(
            11893,
            NukkitMotWorldAdapter.extractUnknownRuntimeId(
                "Can not find legacyId! No runtime2legacy mapping for 11893",
            ),
        )
    }

    @Test fun extractsRuntimeIdFromLegacyIdFallbackMessage() {
        assertEquals(15221, NukkitMotWorldAdapter.extractUnknownRuntimeId("Can not find legacyId: 15221"))
    }

    @Test fun unrelatedNumbersAreNotInventedAsMappings() {
        assertNull(NukkitMotWorldAdapter.extractUnknownRuntimeId("serializer 42 loaded successfully"))
    }

    @Test fun extractsRuntimeIdAfterRuntime2FullIdText() {
        assertEquals(11910, NukkitMotWorldAdapter.extractUnknownRuntimeId("No runtime2FullId mapping for 11910"))
    }

    @Test fun unsupportedBlockEntityReasonIncludesExactRecordEvidence() {
        val report = WorldCompatibilityReport(
            worldName = "fixture",
            sourceWorldHash = "b".repeat(64),
            totalFiles = 1,
            totalBytes = 1,
            lastOpenedMinecraftVersion = "1.26.33.1.0",
            storageVersion = 10,
            networkVersion = 975,
            chunkSerializerVersions = mapOf(42 to 1),
            minimumChunkSerializerVersion = 42,
            maximumChunkSerializerVersion = 42,
            chunkCountByDimension = mapOf(0 to 1),
            dimensionsPresent = setOf(0),
            worldSpawn = WorldCoordinates(0.0, 64.0, 0.0),
            localPlayer = ImportedPlayerData(
                false, null, null, null, null, null, null, null, null,
                emptyList(), emptyList(), emptyList(), emptyList(), emptySet(),
            ),
            blockPalette = BlockPaletteInformation(
                emptyMap(), emptyMap(), emptyMap(), 0, 0, emptyList(),
            ),
            blockEntityIdentifiers = mapOf("Vault" to 1),
            actorEntityIdentifiers = emptyMap(),
            behaviorPacks = emptyList(),
            resourcePacks = emptyList(),
            unknownChunkRecordTypes = emptyMap(),
            levelDbTableErrors = emptyList(),
            activeLevelDbLogs = emptyList(),
            warnings = emptyList(),
            errors = emptyList(),
            sections = emptyMap(),
            blockEntityObservations = listOf(
                BlockEntityObservationSummary(
                    identifier = "Vault",
                    dimensionId = 0,
                    blockX = -285,
                    blockY = -1,
                    blockZ = -33,
                    owningChunkX = -18,
                    owningChunkZ = -3,
                    positionMatchesOwningChunk = true,
                    nbtKeys = setOf("config", "data", "id", "x", "y", "z"),
                    rawNbtSha256 = "c".repeat(64),
                ),
            ),
        )
        val compatibility = NukkitMotWorldAdapter.checkWorldCompatibility(
            report,
            "nukkit-mot:1361",
            EngineArtifactInspection(
                engineId = "nukkit-mot",
                engineVersionId = "nukkit-mot:1361",
                artifactSha256 = "a".repeat(64),
                status = CompatibilityStatus.SUPPORTED,
                persistentRuntimeIdsByCanonicalState = emptyMap(),
                protocolStatesByRuntimeId = mapOf(1001 to emptyMap()),
                inspectedResources = emptySet(),
                warnings = emptyList(),
                errors = emptyList(),
                supportedBlockEntityIdentifiers = emptySet(),
            ),
        )
        assertEquals(CompatibilityStatus.UNSUPPORTED, compatibility.blockEntities)
        assertEquals(true, compatibility.reasons.any {
            it.contains("Vault") && it.contains("pos=-285,-1,-33") &&
                it.contains("chunk=-18,-3") && it.contains("raw=${"c".repeat(64)}")
        })
    }
}
