package com.example.plugins

import java.io.File
import java.util.jar.JarFile

interface PluginEngineAdapter {
    val engineId: String
    fun pluginDirectory(serverRoot: File): File = File(serverRoot, "plugins")
    fun configDirectory(serverRoot: File, pluginName: String): File = File(pluginDirectory(serverRoot), pluginName)
    fun inspectMetadata(jar: File): Result<PluginMetadata>
    fun validateMetadata(metadata: PluginMetadata): List<String>
    fun installedPluginFiles(serverRoot: File): List<File> = pluginDirectory(serverRoot).listFiles().orEmpty()
        .filter { it.isFile && (it.name.endsWith(".jar", true) || it.name.endsWith(".jar.disabled", true)) }
    fun verifyStartup(metadata: PluginMetadata, logs: List<String>): StartupVerification
}

data class StartupVerification(val loaded: Boolean, val failed: Boolean, val evidence: List<String>)

abstract class NukkitPluginAdapter : PluginEngineAdapter {
    override fun inspectMetadata(jar: File): Result<PluginMetadata> = PluginJarInspector.inspect(jar)

    override fun validateMetadata(metadata: PluginMetadata): List<String> = buildList {
        if (metadata.name.isBlank()) add("Plugin name is missing")
        if (metadata.mainClass.isBlank()) add("Plugin main class is missing")
        if (metadata.version.isBlank()) add("Plugin version is missing")
        if (metadata.rawDescriptorName !in setOf("plugin.yml", "nukkit.yml")) {
            add("Unsupported descriptor ${metadata.rawDescriptorName}")
        }
    }

    override fun verifyStartup(metadata: PluginMetadata, logs: List<String>): StartupVerification {
        val relevant = logs.filter { it.contains(metadata.name, ignoreCase = true) }.takeLast(40)
        val failures = relevant.filter {
            it.contains("error", true) || it.contains("exception", true) ||
                it.contains("failed", true) || it.contains("incompatible", true) ||
                it.contains("could not load", true)
        }
        val loaded = relevant.any {
            (it.contains("loaded", true) || it.contains("enabled", true) || it.contains("loading", true)) &&
                !failures.contains(it)
        }
        return StartupVerification(loaded = loaded, failed = failures.isNotEmpty(), evidence = (failures + relevant).distinct().takeLast(20))
    }
}

class PowerNukkitPluginAdapter : NukkitPluginAdapter() { override val engineId = "bedrock_power_nukkit" }
class PowerNukkitXPluginAdapter : NukkitPluginAdapter() { override val engineId = "bedrock_power_nukkit_x" }
class Pm1ePluginAdapter : NukkitPluginAdapter() { override val engineId = "bedrock_nukkit" }
class NukkitMotPluginAdapter : NukkitPluginAdapter() { override val engineId = "nukkit-mot" }

object PluginAdapterRegistry {
    private val adapters = listOf(
        PowerNukkitPluginAdapter(), PowerNukkitXPluginAdapter(), Pm1ePluginAdapter(), NukkitMotPluginAdapter()
    ).associateBy { it.engineId }

    fun get(engineId: String): PluginEngineAdapter? = adapters[engineId]
    fun all(): Collection<PluginEngineAdapter> = adapters.values
}

object PluginJarInspector {
    private val descriptorCandidates = listOf("plugin.yml", "nukkit.yml")

    fun inspect(file: File): Result<PluginMetadata> = runCatching {
        require(
            file.isFile && (file.name.endsWith(".jar", true) || file.name.endsWith(".jar.disabled", true)),
        ) { "Plugin must be a non-empty .jar or .jar.disabled file" }
        require(file.length() in 1..(512L * 1024 * 1024)) { "Plugin JAR size is invalid" }
        JarFile(file, true).use { jar ->
            val entries = jar.entries().asSequence().toList()
            require(entries.none { it.name.startsWith("/") || it.name.split('/').any { part -> part == ".." } }) {
                "Plugin JAR contains unsafe entries"
            }
            require(entries.any { !it.isDirectory && it.name.endsWith(".class") }) { "Plugin JAR contains no Java classes" }
            val descriptor = descriptorCandidates.firstNotNullOfOrNull { name -> jar.getJarEntry(name)?.let { name to it } }
                ?: error("plugin.yml or nukkit.yml is missing")
            val yamlText = jar.getInputStream(descriptor.second).bufferedReader().use { it.readText() }
            parseYaml(yamlText, descriptor.first)
        }
    }

    private fun parseYaml(text: String, descriptor: String): PluginMetadata {
        val yaml = org.yaml.snakeyaml.Yaml()
        val root = yaml.load<Any?>(text) as? Map<*, *> ?: error("Plugin descriptor is not a YAML object")
        fun string(key: String): String = root[key]?.toString()?.trim().orEmpty()
        fun strings(key: String): List<String> = when (val value = root[key]) {
            is Collection<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            is String -> value.split(',').map(String::trim).filter(String::isNotBlank)
            else -> emptyList()
        }
        return PluginMetadata(
            name = string("name"),
            version = string("version"),
            mainClass = string("main"),
            apiVersions = strings("api").ifEmpty { strings("api-version") },
            dependencies = strings("depend").ifEmpty { strings("dependencies") },
            softDependencies = strings("softdepend"),
            loadBefore = strings("loadbefore"),
            authors = strings("authors").ifEmpty { string("author").takeIf(String::isNotBlank)?.let(::listOf).orEmpty() },
            rawDescriptorName = descriptor
        )
    }
}
