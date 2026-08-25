package com.example.backup

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupIsolationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun backupFromAnotherServerIsRejectedBeforeRestore() {
        val root = temporaryFolder.newFolder("server-a")
        File(root, "worlds/world/db").mkdirs()
        File(root, "worlds/world/level.dat").writeText("level")
        File(root, "server.properties").writeText("level-name=world\n")
        val manager = BackupManagerV2(root)
        val created = manager.createWithResult(metadata("server-a"), "test")
        assertTrue(created.result.message, created.result.success)
        val archive = requireNotNull(created.archive)

        val verification = manager.verify(archive, expectedServerUuid = "server-b")
        assertFalse(verification.valid)
        assertTrue(verification.message.contains("UUID", ignoreCase = true))
    }

    @Test
    fun undeclaredZipEntryIsRejected() {
        val root = temporaryFolder.newFolder("server-extra")
        File(root, "worlds/world/db").mkdirs()
        File(root, "worlds/world/level.dat").writeText("level")
        File(root, "server.properties").writeText("level-name=world\n")
        val manager = BackupManagerV2(root)
        val created = manager.createWithResult(metadata("server-extra"), "test")
        assertTrue(created.result.message, created.result.success)
        val archive = requireNotNull(created.archive)
        val injected = File(archive.parentFile, "injected.zip")

        ZipFile(archive).use { source ->
            ZipOutputStream(injected.outputStream()).use { output ->
                source.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry("worlds/world/undeclared.dat"))
                output.write("injected".toByteArray())
                output.closeEntry()
            }
        }

        val verification = manager.verify(injected, expectedServerUuid = "server-extra")
        assertFalse(verification.valid)
        assertTrue(
            verification.message.contains("manifest", ignoreCase = true) ||
                verification.message.contains("extra", ignoreCase = true) ||
                verification.message.contains("declared", ignoreCase = true),
        )
    }

    private fun metadata(uuid: String) = BackupManagerV2.Metadata(
        serverUuid = uuid,
        engineId = "powernukkitx",
        engineVersionId = "test-version",
        javaVersion = 21,
        levelName = "world",
    )
}
