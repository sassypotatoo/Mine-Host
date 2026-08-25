package com.example.server.termux

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class TermuxPackage(
    val name: String,
    val version: String,
    val depends: List<String>,
    val preDepends: List<String>,
    val filename: String,
    val size: Long,
    val sha256: String,
    val provides: List<String> = emptyList(),
)

class TermuxPackageResolver(private val client: OkHttpClient) {
    private val repoBase = "https://packages-cf.termux.dev/apt/termux-main"
    private val packagesUrl = "$repoBase/dists/stable/main/binary-aarch64/Packages"

    suspend fun resolveDependencies(
        rootPackage: String,
    ): List<TermuxPackage> = withContext(Dispatchers.IO) {
        resolveDependenciesFromIndex(
            rootPackage = rootPackage,
            allPackages = fetchAllPackages(),
        )
    }

    internal fun resolveDependenciesFromIndex(
        rootPackage: String,
        allPackages: Map<String, TermuxPackage>,
    ): List<TermuxPackage> {
        val providers = mutableMapOf<String, MutableList<String>>()
        allPackages.values.forEach { pkg ->
            pkg.provides.forEach { providedName ->
                providers.getOrPut(providedName) { mutableListOf() }
                    .add(pkg.name)
            }
        }

        val resolved = linkedMapOf<String, TermuxPackage>()
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.add(rootPackage)

        while (queue.isNotEmpty()) {
            val packageName = queue.removeFirst()
            if (!visited.add(packageName)) continue

            val pkg = allPackages[packageName]
                ?: throw IOException(
                    "Package $packageName not found in repository index"
                )
            resolved[packageName] = pkg

            (pkg.preDepends + pkg.depends).forEach { dependency ->
                val alternatives = dependency
                    .split('|')
                    .map(::normalizeDependencyName)
                    .filter(String::isNotBlank)

                val selected = alternatives.firstNotNullOfOrNull { name ->
                    when {
                        allPackages.containsKey(name) -> name
                        providers[name].orEmpty().distinct().size == 1 ->
                            providers.getValue(name).distinct().single()
                        else -> null
                    }
                }

                if (selected == null) {
                    val ambiguous = alternatives.firstOrNull { name ->
                        providers[name].orEmpty().distinct().size > 1
                    }
                    if (ambiguous != null) {
                        throw IOException(
                            "Dependency '$dependency' for ${pkg.name} is " +
                                "ambiguous; providers are " +
                                providers.getValue(ambiguous)
                                    .distinct()
                                    .sorted()
                                    .joinToString()
                        )
                    }
                    throw IOException(
                        "Dependency '$dependency' required by ${pkg.name} " +
                            "was not found in the repository index"
                    )
                }

                if (selected !in visited) {
                    queue.add(selected)
                }
            }
        }

        return resolved.values.toList()
    }

    private suspend fun fetchAllPackages(): Map<String, TermuxPackage> {
        val request = Request.Builder().url(packagesUrl).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to fetch runtime package index: ${response.code}"
                )
            }

            val body = response.body
                ?: throw IOException("Empty runtime package index")
            val contentLength = body.contentLength()
            if (contentLength > MAX_PACKAGES_INDEX_BYTES) {
                throw IOException("Runtime package index is unexpectedly large")
            }

            val content = body.string()
            if (content.length > MAX_PACKAGES_INDEX_BYTES) {
                throw IOException("Runtime package index exceeded the safe limit")
            }
            parsePackages(content)
        }
    }

    internal fun parsePackages(content: String): Map<String, TermuxPackage> {
        val map = mutableMapOf<String, TermuxPackage>()
        val blocks = content.split("\n\n")
        for (block in blocks) {
            if (block.isBlank()) continue
            val fields = mutableMapOf<String, String>()
            var currentField: String? = null
            for (line in block.lines()) {
                if (line.isBlank()) continue
                if (line.startsWith(" ")) {
                    if (currentField != null) {
                        fields[currentField] = fields[currentField] + "\n" + line.trim()
                    }
                } else if (line.contains(":")) {
                    val parts = line.split(":", limit = 2)
                    currentField = parts[0].trim()
                    fields[currentField] = parts[1].trim()
                }
            }

            val name = fields["Package"] ?: continue
            val architecture = fields["Architecture"] ?: ""
            if (architecture != "aarch64" && architecture != "all") continue

            val version = fields["Version"] ?: ""
            val dependsStr = fields["Depends"] ?: ""
            val preDependsStr = fields["Pre-Depends"] ?: ""
            val providesStr = fields["Provides"] ?: ""

            val depends = parseRelationshipList(dependsStr)
            val preDepends = parseRelationshipList(preDependsStr)
            val provides = parseRelationshipList(providesStr)
                .map(::normalizeDependencyName)
                .filter(String::isNotBlank)
            
            val filename = fields["Filename"]
                ?.trim()
                ?.takeIf(::isSafePackagePath)
                ?: continue
            val size = fields["Size"]?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: continue
            val sha256 = fields["SHA256"]
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.matches(SHA256_PATTERN) }
                ?: continue
            if (version.isBlank()) continue

            map[name] = TermuxPackage(
                name,
                version,
                depends,
                preDepends,
                filename,
                size,
                sha256,
                provides,
            )
        }
        return map
    }
    
    private fun parseRelationshipList(value: String): List<String> =
        value
            .takeIf(String::isNotBlank)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()

    private fun normalizeDependencyName(value: String): String = value
        .substringBefore('(')
        .trim()
        .removeSuffix(":any")
        .removeSuffix(":native")
        .trim()

    fun getFullDownloadUrl(pkg: TermuxPackage): String {
        require(isSafePackagePath(pkg.filename)) {
            "Unsafe runtime package path: ${pkg.filename}"
        }
        return "$repoBase/${pkg.filename}"
    }

    private fun isSafePackagePath(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.startsWith('/') || value.contains("..")) return false
        if (value.contains("://") || value.contains('\\')) return false
        return value.startsWith("pool/") && value.endsWith(".deb")
    }

    companion object {
        private const val MAX_PACKAGES_INDEX_BYTES = 25L * 1024L * 1024L
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
