package com.example.world

import android.util.Log
import com.example.data.StorageResult
import com.example.server.updates.AtomicJsonFileStore
import com.example.server.version.EngineVersion
import com.example.server.version.ResolvedEngineVersion
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class GenerationIdentityInput(
    val levelName: String,
    val worldSeed: Long,
    val worldSeedKnown: Boolean,
    val engineId: String,
    val engineVersionId: String,
    val generatorId: String,
    val generatorRevision: String
)

data class WorldGenerationMarker(
    val schemaVersion: Int = 1,
    val worldName: String,
    val seed: Long,
    val seedKnown: Boolean,
    val engineId: String,
    val engineVersionId: String,
    val generatorId: String,
    val generatorRevision: String,
    val createdAt: Long = System.currentTimeMillis()
)

sealed interface MarkerReadResult {
    data object Missing : MarkerReadResult
    data class Valid(val marker: WorldGenerationMarker) : MarkerReadResult
    data class Corrupt(val reason: String) : MarkerReadResult
    data class IoFailure(val reason: String) : MarkerReadResult
}

data class EngineGeneratorIdentity(
    val id: String,
    val revision: String
)

object EngineGeneratorIdentityResolver {
    fun resolve(
        engineId: String,
        engineVersion: EngineVersion,
        levelType: String = "DEFAULT",
        resolvedIdentity: ResolvedEngineVersion? = null
    ): EngineGeneratorIdentity {
        val catId = engineVersion.generatorId
        val catRev = engineVersion.generatorRevision
        if (!catId.isNullOrBlank() && !catRev.isNullOrBlank()) {
            return EngineGeneratorIdentity(id = catId, revision = catRev)
        }
        val baseType = if (levelType.equals("FLAT", ignoreCase = true)) "flat" else "normal"
        val genId = when (engineId) {
            "bedrock_power_nukkit_x", "PowerNukkitX" -> "powernukkitx/$baseType"
            "bedrock_power_nukkit", "PowerNukkit" -> "powernukkit/$baseType"
            "bedrock_nukkit", "PM1E" -> "pm1e/$baseType"
            "bedrock_cloudburst_nukkit", "Cloudburst" -> "cloudburst/$baseType"
            "nukkit-mot", "Nukkit-MOT" -> "nukkit-mot/$baseType"
            else -> "$engineId/$baseType"
        }
        val rawRev = resolvedIdentity?.resolvedBuildNumber?.toString()
            ?: engineVersion.buildNumber
            ?: engineVersion.sha256
            ?: engineVersion.releaseDateEpochMillis?.toString()
            ?: engineVersion.id
        
        val rev = if (engineId == "nukkit-mot" || engineId == "Nukkit-MOT") {
            if (rawRev.all { it.isDigit() }) "jenkins-build:$rawRev" else rawRev
        } else {
            rawRev
        }
        
        return EngineGeneratorIdentity(id = genId, revision = rev)
    }
}

object WorldGenerationMarkerManager {
    private const val TAG = "WorldGenMarker"
    private const val INDEX_FILENAME = ".minehost/world-generation/index-v1.json"
    private const val LEGACY_MARKER_FILENAME = ".minehost/world-generation-v1.json"

    private fun getMarkerIndexStore(serverDir: File) = AtomicJsonFileStore(File(serverDir, INDEX_FILENAME))

    private fun getMarkerId(serverDir: File, worldName: String, createIfMissing: Boolean = false): String? {
        val store = getMarkerIndexStore(serverDir)
        val jsonStr = when (val res = store.loadRaw()) {
            is StorageResult.Success -> res.value
            is StorageResult.Recovered -> res.value
            else -> "{}"
        }
        val obj = JSONObject(jsonStr)
        if (obj.has(worldName)) {
            return obj.getString(worldName)
        }
        if (!createIfMissing) return null
        
        val newId = java.util.UUID.randomUUID().toString()
        obj.put(worldName, newId)
        store.save(obj.toString(2))
        return newId
    }

