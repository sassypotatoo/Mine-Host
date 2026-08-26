package com.example.server.version

import java.io.File
import java.util.jar.JarFile

object NukkitMotProtocolMetadata {

    // matchEntire anchors both ends, so an optional directory prefix must be
    // part of the pattern itself; "(?:^|/)" can never consume "dir/" here.
    private val protocolResource =
        Regex("""(?:.*/)?runtime_block_states_(\d+)\.dat""")

    private val currentPaletteResource =
        Regex("""(?:.*/)?runtime_block_states\.dat""")

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

    /**
     * True when the artifact also carries the UNNUMBERED runtime_block_states.dat:
     * upstream ships its own current protocol's palette under that name, which
     * never appears in the numbered discovery set above.
     */
    fun hasCurrentProtocolPalette(
        jarFile: File,
    ): Boolean {
        require(jarFile.isFile && jarFile.length() > 0L) {
            "Nukkit-MOT artifact is missing or empty: ${jarFile.absolutePath}"
        }

        return JarFile(jarFile).use { jar ->
            val entries = jar.entries()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()

                if (entry.isDirectory) {
                    continue
                }

                if (currentPaletteResource.matchEntire(entry.name) != null) {
                    return@use true
                }
            }

            false
        }
    }
}
