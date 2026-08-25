package com.example.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Read-only inspection of the exact verified PowerNukkitX JAR used for launch.
 *
 * PowerNukkitX 2.x initializes its Bedrock block-state registry from the bundled
 * gamedata/kaooot/block_palette.nbt resource. MineHost decodes that exact resource
 * without loading any engine classes. Unknown or ambiguous states fail closed.
 */
object PowerNukkitXArtifactInspector {
    const val PALETTE_RESOURCE = "gamedata/kaooot/block_palette.nbt"

    fun inspect(context: EngineArtifactContext): Result<EngineArtifactInspection> = runCatching {
        require(context.artifact.isFile && context.artifact.length() > 0L) {
            "PowerNukkitX artifact is missing or empty: ${context.artifact.absolutePath}"
        }
        val expected = context.expectedSha256?.trim()?.lowercase()
        require(expected?.matches(SHA_256_PATTERN) == true) {
            "PowerNukkitX artifact inspection requires the catalogued SHA-256"
        }
        val actual = sha256(context.artifact)
        require(actual == expected) {
            "PowerNukkitX artifact SHA-256 mismatch: expected $expected but found $actual"
        }

        ZipFile(context.artifact).use { jar ->
            require(jar.getEntry("cn/nukkit/Server.class") != null || jar.getEntry("org/powernukkitx/Server.class") != null) {
                "Artifact does not contain a recognizable PowerNukkitX server class"
            }
            val paletteEntry = jar.getEntry(PALETTE_RESOURCE)
                ?: error("PowerNukkitX JAR is missing required resource $PALETTE_RESOURCE")
            require(!paletteEntry.isDirectory) {
                "PowerNukkitX palette resource is a directory: $PALETTE_RESOURCE"
            }

            val compressed = jar.getInputStream(paletteEntry).use {
                it.readLimited(MAX_COMPRESSED_PALETTE_BYTES, "$PALETTE_RESOURCE compressed payload")
            }
            require(compressed.isNotEmpty()) { "PowerNukkitX palette resource is empty" }
            val decompressed = GZIPInputStream(ByteArrayInputStream(compressed)).use {
                it.readLimited(MAX_DECOMPRESSED_PALETTE_BYTES, "$PALETTE_RESOURCE decompressed payload")
            }
            val parsed = BedrockNbt.parse(decompressed, byteOrder = ByteOrder.BIG_ENDIAN)
            require(parsed.bytesConsumed == decompressed.size) {
                "$PALETTE_RESOURCE has ${decompressed.size - parsed.bytesConsumed} trailing decompressed bytes"
            }
            val root = parsed.value.stringKeyMap("$PALETTE_RESOURCE root")
            val blocks = root["blocks"] as? List<*>
                ?: error("$PALETTE_RESOURCE has no compound list named 'blocks'")
            require(blocks.isNotEmpty()) { "$PALETTE_RESOURCE contains no block states" }
            require(blocks.size <= MAX_BLOCK_STATE_COUNT) {
                "$PALETTE_RESOURCE contains an unreasonable ${blocks.size} block states"
            }

            val canonicalToNetworkId = linkedMapOf<String, Int>()
            val networkIdToCanonical = linkedMapOf<Int, String>()
            val warnings = mutableListOf<String>()
            val errors = mutableListOf<String>()

            blocks.forEachIndexed { index, raw ->
                val compound = raw.stringKeyMap("$PALETTE_RESOURCE block $index")
                val canonical = CanonicalBlockStateCodec.fromCompound(compound)
                val networkId = compound.intValue("network_id")
                    ?: compound.intValue("networkId")
                    ?: error("$PALETTE_RESOURCE block $index has no network_id")

                val previousNetworkId = canonicalToNetworkId.putIfAbsent(canonical.canonicalIdentity, networkId)
                if (previousNetworkId != null && previousNetworkId != networkId) {
                    errors += "Canonical state ${canonical.blockName} maps to conflicting network IDs " +
                        "$previousNetworkId and $networkId"
                }
                val previousCanonical = networkIdToCanonical.putIfAbsent(networkId, canonical.canonicalIdentity)
                if (previousCanonical != null && previousCanonical != canonical.canonicalIdentity) {
                    errors += "PowerNukkitX network ID $networkId maps to multiple canonical block states"
                }
            }

            if (context.protocolVersions.isEmpty()) {
                warnings += "No client protocol identifiers were supplied; protocol palette validation remains separate"
            }

            val supportedBlockEntities = inspectBlockEntityRegistry(jar, context.engineVersionId, warnings)

            EngineArtifactInspection(
                engineId = PowerNukkitXWorldAdapter.engineId,
                engineVersionId = context.engineVersionId,
                artifactSha256 = actual,
                status = if (errors.isEmpty()) CompatibilityStatus.SUPPORTED else CompatibilityStatus.CORRUPT,
                persistentRuntimeIdsByCanonicalState = canonicalToNetworkId.toMap(),
                protocolStatesByRuntimeId = emptyMap(),
                inspectedResources = setOf(PALETTE_RESOURCE),
                warnings = warnings.distinct(),
                errors = errors.distinct(),
                supportedBlockEntityIdentifiers = supportedBlockEntities,
            )
        }
    }


