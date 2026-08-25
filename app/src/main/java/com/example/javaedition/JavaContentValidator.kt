package com.example.javaedition

import java.io.File
import java.util.jar.JarFile

object JavaContentValidator {
    fun validate(kind: JavaContentKind, file: File, platform: JavaServerPlatform): Result<Unit> = runCatching {
        require(file.isFile && file.length() > 0) { "Content file is missing" }
        when (kind) {
            JavaContentKind.PLUGIN -> {
                require(platform in setOf(JavaServerPlatform.PAPER, JavaServerPlatform.PURPUR)) {
                    "Java plugins are supported only on plugin platforms"
                }
                require(file.extension.equals("jar", true)) { "Plugin must be a JAR" }
                JarFile(file).use { jar ->
                    require(jar.getJarEntry("plugin.yml") != null || jar.getJarEntry("paper-plugin.yml") != null) {
                        "Plugin descriptor is missing"
                    }
                }
            }
            JavaContentKind.MOD -> {
                require(platform in setOf(JavaServerPlatform.FABRIC, JavaServerPlatform.FORGE, JavaServerPlatform.NEOFORGE)) {
                    "Java mods are supported only on mod-loader platforms"
                }
                require(file.extension.equals("jar", true)) { "Mod must be a JAR" }
                JarFile(file).use { jar ->
                    require(
                        jar.getJarEntry("fabric.mod.json") != null ||
                            jar.getJarEntry("META-INF/mods.toml") != null ||
                            jar.getJarEntry("META-INF/neoforge.mods.toml") != null
                    ) { "Recognized mod metadata is missing" }
                }
            }
            else -> Unit
        }
    }
}
