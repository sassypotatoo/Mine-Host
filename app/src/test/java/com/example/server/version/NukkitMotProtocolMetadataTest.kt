package com.example.server.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NukkitMotProtocolMetadataTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun discoversProtocolsFromJarEntries() {
        val jarFile = tempFolder.newFile("nukkit-mot.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("runtime_block_states_1001.dat"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("resources/runtime_block_states_2168.dat"))
            zos.write(byteArrayOf(4, 5, 6))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("other_file.txt"))
            zos.write(byteArrayOf(7, 8, 9))
            zos.closeEntry()
        }

        val protocols = NukkitMotProtocolMetadata.discoverSupportedProtocols(jarFile)
        assertEquals(listOf(1001, 2168), protocols)
    }

    @Test
    fun returnsEmptyListWhenNoProtocolDatFiles() {
        val jarFile = tempFolder.newFile("empty.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("some_resource.txt"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        val protocols = NukkitMotProtocolMetadata.discoverSupportedProtocols(jarFile)
        assertTrue(protocols.isEmpty())
    }

    @Test
    fun currentPaletteDetectedWhenUnnumberedDatPresent() {
        val jarFile = tempFolder.newFile("with-current.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("resources/runtime_block_states_2168.dat"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("resources/runtime_block_states.dat"))
            zos.write(byteArrayOf(4, 5, 6))
            zos.closeEntry()
        }

        assertTrue(NukkitMotProtocolMetadata.hasCurrentProtocolPalette(jarFile))

        // The unnumbered palette carries no protocol number and must not enter
        // the numbered discovery set.
        assertEquals(
            listOf(2168),
            NukkitMotProtocolMetadata.discoverSupportedProtocols(jarFile)
        )
    }

    @Test
    fun currentPaletteAbsentWhenOnlyNumberedDatFiles() {
        val jarFile = tempFolder.newFile("numbered-only.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("runtime_block_states_1001.dat"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        assertEquals(false, NukkitMotProtocolMetadata.hasCurrentProtocolPalette(jarFile))
    }

    @Test(expected = IllegalArgumentException::class)
    fun currentPaletteCheckThrowsOnMissingFile() {
        NukkitMotProtocolMetadata.hasCurrentProtocolPalette(File(tempFolder.root, "nonexistent.jar"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun throwsOnMissingFile() {
        NukkitMotProtocolMetadata.discoverSupportedProtocols(File(tempFolder.root, "nonexistent.jar"))
    }
}