    fun validateWorldName(serverDir: File, worldName: String): Result<String> {
        val normalized = worldName.trim()
        if (normalized.isBlank()) return Result.failure(IllegalArgumentException("World name cannot be blank"))
        if (normalized.length > 128) return Result.failure(IllegalArgumentException("World name exceeds 128 characters"))
        if (normalized.contains("\u0000")) return Result.failure(IllegalArgumentException("World name cannot contain NUL"))
        if (normalized.contains("/") || normalized.contains("\\")) return Result.failure(IllegalArgumentException("World name cannot contain path separators"))
        if (normalized == "." || normalized == "..") return Result.failure(IllegalArgumentException("World name cannot be relative path navigation"))
        if (normalized.endsWith(".")) return Result.failure(IllegalArgumentException("World name cannot have trailing dot"))
        
        // Block unsafe characters
        val unsafeChars = charArrayOf('\n', '\r', '\t')
        for (c in unsafeChars) {
            if (normalized.contains(c)) return Result.failure(IllegalArgumentException("World name contains unsafe whitespace or control character"))
        }
        for (i in 0 until normalized.length) {
            val c = normalized[i]
            if (Character.isISOControl(c)) {
                return Result.failure(IllegalArgumentException("World name contains ISO control characters"))
            }
        }

        return runCatching {
            val worldsBaseDir = File(serverDir, "worlds").canonicalFile
            val targetDir = File(worldsBaseDir, normalized).canonicalFile
            if (targetDir.parentFile != worldsBaseDir) {
                throw IllegalArgumentException("World path must be a direct child of worlds directory")
            }
            normalized
        }
    }

    fun getMarkerFile(serverDir: File, worldName: String, createIfMissing: Boolean = false): Result<File> {
        val validation = validateWorldName(serverDir, worldName)
        if (validation.isFailure) return Result.failure(validation.exceptionOrNull()!!)
        val normalized = validation.getOrThrow()
        
        // 1. Try index-v1.json
        val markerId = getMarkerId(serverDir, normalized, createIfMissing) ?: return Result.failure(NoSuchElementException("No marker ID assigned to world '$normalized'"))
        return Result.success(File(serverDir, ".minehost/world-generation/$markerId.marker.json"))
    }

    private fun encodeLegacyWorldName(worldName: String): String {
        val bytes = worldName.toByteArray(Charsets.UTF_8)
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "$b64.marker.json"
    }

    fun readMarkerResult(serverDir: File, worldName: String): MarkerReadResult {
        val nameValidation = validateWorldName(serverDir, worldName)
        if (nameValidation.isFailure) {
            return MarkerReadResult.Corrupt("Invalid world name: ${nameValidation.exceptionOrNull()?.message}")
        }
        val normalized = nameValidation.getOrThrow()

        val markerFileRes = getMarkerFile(serverDir, normalized, createIfMissing = false)
        if (markerFileRes.isFailure) {
            // Check Base64 legacy location (it doesn't use the index)
            val legacyB64File = File(serverDir, ".minehost/world-generation/${encodeLegacyWorldName(normalized)}")
            if (legacyB64File.isFile) {
                val legacyRes = parseMarkerFile(legacyB64File)
                if (legacyRes is MarkerReadResult.Valid && legacyRes.marker.worldName == normalized) {
                    val m = legacyRes.marker
                    val written = writeMarker(
                        serverDir = serverDir,
                        worldName = m.worldName,
                        seed = m.seed,
                        seedKnown = m.seedKnown,
                        engineId = m.engineId,
                        engineVersionId = m.engineVersionId,
                        generatorId = m.generatorId,
                        generatorRevision = m.generatorRevision,
                        createdAt = m.createdAt
                    )
                    if (written) {
                        legacyB64File.delete()
                        val newFileRes = getMarkerFile(serverDir, normalized, createIfMissing = false)
                        return if (newFileRes.isSuccess) parseMarkerFile(newFileRes.getOrThrow()) else MarkerReadResult.Missing
                    }
                }
            }
            
            // Legacy single-file location check
            val legacyFile = File(serverDir, LEGACY_MARKER_FILENAME)
            if (legacyFile.isFile) {
                val legacyRes = parseMarkerFile(legacyFile)
                if (legacyRes is MarkerReadResult.Valid && legacyRes.marker.worldName == normalized) {
                     val m = legacyRes.marker
                     val written = writeMarker(
                        serverDir = serverDir,
                        worldName = m.worldName,
                        seed = m.seed,
                        seedKnown = m.seedKnown,
                        engineId = m.engineId,
                        engineVersionId = m.engineVersionId,
                        generatorId = m.generatorId,
                        generatorRevision = m.generatorRevision,
                        createdAt = m.createdAt
                    )
                    if (written) {
                        legacyFile.delete()
                        val newFileRes = getMarkerFile(serverDir, normalized, createIfMissing = false)
                        return if (newFileRes.isSuccess) parseMarkerFile(newFileRes.getOrThrow()) else MarkerReadResult.Missing
                    }
                }
            }

            return MarkerReadResult.Missing
        }
        val file = markerFileRes.getOrThrow()

        if (!file.isFile) {
            return MarkerReadResult.Missing
        }

        return parseMarkerFile(file)
    }

