package com.example.world

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineGeneratedWorldStoreTest {
    @Test
    fun identityRequiresMatchingInnerAndOuterTokens() {
        val root = createTempDir(prefix = "minehost-origin-")
        try {
            val world = createWorld(root, "world")
            assertTrue(EngineGeneratedWorldStore.mark(root, "world", world, "nukkit-mot", "nukkit-mot:1361"))
            assertTrue(EngineGeneratedWorldStore.isRecognized(root, "world", world))

            File(world, ".minehost-engine-generated.properties").delete()
            assertFalse(EngineGeneratedWorldStore.isRecognized(root, "world", world))
            assertTrue(EngineGeneratedWorldStore.markerExists(root, "world", world))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun renameAndDeleteFollowMovedWorldDirectory() {
        val root = createTempDir(prefix = "minehost-origin-lifecycle-")
        try {
            val oldWorld = createWorld(root, "old")
            assertTrue(EngineGeneratedWorldStore.mark(root, "old", oldWorld, "nukkit-mot", "nukkit-mot:1361"))
            val newWorld = File(root, "worlds/new")
            assertTrue(oldWorld.renameTo(newWorld))
            assertTrue(EngineGeneratedWorldStore.rename(root, "old", "new", newWorld))
            assertTrue(EngineGeneratedWorldStore.isRecognized(root, "new", newWorld))
            assertFalse(EngineGeneratedWorldStore.isRecognized(root, "old", newWorld))
            assertTrue(EngineGeneratedWorldStore.delete(root, "new", newWorld))
            assertFalse(EngineGeneratedWorldStore.markerExists(root, "new", newWorld))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createWorld(root: File, name: String): File = File(root, "worlds/$name").apply {
        mkdirs()
        File(this, "level.dat").writeBytes(byteArrayOf(1, 2, 3))
        File(this, "db").mkdirs()
    }
}
