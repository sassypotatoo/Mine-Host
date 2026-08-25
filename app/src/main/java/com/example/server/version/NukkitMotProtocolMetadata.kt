package com.example.server.version

import java.io.File
import java.util.jar.JarFile

object NukkitMotProtocolMetadata {

    private val protocolResource =
        Regex("""(?:^|/)runtime_block_states_(\d+)\.dat$""")

    fun discoverSupportedProtocols(
        jarFile: File,
    ): List<Int> {
        require(jarFile.isFile && jarFile.length() > 0L) {
            "Nukkit-MOT artifact is missing or empty: ${jarFile.absolutePath}"
        }

        return JarFile(jarFile).use { jar ->
            buildSet {
                val entries = jar.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    if (entry.isDirectory) {
                        continue
                    }

                    val match =
                        protocolResource.matchEntire(entry.name)
                            ?: continue

                    val protocol =
                        match.groupValues[1]
                            .toIntOrNull()
                            ?.takeIf { it > 0 }
                            ?: continue

                    add(protocol)
                }
            }.sorted()
        }
    }
}