    fun readMarker(serverDir: File, worldName: String): WorldGenerationMarker? {
        return when (val res = readMarkerResult(serverDir, worldName)) {
            is MarkerReadResult.Valid -> res.marker
            else -> null
        }
    }

    fun hasMarkerForWorld(serverDir: File, worldName: String): Boolean {
        return readMarker(serverDir, worldName) != null
    }

    private fun parseMarkerFile(file: File): MarkerReadResult {
        val store = AtomicJsonFileStore(file)
        val jsonStr = when (val result = store.loadRaw()) {
            is StorageResult.Success -> result.value
            is StorageResult.Recovered -> result.value
            is StorageResult.Missing -> return MarkerReadResult.Missing
            is StorageResult.Corrupt -> return MarkerReadResult.Corrupt("Marker file corrupt: ${result.warning}")
            is StorageResult.Failure -> return MarkerReadResult.IoFailure(result.message)
        }
        return try {
            val obj = JSONObject(jsonStr)
            val version = obj.optInt("schemaVersion", -1)
            if (version != 1) {
                return MarkerReadResult.Corrupt("Unsupported marker schema version $version")
            }
            if (!obj.has("worldName") || !obj.has("seed") || !obj.has("seedKnown") || !obj.has("engineId") ||
                !obj.has("engineVersionId") || !obj.has("generatorId") || !obj.has("generatorRevision") || !obj.has("createdAt")
            ) {
                return MarkerReadResult.Corrupt("World marker missing required identity fields")
            }
            val marker = WorldGenerationMarker(
                schemaVersion = version,
                worldName = obj.getString("worldName"),
                seed = obj.getLong("seed"),
                seedKnown = obj.getBoolean("seedKnown"),
                engineId = obj.getString("engineId"),
                engineVersionId = obj.getString("engineVersionId"),
                generatorId = obj.getString("generatorId"),
                generatorRevision = obj.getString("generatorRevision"),
                createdAt = obj.getLong("createdAt")
            )
            MarkerReadResult.Valid(marker)
        } catch (ex: Exception) {
            MarkerReadResult.Corrupt("Failed to parse world marker JSON: ${ex.message}")
        }
    }

