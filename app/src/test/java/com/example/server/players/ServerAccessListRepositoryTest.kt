package com.example.server.players

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerAccessListRepositoryTest {
    @Test
    fun readsRealWhitelistAndNukkitStyleBanMap() {
        val root = Files.createTempDirectory("minehost-access-lists").toFile()
        try {
            root.resolve("white-list.txt").writeText("Steve=true\nAlex\nDisabled=false\n")
            root.resolve("banned-players.json").writeText(
                """{
                  "griefer": {
                    "name": "Griefer",
                    "reason": "Breaking spawn",
                    "source": "Console",
                    "expires": "Forever"
                  }
                }""".trimIndent(),
            )

            val state = ServerAccessListRepository.read(root)

            assertEquals(listOf("Alex", "Steve"), state.whitelist.sorted())
            assertEquals(listOf("Griefer"), state.bannedPlayers.map { it.name }.sorted())
            assertEquals("Breaking spawn", state.bannedPlayers.single().reason)
            assertTrue(state.whitelistFilePresent)
            assertTrue(state.bannedPlayersFilePresent)
            assertNull(state.error)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingFilesAreReportedWithoutInventingEntries() {
        val root = Files.createTempDirectory("minehost-empty-access-lists").toFile()
        try {
            val state = ServerAccessListRepository.read(root)
            assertFalse(state.whitelistFilePresent)
            assertFalse(state.bannedPlayersFilePresent)
            assertTrue(state.whitelist.isEmpty())
            assertTrue(state.bannedPlayers.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun readsNameOnlyAndReasonMapBanEntries() {
        val root = Files.createTempDirectory("minehost-ban-list-variants").toFile()
        try {
            root.resolve("banned-players.json").writeText(
                """{
                  "Steve": "Griefing",
                  "Alex": { "name": "Alex" },
                  "version": "1"
                }""".trimIndent(),
            )

            val state = ServerAccessListRepository.read(root)

            assertEquals(listOf("Alex", "Steve"), state.bannedPlayers.map { it.name }.sorted())
            assertEquals("Griefing", state.bannedPlayers.first { it.name == "Steve" }.reason)
            assertNull(state.error)
        } finally {
            root.deleteRecursively()
        }
    }

}
