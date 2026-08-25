package com.example.javaedition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.Properties

class JavaServerPropertiesAdapterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testApplyPropertiesAndVerify() {
        val serverDir = tempFolder.newFolder("java_server")
        
        JavaServerPropertiesAdapter.applyProperties(
            serverDir = serverDir,
            port = 25565,
            onlineMode = true,
            levelName = "world_java",
            gameMode = "survival",
            difficulty = "hard",
            maxPlayers = 25,
            motd = "Test Paper Server"
        )

        val propsFile = File(serverDir, "server.properties")
        assertTrue(propsFile.isFile)

        val props = Properties().apply {
            FileInputStream(propsFile).use { load(it) }
        }

        assertEquals("25565", props.getProperty("server-port"))
        assertEquals("true", props.getProperty("online-mode"))
        assertEquals("world_java", props.getProperty("level-name"))
        assertEquals("survival", props.getProperty("gamemode"))
        assertEquals("hard", props.getProperty("difficulty"))
        assertEquals("25", props.getProperty("max-players"))
        assertEquals("Test Paper Server", props.getProperty("motd"))
    }

    @Test
    fun testPreserveCustomKeysAndRemoveXboxAuth() {
        val serverDir = tempFolder.newFolder("java_server_custom")
        val propsFile = File(serverDir, "server.properties")
        propsFile.writeText(
            """
            custom-paper-key=keep-me
            xbox-auth=true
            server-port=12345
            online-mode=false
            """.trimIndent()
        )

        JavaServerPropertiesAdapter.applyProperties(
            serverDir = serverDir,
            port = 25565,
            onlineMode = true,
            levelName = "world",
            gameMode = "survival",
            difficulty = "normal",
            maxPlayers = 20,
            motd = "Updated Server"
        )

        val props = Properties().apply {
            FileInputStream(propsFile).use { load(it) }
        }

        assertEquals("keep-me", props.getProperty("custom-paper-key"))
        assertEquals("true", props.getProperty("online-mode"))
        assertEquals(null, props.getProperty("xbox-auth"))
        assertEquals("25565", props.getProperty("server-port"))
    }
}
