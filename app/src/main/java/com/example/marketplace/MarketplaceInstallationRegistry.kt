package com.example.marketplace

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-server installed-content ledger. The ledger never replaces filesystem or
 * startup-log verification; it records evidence after those checks succeed.
 */
class MarketplaceInstallationRegistry(private val serverRoot: File) {
    enum class Status { INSTALLED, PENDING_VERIFICATION, FAILED, REMOVED }

    data class Record(
        val itemId: String,
        val category: MarketplaceCategory,
        val version: String,
        val status: Status,
        val installedAt: Long,
        val files: List<String>,
        val sha256: String?,
        val message: String,
    )

    private val file = File(serverRoot, ".minehost/marketplace-installed.json")

    @Synchronized
    fun all(): List<Record> = read().values.sortedBy { it.itemId }

    @Synchronized
    fun installedIds(): Set<String> = read().values
        .filter { it.status == Status.INSTALLED || it.status == Status.PENDING_VERIFICATION }
        .mapTo(linkedSetOf()) { it.itemId }

    @Synchronized
    fun get(itemId: String): Record? = read()[itemId]

    @Synchronized
    fun put(record: Record) {
        val current = read().toMutableMap()
        current[record.itemId] = record
        write(current)
    }


    @Synchronized
    fun markByInstalledFile(installedFile: File, status: Status, message: String): Boolean {
        val root = serverRoot.canonicalFile
        val canonical = installedFile.canonicalFile
        require(canonical.path.startsWith(root.path + File.separator)) { "Installed file belongs to another server" }
        val relative = canonical.relativeTo(root).invariantSeparatorsPath
        val current = read().toMutableMap()
        val match = current.values.firstOrNull { relative in it.files } ?: return false
        current[match.itemId] = match.copy(status = status, installedAt = System.currentTimeMillis(), message = message)
        write(current)
        return true
    }

    @Synchronized
    fun markRemoved(itemId: String, message: String) {
        val previous = get(itemId) ?: return
        put(previous.copy(status = Status.REMOVED, installedAt = System.currentTimeMillis(), message = message))
    }

    fun relativeVerifiedFiles(files: Collection<File>): List<String> {
        val root = serverRoot.canonicalFile
        return files.map { candidate ->
            val canonical = candidate.canonicalFile
            require(canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)) {
                "Marketplace record attempted to reference a file outside this server"
            }
            canonical.relativeTo(root).invariantSeparatorsPath
        }.distinct().sorted()
    }

    fun verify(record: Record): Result<Unit> = runCatching {
        require(record.status != Status.REMOVED) { "Marketplace item is marked removed" }
        require(record.files.isNotEmpty() || record.category == MarketplaceCategory.SEED) {
            "Installed item has no recorded files"
        }
        record.files.forEach { relative ->
            require(!relative.startsWith("/") && relative.split('/').none { it == ".." }) { "Unsafe recorded path" }
            require(File(serverRoot, relative).exists()) { "Installed marketplace file is missing: $relative" }
        }
        if (record.files.size == 1 && !record.sha256.isNullOrBlank()) {
            val target = File(serverRoot, record.files.single())
            require(target.isFile && sha256(target).equals(record.sha256, true)) {
                "Installed marketplace checksum does not match the ledger"
            }
        }
    }

    private fun read(): Map<String, Record> {
        if (!file.isFile) return emptyMap()
        return runCatching {
        val root = JSONObject(file.readText())
        require(root.optInt("schema_version", -1) == SCHEMA_VERSION) { "Unsupported marketplace ledger schema" }
        val output = linkedMapOf<String, Record>()
        val array = root.optJSONArray("items") ?: JSONArray()
        for (index in 0 until array.length()) {
            val json = array.getJSONObject(index)
            val itemId = json.getString("item_id")
            output[itemId] = Record(
                itemId = itemId,
                category = MarketplaceCategory.valueOf(json.getString("category")),
                version = json.getString("version"),
                status = Status.valueOf(json.getString("status")),
                installedAt = json.getLong("installed_at"),
                files = json.optJSONArray("files")?.strings().orEmpty(),
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() && it != "null" },
                message = json.optString("message"),
            )
        }
            output
        }.getOrElse { error ->
            throw IllegalStateException("Marketplace installation ledger is corrupted: ${error.message}", error)
        }
    }

    private fun write(records: Map<String, Record>) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        val items = JSONArray()
        records.values.sortedBy { it.itemId }.forEach { record ->
            items.put(
                JSONObject()
                    .put("item_id", record.itemId)
                    .put("category", record.category.name)
                    .put("version", record.version)
                    .put("status", record.status.name)
                    .put("installed_at", record.installedAt)
                    .put("files", JSONArray(record.files))
                    .put("sha256", record.sha256 ?: JSONObject.NULL)
                    .put("message", record.message),
            )
        }
        part.writeText(
            JSONObject()
                .put("schema_version", SCHEMA_VERSION)
                .put("server_uuid", serverRoot.name)
                .put("updated_at", System.currentTimeMillis())
                .put("items", items)
                .toString(2),
        )
        if (file.exists()) require(file.delete()) { "Unable to replace marketplace ledger" }
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = true)
            require(part.delete()) { "Unable to remove marketplace ledger staging file" }
        }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { index ->
        optString(index).trim().takeIf(String::isNotBlank)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { private const val SCHEMA_VERSION = 1 }
}
