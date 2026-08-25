package com.example.world

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Read-only inspection of the exact verified Nukkit-MOT JAR used for launch.
 * It does not load engine classes and never modifies the JAR.
 */
object NukkitMotArtifactInspector {
    fun inspect(context: EngineArtifactContext): Result<EngineArtifactInspection> = runCatching {
        require(context.artifact.isFile && context.artifact.length() > 0L) {
            "Nukkit-MOT artifact is missing or empty: ${context.artifact.absolutePath}"
        }
        val expected = context.expectedSha256?.trim()?.lowercase()
        require(expected?.matches(Regex("^[a-f0-9]{64}$")) == true) {
            "Nukkit-MOT artifact inspection requires the catalogued SHA-256"
        }
        val actual = sha256(context.artifact)
        require(actual == expected) {
            "Nukkit-MOT artifact SHA-256 mismatch: expected $expected but found $actual"
        }
        require(context.protocolVersions.isNotEmpty()) {
            "Nukkit-MOT catalog entry has no protocol palette identifiers"
        }

        ZipFile(context.artifact).use { jar ->
            val inspected = linkedSetOf<String>()
            val warnings = mutableListOf<String>()
            val errors = mutableListOf<String>()

            val legacyLayoutScan = inspectLegacyIdLayout(context.artifact)
            inspected += legacyLayoutScan.inspectedClasses
            warnings += legacyLayoutScan.warnings
            if (legacyLayoutScan.dataBits == null || legacyLayoutScan.dataMask == null) {
                errors += "The exact artifact Block.DATA_BITS value could not be proven from static initializer bytecode"
            }

            if (jar.getEntry("cn/nukkit/Nukkit.class") == null) {
                errors += "The exact artifact is missing cn.nukkit.Nukkit; it is not a valid Nukkit-MOT engine"
            }

            val levelDbResource = "leveldb_palette.nbt"
            val levelDbRoot = readGzipNbt(jar, levelDbResource).also { inspected += levelDbResource }
            val levelDbCompound = levelDbRoot as? Map<*, *>
                ?: error("$levelDbResource root is not a compound")
            val blocks = levelDbCompound["blocks"] as? List<*>
                ?: error("$levelDbResource has no compound list named 'blocks'")
            val persistent = linkedMapOf<String, Int>()
            blocks.forEachIndexed { runtimeId, element ->
                val compound = element.stringKeyMap("$levelDbResource block $runtimeId")
                val canonical = CanonicalBlockStateCodec.fromCompound(compound)
                val previous = persistent.putIfAbsent(canonical.canonicalIdentity, runtimeId)
                if (previous != null) {
                    errors += "Duplicate persistent canonical state at runtime IDs $previous and $runtimeId: ${canonical.blockName}"
                }
            }

            val protocolStates = linkedMapOf<Int, Map<Int, EnginePaletteBlockState>>()
            for (protocol in context.protocolVersions.distinct().sorted()) {
                require(protocol > 0) { "Invalid protocol palette number $protocol" }
                val resource = "runtime_block_states_${protocol}.dat"
                val root = readGzipNbt(jar, resource).also { inspected += resource }
                val list = root as? List<*>
                    ?: error("$resource root is not an NBT list")
                val normal = mutableListOf<EnginePaletteBlockState>()
                val overload = mutableListOf<EnginePaletteBlockState>()
                list.forEachIndexed { index, element ->
                    val compound = element.stringKeyMap("$resource entry $index")
                    val canonical = CanonicalBlockStateCodec.fromCompound(compound)
                    val runtimeId = compound.intValue("runtimeId")
                        ?: error("$resource entry $index has no runtimeId")
                    val legacyBlockId = compound.intValue("id")
                        ?: error("$resource entry $index has no legacy id")
                    val legacyData = compound.intValue("data")
                        ?: error("$resource entry $index has no legacy data")
                    require(runtimeId >= 0) { "$resource entry $index has negative runtimeId $runtimeId" }
                    require(legacyBlockId >= 0) { "$resource entry $index has negative legacy id $legacyBlockId" }
                    require(legacyData >= 0) { "$resource entry $index has negative legacy data $legacyData" }
                    legacyLayoutScan.dataMask?.let { mask ->
                        require(legacyData <= mask) {
                            "$resource entry $index has legacy data $legacyData outside proven mask $mask"
                        }
                    }
                    val state = EnginePaletteBlockState(
                        protocol = protocol,
                        runtimeId = runtimeId,
                        legacyBlockId = legacyBlockId,
                        legacyData = legacyData,
                        stateOverload = compound.booleanValue("stateOverload"),
                        canonicalIdentity = canonical.canonicalIdentity,
                        blockName = canonical.blockName,
                        blockStateVersion = canonical.blockStateVersion,
                    )
                    if (state.stateOverload) overload += state else normal += state
                }

                // Mirrors Nukkit-MOT's two-pass registration and runtimeIdToLegacy.putIfAbsent behavior.
                val byRuntimeId = linkedMapOf<Int, EnginePaletteBlockState>()
                (normal + overload).forEach { state ->
                    val previous = byRuntimeId.putIfAbsent(state.runtimeId, state)
                    if (previous != null && previous.canonicalIdentity != state.canonicalIdentity) {
                        warnings += "$resource runtimeId ${state.runtimeId} has an overload; first registered canonical state is retained"
                    }
                }
                protocolStates[protocol] = byRuntimeId.toMap()
            }

            val blockEntityScan = inspectBlockEntityCapabilities(context.artifact)
            inspected += blockEntityScan.inspectedClasses
            warnings += blockEntityScan.warnings

            val runtimeLookupScan = inspectRuntimeLookupEvidence(context, context.artifact, actual)
            inspected += runtimeLookupScan.inspectedClasses
            warnings += runtimeLookupScan.warnings

            EngineArtifactInspection(
                engineId = NukkitMotWorldAdapter.engineId,
                engineVersionId = context.engineVersionId,
                artifactSha256 = actual,
                status = if (errors.isEmpty()) CompatibilityStatus.SUPPORTED else CompatibilityStatus.CORRUPT,
                persistentRuntimeIdsByCanonicalState = persistent.toMap(),
                protocolStatesByRuntimeId = protocolStates.toMap(),
                inspectedResources = inspected,
                warnings = warnings.distinct(),
                errors = errors,
                supportedBlockEntityIdentifiers = blockEntityScan.evidence.keys,
                blockEntityCapabilityEvidence = blockEntityScan.evidence,
                runtimeLookupEvidence = runtimeLookupScan.evidence,
                runtimeLookupNamespace = runtimeLookupScan.namespace,
                runtimeLookupClassName = runtimeLookupScan.lookupClassName,
                runtimeLookupMethodName = runtimeLookupScan.lookupMethodName,
                legacyDataBits = legacyLayoutScan.dataBits,
                legacyDataMask = legacyLayoutScan.dataMask,
            )
        }
    }