    fun writeMarker(
        serverDir: File,
        worldName: String,
        seed: Long,
        seedKnown: Boolean,
        engineId: String,
        engineVersionId: String,
        generatorId: String,
        generatorRevision: String,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean {
        val nameValidation = validateWorldName(serverDir, worldName)
        if (nameValidation.isFailure) return false
        val normalized = nameValidation.getOrThrow()

        val markerFileRes = getMarkerFile(serverDir, normalized, createIfMissing = true)
        if (markerFileRes.isFailure) return false
        val file = markerFileRes.getOrThrow()
        file.parentFile?.mkdirs()
        val marker = WorldGenerationMarker(
            worldName = normalized,
            seed = seed,
            seedKnown = seedKnown,
            engineId = engineId,
            engineVersionId = engineVersionId,
            generatorId = generatorId,
            generatorRevision = generatorRevision,
            createdAt = createdAt
        )
        val json = JSONObject().apply {
            put("schemaVersion", marker.schemaVersion)
            put("worldName", marker.worldName)
            put("seed", marker.seed)
            put("seedKnown", marker.seedKnown)
            put("engineId", marker.engineId)
            put("engineVersionId", marker.engineVersionId)
            put("generatorId", marker.generatorId)
            put("generatorRevision", marker.generatorRevision)
            put("createdAt", marker.createdAt)
        }
        val store = AtomicJsonFileStore(file)
        return store.save(json.toString(2)) is StorageResult.Success
    }

    fun verifyExact(
        serverDir: File,
        worldName: String,
        seed: Long,
        seedKnown: Boolean,
        engineId: String,
        engineVersionId: String,
        generatorId: String,
        generatorRevision: String
    ): Boolean {
        val marker = readMarker(serverDir, worldName) ?: return false
        if (marker.worldName != worldName.trim()) return false
        if (marker.seedKnown != seedKnown) return false
        if (seedKnown && marker.seed != seed) return false
        if (marker.engineId != engineId) return false
        if (marker.engineVersionId != engineVersionId) return false
        if (marker.generatorId != generatorId) return false
        if (marker.generatorRevision != generatorRevision) return false
        return true
    }

    fun verifyOrBlock(
        serverDir: File,
        input: GenerationIdentityInput
    ): Result<Unit> {
        val nameVal = validateWorldName(serverDir, input.levelName)
        if (nameVal.isFailure) return nameVal.map { }
        val normalized = nameVal.getOrThrow()

        val worldDir = File(serverDir, "worlds/$normalized")
        val isImported = ImportedWorldVerificationStore.markerExists(serverDir, normalized)
        if (isImported) return Result.success(Unit)

        return when (val readRes = readMarkerResult(serverDir, normalized)) {
            is MarkerReadResult.Corrupt -> {
                Result.failure(IllegalStateException("World generation marker is corrupt: ${readRes.reason}"))
            }
            is MarkerReadResult.IoFailure -> {
                Result.failure(IllegalStateException("Failed to read world generation marker due to I/O error: ${readRes.reason}"))
            }
            is MarkerReadResult.Missing -> {
                val hasChunks = File(worldDir, "level.dat").isFile && File(worldDir, "db").isDirectory
                if (hasChunks) {
                    val reason = "This world contains generated terrain but its generation identity marker is missing. Restore the marker or regenerate the world safely."
                    Result.failure(IllegalStateException(reason))
                } else {
                    Result.success(Unit)
                }
            }
            is MarkerReadResult.Valid -> {
                val marker = readRes.marker
                val matched = marker.worldName == normalized &&
                    marker.seedKnown == input.worldSeedKnown &&
                    (!input.worldSeedKnown || marker.seed == input.worldSeed) &&
                    marker.engineId == input.engineId &&
                    marker.engineVersionId == input.engineVersionId &&
                    marker.generatorId == input.generatorId &&
                    marker.generatorRevision == input.generatorRevision

                if (!matched) {
                    val reason = "This world was generated using different seed or generator settings (expected engine=${input.engineId}, seed=${input.worldSeed}, gen=${input.generatorId}/${input.generatorRevision}; found engine=${marker.engineId}, seed=${marker.seed}, gen=${marker.generatorId}/${marker.generatorRevision}). Starting it could create severe terrain seams. Restore previous settings or regenerate the world safely."
                    Result.failure(IllegalStateException(reason))
                } else {
                    Result.success(Unit)
                }
            }
        }
    }

    fun verifyOrBlock(
        serverDir: File,
        input: GenerationIdentityInput,
        engineVersion: EngineVersion
    ): Result<Unit> = verifyOrBlock(serverDir, input)

    fun deleteMarker(serverDir: File, worldName: String): Boolean {
        val nameVal = validateWorldName(serverDir, worldName)
        if (nameVal.isFailure) return false
        val normalized = nameVal.getOrThrow()
        
        val markerFileRes = getMarkerFile(serverDir, normalized, createIfMissing = false)
        val file = markerFileRes.getOrNull()

        // 1. Remove from index
        val store = getMarkerIndexStore(serverDir)
        val jsonStr = when (val res = store.loadRaw()) {
            is StorageResult.Success -> res.value
            is StorageResult.Recovered -> res.value
            else -> "{}"
        }
        val obj = JSONObject(jsonStr)
        if (obj.has(normalized)) {
            obj.remove(normalized)
            store.save(obj.toString(2))
        }

        // 2. Legacy Base64 check
        val legacyB64File = File(serverDir, ".minehost/world-generation/${encodeLegacyWorldName(normalized)}")
        if (legacyB64File.exists()) legacyB64File.delete()

        val legacyFile = File(serverDir, LEGACY_MARKER_FILENAME)
        if (legacyFile.isFile) {
            val res = parseMarkerFile(legacyFile)
            if (res is MarkerReadResult.Valid && res.marker.worldName == normalized) {
                legacyFile.delete()
            }
        }
        return if (file != null && file.exists()) file.delete() else true
    }
}
