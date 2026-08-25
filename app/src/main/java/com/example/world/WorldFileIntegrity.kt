package com.example.world

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

object WorldFileIntegrity {
    data class Fingerprint(
        val rootHash: String,
        val fileCount: Int,
        val totalBytes: Long,
        val fileHashes: Map<String, String>,
    )

    data class Difference(
        val added: Set<String>,
        val removed: Set<String>,
        val changed: Set<String>,
    ) {
        val isIdentical: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
    }

    fun fingerprint(root: File): Fingerprint {
        require(root.isDirectory) { "World directory is missing: ${root.absolutePath}" }
        val canonicalRoot = root.canonicalFile
        val files = collectFiles(canonicalRoot)
        val aggregate = MessageDigest.getInstance("SHA-256")
        val hashes = linkedMapOf<String, String>()
        var totalBytes = 0L

        for (file in files) {
            val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath
            val digest = sha256(file)
            hashes[relative] = digest
            totalBytes += file.length()
            aggregate.update(relative.toByteArray(Charsets.UTF_8))
            aggregate.update(0)
            aggregate.update(file.length().toString().toByteArray(Charsets.US_ASCII))
            aggregate.update(0)
            aggregate.update(hexToBytes(digest))
        }
        return Fingerprint(
            rootHash = aggregate.digest().toHex(),
            fileCount = files.size,
            totalBytes = totalBytes,
            fileHashes = hashes,
        )
    }

    fun compare(before: Fingerprint, after: Fingerprint): Difference {
        val beforeKeys = before.fileHashes.keys
        val afterKeys = after.fileHashes.keys
        val common = beforeKeys intersect afterKeys
        return Difference(
            added = afterKeys - beforeKeys,
            removed = beforeKeys - afterKeys,
            changed = common.filterTo(linkedSetOf()) { before.fileHashes[it] != after.fileHashes[it] },
        )
    }

    fun copyVerified(source: File, destination: File): Fingerprint {
        val sourceFingerprint = fingerprint(source)
        require(!destination.exists()) { "Destination already exists: ${destination.absolutePath}" }
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, ".${destination.name}.part-${UUID.randomUUID()}")
        try {
            copyTree(source, staging)
            val copied = fingerprint(staging)
            require(compare(sourceFingerprint, copied).isIdentical) { "Copied world fingerprint mismatch" }
            moveAtomic(staging, destination)
            val committed = fingerprint(destination)
            require(compare(sourceFingerprint, committed).isIdentical) { "Committed world fingerprint mismatch" }
            return committed
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun replaceWithVerifiedCopy(source: File, destination: File): Fingerprint {
        val expected = fingerprint(source)
        val parent = destination.parentFile ?: error("World destination has no parent")
        parent.mkdirs()
        val staging = File(parent, ".${destination.name}.prepare-${UUID.randomUUID()}")
        val rollback = File(parent, ".${destination.name}.rollback-${UUID.randomUUID()}")
        try {
            copyTree(source, staging)
            val staged = fingerprint(staging)
            require(compare(expected, staged).isIdentical) { "Prepared world fingerprint mismatch" }
            if (destination.exists()) moveAtomic(destination, rollback)
            try {
                moveAtomic(staging, destination)
            } catch (error: Throwable) {
                if (rollback.exists() && !destination.exists()) moveAtomic(rollback, destination)
                throw error
            }
            val committed = fingerprint(destination)
            require(compare(expected, committed).isIdentical) { "Replacement world fingerprint mismatch" }
            rollback.deleteRecursively()
            return committed
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (rollback.exists() && !destination.exists()) runCatching { moveAtomic(rollback, destination) }
            throw error
        }
    }

    fun sha256(file: File): String {
        require(file.isFile) { "File is missing: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun collectFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        fun walk(current: File) {
            require(!Files.isSymbolicLink(current.toPath())) { "Symbolic links are not allowed in imported worlds" }
            val canonical = current.canonicalFile
            require(canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)) {
                "World path escaped its root"
            }
            if (current.isDirectory) {
                current.listFiles().orEmpty().sortedBy { it.name }.forEach(::walk)
            } else if (current.isFile) {
                result += current
            }
        }
        walk(root)
        return result.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    }

    private fun copyTree(source: File, destination: File) {
        val canonicalSource = source.canonicalFile
        fun copy(current: File, target: File) {
            require(!Files.isSymbolicLink(current.toPath())) { "Symbolic links are not allowed in imported worlds" }
            val canonical = current.canonicalFile
            require(canonical.path == canonicalSource.path || canonical.path.startsWith(canonicalSource.path + File.separator)) {
                "World path escaped its root"
            }
            if (current.isDirectory) {
                require(target.mkdirs() || target.isDirectory) { "Unable to create ${target.absolutePath}" }
                current.listFiles().orEmpty().sortedBy { it.name }.forEach { child -> copy(child, File(target, child.name)) }
            } else {
                target.parentFile?.mkdirs()
                current.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        copy(source, destination)
    }

    private fun moveAtomic(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
