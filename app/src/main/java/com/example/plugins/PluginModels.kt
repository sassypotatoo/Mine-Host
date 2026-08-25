package com.example.plugins

import org.json.JSONArray
import org.json.JSONObject

enum class CompatibilityState { VERIFIED, COMPATIBLE, EXPERIMENTAL, UNSUPPORTED, UNKNOWN }

data class PluginMetadata(
    val name: String,
    val version: String,
    val mainClass: String,
    val apiVersions: List<String>,
    val dependencies: List<String>,
    val softDependencies: List<String>,
    val loadBefore: List<String>,
    val authors: List<String>,
    val rawDescriptorName: String
)

data class EngineCompatibility(
    val engineId: String,
    val state: CompatibilityState,
    val engineVersionRange: String? = null,
    val minecraftVersionRange: String? = null,
    val javaRange: String? = null,
    val reason: String? = null
)

data class MarketplacePlugin(
    val pluginId: String,
    val name: String,
    val author: String,
    val license: String,
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val fileSize: Long,
    val dependencies: List<String>,
    val conflicts: List<String>,
    val restartRequired: Boolean,
    val compatibility: Map<String, EngineCompatibility>,
    val description: String = ""
) {
    companion object {
        fun fromJson(json: JSONObject): MarketplacePlugin {
            fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else
                (0 until array.length()).map { array.getString(it) }
            val compatibility = linkedMapOf<String, EngineCompatibility>()
            val objectCompat = json.optJSONObject("compatibility") ?: JSONObject()
            objectCompat.keys().forEach { engineId ->
                val item = objectCompat.getJSONObject(engineId)
                val state = runCatching { CompatibilityState.valueOf(item.optString("state", "UNKNOWN").uppercase()) }
                    .getOrDefault(CompatibilityState.UNKNOWN)
                compatibility[engineId] = EngineCompatibility(
                    engineId = engineId,
                    state = state,
                    engineVersionRange = item.optString("engineVersionRange").takeIf(String::isNotBlank),
                    minecraftVersionRange = item.optString("minecraftVersionRange").takeIf(String::isNotBlank),
                    javaRange = item.optString("javaRange").takeIf(String::isNotBlank),
                    reason = item.optString("reason").takeIf(String::isNotBlank)
                )
            }
            return MarketplacePlugin(
                pluginId = json.getString("pluginId"),
                name = json.getString("name"),
                author = json.getString("author"),
                license = json.getString("license"),
                version = json.getString("version"),
                downloadUrl = json.getString("downloadUrl"),
                sha256 = json.getString("sha256").lowercase(),
                fileSize = json.getLong("fileSize"),
                dependencies = strings(json.optJSONArray("dependencies")),
                conflicts = strings(json.optJSONArray("conflicts")),
                restartRequired = json.optBoolean("restartRequired", true),
                compatibility = compatibility,
                description = json.optString("description")
            )
        }
    }
}

data class PluginInstallRequest(
    val serverUuid: String,
    val engineId: String,
    val engineVersionId: String,
    val minecraftVersion: String,
    val javaVersion: Int,
    val serverDirectory: java.io.File,
    val plugin: MarketplacePlugin
)

sealed class PluginInstallResult {
    data class Success(val message: String, val installedFile: java.io.File, val restartRequired: Boolean) : PluginInstallResult()
    data class PendingVerification(val message: String, val installedFile: java.io.File) : PluginInstallResult()
    data class Failure(val message: String, val rollbackAvailable: Boolean = false) : PluginInstallResult()
}


data class LocalPluginInstallRequest(
    val serverUuid: String,
    val engineId: String,
    val engineVersionId: String,
    val minecraftVersion: String,
    val javaVersion: Int,
    val serverDirectory: java.io.File,
    val sourceJar: java.io.File,
)