    private data class LegacyIdLayoutScan(
        val dataBits: Int?,
        val dataMask: Int?,
        val inspectedClasses: Set<String>,
        val warnings: List<String>,
    )

    /**
     * Proves Block.DATA_BITS from the exact artifact's <clinit> bytecode.
     * Nukkit-MOT initializes the field through Utils.dynamic(int), so relying
     * on a hardcoded shift or a ConstantValue attribute is unsafe.
     */
    private fun inspectLegacyIdLayout(artifact: File): LegacyIdLayoutScan {
        val blockClass = "cn/nukkit/block/Block.class"
        val scan = ClassFileUtf8Reader.scanStaticInitializerInts(artifact) { it == blockClass }
        val dataBits = scan.valuesByClass[blockClass]?.get("DATA_BITS")
            ?.takeIf { it in 1..16 }
        val dataMask = dataBits?.let { (1 shl it) - 1 }
        return LegacyIdLayoutScan(
            dataBits = dataBits,
            dataMask = dataMask,
            inspectedClasses = scan.valuesByClass.keys,
            warnings = buildList {
                addAll(scan.errors.map { "Legacy ID layout scan: $it" })
                if (dataBits == null) {
                    add("Exact artifact Block.DATA_BITS could not be proven; runtime-to-legacy repair is disabled.")
                } else {
                    add("Exact artifact Block.DATA_BITS=$dataBits and derived DATA_MASK=$dataMask were proven from Block.<clinit>.")
                }
            },
        )
    }

    data class RuntimeLookupScan(
        val evidence: List<String>,
        val inspectedClasses: Set<String>,
        val warnings: List<String>,
        val namespace: RuntimeLookupNamespace,
        val lookupClassName: String?,
        val lookupMethodName: String?,
    )

