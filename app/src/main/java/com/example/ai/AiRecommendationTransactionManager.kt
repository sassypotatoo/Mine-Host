package com.example.ai

import com.example.backup.BackupManagerV2
import com.example.data.OperationResult
import com.example.data.ServerProfile
import java.io.File
import java.util.Properties
import org.json.JSONArray
import org.json.JSONObject

/** Applies only a small audited allowlist after an explicit confirmation token. */
class AiRecommendationTransactionManager(
    private val profile: ServerProfile,
    private val backupMetadata: BackupManagerV2.Metadata,
) {
    data class Baseline(
        val cpuPercent: Double?,
        val processMemoryMb: Long?,
        val tps: Double?,
        val capturedAt: Long = System.currentTimeMillis(),
    )

    data class Applied(
        val planId: String,
        val serverUuid: String,
        val message: String,
        val restartRequired: Boolean,
        val baseline: Baseline,
        val historyFile: File,
    )

    fun apply(
        plan: AiChangePlan,
        confirmationPlanId: String,
        baseline: Baseline,
    ): Result<Applied> {
        val root = runCatching { File(profile.serverDirectory).canonicalFile }
            .getOrElse { return Result.failure(it) }
        val transaction = File(root, ".minehost/ai-transactions/${safe(plan.planId)}-${System.currentTimeMillis()}")
        val originalProperties = File(transaction, "server.properties.original")
        val propertiesFile = File(root, "server.properties")
        val propertiesExisted = propertiesFile.isFile
        val pluginRenames = mutableListOf<Pair<File, File>>()

        return try {
            require(plan.serverUuid == profile.id) { "AI plan belongs to another server UUID" }
            require(confirmationPlanId == plan.planId) { "Explicit confirmation token does not match this plan" }
            require(plan.changes.isNotEmpty()) { "AI plan contains no changes" }
            require(plan.changes.size <= 20) { "AI plan contains too many changes" }
            require(transaction.mkdirs()) { "Unable to create AI transaction directory" }
            if (propertiesExisted) propertiesFile.copyTo(originalProperties, overwrite = false)

            val backup = BackupManagerV2(root).create(backupMetadata, "before-ai-plan")
            require(backup.success) { "AI changes cancelled: ${backup.message}" }

            val properties = Properties()
            if (propertiesFile.isFile) propertiesFile.inputStream().use(properties::load)
            val historyDir = File(root, ".minehost/config-history").apply { mkdirs() }
            val history = File(historyDir, "ai-${System.currentTimeMillis()}-${safe(plan.planId)}.json")
            val historyPart = File(transaction, "history.json.part")
            val applied = JSONArray()

            plan.changes.forEach { change ->
                when (change.type) {
                    AiChangeType.SET_SERVER_PROPERTY -> {
                        require(change.key in ALLOWED_PROPERTIES) { "AI cannot change server property ${change.key}" }
                        validateProperty(change.key, change.newValue)
                        val current = properties.getProperty(change.key)
                        if (change.oldValue != null) require(current == change.oldValue) {
                            "Property ${change.key} changed after the recommendation was generated"
                        }
                        properties[change.key] = change.newValue
                        applied.put(JSONObject().put("type", change.type.name).put("key", change.key)
                            .put("old", current ?: JSONObject.NULL).put("new", change.newValue).put("reason", change.reason))
                    }
                    AiChangeType.DISABLE_PLUGIN -> {
                        val pluginName = File(change.key).name
                        require(pluginName == change.key && pluginName.endsWith(".jar", true)) { "Unsafe plugin filename" }
                        val pluginDir = File(root, "plugins").canonicalFile
                        val source = File(pluginDir, pluginName).canonicalFile
                        require(source.parentFile == pluginDir && source.isFile) { "Plugin is not installed: $pluginName" }
                        val disabled = File(pluginDir, "$pluginName.disabled").canonicalFile
                        require(disabled.parentFile == pluginDir && !disabled.exists()) { "Disabled plugin target already exists" }
                        require(source.renameTo(disabled)) { "Unable to disable $pluginName" }
                        pluginRenames += source to disabled
                        applied.put(JSONObject().put("type", change.type.name).put("key", pluginName)
                            .put("old", source.name).put("new", disabled.name).put("reason", change.reason))
                    }
                    AiChangeType.SET_MEMORY_MB -> {
                        throw IllegalArgumentException("Memory allocation changes require a profile transaction and are not auto-applied yet")
                    }
                }
            }

            writeProperties(propertiesFile, properties)
            historyPart.writeText(
                JSONObject()
                    .put("plan_id", plan.planId)
                    .put("server_uuid", plan.serverUuid)
                    .put("applied_at", System.currentTimeMillis())
                    .put("backup_message", backup.message)
                    .put("baseline", JSONObject()
                        .put("cpu_percent", baseline.cpuPercent ?: JSONObject.NULL)
                        .put("process_memory_mb", baseline.processMemoryMb ?: JSONObject.NULL)
                        .put("tps", baseline.tps ?: JSONObject.NULL))
                    .put("changes", applied)
                    .toString(2),
            )
            historyPart.copyTo(history, overwrite = false)
            transaction.deleteRecursively()
            Result.success(
                Applied(plan.planId, plan.serverUuid, "AI plan applied after verified backup", plan.restartRequired, baseline, history),
            )
        } catch (error: Throwable) {
            pluginRenames.asReversed().forEach { (source, disabled) ->
                runCatching {
                    if (disabled.isFile) {
                        source.delete()
                        require(disabled.renameTo(source)) { "Unable to roll back plugin ${source.name}" }
                    }
                }
            }
            runCatching {
                if (propertiesExisted && originalProperties.isFile) {
                    originalProperties.copyTo(propertiesFile, overwrite = true)
                } else if (!propertiesExisted) {
                    propertiesFile.delete()
                }
                Unit
            }
            transaction.deleteRecursively()
            Result.failure(error)
        }
    }

    fun compare(applied: Applied, after: Baseline): JSONObject = JSONObject()
        .put("plan_id", applied.planId)
        .put("baseline", metricJson(applied.baseline))
        .put("after", metricJson(after))
        .put("cpu_delta", delta(after.cpuPercent, applied.baseline.cpuPercent))
        .put("memory_delta_mb", delta(after.processMemoryMb?.toDouble(), applied.baseline.processMemoryMb?.toDouble()))
        .put("tps_delta", delta(after.tps, applied.baseline.tps))
        .put("note", "Positive TPS delta and negative CPU/memory deltas may indicate improvement; player load must be comparable.")

    private fun metricJson(value: Baseline): JSONObject = JSONObject()
        .put("cpu_percent", value.cpuPercent ?: JSONObject.NULL)
        .put("process_memory_mb", value.processMemoryMb ?: JSONObject.NULL)
        .put("tps", value.tps ?: JSONObject.NULL)
        .put("captured_at", value.capturedAt)

    private fun delta(after: Double?, before: Double?): Any =
        if (after == null || before == null) JSONObject.NULL else after - before

    private fun validateProperty(key: String, value: String) {
        require(value.length <= 128 && value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Unsafe property value" }
        when (key) {
            "view-distance", "max-players", "spawn-protection", "tick-distance" ->
                require(value.toIntOrNull() in 0..128) { "Invalid numeric value for $key" }
            "allow-flight", "white-list", "force-gamemode", "pvp", "spawn-animals", "spawn-mobs" ->
                require(value in setOf("true", "false")) { "Invalid boolean for $key" }
            "difficulty" -> require(value.lowercase() in setOf("peaceful", "easy", "normal", "hard", "0", "1", "2", "3"))
            "gamemode" -> require(value.lowercase() in setOf("survival", "creative", "adventure", "spectator", "0", "1", "2", "3"))
        }
    }

    private fun writeProperties(file: File, properties: Properties) {
        val part = File(file.parentFile, file.name + ".ai.part")
        part.outputStream().use { properties.store(it, "MineHost AI-confirmed settings") }
        if (file.exists()) require(file.delete()) { "Unable to replace server.properties" }
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = true)
            require(part.delete()) { "Unable to remove property staging file" }
        }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)

    companion object {
        private val ALLOWED_PROPERTIES = setOf(
            "view-distance", "tick-distance", "max-players", "spawn-protection",
            "allow-flight", "white-list", "force-gamemode", "pvp",
            "spawn-animals", "spawn-mobs", "difficulty", "gamemode",
        )
    }
}
