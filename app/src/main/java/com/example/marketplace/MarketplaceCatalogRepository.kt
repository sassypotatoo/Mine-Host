package com.example.marketplace

import android.content.Context
import com.example.plugins.PluginAdapterRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Catalog loader with a cached last-known-good copy. A remote catalog is accepted
 * only when its externally supplied SHA-256 matches and every item validates.
 */
class MarketplaceCatalogRepository(private val context: Context) {
    private val cacheDir = File(context.filesDir, "marketplace-catalog").apply { mkdirs() }
    private val cachedCatalog = File(cacheDir, "catalog.json")

    fun load(): Result<MarketplaceCatalog> {
        if (cachedCatalog.isFile) {
            val cached = runCatching { parse(cachedCatalog.readText()) }
            if (cached.isSuccess) return cached
            val corrupt = File(cacheDir, "catalog.corrupt.${System.currentTimeMillis()}.json")
            runCatching { cachedCatalog.renameTo(corrupt) }
        }
        return runCatching {
            val bundled = context.assets.open("marketplace/content-catalog.json")
                .bufferedReader()
                .use { it.readText() }
            parse(bundled)
        }
    }

    fun replaceVerifiedCatalog(source: File, expectedSha256: String): Result<MarketplaceCatalog> = runCatching {
        require(source.isFile && source.length() in 2..(10L * 1024 * 1024)) { "Marketplace catalog file is invalid" }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Catalog SHA-256 is invalid" }
        require(sha256(source).equals(expectedSha256, true)) { "Marketplace catalog checksum mismatch" }
        val parsed = parse(source.readText())
        val part = File(cacheDir, "catalog.json.part")
        val backup = File(cacheDir, "catalog.json.bak")
        part.delete()
        FileOutputStream(part).use { output ->
            source.inputStream().use { it.copyTo(output) }
            output.fd.sync()
        }
        require(sha256(part).equals(expectedSha256, true)) { "Staged marketplace catalog checksum mismatch" }

        backup.delete()
        if (cachedCatalog.isFile && !cachedCatalog.renameTo(backup)) {
            error("Unable to preserve the last-known-good marketplace catalog")
        }
        try {
            if (!part.renameTo(cachedCatalog)) {
                FileOutputStream(cachedCatalog).use { output ->
                    part.inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
                part.delete()
            }
            val committed = parse(cachedCatalog.readText())
            require(sha256(cachedCatalog).equals(expectedSha256, true)) {
                "Committed marketplace catalog checksum mismatch"
            }
            backup.delete()
            committed
        } catch (failure: Throwable) {
            cachedCatalog.delete()
            if (backup.isFile) backup.renameTo(cachedCatalog)
            throw failure
        } finally {
            part.delete()
        }
    }

    fun parse(raw: String): MarketplaceCatalog {
        val root = JSONObject(raw)
        val schema = root.getInt("schemaVersion")
        require(schema == 2) { "Unsupported marketplace schema $schema" }
        val array = root.getJSONArray("items")
        val items = (0 until array.length()).map { MarketplaceItem.fromJson(array.getJSONObject(it)) }
        require(items.map { it.itemId }.distinct().size == items.size) { "Duplicate marketplace item IDs" }
        val ids = items.mapTo(hashSetOf()) { it.itemId }
        items.forEach { validateItem(it, ids) }
        validateDependencyGraph(items)
        return MarketplaceCatalog(
            schemaVersion = schema,
            revision = root.getString("revision"),
            generatedAt = root.optString("generatedAt").takeIf(String::isNotBlank),
            items = items,
        )
    }

    private fun validateItem(item: MarketplaceItem, allIds: Set<String>) {
        require(item.itemId.matches(Regex("[a-z0-9][a-z0-9._-]{2,127}"))) { "Invalid item ID ${item.itemId}" }
        require(item.name.isNotBlank() && item.author.isNotBlank() && item.license.isNotBlank()) {
            "Marketplace item ${item.itemId} is missing identity or licence metadata"
        }
        if (item.isDownloadable) {
            require(item.downloadUrl?.startsWith("https://") == true) { "${item.itemId} requires an HTTPS download URL" }
            require(item.sha256?.matches(Regex("[a-f0-9]{64}")) == true) { "${item.itemId} requires SHA-256" }
            require((item.fileSize ?: 0L) > 0L) { "${item.itemId} requires a positive file size" }
        } else {
            require(!item.seedValue.isNullOrBlank()) { "Seed ${item.itemId} has no seed value" }
        }
        item.previewImages.forEach { preview ->
            require(preview.url.startsWith("https://")) { "Preview URL for ${item.itemId} is insecure" }
            preview.sha256?.let { require(it.matches(Regex("[a-f0-9]{64}"))) }
        }
        item.conflicts.forEach { conflict ->
            require(conflict in allIds) { "${item.itemId} references missing conflict $conflict" }
            require(conflict != item.itemId) { "${item.itemId} conflicts with itself" }
        }
        item.dependencies.forEach { dependency ->
            require(dependency.itemId in allIds) { "${item.itemId} references missing dependency ${dependency.itemId}" }
            require(dependency.itemId != item.itemId) { "${item.itemId} depends on itself" }
        }
        if (item.dependencies.map { it.itemId }.distinct().size != item.dependencies.size) {
            error("${item.itemId} contains duplicate dependencies")
        }
        if (item.category != MarketplaceCategory.JAVA_MOD) {
            PluginAdapterRegistry.all().forEach { adapter ->
                require(item.compatibility.containsKey(adapter.engineId)) {
                    "${item.itemId} is missing compatibility for ${adapter.engineId}"
                }
            }
        }
        if (item.category == MarketplaceCategory.JAVA_MOD) {
            require(!item.enabled) { "Java mods must remain disabled until Java Edition support is enabled" }
        }
    }


    private fun validateDependencyGraph(items: List<MarketplaceItem>) {
        val byId = items.associateBy { it.itemId }
        val visiting = hashSetOf<String>()
        val visited = hashSetOf<String>()

        fun visit(itemId: String, path: List<String>) {
            if (itemId in visited) return
            require(itemId !in visiting) {
                "Marketplace dependency cycle detected: ${(path + itemId).joinToString(" -> ")}"
            }
            visiting += itemId
            byId.getValue(itemId).dependencies.forEach { dependency ->
                visit(dependency.itemId, path + itemId)
            }
            visiting -= itemId
            visited += itemId
        }

        items.forEach { visit(it.itemId, emptyList()) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
