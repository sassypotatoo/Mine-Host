package com.example.marketplace

import com.example.plugins.CompatibilityState
import com.example.plugins.EngineCompatibility
import org.json.JSONArray
import org.json.JSONObject

enum class MarketplaceCategory {
    PLUGIN,
    BEHAVIOR_PACK,
    RESOURCE_PACK,
    WORLD_TEMPLATE,
    SEED,
    SERVER_TEMPLATE,
    JAVA_MOD,
}

data class MarketplacePreview(
    val url: String,
    val sha256: String?,
    val altText: String,
)

data class MarketplaceDependency(
    val itemId: String,
    val required: Boolean,
    val purpose: String?,
)

data class MarketplaceItem(
    val itemId: String,
    val category: MarketplaceCategory,
    val name: String,
    val author: String,
    val license: String,
    val description: String,
    val version: String,
    val downloadUrl: String?,
    val sha256: String?,
    val fileSize: Long?,
    val seedValue: String?,
    val worldType: String?,
    val compatibility: Map<String, EngineCompatibility>,
    val minecraftVersions: List<String>,
    val dependencies: List<MarketplaceDependency>,
    val conflicts: List<String>,
    val previewImages: List<MarketplacePreview>,
    val installationInstructions: String,
    val manifestPath: String?,
    val enabled: Boolean = true,
) {
    val isDownloadable: Boolean get() = category != MarketplaceCategory.SEED

    companion object {
        fun fromJson(json: JSONObject): MarketplaceItem {
            val category = MarketplaceCategory.valueOf(json.getString("category").uppercase())
            val compatibilityObject = json.optJSONObject("compatibility") ?: JSONObject()
            val compatibility = linkedMapOf<String, EngineCompatibility>()
            compatibilityObject.keys().forEach { engineId ->
                val value = compatibilityObject.getJSONObject(engineId)
                compatibility[engineId] = EngineCompatibility(
                    engineId = engineId,
                    state = runCatching {
                        CompatibilityState.valueOf(value.optString("state", "UNKNOWN").uppercase())
                    }.getOrDefault(CompatibilityState.UNKNOWN),
                    engineVersionRange = value.nullableString("engineVersionRange"),
                    minecraftVersionRange = value.nullableString("minecraftVersionRange"),
                    javaRange = value.nullableString("javaRange"),
                    reason = value.nullableString("reason"),
                )
            }
            return MarketplaceItem(
                itemId = json.getString("itemId"),
                category = category,
                name = json.getString("name"),
                author = json.getString("author"),
                license = json.getString("license"),
                description = json.optString("description"),
                version = json.optString("version", "1"),
                downloadUrl = json.nullableString("downloadUrl"),
                sha256 = json.nullableString("sha256")?.lowercase(),
                fileSize = if (json.has("fileSize") && !json.isNull("fileSize")) json.getLong("fileSize") else null,
                seedValue = json.nullableString("seedValue"),
                worldType = json.nullableString("worldType"),
                compatibility = compatibility,
                minecraftVersions = json.optJSONArray("minecraftVersions").strings(),
                dependencies = json.optJSONArray("dependencies").objects().map { dependency ->
                    MarketplaceDependency(
                        itemId = dependency.getString("itemId"),
                        required = dependency.optBoolean("required", true),
                        purpose = dependency.nullableString("purpose"),
                    )
                },
                conflicts = json.optJSONArray("conflicts").strings(),
                previewImages = json.optJSONArray("previewImages").objects().map { preview ->
                    MarketplacePreview(
                        url = preview.getString("url"),
                        sha256 = preview.nullableString("sha256")?.lowercase(),
                        altText = preview.optString("altText", "${json.getString("name")} preview"),
                    )
                },
                installationInstructions = json.optString("installationInstructions"),
                manifestPath = json.nullableString("manifestPath"),
                enabled = json.optBoolean("enabled", true),
            )
        }
    }
}

data class MarketplaceCatalog(
    val schemaVersion: Int,
    val revision: String,
    val generatedAt: String?,
    val items: List<MarketplaceItem>,
)

private fun JSONObject.nullableString(key: String): String? =
    optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotBlank) }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)
