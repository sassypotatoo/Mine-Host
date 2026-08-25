package com.example.world

import java.io.File

class WorldCompatibilityCore(private val serverRoot: File) {
    data class PreparedLaunch(
        val inspection: BedrockWorldInspectionResult,
        val compatibility: EngineWorldCompatibility,
        val protection: WorldWorkingCopyManager.Metadata,
        val resumedWorkingCopy: Boolean,
        val adapter: EngineWorldAdapter,
        val artifactInspection: EngineArtifactInspection,
        val launchArtifact: File,
        val repairs: List<NukkitMotArtifactRepairer.RuntimeMappingRepair> = emptyList(),
        val diagnostics: RuntimeMappingDiagnostics.DiagnosticSummary? = null,
    )

    private val inspector = BedrockWorldInspector()
    private val workingCopies = WorldWorkingCopyManager(serverRoot)

    fun inspect(worldDirectory: File): Result<BedrockWorldInspectionResult> = inspector.inspect(worldDirectory)

    fun protectImportedWorld(worldName: String, worldDirectory: File): Result<WorldWorkingCopyManager.Metadata> = runCatching {
        val inspection = inspector.inspect(worldDirectory).getOrThrow()
        val levelMetadata = BedrockLevelDatReader.read(File(worldDirectory, "level.dat")).getOrThrow()
        val adoptionTransactionId = "adopt-${java.util.UUID.randomUUID()}"
        val protected = workingCopies.protectImportedWorld(
            worldName = worldName,
            installedWorld = worldDirectory,
            inspection = inspection,
            importTransactionId = adoptionTransactionId,
        ).metadata
        require(
            ImportedWorldVerificationStore.markPending(
                serverRoot = serverRoot,
                worldName = worldName,
                metadata = levelMetadata,
                sourceWorldHash = protected.sourceWorldHash,
                transactionId = adoptionTransactionId,
            )
        ) {
            "Unable to create imported-world verification marker"
        }
        require(
            ImportedWorldVerificationStore.agreesWithProtection(
                serverRoot = serverRoot,
                worldName = worldName,
                protection = protected,
            )
        ) {
            "Adopted world marker does not agree with protection metadata"
        }
        protected
    }