    private fun inspectBlockEntityRegistry(
        jar: ZipFile,
        engineVersionId: String,
        warnings: MutableList<String>,
    ): Set<String> {
        val normalizedVersion = engineVersionId.trim().lowercase()
        val isProvenStable200 = normalizedVersion == "2.0.0" || normalizedVersion.endsWith(":2.0.0")
        if (!isProvenStable200) {
            warnings += "Block-entity registration proof is currently pinned to stable PowerNukkitX 2.0.0"
            return emptySet()
        }
        if (jar.getEntry("cn/nukkit/registry/BlockEntityRegistry.class") == null) {
            warnings += "PowerNukkitX BlockEntityRegistry.class is missing; no block-entity identifier is trusted"
            return emptySet()
        }
        val supported = PROVEN_2_0_0_BLOCK_ENTITY_CLASSES.mapNotNullTo(linkedSetOf()) { (identifier, classEntry) ->
            identifier.takeIf { jar.getEntry(classEntry) != null }
        }
        val missing = PROVEN_2_0_0_BLOCK_ENTITY_CLASSES.keys - supported
        if (missing.isNotEmpty()) {
            warnings += "The exact JAR is missing ${missing.size} block-entity classes expected by the 2.0.0 registry"
        }
        return supported
    }

    private fun Any?.stringKeyMap(label: String): Map<String, Any?> {
        val source = this as? Map<*, *> ?: error("$label is not an NBT compound")
        return source.entries.associate { entry ->
            val key = entry.key as? String ?: error("$label contains a non-string key")
            key to entry.value
        }
    }

    private fun Map<String, Any?>.intValue(name: String): Int? = (this[name] as? Number)?.toInt()

