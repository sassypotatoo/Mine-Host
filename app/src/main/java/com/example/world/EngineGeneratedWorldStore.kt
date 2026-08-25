package com.example.world

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * Identifies worlds created by the selected server engine rather than imported from Bedrock.
 * A token is stored both outside and inside the world directory. Replacing the world folder
 * removes the inner token, so the replacement cannot silently inherit engine-generated trust.
 */
object EngineGeneratedWorldStore {
    private const val INNER_MARKER_NAME = ".minehost-engine-generated.properties"

    fun isRecognized(serverRoot: File, worldName: String, worldDirectory: File): Boolean = runCatching {
        val external = externalMarker(serverRoot, worldName)
        val internal = File(worldDirectory, INNER_MARKER_NAME)
        if (!external.isFile || !internal.isFile) return@runCatching false
        val externalValues = read(external)
        val internalValues = read(internal)
        val externalToken = externalValues.getProperty("token")?.trim().orEmpty()
        val internalToken = internalValues.getProperty("token")?.trim().orEmpty()
        externalToken.isNotBlank() &&
            externalToken == internalToken &&
            externalValues.getProperty("worldName") == worldName &&
            internalValues.getProperty("worldName") == worldName &&
            externalValues.getProperty("state") == "ENGINE_GENERATED" &&
            internalValues.getProperty("state") == "ENGINE_GENERATED"
    }.getOrDefault(false)

    fun markerExists(serverRoot: File, worldName: String, worldDirectory: File): Boolean =
        externalMarker(serverRoot, worldName).exists() || File(worldDirectory, INNER_MARKER_NAME).exists()

    fun mark(
        serverRoot: File,
        worldName: String,
        worldDirectory: File,
        engineId: String,
        engineVersionId: String,
    ): Boolean = runCatching {
        require(File(worldDirectory, "level.dat").isFile) { "Generated world has no level.dat" }
        require(File(worldDirectory, "db").isDirectory) { "Generated world has no LevelDB directory" }
        val token = UUID.randomUUID().toString()
        val now = System.currentTimeMillis().toString()
        val values = Properties().apply {
            setProperty("state", "ENGINE_GENERATED")
            setProperty("token", token)
            setProperty("worldName", worldName)
            setProperty("engineId", engineId)
            setProperty("engineVersionId", engineVersionId)
            setProperty("createdAt", now)
            setProperty("updatedAt", now)
        }
        val internal = File(worldDirectory, INNER_MARKER_NAME)
        val external = externalMarker(serverRoot, worldName)
        atomicWrite(internal, values, "MineHost engine-generated world identity")
        try {
            atomicWrite(external, values, "MineHost engine-generated world identity")
        } catch (error: Throwable) {
            internal.delete()
            throw error
        }
        require(isRecognized(serverRoot, worldName, worldDirectory)) {
            "Engine-generated world identity could not be verified"
        }
        true
    }.getOrDefault(false)

    /** Remove stale trust before an untracked world is adopted as an imported protected world. */
    fun clear(serverRoot: File, worldName: String, worldDirectory: File): Boolean {
        val external = externalMarker(serverRoot, worldName)
        val internal = File(worldDirectory, INNER_MARKER_NAME)
        val externalDeleted = !external.exists() || external.delete()
        val internalDeleted = !internal.exists() || internal.delete()
        return externalDeleted && internalDeleted
    }

    /**
     * Moves the external identity and rewrites the inner identity in the directory that currently
     * contains the moved world. This also works for transaction/rollback directories.
     */
    fun rename(
        serverRoot: File,
        oldWorldName: String,
        newWorldName: String,
        currentWorldDirectory: File,
    ): Boolean {
        if (oldWorldName == newWorldName) return isRecognized(serverRoot, oldWorldName, currentWorldDirectory)
        val oldExternal = externalMarker(serverRoot, oldWorldName)
        val internal = File(currentWorldDirectory, INNER_MARKER_NAME)
        require(oldExternal.isFile && internal.isFile) { "Engine-generated world identity is incomplete" }
        val oldExternalValues = read(oldExternal)
        val oldInternalValues = read(internal)
        require(oldExternalValues.getProperty("token") == oldInternalValues.getProperty("token")) {
            "Engine-generated world identity token mismatch"
        }
        val newExternal = externalMarker(serverRoot, newWorldName)
        require(!newExternal.exists()) { "Engine-generated identity already exists for '$newWorldName'" }
        val now = System.currentTimeMillis().toString()
        val updated = Properties().apply {
            putAll(oldExternalValues)
            setProperty("worldName", newWorldName)
            setProperty("updatedAt", now)
        }
        atomicWrite(internal, updated, "MineHost engine-generated world identity")
        try {
            atomicWrite(newExternal, updated, "MineHost engine-generated world identity")
            if (!oldExternal.delete()) error("Unable to remove old engine-generated identity")
        } catch (error: Throwable) {
            runCatching { atomicWrite(internal, oldInternalValues, "MineHost engine-generated world identity") }
            newExternal.delete()
            throw error
        }
        return isRecognized(serverRoot, newWorldName, currentWorldDirectory)
    }

    fun delete(serverRoot: File, worldName: String, worldDirectory: File): Boolean =
        clear(serverRoot, worldName, worldDirectory)

    fun removeInnerMarker(worldDirectory: File): Boolean {
        val internal = File(worldDirectory, INNER_MARKER_NAME)
        return !internal.exists() || internal.delete()
    }

    private fun externalMarker(serverRoot: File, worldName: String): File =
        File(serverRoot, ".minehost/engine-generated-worlds/${worldKey(worldName)}.properties")

    private fun worldKey(worldName: String): String = MessageDigest.getInstance("SHA-256")
        .digest(worldName.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun read(file: File): Properties = Properties().apply {
        file.inputStream().use(::load)
    }

    private fun atomicWrite(destination: File, values: Properties, comment: String) {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, ".${destination.name}.part-${UUID.randomUUID()}")
        try {
            part.outputStream().use { output ->
                values.store(output, comment)
                output.flush()
            }
            try {
                Files.move(
                    part.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Throwable) {
                Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            require(destination.isFile && destination.length() > 0L) { "Unable to commit world identity" }
        } finally {
            part.delete()
        }
    }
}
