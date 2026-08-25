package com.example.world

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRunStateStoreTest {
    @Test
    fun liveProcessMarkerBlocksWorldCopyUntilOwnedCleanup() {
        val root = Files.createTempDirectory("minehost-run-guard").toFile()
        try {
            val currentPid = ProcessHandle.current().pid()
            ServerRunStateStore.markRunning(root, "session-a", currentPid)
            assertTrue(ServerRunStateStore.isRunning(root))
            ServerRunStateStore.clearIfOwned(root, "another-session")
            assertTrue(ServerRunStateStore.isRunning(root))
            ServerRunStateStore.clearIfOwned(root, "session-a")
            assertFalse(ServerRunStateStore.isRunning(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deadPidMarkerIsRemoved() {
        val root = Files.createTempDirectory("minehost-dead-run-guard").toFile()
        try {
            ServerRunStateStore.markRunning(root, "session-a", Long.MAX_VALUE)
            assertFalse(ServerRunStateStore.isRunning(root))
            assertFalse(File(root, ".minehost/server-run-state.properties").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ambiguousMarkerBlocksFailClosed() {
        val root = Files.createTempDirectory("minehost-ambiguous-run-guard").toFile()
        try {
            val marker = File(root, ".minehost/server-run-state.properties")
            marker.parentFile?.mkdirs()
            marker.writeText("sessionId=session-a\n")
            assertTrue(ServerRunStateStore.isRunning(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
