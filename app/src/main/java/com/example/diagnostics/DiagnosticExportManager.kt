package com.example.diagnostics

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.ai.AiContextRedactor
import com.example.data.CrashEntry
import com.example.performance.DeviceHealthSnapshot
import com.example.data.OperationResult
import com.example.data.PluginEntry
import com.example.data.RuntimeMetrics
import com.example.data.ServerProfile
import com.example.server.ServerStatus
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Creates a deliberately redacted support bundle. It never exports worlds, tokens, auth sessions or raw phone paths. */
class DiagnosticExportManager(private val context: Context) {
    data class Input(
        val profile: ServerProfile,
        val status: ServerStatus,
        val metrics: RuntimeMetrics,
        val plugins: List<PluginEntry>,
        val logs: List<String>,
        val crashes: List<CrashEntry>,
        val device: DeviceHealthSnapshot?,
    )

    fun export(resolver: ContentResolver, destination: Uri, input: Input): OperationResult {
        val transaction = File(context.cacheDir, "diagnostic-${UUID.randomUUID()}").apply { mkdirs() }
        val staged = File(transaction, "minehost-diagnostics.zip.part")
        return runCatching {
            val entries = linkedMapOf<String, ByteArray>()
            entries["diagnostic.json"] = buildManifest(input).toString(2).toByteArray()
            entries["logs-redacted.txt"] = AiContextRedactor.redactLines(input.logs, 500)
                .joinToString("\n", postfix = "\n").toByteArray()
            entries["crashes-redacted.json"] = JSONArray(
                input.crashes.takeLast(50).map { crash ->
                    JSONObject()
                        .put("title", crash.title.take(256))
                        .put("details", AiContextRedactor.redactLine(crash.details).take(8_192))
                        .put("severity", crash.severity.name)
                        .put("timestamp", crash.timestamp)
                },
            ).toString(2).toByteArray()
            redactedProperties(input.profile)?.let { entries["server-properties-redacted.txt"] = it }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(staged))).use { zip ->
                entries.forEach { (name, bytes) ->
                    val entry = ZipEntry(name).apply { time = System.currentTimeMillis() }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
                val checksums = JSONObject().apply {
                    entries.forEach { (name, bytes) -> put(name, sha256(bytes)) }
                }
                zip.putNextEntry(ZipEntry("checksums.json"))
                zip.write(checksums.toString(2).toByteArray())
                zip.closeEntry()
            }
            require(staged.isFile && staged.length() > 0) { "Diagnostic archive was not created" }
            resolver.openOutputStream(destination, "w")?.use { output ->
                staged.inputStream().use { inputStream -> inputStream.copyTo(output) }
            } ?: error("Unable to open the selected export destination")
            OperationResult(true, "Redacted diagnostic bundle exported")
        }.getOrElse { error ->
            OperationResult(false, error.message ?: "Diagnostic export failed")
        }.also { transaction.deleteRecursively() }
    }

    private fun buildManifest(input: Input): JSONObject = JSONObject()
        .put("format_version", 1)
        .put("created_at", System.currentTimeMillis())
        .put("server_uuid", input.profile.id)
        .put("server_name", input.profile.name.take(128))
        .put("engine_id", input.profile.engineId)
        .put("engine_version", input.profile.engineVersionId)
        .put("minecraft_version", input.profile.bedrockVersion)
        .put("allocated_memory_mb", input.profile.memoryMb)
        .put("port", input.profile.port)
        .put("status", input.status.name)
        .put("metrics", JSONObject()
            .put("cpu_percent", input.metrics.cpuPercent ?: JSONObject.NULL)
            .put("process_memory_bytes", input.metrics.ramBytes ?: JSONObject.NULL)
            .put("tps", input.metrics.tps ?: JSONObject.NULL)
            .put("players", input.metrics.playersOnline ?: JSONObject.NULL)
            .put("server_data_bytes", input.metrics.serverDataBytes)
            .put("available_storage_bytes", input.metrics.availableStorageBytes))
        .put("device", input.device?.let { device ->
            JSONObject()
                .put("battery_percent", device.batteryPercent ?: JSONObject.NULL)
                .put("charging", device.charging)
                .put("temperature_celsius", device.temperatureCelsius ?: JSONObject.NULL)
                .put("available_storage_bytes", device.availableStorageBytes)
                .put("low_storage", device.lowStorage)
                .put("overheating", device.overheating)
        } ?: JSONObject.NULL)
        .put("plugins", JSONArray(input.plugins.map { plugin ->
            JSONObject()
                .put("name", plugin.name.take(256))
                .put("file_name", plugin.fileName.take(256))
                .put("size_bytes", plugin.sizeBytes)
                .put("enabled", plugin.enabled)
        }))

    private fun redactedProperties(profile: ServerProfile): ByteArray? {
        val source = File(profile.serverDirectory, "server.properties")
        if (!source.isFile) return null
        val properties = Properties().apply { source.inputStream().use { input -> load(input) } }
        val sensitive = Regex("token|secret|password|key|auth|credential", RegexOption.IGNORE_CASE)
        val safe = Properties()
        properties.stringPropertyNames().sorted().forEach { key ->
            safe.setProperty(key, if (sensitive.containsMatchIn(key)) "[REDACTED]" else properties.getProperty(key).take(4_096))
        }
        return java.io.ByteArrayOutputStream().use { output ->
            safe.store(output, "MineHost redacted server properties")
            output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
