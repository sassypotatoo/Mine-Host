package com.example.world

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

class NukkitMotArtifactRepairerTest {
    @Test
    fun `prepare requires proven namespace`() {
        val artifact = createTempFile(suffix = ".jar").toFile()
        
        val fakeJar = FakeNukkitJar.createValid(artifact)
        
        val context = EngineArtifactContext(
            engineVersionId = "test",
            artifact = artifact,
            expectedSha256 = fakeJar.sha256,
            protocolVersions = listOf(400)
        )
        
        val report = WorldCompatibilityReport(
            worldName = "test",
            sourceWorldHash = "hash",
            totalFiles = 1,
            totalBytes = 1L,
            lastOpenedMinecraftVersion = null,
            storageVersion = null,
            networkVersion = null,
            chunkSerializerVersions = emptyMap(),
            minimumChunkSerializerVersion = null,
            maximumChunkSerializerVersion = null,
            chunkCountByDimension = emptyMap(),
            dimensionsPresent = emptySet(),
            worldSpawn = null,
            localPlayer = ImportedPlayerData(false, null, null, null, null, null, null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptySet()),
            blockPalette = BlockPaletteInformation(emptyMap(), emptyMap(), emptyMap(), 0, 0L, emptyList()),
            blockEntityIdentifiers = emptyMap(),
            actorEntityIdentifiers = emptyMap(),
            behaviorPacks = emptyList(),
            resourcePacks = emptyList(),
            unknownChunkRecordTypes = emptyMap(),
            levelDbTableErrors = emptyList(),
            activeLevelDbLogs = emptyList(),
            warnings = emptyList(),
            errors = emptyList(),
            sections = emptyMap()
        )
        
        val result = NukkitMotArtifactRepairer.prepare(
            context,
            report,
            createTempFile().toFile().parentFile ?: File("/tmp")
        )
        
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("Expected namespace error, got: ${ex?.message}", ex?.message?.contains("could not be proven") == true)
    }
}