    /**
     * Finds exact-artifact classes that contain the runtime-to-legacy warning
     * text or lookup symbols. This is diagnostic evidence only. It deliberately
     * does not infer that a persistent palette index is a protocol runtime ID.
     */
    internal fun inspectRuntimeLookupEvidence(context: EngineArtifactContext, artifact: File, artifactSha256: String): RuntimeLookupScan {
        val exactClasses = setOf(
            "cn/nukkit/level/format/leveldb/BlockStateMapping.class",
            "cn/nukkit/level/format/leveldb/NukkitLegacyMapper.class",
            "cn/nukkit/level/BlockPalette.class",
        )
        
        var profile: EngineProfile? = null
        val warnings = mutableListOf<String>()
        val evidence = mutableListOf<String>()
        val baseProfile = NukkitMotBuild1361Profile.PROFILE

        if (artifactSha256 == baseProfile.artifactSha256) {
            // Validate that artifact JAR zip file actually contains the proof classes and resource entries
            val entryHashes = mutableMapOf<String, String>()
            ZipFile(artifact).use { jar ->
                for (className in exactClasses) {
                    if (jar.getEntry(className) == null) {
                        warnings.add("Proof class $className is missing from artifact JAR zip file")
                    }
                }
                for ((resName, expectedHash) in baseProfile.resourceHashes) {
                    val entry = jar.getEntry(resName)
                    if (entry == null) {
                        warnings.add("Profile resource $resName is missing from artifact JAR zip file")
                    } else {
                        val actualHash = sha256(jar.getInputStream(entry))
                        entryHashes[resName] = actualHash
                        if (actualHash != expectedHash) {
                            warnings.add("Profile resource $resName hash mismatch: expected $expectedHash, found $actualHash")
                        }
                    }
                }
            }
            if (warnings.isEmpty()) {
                profile = baseProfile
                evidence.add("Base artifact verified directly against build profile ${baseProfile.engine}-${baseProfile.build}.")
            }
        } else if (context.provenance != null) {
            val prov = context.provenance
            val provValid = prov.baseArtifactSha256 == baseProfile.artifactSha256 &&
                prov.derivedArtifactSha256 == artifactSha256 &&
                prov.profileSchema == baseProfile.profileSchema &&
                prov.engineId == "Nukkit-MOT" &&
                prov.engineBuild == "1361" &&
                prov.allowedChangedEntries == setOf("leveldb_palette.nbt")

            if (!provValid) {
                warnings.add("Provenance metadata invalid or fails schema/hash expectations.")
            } else {
                // Perform entry-by-entry verification of derived JAR
                var entryCheckFailed = false
                ZipFile(artifact).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name
                        val entryHash = sha256(jar.getInputStream(entry))

                        if (name in prov.allowedChangedEntries) {
                            val expectedDerivedHash = prov.derivedEntryHashes[name]
                            val expectedPairHash = prov.changedEntryHashes[name]?.second
                            if (entryHash != expectedDerivedHash || entryHash != expectedPairHash) {
                                warnings.add("Derived changed entry $name SHA-256 $entryHash does not match expected derived hash $expectedDerivedHash")
                                entryCheckFailed = true
                            }
                        } else {
                            val expectedOrigHash = prov.originalEntryHashes[name]
                            if (expectedOrigHash != null && entryHash != expectedOrigHash) {
                                warnings.add("Unallowed modification detected in entry $name: found $entryHash, expected $expectedOrigHash")
                                entryCheckFailed = true
                            }
                        }
                    }
                }
                if (!entryCheckFailed) {
                    profile = baseProfile
                    evidence.add("Inherited profile from base artifact ${prov.baseArtifactSha256} via verified atomic derived artifact.")
                }
            }
        }
        
        val provenProfile = profile
        val proven = provenProfile != null

        if (provenProfile != null) {
            evidence.add(provenProfile.bytecodeSourceEvidence)
            evidence.add("Proven lookup path: leveldb_palette.nbt list index -> BlockStateMapping runtime ID -> NukkitLegacyMapper.runtimeToLegacyId/runtimeToLegacyData -> BlockPalette.getLegacyFullId(runtime ID).")
        }
        
        if (!proven) {
            warnings.add("The exact artifact runtime2legacy input namespace could not be proven; derived palette repair is disabled. Profile missing or unverified for SHA-256 $artifactSha256")
        }
        
        return RuntimeLookupScan(
            evidence = evidence,
            inspectedClasses = exactClasses,
            warnings = warnings,
            namespace = provenProfile?.lookupKeyNamespace ?: RuntimeLookupNamespace.UNKNOWN,
            lookupClassName = provenProfile?.warningClass,
            lookupMethodName = provenProfile?.warningMethod,
        )
    }

    private data class BlockEntityScan(
        val evidence: Map<String, BlockEntityCapabilityEvidence>,
        val inspectedClasses: Set<String>,
        val warnings: List<String>,
    )

    /**
     * Derives block-entity save identifiers from the exact verified artifact.
     * A capability is accepted only when an implementation class exists and a
     * registry/factory class references either that implementation or the same
     * save identifier. No engine class is loaded.
     */
    private fun inspectBlockEntityCapabilities(artifact: File): BlockEntityScan {
        val scan = ClassFileUtf8Reader.scanJar(artifact) { name ->
            name.endsWith(".class") && (
                name.startsWith("cn/nukkit/blockentity/") ||
                    name == "cn/nukkit/Server.class" ||
                    name.contains("BlockEntityRegistry", ignoreCase = true) ||
                    name.contains("BlockEntityFactory", ignoreCase = true)
                )
        }
        val classes = scan.stringsByClass
        val registryClasses = classes.keys.filter { path ->
            val simple = path.substringAfterLast('/').removeSuffix(".class")
            path == "cn/nukkit/Server.class" ||
                simple == "BlockEntity" ||
                simple.contains("Registry", ignoreCase = true) ||
                simple.contains("Factory", ignoreCase = true)
        }.toSet()
        val baseIdentifierStrings = classes["cn/nukkit/blockentity/BlockEntity.class"]
            .orEmpty()
            .filter(::looksLikeBlockEntityIdentifier)
            .toSet()
        val registryStringsByClass = registryClasses.associateWith { classes[it].orEmpty() }
        val registryStrings = registryStringsByClass.values.flatten().toSet()
        val excludedSuffixes = setOf(
            "", "Spawnable", "Container", "Inventory", "Holder", "Factory",
            "Registry", "Timing", "Unknown", "Base", "BlockEntity",
        )
        val evidence = linkedMapOf<String, BlockEntityCapabilityEvidence>()

        classes.forEach { (path, strings) ->
            if (!path.startsWith("cn/nukkit/blockentity/")) return@forEach
            val simple = path.substringAfterLast('/').removeSuffix(".class")
            if (!simple.startsWith("BlockEntity") || simple in setOf("BlockEntity", "BlockEntityFactory")) {
                return@forEach
            }
            val suffix = simple.removePrefix("BlockEntity")
            if (suffix in excludedSuffixes) return@forEach

            val internalName = path.removeSuffix(".class")
            val implementationReferences = registryStringsByClass.filterValues { values ->
                values.any { value ->
                    value == simple ||
                        value == internalName ||
                        value.endsWith("/$simple") ||
                        value.contains("L$internalName;")
                }
            }.keys
            if (implementationReferences.isEmpty()) return@forEach

            val normalizedSuffix = normalizeIdentifier(suffix)
            val identifierCandidates = (baseIdentifierStrings + strings.filter(::looksLikeBlockEntityIdentifier))
                .asSequence()
                .filter { candidate ->
                    val normalizedCandidate = normalizeIdentifier(candidate)
                    normalizedCandidate == normalizedSuffix ||
                        normalizedCandidate.endsWith(normalizedSuffix) ||
                        normalizedSuffix.endsWith(normalizedCandidate)
                }
                .distinct()
                .sortedWith(compareBy<String> { normalizeIdentifier(it) != normalizedSuffix }.thenBy { it.length })
                .toList()

            val identifier = identifierCandidates.firstOrNull { candidate ->
                baseIdentifierStrings.any { it.equals(candidate, ignoreCase = true) } &&
                    registryStrings.any { registryValue ->
                        registryValue.equals(candidate, ignoreCase = true) ||
                            normalizeIdentifier(registryValue) == normalizeIdentifier(candidate)
                    }
            } ?: identifierCandidates.firstOrNull { candidate ->
                baseIdentifierStrings.any { it.equals(candidate, ignoreCase = true) }
            }

            // Presence of a class and registry reference alone is not enough.
            // Require static evidence that the implementation participates in
            // NBT load/save handling. This remains fail-closed and never loads
            // the engine class into the Android process.
            val nbtEvidence = strings.filter { value ->
                value == "namedTag" ||
                    value == "loadNBT" ||
                    value == "saveNBT" ||
                    value == "initBlockEntity" ||
                    value.endsWith("/CompoundTag") ||
                    value.contains("nbt/tag/CompoundTag")
            }.distinct()

            if (identifier != null && nbtEvidence.isNotEmpty()) {
                val current = evidence[identifier]
                evidence[identifier] = BlockEntityCapabilityEvidence(
                    identifier = identifier,
                    registryClasses = current?.registryClasses.orEmpty() + implementationReferences +
                        setOfNotNull("cn/nukkit/blockentity/BlockEntity.class".takeIf(classes::containsKey)),
                    implementationClasses = current?.implementationClasses.orEmpty() + path,
                    evidence = (current?.evidence.orEmpty() + listOf(
                        "Exact artifact contains implementation class $path",
                        "Exact artifact BlockEntity constants contain identifier $identifier",
                        "Registry/bootstrap class references implementation $simple: ${implementationReferences.sorted().joinToString()}",
                        "Implementation contains NBT lifecycle evidence: ${nbtEvidence.sorted().joinToString()}",
                    )).distinct(),
                )
            }
        }

        val warnings = buildList {
            addAll(scan.errors.map { "Block-entity class scan: $it" })
            if (registryClasses.isEmpty()) {
                add("No Nukkit-MOT block-entity registry/bootstrap class was found by bounded static inspection")
            }
            if (baseIdentifierStrings.isEmpty()) {
                add("No block-entity save identifiers were found in the exact artifact BlockEntity class")
            }
            if (evidence.isEmpty()) {
                add("No block-entity capability could be proven from identifier, implementation, and registry/bootstrap evidence")
            }
        }
        return BlockEntityScan(
            evidence = evidence,
            inspectedClasses = classes.keys,
            warnings = warnings,
        )
    }

    private fun looksLikeBlockEntityIdentifier(value: String): Boolean {
        if (value.length !in 2..64) return false
        if (value.any { it == '/' || it == ';' || it == '(' || it == ')' || it.isWhitespace() }) return false
        if (value.startsWith("java.") || value.startsWith("cn.nukkit.")) return false
        return value.matches(Regex("^[A-Za-z][A-Za-z0-9_:.\\-]{1,63}$"))
    }

    private fun normalizeIdentifier(value: String): String = value
        .substringAfterLast(':')
        .filter(Char::isLetterOrDigit)
        .lowercase()

    private fun readGzipNbt(jar: ZipFile, resource: String): Any? {
        val entry = jar.getEntry(resource) ?: error("Nukkit-MOT JAR is missing required resource $resource")
        require(!entry.isDirectory) { "Nukkit-MOT resource is a directory: $resource" }
        require(entry.size <= MAX_RESOURCE_BYTES) { "Nukkit-MOT resource $resource exceeds maximum allowed size ($MAX_RESOURCE_BYTES bytes)" }

        val compressed = jar.getInputStream(entry).use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var totalRead = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                totalRead += read
                require(totalRead <= MAX_RESOURCE_BYTES) { "Compressed resource $resource exceeded $MAX_RESOURCE_BYTES bytes" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        require(compressed.isNotEmpty()) { "Nukkit-MOT resource is empty: $resource" }

        val bytes = GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var totalRead = 0L
            while (true) {
                val read = gzip.read(buffer)
                if (read < 0) break
                totalRead += read
                require(totalRead <= MAX_RESOURCE_BYTES) { "Decompressed resource $resource exceeded $MAX_RESOURCE_BYTES bytes" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

        val parsed = BedrockNbt.parse(bytes, byteOrder = ByteOrder.BIG_ENDIAN)
        require(parsed.bytesConsumed == bytes.size) {
            "$resource has ${bytes.size - parsed.bytesConsumed} trailing decompressed bytes"
        }
        return parsed.value
    }

    private const val MAX_RESOURCE_BYTES = 32L * 1024L * 1024L

    private fun Any?.stringKeyMap(label: String): Map<String, Any?> {
        val source = this as? Map<*, *> ?: error("$label is not an NBT compound")
        return source.entries.associate { entry ->
            val key = entry.key as? String ?: error("$label contains a non-string key")
            key to entry.value
        }
    }

    private fun Map<String, Any?>.intValue(name: String): Int? = (this[name] as? Number)?.toInt()

    private fun Map<String, Any?>.booleanValue(name: String): Boolean = when (val value = this[name]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use { sha256(it) }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