    private fun InputStream.readLimited(limit: Int, label: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE * 4))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= limit) { "$label exceeds the $limit-byte safety limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val PROVEN_2_0_0_BLOCK_ENTITY_CLASSES = linkedMapOf(
        "Furnace" to "cn/nukkit/blockentity/BlockEntityFurnace.class",
        "Chest" to "cn/nukkit/blockentity/BlockEntityChest.class",
        "Sign" to "cn/nukkit/blockentity/BlockEntitySign.class",
        "EnchantTable" to "cn/nukkit/blockentity/BlockEntityEnchantTable.class",
        "Skull" to "cn/nukkit/blockentity/BlockEntitySkull.class",
        "FlowerPot" to "cn/nukkit/blockentity/BlockEntityFlowerPot.class",
        "BrewingStand" to "cn/nukkit/blockentity/BlockEntityBrewingStand.class",
        "ItemFrame" to "cn/nukkit/blockentity/BlockEntityItemFrame.class",
        "Cauldron" to "cn/nukkit/blockentity/BlockEntityCauldron.class",
        "EnderChest" to "cn/nukkit/blockentity/BlockEntityEnderChest.class",
        "Beacon" to "cn/nukkit/blockentity/BlockEntityBeacon.class",
        "PistonArm" to "cn/nukkit/blockentity/BlockEntityPistonArm.class",
        "Comparator" to "cn/nukkit/blockentity/BlockEntityComparator.class",
        "Hopper" to "cn/nukkit/blockentity/BlockEntityHopper.class",
        "Bed" to "cn/nukkit/blockentity/BlockEntityBed.class",
        "Jukebox" to "cn/nukkit/blockentity/BlockEntityJukebox.class",
        "ShulkerBox" to "cn/nukkit/blockentity/BlockEntityShulkerBox.class",
        "Banner" to "cn/nukkit/blockentity/BlockEntityBanner.class",
        "Music" to "cn/nukkit/blockentity/BlockEntityMusic.class",
        "MobSpawner" to "cn/nukkit/blockentity/BlockEntityMobSpawner.class",
        "CreakingHeart" to "cn/nukkit/blockentity/BlockEntityCreakingHeart.class",
        "Lectern" to "cn/nukkit/blockentity/BlockEntityLectern.class",
        "BlastFurnace" to "cn/nukkit/blockentity/BlockEntityBlastFurnace.class",
        "Smoker" to "cn/nukkit/blockentity/BlockEntitySmoker.class",
        "Beehive" to "cn/nukkit/blockentity/BlockEntityBeehive.class",
        "Conduit" to "cn/nukkit/blockentity/BlockEntityConduit.class",
        "Barrel" to "cn/nukkit/blockentity/BlockEntityBarrel.class",
        "Campfire" to "cn/nukkit/blockentity/BlockEntityCampfire.class",
        "Bell" to "cn/nukkit/blockentity/BlockEntityBell.class",
        "DaylightDetector" to "cn/nukkit/blockentity/BlockEntityDaylightDetector.class",
        "Dispenser" to "cn/nukkit/blockentity/BlockEntityDispenser.class",
        "Dropper" to "cn/nukkit/blockentity/BlockEntityDropper.class",
        "MovingBlock" to "cn/nukkit/blockentity/BlockEntityMovingBlock.class",
        "NetherReactor" to "cn/nukkit/blockentity/BlockEntityNetherReactor.class",
        "Lodestone" to "cn/nukkit/blockentity/BlockEntityLodestone.class",
        "Target" to "cn/nukkit/blockentity/BlockEntityTarget.class",
        "EndPortal" to "cn/nukkit/blockentity/BlockEntityEndPortal.class",
        "EndGateway" to "cn/nukkit/blockentity/BlockEntityEndGateway.class",
        "CommandBlock" to "cn/nukkit/blockentity/BlockEntityCommandBlock.class",
        "SculkSensor" to "cn/nukkit/blockentity/BlockEntitySculkSensor.class",
        "CalibratedSculkSensor" to "cn/nukkit/blockentity/BlockEntityCalibratedSculkSensor.class",
        "SculkCatalyst" to "cn/nukkit/blockentity/BlockEntitySculkCatalyst.class",
        "SculkShrieker" to "cn/nukkit/blockentity/BlockEntitySculkShrieker.class",
        "StructureBlock" to "cn/nukkit/blockentity/BlockEntityStructBlock.class",
        "GlowItemFrame" to "cn/nukkit/blockentity/BlockEntityGlowItemFrame.class",
        "HangingSign" to "cn/nukkit/blockentity/BlockEntityHangingSign.class",
        "ChiseledBookshelf" to "cn/nukkit/blockentity/BlockEntityChiseledBookshelf.class",
        "DecoratedPot" to "cn/nukkit/blockentity/BlockEntityDecoratedPot.class",
        "Crafter" to "cn/nukkit/blockentity/BlockEntityCrafter.class",
        "CopperGolemStatue" to "cn/nukkit/blockentity/BlockEntityCopperGolemStatue.class",
        "Shelf" to "cn/nukkit/blockentity/BlockEntityShelf.class",
        "TrialSpawner" to "cn/nukkit/blockentity/BlockEntityTrialSpawner.class",
        "Vault" to "cn/nukkit/blockentity/BlockEntityVault.class",
        "BrushableBlock" to "cn/nukkit/blockentity/BlockEntityBrushable.class",
    )

    private val SHA_256_PATTERN = Regex("^[a-f0-9]{64}$")
    private const val MAX_COMPRESSED_PALETTE_BYTES = 8 * 1024 * 1024
    private const val MAX_DECOMPRESSED_PALETTE_BYTES = 64 * 1024 * 1024
    private const val MAX_BLOCK_STATE_COUNT = 250_000
}
