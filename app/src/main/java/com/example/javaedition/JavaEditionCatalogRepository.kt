package com.example.javaedition

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Verified local catalog cache. MineHost does not guess mutable Java server URLs.
 * A release is installable only when HTTPS URL, exact size and SHA-256 are present.
 */
class JavaEditionCatalogRepository(context: Context) {
    private val catalogDir = File(context.filesDir, "java-edition-catalog").apply { mkdirs() }
    private val catalogFile = File(catalogDir, "catalog.json")

    fun load(): List<JavaEditionRelease> = runCatching {
        if (!catalogFile.isFile) return emptyList()
        val root = JSONObject(catalogFile.readText())
        val array = root.getJSONArray("releases")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val release = JavaEditionRelease(
                    releaseId = item.getString("release_id"),
                    platform = JavaServerPlatform.valueOf(item.getString("platform")),
                    displayVersion = item.getString("display_version"),
                    minecraftVersion = item.getString("minecraft_version"),
                    channel = JavaReleaseChannel.valueOf(item.getString("channel")),
                    requiredJavaMajor = item.getInt("required_java_major"),
                    downloadUrl = item.getString("download_url"),
                    sha256 = item.getString("sha256"),
                    fileSize = item.getLong("file_size"),
                    releaseDate = item.optString("release_date").takeIf(String::isNotBlank),
                    recommended = item.optBoolean("recommended", false),
                    deprecated = item.optBoolean("deprecated", false),
                )
                require(release.downloadUrl.startsWith("https://"))
                require(release.sha256.matches(Regex("[a-fA-F0-9]{64}")))
                require(release.fileSize > 0)
                add(release)
            }
        }
    }.getOrElse { emptyList() }

    fun replaceVerifiedCatalog(payload: File, expectedSha256: String): Result<Unit> = runCatching {
        require(payload.isFile && payload.length() in 2..(5L * 1024 * 1024)) { "Java catalog file is invalid" }
        require(sha256(payload).equals(expectedSha256, true)) { "Java catalog checksum mismatch" }
        JSONObject(payload.readText()).getJSONArray("releases")
        val part = File(catalogDir, "catalog.json.part")
        payload.copyTo(part, overwrite = true)
        if (catalogFile.exists() && !catalogFile.delete()) error("Unable to replace Java catalog")
        if (!part.renameTo(catalogFile)) {
            part.copyTo(catalogFile, overwrite = true)
            part.delete()
        }
        load().also { require(it.isNotEmpty()) { "Java catalog contains no verified releases" } }
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
