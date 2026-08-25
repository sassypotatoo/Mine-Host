package com.example.performance

import com.example.data.OperationResult
import java.io.File
import java.util.Properties
import java.util.UUID

enum class OptimizationPreset { SAFE, BALANCED, PERFORMANCE, CUSTOM }

data class ProposedSettingChange(
    val key: String,
    val oldValue: String?,
    val newValue: String,
    val reason: String,
    val engineId: String
)

data class OptimizationPlan(
    val serverUuid: String,
    val engineId: String,
    val preset: OptimizationPreset,
    val changes: List<ProposedSettingChange>,
    val warnings: List<String>
)

class ServerOptimizationManager(
    private val serverRoot: File,
    private val createBackup: (String) -> OperationResult
) {
    fun propose(
        serverUuid: String,
        engineId: String,
        preset: OptimizationPreset,
        customValues: Map<String, String> = emptyMap()
    ): OptimizationPlan {
        val properties = loadProperties()
        val desired = when (preset) {
            OptimizationPreset.SAFE -> mapOf(
                "view-distance" to "6",
                "max-players" to properties.getProperty("max-players", "10"),
                "spawn-protection" to properties.getProperty("spawn-protection", "16")
            )
            OptimizationPreset.BALANCED -> mapOf(
                "view-distance" to "8",
                "max-players" to properties.getProperty("max-players", "10")
            )
            OptimizationPreset.PERFORMANCE -> mapOf(
                "view-distance" to "4",
                "max-players" to properties.getProperty("max-players", "10"),
                "spawn-protection" to "8"
            )
            OptimizationPreset.CUSTOM -> customValues
        }.toMutableMap()

        // Engine-specific keys only. No universal magic boost switch.
        when (engineId) {
            "bedrock_power_nukkit_x" -> when (preset) {
                OptimizationPreset.SAFE -> desired["chunk-sending.per-tick"] = "4"
                OptimizationPreset.BALANCED -> desired["chunk-sending.per-tick"] = "8"
                OptimizationPreset.PERFORMANCE -> desired["chunk-sending.per-tick"] = "2"
                else -> Unit
            }
            "bedrock_power_nukkit" -> when (preset) {
                OptimizationPreset.SAFE -> desired["chunk-sending-per-tick"] = "4"
                OptimizationPreset.BALANCED -> desired["chunk-sending-per-tick"] = "8"
                OptimizationPreset.PERFORMANCE -> desired["chunk-sending-per-tick"] = "2"
                else -> Unit
            }
            "bedrock_nukkit", "nukkit-mot" -> when (preset) {
                OptimizationPreset.SAFE -> desired["chunk-sending-per-tick"] = "4"
                OptimizationPreset.BALANCED -> desired["chunk-sending-per-tick"] = "8"
                OptimizationPreset.PERFORMANCE -> desired["chunk-sending-per-tick"] = "2"
                else -> Unit
            }
        }

        val changes = desired.entries.mapNotNull { (key, value) ->
            val old = properties.getProperty(key)
            if (old == value) null else ProposedSettingChange(
                key = key,
                oldValue = old,
                newValue = value,
                reason = reasonFor(key, preset),
                engineId = engineId
            )
        }
        return OptimizationPlan(
            serverUuid = serverUuid,
            engineId = engineId,
            preset = preset,
            changes = changes,
            warnings = buildList {
                if (preset == OptimizationPreset.PERFORMANCE) add("Performance preset reduces simulation visibility and may affect gameplay")
                if (changes.isEmpty()) add("No configuration changes are required")
            }
        )
    }

    fun apply(plan: OptimizationPlan): OperationResult {
        if (plan.changes.isEmpty()) return OperationResult(true, "No optimization changes required")
        val backup = createBackup("before-optimization")
        if (!backup.success) return OperationResult(false, "Optimization cancelled: ${backup.message}")
        val properties = loadProperties()
        plan.changes.forEach { properties[it.key] = it.newValue }

        val file = File(serverRoot, "server.properties")
        val part = File(serverRoot, "server.properties.${UUID.randomUUID()}.part")
        val historyDir = File(serverRoot, ".minehost/config-history").apply { mkdirs() }
        val oldCopy = File(historyDir, "server.properties-${System.currentTimeMillis()}.before-optimization")
        return runCatching {
            if (file.isFile) file.copyTo(oldCopy, overwrite = false)
            part.outputStream().use { properties.store(it, "MineHost ${plan.preset.name.lowercase()} optimization") }
            val verify = Properties().also { part.inputStream().use(it::load) }
            plan.changes.forEach { change -> check(verify.getProperty(change.key) == change.newValue) { "Failed to verify ${change.key}" } }
            if (file.exists() && !file.delete()) error("Unable to replace server.properties")
            if (!part.renameTo(file)) {
                part.copyTo(file, overwrite = true)
                part.delete()
            }
            OperationResult(true, "${plan.preset.name.lowercase().replaceFirstChar(Char::uppercase)} preset applied; restart required")
        }.getOrElse { error ->
            part.delete()
            if (oldCopy.isFile) oldCopy.copyTo(file, overwrite = true)
            OperationResult(false, error.message ?: "Optimization failed and configuration was rolled back")
        }
    }

    private fun loadProperties(): Properties = Properties().also { props ->
        val file = File(serverRoot, "server.properties")
        if (file.isFile) runCatching { file.inputStream().use(props::load) }
    }

    private fun reasonFor(key: String, preset: OptimizationPreset): String = when (key) {
        "view-distance" -> "Controls loaded chunks; lower values reduce CPU and memory pressure"
        "chunk-sending-per-tick", "chunk-sending.per-tick" -> "Limits per-tick network and chunk serialization work for this engine"
        "spawn-protection" -> "Reduces protected-area processing footprint"
        else -> "Requested ${preset.name.lowercase()} configuration"
    }
}
