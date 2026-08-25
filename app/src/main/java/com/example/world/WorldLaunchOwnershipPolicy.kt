package com.example.world

import java.io.File

enum class WorldLaunchOwnership {
    ENGINE_WILL_GENERATE,
    ENGINE_GENERATED_VALID,
    IMPORTED_PROTECTED_VALID,
    UNTRACKED_REQUIRES_ADOPTION,
    BROKEN_ONE_SIDED_REGISTRATION,
    INCOMPLETE_IMPORT_TRANSACTION,
    CORRUPT_PROTECTED_ORIGINAL,
    EXTERNAL_ADOPTION_REQUIRED,
    UNKNOWN,
}

data class WorldLaunchOwnershipDecision(
    val ownership: WorldLaunchOwnership,
    val reasons: List<String>,
)

object WorldLaunchOwnershipPolicy {
    fun classify(
        serverRoot: File,
        worldName: String,
    ): WorldLaunchOwnershipDecision {
        val normalized = worldName.trim()

        if (
            normalized.isBlank() ||
            normalized.length > 128 ||
            '/' in normalized ||
            '\\' in normalized ||
            normalized == "." ||
            normalized == ".."
        ) {
            return WorldLaunchOwnershipDecision(
                WorldLaunchOwnership.UNKNOWN,
                listOf("Unsafe world name"),
            )
        }

        val worldsRoot = File(serverRoot, "worlds").canonicalFile
        val world = File(worldsRoot, normalized).canonicalFile

        if (world.parentFile != worldsRoot) {
            return WorldLaunchOwnershipDecision(
                WorldLaunchOwnership.UNKNOWN,
                listOf("World path escaped the server worlds directory"),
            )
        }

        val levelDat = File(world, "level.dat")
        val db = File(world, "db")

        if (WorldImportJournalManager.hasIncompleteTransactionForWorld(serverRoot, normalized)) {
            return WorldLaunchOwnershipDecision(
                WorldLaunchOwnership.INCOMPLETE_IMPORT_TRANSACTION,
                listOf("Incomplete import transaction exists"),
            )
        }

        val verificationIdentity = ImportedWorldVerificationStore.identity(serverRoot, normalized)
        val manager = WorldWorkingCopyManager(serverRoot)
        val protection = manager.metadata(normalized)
        val hasMarker = ImportedWorldVerificationStore.state(serverRoot, normalized) != null
        val hasProtection = protection != null

        if (!world.exists() && !hasMarker && !hasProtection) {
            return WorldLaunchOwnershipDecision(
                WorldLaunchOwnership.ENGINE_WILL_GENERATE,
                emptyList(),
            )
        }

        if (hasMarker != hasProtection) {
            return WorldLaunchOwnershipDecision(
                WorldLaunchOwnership.BROKEN_ONE_SIDED_REGISTRATION,
                listOf("Imported marker and protection registration are one-sided"),
            )
        }

        if (hasMarker && protection != null) {
            if (
                verificationIdentity == null ||
                !ImportedWorldVerificationStore.agreesWithProtection(
                    serverRoot,
                    normalized,
                    protection,
                )
            ) {
                return WorldLaunchOwnershipDecision(
                    WorldLaunchOwnership.BROKEN_ONE_SIDED_REGISTRATION,
                    listOf("Imported marker and protection identity disagree"),
                )
            }

            val original = File(protection.originalWorldPath)
            val fingerprint = runCatching { WorldFileIntegrity.fingerprint(original) }.getOrNull()

            if (!original.isDirectory || fingerprint?.rootHash != protection.sourceWorldHash) {
                return WorldLaunchOwnershipDecision(
                    WorldLaunchOwnership.CORRUPT_PROTECTED_ORIGINAL,
                    listOf("Immutable original is missing or has a fingerprint mismatch"),
                )
            }

            return WorldLaunchOwnershipDecision(
                WorldLaunchOwnership.IMPORTED_PROTECTED_VALID,
                emptyList(),
            )
        }

        if (world.isDirectory && EngineGeneratedWorldStore.isRecognized(serverRoot, normalized, world)) {
            if (WorldGenerationMarkerManager.hasMarkerForWorld(serverRoot, normalized)) {
                return WorldLaunchOwnershipDecision(
                    WorldLaunchOwnership.ENGINE_GENERATED_VALID,
                    emptyList(),
                )
            } else {
                return WorldLaunchOwnershipDecision(
                    WorldLaunchOwnership.EXTERNAL_ADOPTION_REQUIRED,
                    listOf("World is recognized as engine-generated but is missing its generation identity marker"),
                )
            }
        }

        if (levelDat.isFile && db.isDirectory) {
            return WorldLaunchOwnershipDecision(
                WorldLaunchOwnership.EXTERNAL_ADOPTION_REQUIRED,
                listOf("Existing Bedrock world has no ownership records; manual adoption required"),
            )
        }

        return WorldLaunchOwnershipDecision(
            WorldLaunchOwnership.UNKNOWN,
            listOf("World ownership cannot be proven"),
        )
    }

    fun requiresProtectedLaunch(ownership: WorldLaunchOwnership): Boolean =
        ownership == WorldLaunchOwnership.IMPORTED_PROTECTED_VALID ||
        ownership == WorldLaunchOwnership.UNTRACKED_REQUIRES_ADOPTION ||
        ownership == WorldLaunchOwnership.EXTERNAL_ADOPTION_REQUIRED
}