    fun prepareProtectedLaunch(
        worldName: String,
        engineId: String,
        engineVersionId: String,
        engineArtifact: File,
        expectedArtifactSha256: String?,
        protocolVersions: List<Int>,
    ): Result<PreparedLaunch> = runCatching {
        val world = File(serverRoot, "worlds/$worldName")
        require(workingCopies.hasProtectedOriginal(worldName)) {
            "Imported world has no verified immutable original. Re-import the untouched world before starting compatibility testing."
        }

        val adapter = EngineWorldAdapterRegistry.forEngine(engineId)
            ?: error("No world adapter is registered for engine '$engineId'")

        // Resume only a fingerprinted clean provisional copy for the exact same engine tuple.
        // Every failed, changed or cross-engine attempt resets from the immutable original.
        val markerState = ImportedWorldVerificationStore.state(serverRoot, worldName)
        val preparedCopy = adapter.prepareWorkingCopy(
            manager = workingCopies,
            worldName = worldName,
            engineVersionId = engineVersionId,
            allowResume = markerState == ImportedWorldVerificationState.PROVISIONAL ||
                markerState == ImportedWorldVerificationState.VERIFIED,
        )
        val prepared = preparedCopy.metadata
        if (!preparedCopy.resumedExistingCopy) {
            adapter.validatePreparedWorld(world, prepared.sourceWorldHash).getOrThrow()
        }
        val inspection = inspector.inspect(world).getOrThrow()
        require(inspection.report.sourceWorldHash == prepared.preparedWorldHash) {
            "Prepared world changed during read-only inspection"
        }
        val originalContext = EngineArtifactContext(
            engineVersionId = engineVersionId,
            artifact = engineArtifact,
            expectedSha256 = expectedArtifactSha256,
            protocolVersions = protocolVersions,
        )

        var launchArtifact = engineArtifact
        var repairs = emptyList<NukkitMotArtifactRepairer.RuntimeMappingRepair>()
        val launchInspection: EngineArtifactInspection

        if (engineId == "nukkit-mot") {
            val cacheRoot = File(serverRoot, ".minehost/derived-artifacts")
            val repairResult = NukkitMotArtifactRepairer.prepare(
                context = originalContext,
                report = inspection.report,
                cacheRoot = cacheRoot,
            )
            val preparedArtifact = repairResult.getOrElse { error ->
                throw IllegalStateException("Nukkit-MOT artifact repair failed: ${error.message}", error)
            }
            launchArtifact = preparedArtifact.launchArtifact
            repairs = preparedArtifact.worldRepairs
            launchInspection = adapter.inspectEngineArtifact(
                originalContext.copy(
                    artifact = preparedArtifact.launchArtifact,
                    expectedSha256 = preparedArtifact.launchArtifactSha256,
                    provenance = preparedArtifact.provenance,
                )
            )
        } else {
            launchInspection = adapter.inspectEngineArtifact(originalContext)
        }

        val artifactInspection = launchInspection
        require(
            artifactInspection.status != CompatibilityStatus.CORRUPT &&
                artifactInspection.status != CompatibilityStatus.UNSUPPORTED
        ) {
            "Engine artifact cannot be used safely: ${artifactInspection.errors.joinToString()}"
        }

        val compatibility = adapter.checkWorldCompatibility(
            inspection.report,
            engineVersionId,
            artifactInspection,
        )
        require(compatibility.overall != CompatibilityStatus.CORRUPT) {
            "World inspection is corrupt: ${compatibility.reasons.joinToString()}"
        }
        require(compatibility.overall != CompatibilityStatus.UNSUPPORTED) {
            "World is incompatible with $engineId: ${compatibility.reasons.joinToString()}"
        }

        val diagnostics = RuntimeMappingDiagnostics.analyze(
            engineId = engineId,
            engineVersionId = engineVersionId,
            launchArtifact = launchArtifact,
            inspection = artifactInspection,
            report = inspection.report,
            repairs = repairs,
        )

        PreparedLaunch(
            inspection = inspection,
            compatibility = compatibility,
            protection = prepared,
            resumedWorkingCopy = preparedCopy.resumedExistingCopy,
            adapter = adapter,
            artifactInspection = artifactInspection,
            launchArtifact = launchArtifact,
            repairs = repairs,
            diagnostics = diagnostics,
        )
    }


    fun finalizeProtectedVerification(
        worldName: String,
        engineVersionId: String,
        evidence: ProtectedWorldVerificationEvidence,
    ): Result<WorldWorkingCopyManager.Metadata> = runCatching {
        val failures = evidence.failures()
        require(failures.isEmpty()) {
            "WORLD_VERIFIED rejected: ${failures.joinToString(" | ")}"
        }
        require(ImportedWorldVerificationStore.state(serverRoot, worldName) ==
            ImportedWorldVerificationState.PROVISIONAL) {
            "Imported world is not in the PROVISIONAL verification state"
        }
        val compatible = workingCopies.markCompatible(worldName, evidence)
        if (!ImportedWorldVerificationStore.markVerified(serverRoot, worldName, engineVersionId)) {
            workingCopies.markIncompatible(
                worldName,
                listOf("Verification marker could not be committed after evidence validation"),
            )
            error("Unable to commit WORLD_VERIFIED marker")
        }
        compatible
    }

    fun restoreAfterRuntimeFailure(
        worldName: String,
        engineId: String,
        errors: List<String>,
    ): Result<WorldWorkingCopyManager.Metadata> = runCatching {
        val adapter = EngineWorldAdapterRegistry.forEngine(engineId)
            ?: error("No world adapter is registered for engine '$engineId'")
        adapter.restoreAfterFailure(workingCopies, worldName, errors)
    }
}
