package com.example.ai

import org.json.JSONArray
import org.json.JSONObject

enum class AiProviderMode { NOT_CONFIGURED, USER_GEMINI_KEY, AUTHENTICATED_PROXY }

data class AiServerContext(
    val serverUuid: String?,
    val engineId: String,
    val engineVersion: String?,
    val minecraftVersion: String?,
    val javaVersion: Int?,
    val allocatedMemoryMb: Int,
    val deviceMemoryMb: Long?,
    val processMemoryMb: Long?,
    val cpuPercent: Double?,
    val tps: Double?,
    val playerCount: Int,
    val installedPlugins: List<String>,
    val currentSettings: Map<String, String>,
    val recentLogs: List<String>,
    val recentCrashes: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("server_uuid", serverUuid ?: JSONObject.NULL)
        .put("engine_id", engineId)
        .put("engine_version", engineVersion ?: JSONObject.NULL)
        .put("minecraft_version", minecraftVersion ?: JSONObject.NULL)
        .put("java_version", javaVersion ?: JSONObject.NULL)
        .put("allocated_memory_mb", allocatedMemoryMb)
        .put("device_memory_mb", deviceMemoryMb ?: JSONObject.NULL)
        .put("process_memory_mb", processMemoryMb ?: JSONObject.NULL)
        .put("cpu_percent", cpuPercent ?: JSONObject.NULL)
        .put("tps", tps ?: JSONObject.NULL)
        .put("player_count", playerCount)
        .put("installed_plugins", JSONArray(installedPlugins))
        .put("current_settings", JSONObject(currentSettings))
        .put("recent_logs", JSONArray(recentLogs))
        .put("recent_crashes", JSONArray(recentCrashes))
}

data class AiReply(
    val text: String,
    val providerMode: AiProviderMode,
    val model: String?,
    val contextWasRedacted: Boolean = true,
)

enum class AiChangeType { SET_SERVER_PROPERTY, DISABLE_PLUGIN, SET_MEMORY_MB }

data class AiProposedChange(
    val type: AiChangeType,
    val key: String,
    val oldValue: String?,
    val newValue: String,
    val reason: String,
)

data class AiChangePlan(
    val planId: String,
    val serverUuid: String,
    val diagnosis: String,
    val evidence: List<String>,
    val changes: List<AiProposedChange>,
    val risks: List<String>,
    val restartRequired: Boolean,
    val rollbackGuidance: String,
) {
    companion object {
        fun fromJson(json: JSONObject): AiChangePlan {
            val changesJson = json.optJSONArray("changes") ?: JSONArray()
            val evidenceJson = json.optJSONArray("evidence") ?: JSONArray()
            val risksJson = json.optJSONArray("risks") ?: JSONArray()
            fun strings(array: JSONArray): List<String> = (0 until array.length())
                .mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
            return AiChangePlan(
                planId = json.getString("plan_id"),
                serverUuid = json.getString("server_uuid"),
                diagnosis = json.getString("diagnosis"),
                evidence = strings(evidenceJson),
                changes = (0 until changesJson.length()).map { index ->
                    val change = changesJson.getJSONObject(index)
                    AiProposedChange(
                        type = AiChangeType.valueOf(change.getString("type").uppercase()),
                        key = change.getString("key"),
                        oldValue = change.optString("old_value").takeIf { it.isNotBlank() && it != "null" },
                        newValue = change.getString("new_value"),
                        reason = change.getString("reason"),
                    )
                },
                risks = strings(risksJson),
                restartRequired = json.optBoolean("restart_required", true),
                rollbackGuidance = json.getString("rollback_guidance"),
            )
        }
    }
}
