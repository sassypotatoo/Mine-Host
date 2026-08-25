package com.example.world

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerNukkitXArtifactInspectorTest {
    @Test fun exactBundledPaletteIsDecoded() = withFixture(Variant.COMPLETE) { jar, sha, vault ->
        val inspection = inspect(jar, sha)
        assertEquals(CompatibilityStatus.SUPPORTED, inspection.status)
        assertEquals(91_337, inspection.persistentRuntimeIdsByCanonicalState[vault.canonicalIdentity])
        assertTrue(PowerNukkitXArtifactInspector.PALETTE_RESOURCE in inspection.inspectedResources)
        assertTrue("TrialSpawner" in inspection.supportedBlockEntityIdentifiers)
        assertTrue("Vault" in inspection.supportedBlockEntityIdentifiers)
    }

    @Test fun worldStateMissingFromExactPaletteFailsClosed() = withFixture(Variant.AIR_ONLY) { jar, sha, vault ->
        val compatibility = PowerNukkitXWorldAdapter.checkWorldCompatibility(
            reportFor(vault),
            "powernukkitx:2.0.0",
            inspect(jar, sha),
        )
        assertEquals(CompatibilityStatus.UNSUPPORTED, compatibility.overall)
        assertEquals(CompatibilityStatus.UNSUPPORTED, compatibility.blockPalette)
        assertTrue(compatibility.reasons.any { it.contains("minecraft:vault") && it.contains("chunk=12,-7") })
    }

    @Test fun conflictingNetworkIdsCorruptArtifactInspection() = withFixture(Variant.COLLISION) { jar, sha, _ ->
        val inspection = inspect(jar, sha)
        assertEquals(CompatibilityStatus.CORRUPT, inspection.status)
        assertTrue(inspection.errors.any { it.contains("network ID 0") })
    }

    @Test fun exactRegistryRecognizesTrialAndVaultButRejectsSporeBlossom() =
        withFixture(Variant.COMPLETE) { jar, sha, vault ->
            val base = reportFor(vault)
            val report = base.copy(
                blockEntityIdentifiers = mapOf("TrialSpawner" to 2, "Vault" to 3, "SporeBlossom" to 4),
                blockEntityObservations = listOf(
                    BlockEntityObservationSummary(
                        identifier = "SporeBlossom",
                        dimensionId = 0,
                        blockX = 32,
                        blockY = 70,
                        blockZ = -16,
                        owningChunkX = 2,
                        owningChunkZ = -1,
                        positionMatchesOwningChunk = true,
                        nbtKeys = setOf("id", "x", "y", "z"),
                        rawNbtSha256 = "c".repeat(64),
                    ),
                ),
            )
            val compatibility = PowerNukkitXWorldAdapter.checkWorldCompatibility(
                report,
                "powernukkitx:2.0.0",
                inspect(jar, sha),
            )
            assertEquals(CompatibilityStatus.UNSUPPORTED, compatibility.blockEntities)
            assertTrue(compatibility.reasons.any { it.contains("SporeBlossom") && it.contains("4 records") })
            assertTrue(compatibility.reasons.none { it.contains("Not registered") && it.contains("TrialSpawner") })
            assertTrue(compatibility.reasons.none { it.contains("Not registered") && it.contains("Vault") })
        }

    @Test fun incorrectArtifactHashFailsClosed() = withFixture(Variant.COMPLETE) { jar, _, _ ->
        val inspection = PowerNukkitXWorldAdapter.inspectEngineArtifact(
            EngineArtifactContext("powernukkitx:2.0.0", jar, "0".repeat(64), listOf(1001)),
        )
        assertEquals(CompatibilityStatus.CORRUPT, inspection.status)
        assertTrue(inspection.errors.any { it.contains("SHA-256 mismatch") })
    }

    private enum class Variant { COMPLETE, AIR_ONLY, COLLISION }

    private fun inspect(jar: File, sha: String): EngineArtifactInspection =
        PowerNukkitXWorldAdapter.inspectEngineArtifact(
            EngineArtifactContext("powernukkitx:2.0.0", jar, sha, listOf(1001)),
        )

    private fun withFixture(
        variant: Variant,
        action: (File, String, CanonicalBlockStateCodec.State) -> Unit,
    ) {
        val root = Files.createTempDirectory("minehost-pnx-palette").toFile()
        try {
            val jar = File(root, "powernukkitx.jar")
            val vault = vaultState()
            writeJar(jar, variant)
            action(jar, sha256(jar), vault)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun reportFor(state: CanonicalBlockStateCodec.State): WorldCompatibilityReport {
        val observation = CanonicalBlockStateObservation(
            canonicalIdentity = state.canonicalIdentity,
            blockName = state.blockName,
            properties = state.properties,
            blockStateVersion = state.blockStateVersion,
            originalRawNbtSha256 = "a".repeat(64),
            sampleLocation = BlockStateSourceLocation(0, 12, -7, 3, 4),
            occurrenceCount = 1,
        )
        return WorldCompatibilityReport(
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
                subchunkFormatVersions = mapOf(9 to 1),
                paletteVersions = mapOf(18_168_865 to 1),
                blockNameCounts = mapOf(state.blockName to 1),
                uniqueBlockStateCount = 1,
                paletteEntryCount = 1,
                parseErrors = emptyList(),
                canonicalStates = listOf(observation),
            ),
            blockEntityIdentifiers = emptyMap(),
            actorEntityIdentifiers = emptyMap(),
            behaviorPacks = emptyList(),
            resourcePacks = emptyList(),
            unknownChunkRecordTypes = emptyMap(),
            levelDbTableErrors = emptyList(),
            activeLevelDbLogs = emptyList(),
            warnings = emptyList(),
            errors = emptyList(),
            sections = emptyMap(),
        )
    }

    private fun vaultState() = CanonicalBlockStateCodec.fromCompound(
        linkedMapOf(
            "name" to "minecraft:vault",
            "states" to linkedMapOf(
                "minecraft:cardinal_direction" to "east",
                "ominous" to 0.toByte(),
                "vault_state" to "inactive",
            ),
            "version" to 18_168_865,
        ),
    )

    private fun writeJar(jar: File, variant: Variant) {
        val air = blockCompound("minecraft:air", emptyMap(), 0)
        val vault = blockCompound(
            "minecraft:vault",
            mapOf(
                "minecraft:cardinal_direction" to "east",
                "ominous" to 0.toByte(),
                "vault_state" to "inactive",
            ),
            91_337,
        )
        val colliding = blockCompound(
            "minecraft:trial_spawner",
            mapOf("ominous" to 0.toByte(), "trial_spawner_state" to 0),
            0,
        )
        val blocks = when (variant) {
            Variant.COMPLETE -> listOf(air, vault)
            Variant.AIR_ONLY -> listOf(air)
            Variant.COLLISION -> listOf(air, colliding)
        }
        val palette = rootCompound(namedList("blocks", blocks))

        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("cn/nukkit/Server.class"))
            zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            zip.closeEntry()
            listOf(
                "cn/nukkit/registry/BlockEntityRegistry.class",
                "cn/nukkit/blockentity/BlockEntityTrialSpawner.class",
                "cn/nukkit/blockentity/BlockEntityVault.class",
            ).forEach { classEntry ->
                zip.putNextEntry(ZipEntry(classEntry))
                zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(PowerNukkitXArtifactInspector.PALETTE_RESOURCE))
            zip.write(gzip(palette))
            zip.closeEntry()
        }
    }

    private fun blockCompound(name: String, states: Map<String, Any>, networkId: Int): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                namedString(data, "name", name)
                namedCompound(data, "states") {
                    states.toSortedMap().forEach { (key, value) -> writeNamedValue(it, key, value) }
                }
                namedInt(data, "version", 18_168_865)
                namedInt(data, "network_id", networkId)
                data.writeByte(0)
            }
        }.toByteArray()

    private fun rootCompound(payload: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { data ->
            data.writeByte(10); data.writeUTF(""); data.write(payload); data.writeByte(0)
        }
    }.toByteArray()

    private fun namedList(name: String, compounds: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { data ->
            data.writeByte(9); data.writeUTF(name); data.writeByte(10); data.writeInt(compounds.size)
            compounds.forEach(data::write)
        }
    }.toByteArray()

    private fun namedCompound(data: DataOutputStream, name: String, body: (DataOutputStream) -> Unit) {
        data.writeByte(10); data.writeUTF(name); body(data); data.writeByte(0)
    }

    private fun writeNamedValue(data: DataOutputStream, name: String, value: Any) = when (value) {
        is Byte -> namedByte(data, name, value.toInt())
        is Int -> namedInt(data, name, value)
        is String -> namedString(data, name, value)
        else -> error("Unsupported fixture value ${value::class.java.name}")
    }

    private fun namedByte(data: DataOutputStream, name: String, value: Int) {
        data.writeByte(1); data.writeUTF(name); data.writeByte(value)
    }

    private fun namedInt(data: DataOutputStream, name: String, value: Int) {
        data.writeByte(3); data.writeUTF(name); data.writeInt(value)
    }

    private fun namedString(data: DataOutputStream, name: String, value: String) {
        data.writeByte(8); data.writeUTF(name); data.writeUTF(value)
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
