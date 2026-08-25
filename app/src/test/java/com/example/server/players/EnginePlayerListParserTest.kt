package com.example.server.players

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnginePlayerListParserTest {
    @Test
    fun powerNukkitParsesInlineList() {
        val result = EnginePlayerListParser.parse(
            "bedrock_power_nukkit",
            "[19:04:11 INFO] There are 2/20 players online: Steve, Alex_2",
        )
        assertEquals(listOf("Steve", "Alex_2"), result.authoritativeNames)
    }

    @Test
    fun powerNukkitXParsesHeaderThenNames() {
        val header = EnginePlayerListParser.parse(
            "bedrock_power_nukkit_x",
            "[INFO] There are 2/20 players online:",
        )
        assertEquals(2, header.pendingExpectedCount)
        val names = EnginePlayerListParser.parse(
            "bedrock_power_nukkit_x",
            "[INFO] Steve, Alex",
            header.pendingExpectedCount,
        )
        assertEquals(listOf("Steve", "Alex"), names.authoritativeNames)
    }

    @Test
    fun pm1eParsesLoginWithAddress() {
        val result = EnginePlayerListParser.parse(
            "bedrock_nukkit",
            "[INFO] Steve[/192.168.1.2:54321] logged in with entity id 7",
        )
        assertEquals("Steve", result.joinedName)
    }

    @Test
    fun nukkitMotParsesAlternativeListHeader() {
        val result = EnginePlayerListParser.parse(
            "nukkit-mot",
            "[main] INFO Players: 1/20: Bedrock Player",
        )
        assertEquals(listOf("Bedrock Player"), result.authoritativeNames)
    }

    @Test
    fun playerPrefixedJoinDoesNotKeepTheWordPlayer() {
        val result = EnginePlayerListParser.parse(
            "bedrock_power_nukkit_x",
            "[INFO] Player Steve joined the server",
        )
        assertEquals("Steve", result.joinedName)
    }

    @Test
    fun incompleteFollowUpDoesNotBecomeAnAuthoritativeRoster() {
        val result = EnginePlayerListParser.parse(
            "bedrock_power_nukkit_x",
            "Steve",
            pendingExpectedCount = 2,
        )
        assertNull(result.authoritativeNames)
    }

    @Test
    fun arbitraryConsoleLineDoesNotBecomePlayer() {
        val result = EnginePlayerListParser.parse(
            "bedrock_power_nukkit_x",
            "[INFO] Loading plugins completed",
            pendingExpectedCount = 2,
        )
        assertNull(result.authoritativeNames)
        assertNull(result.joinedName)
        assertNull(result.leftName)
    }

    @Test
    fun parsesCountOnlyParenthesizedHeaderAndFollowUp() {
        val header = EnginePlayerListParser.parse(
            "bedrock_power_nukkit_x",
            "[INFO] Online players (2):",
        )
        assertEquals(2, header.pendingExpectedCount)
        val names = EnginePlayerListParser.parse(
            "bedrock_power_nukkit_x",
            "Players: Steve, Alex",
            pendingExpectedCount = header.pendingExpectedCount,
        )
        assertEquals(listOf("Steve", "Alex"), names.authoritativeNames)
    }

    @Test
    fun stripsMinecraftColorCodesFromJoinOutput() {
        val result = EnginePlayerListParser.parse(
            "nukkit-mot",
            "[INFO] §aBedrock Player joined the game",
        )
        assertEquals("Bedrock Player", result.joinedName)
    }

    @Test
    fun parsesPlayerDisconnectedColonFormat() {
        val result = EnginePlayerListParser.parse(
            "bedrock_nukkit",
            "[INFO] Player disconnected: Steve",
        )
        assertEquals("Steve", result.leftName)
    }
    @Test
    fun preservesUnicodePlayerNamesFromAuthoritativeList() {
        val result = EnginePlayerListParser.parse(
            "bedrock_power_nukkit_x",
            "[INFO] There are 1/20 players online: 玩家一",
        )
        assertEquals(listOf("玩家一"), result.authoritativeNames)
    }

}
