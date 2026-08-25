package com.example.server.engine

import android.content.Context
import com.example.server.ServerStatus
import com.example.server.version.EngineVersion
import com.example.server.version.InstalledEngineVersionRepository
import com.example.server.version.NukkitMotProtocolMetadata
import com.example.server.version.ResolvedEngineVersion
import com.example.server.version.RuntimeProtocolExpectation
import com.example.world.BedrockLevelMetadata
import java.io.File

class NukkitMOTEngine(
    context: Context,
    serverDir: File,
    engineVersion: EngineVersion,
    selectedBedrockVersion: String,
    port: Int,
    profileId: String,
    runtimeSessionId: String,
    serverConfig: EngineServerConfig = EngineServerConfig(worldSeed = 0L, worldSeedKnown = true),
    onLog: (String) -> Unit = {},
    onStatusChange: (ServerStatus) -> Unit = {},
    explicitResolvedIdentity: ResolvedEngineVersion? = null,
) : BedrockJavaEngineBase(
    context,
    serverDir,
    engineVersion,
    selectedBedrockVersion,
    port,
    profileId,
    runtimeSessionId,
    serverConfig,
    onLog,
    onStatusChange
) {
    init {
        if (explicitResolvedIdentity != null) {
            resolvedIdentity = explicitResolvedIdentity
        }
    }

    override fun getEngineId(): String = "nukkit-mot"

    override fun additionalJvmArguments(): List<String> {
        val log4jFile = NukkitMOTLog4jManager.ensureConfig(context, serverDir)
        return listOf("-Dlog4j.configurationFile=${log4jFile.absolutePath}")
    }

    override fun runtimeProtocolExpectation(): RuntimeProtocolExpectation {
        val identity = resolvedIdentity
            ?: InstalledEngineVersionRepository.read(serverDir)?.resolvedIdentity

        if (identity == null ||
            identity.effectiveVersionId == identity.catalogBaseId ||
            identity.effectiveVersionId == engineVersion.id
        ) {
            return super.runtimeProtocolExpectation()
        }

        val jarName = engineVersion.jarFileName.ifBlank { spec.jarName }
        val serverJar = File(serverDir, jarName)

        val discoveredProtocols = runCatching {
            NukkitMotProtocolMetadata.discoverSupportedProtocols(serverJar)
        }.getOrElse { error ->
            throw IllegalStateException(
                "Resolved Nukkit-MOT build #${identity.resolvedBuildNumber} protocol discovery failed: ${error.message}",
                error
            )
        }

        if (discoveredProtocols.isEmpty()) {
            throw IllegalStateException(
                "Resolved Nukkit-MOT build #${identity.resolvedBuildNumber} contains no discoverable " +
                    "runtime_block_states_<protocol>.dat metadata; refusing unverified protocol startup."
            )
        }

        return RuntimeProtocolExpectation(
            expectedProtocols = discoveredProtocols,
            selectedBedrockVersion = "AUTO",
            source = "resolved-artifact:${identity.effectiveVersionId}",
        )
    }

    fun runtimeProtocolExpectationForTest(): RuntimeProtocolExpectation =
        runtimeProtocolExpectation()

    override fun onValidateBedrockWorld(
        worldName: String,
        ownership: com.example.world.WorldLaunchOwnership
    ): String? {
        if (ownership != com.example.world.WorldLaunchOwnership.IMPORTED_PROTECTED_VALID &&
            ownership != com.example.world.WorldLaunchOwnership.UNTRACKED_REQUIRES_ADOPTION) {
            return null
        }

        // World Adapter check in base class will catch this, but we keep the logic structure.
        return null
    }

    private companion object {
        const val LEGACY_JAVA25_BUILD_ID = "nukkit-mot:59"
    }
}
