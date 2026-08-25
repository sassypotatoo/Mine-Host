package com.example.javaedition

enum class JavaServerPlatform { PAPER, PURPUR, FABRIC, FORGE, NEOFORGE }
enum class JavaContentKind { PLUGIN, MOD, WORLD, DATAPACK, RESOURCE_PACK }
enum class JavaReleaseChannel { STABLE, BETA, SNAPSHOT }

data class JavaEditionRelease(
    val releaseId: String,
    val platform: JavaServerPlatform,
    val displayVersion: String,
    val minecraftVersion: String,
    val channel: JavaReleaseChannel,
    val requiredJavaMajor: Int,
    val downloadUrl: String,
    val sha256: String,
    val fileSize: Long,
    val releaseDate: String?,
    val recommended: Boolean,
    val deprecated: Boolean,
)

data class JavaServerLayout(
    val rootDirectoryName: String,
    val worldDirectoryName: String = "world",
    val pluginsDirectoryName: String?,
    val modsDirectoryName: String?,
) {
    companion object {
        fun forPlatform(platform: JavaServerPlatform): JavaServerLayout = when (platform) {
            JavaServerPlatform.PAPER, JavaServerPlatform.PURPUR -> JavaServerLayout(
                rootDirectoryName = "java-server",
                pluginsDirectoryName = "plugins",
                modsDirectoryName = null,
            )
            JavaServerPlatform.FABRIC, JavaServerPlatform.FORGE, JavaServerPlatform.NEOFORGE -> JavaServerLayout(
                rootDirectoryName = "java-server",
                pluginsDirectoryName = null,
                modsDirectoryName = "mods",
            )
        }
    }
}
