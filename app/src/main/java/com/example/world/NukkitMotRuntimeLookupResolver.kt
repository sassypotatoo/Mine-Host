package com.example.world

/**
 * Central evidence gate for Nukkit-MOT's runtime-to-legacy lookup.
 * No numeric ID crosses namespaces unless the exact inspected launch artifact
 * proves the persistent-palette lookup path and the selected protocol owns the
 * same semantic state at that lookup key.
 */
object NukkitMotRuntimeLookupResolver {
    data class ResolvedState(
        val resolution: RuntimeLookupResolution.Proven,
        val protocol: Int,
        val protocolState: EnginePaletteBlockState,
    )

    fun resolve(
        observation: CanonicalBlockStateObservation,
        artifact: EngineArtifactInspection,
        protocol: Int,
    ): RuntimeLookupResolution {
        val semantic = CanonicalBlockStateCodec.semanticIdentity(
            observation.blockName,
            observation.properties,
        )

        // 1. Look for persistent ID
        val exactPersistent = artifact.persistentRuntimeIdsByCanonicalState[observation.canonicalIdentity]
        val persistentIdVal: Int
        if (exactPersistent != null) {
            persistentIdVal = exactPersistent
        } else {
            val semanticMatches = artifact.persistentRuntimeIdsByCanonicalState.entries.filter { entry ->
                CanonicalBlockStateCodec.semanticIdentityFromCanonical(entry.key) == semantic
            }
            if (semanticMatches.size > 1) {
                return RuntimeLookupResolution.Ambiguous(
                    persistentId = PersistentPaletteId(-1),
                    canonicalIdentity = observation.canonicalIdentity,
                    candidates = semanticMatches.map { it.key },
                    evidence = listOf("Multiple persistent palette entries match semantic state $semantic"),
                )
            }
            if (semanticMatches.isEmpty()) {
                return RuntimeLookupResolution.Unresolved(
                    persistentId = PersistentPaletteId(-1),
                    canonicalIdentity = observation.canonicalIdentity,
                    evidence = listOf("Canonical state ${observation.canonicalIdentity} is absent from the persistent palette."),
                )
            }
            persistentIdVal = semanticMatches.first().value
        }

        // 2. Namespace verification
        if (artifact.runtimeLookupNamespace != RuntimeLookupNamespace.PERSISTENT_PALETTE) {
            return RuntimeLookupResolution.NamespaceUnproven(
                evidence = artifact.runtimeLookupEvidence +
                    "The exact launch artifact did not prove that runtime2legacy consumes persistent palette indexes.",
            )
        }

        // 3. Protocol palette lookup
        val protocolStates = artifact.protocolStatesByRuntimeId[protocol]
            ?: return RuntimeLookupResolution.Unsupported(
                evidence = artifact.runtimeLookupEvidence +
                    "Protocol $protocol has no runtime-to-legacy mapping table.",
            )

        val exactProtocolState = protocolStates.values.firstOrNull { it.canonicalIdentity == observation.canonicalIdentity }
        val protocolState: EnginePaletteBlockState
        if (exactProtocolState != null) {
            protocolState = exactProtocolState
        } else {
            val protocolSemanticMatches = protocolStates.values.filter {
                CanonicalBlockStateCodec.semanticIdentityFromCanonical(it.canonicalIdentity) == semantic
            }
            if (protocolSemanticMatches.size > 1) {
                return RuntimeLookupResolution.Ambiguous(
                    persistentId = PersistentPaletteId(persistentIdVal),
                    canonicalIdentity = observation.canonicalIdentity,
                    candidates = protocolSemanticMatches.map { it.canonicalIdentity },
                    evidence = listOf("Multiple protocol $protocol states match semantic state $semantic"),
                )
            }
            if (protocolSemanticMatches.isEmpty()) {
                return RuntimeLookupResolution.Unresolved(
                    persistentId = PersistentPaletteId(persistentIdVal),
                    canonicalIdentity = observation.canonicalIdentity,
                    evidence = artifact.runtimeLookupEvidence +
                        "Protocol $protocol has no runtime-to-legacy mapping for semantic state $semantic.",
                )
            }
            protocolState = protocolSemanticMatches.first()
        }

        // Check version compatibility
        if (observation.blockStateVersion != null && protocolState.blockStateVersion != null &&
            observation.blockStateVersion != protocolState.blockStateVersion) {
            return RuntimeLookupResolution.VersionTranslationRequired(
                persistentId = PersistentPaletteId(persistentIdVal),
                canonicalIdentity = observation.canonicalIdentity,
                worldVersion = observation.blockStateVersion,
                targetVersion = protocolState.blockStateVersion,
                evidence = listOf("Block state version mismatch: world has ${observation.blockStateVersion} but protocol has ${protocolState.blockStateVersion}"),
            )
        }

        val dataBits = artifact.legacyDataBits
            ?: return RuntimeLookupResolution.Unresolved(
                persistentId = PersistentPaletteId(persistentIdVal),
                canonicalIdentity = observation.canonicalIdentity,
                evidence = artifact.runtimeLookupEvidence +
                    "Exact artifact Block.DATA_BITS was not proven; legacy full ID cannot be calculated safely.",
            )
        val dataMask = artifact.legacyDataMask
            ?: return RuntimeLookupResolution.Unresolved(
                persistentId = PersistentPaletteId(persistentIdVal),
                canonicalIdentity = observation.canonicalIdentity,
                evidence = artifact.runtimeLookupEvidence +
                    "Exact artifact Block.DATA_MASK was not derived from Block.DATA_BITS.",
            )
        if (protocolState.legacyData !in 0..dataMask) {
            return RuntimeLookupResolution.Unresolved(
                persistentId = PersistentPaletteId(persistentIdVal),
                canonicalIdentity = observation.canonicalIdentity,
                evidence = artifact.runtimeLookupEvidence +
                    "Protocol state legacy data ${protocolState.legacyData} exceeds proven mask $dataMask.",
            )
        }

        return RuntimeLookupResolution.Proven(
            persistentId = PersistentPaletteId(persistentIdVal),
            protocolRuntimeId = ProtocolRuntimeId(protocolState.runtimeId),
            runtime2LegacyKey = Runtime2LegacyLookupKey(persistentIdVal),
            legacyFullId = LegacyFullId(
                (protocolState.legacyBlockId shl dataBits) or
                    (protocolState.legacyData and dataMask),
            ),
            namespace = RuntimeLookupNamespace.PERSISTENT_PALETTE,
            evidence = artifact.runtimeLookupEvidence + listOf(
                "Exact artifact Block.DATA_BITS=$dataBits and DATA_MASK=$dataMask were used for legacy full ID calculation.",
                "Protocol $protocol semantically aligns persistent lookup key $persistentIdVal with ${observation.canonicalIdentity}.",
            ),
        )
    }

    fun resolveState(
        observation: CanonicalBlockStateObservation,
        artifact: EngineArtifactInspection,
        protocol: Int,
    ): ResolvedState? {
        val resolution = resolve(observation, artifact, protocol)
            as? RuntimeLookupResolution.Proven
            ?: return null
        val protocolStates = artifact.protocolStatesByRuntimeId[protocol] ?: return null
        val targetRuntimeId = resolution.protocolRuntimeId?.value ?: return null
        val state = protocolStates[targetRuntimeId] ?: return null
        return ResolvedState(resolution, protocol, state)
    }
}
