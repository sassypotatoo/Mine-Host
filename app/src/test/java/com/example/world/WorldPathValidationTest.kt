package com.example.world

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WorldPathValidationTest {

    @Test
    fun testSafePaths() {
        val manager = WorldManagerV2(
            context = ApplicationProvider.getApplicationContext(),
            serverRoot = File("/tmp"),
            createSafetyBackup = { com.example.data.OperationResult(true, "") }
        )

        // Accepted paths
        assertTrue("Base64-like directory name should be accepted", manager.isSafePath("9A2FkCJ7OHU=/", true))
        assertTrue("Normal directory should be accepted", manager.isSafePath("MyWorld/", true))
        assertTrue("Nested directory should be accepted", manager.isSafePath("MyWorld/db/", true))
        assertTrue("Normal file should be accepted", manager.isSafePath("MyWorld/db/file.ldb", false))
        assertTrue("File with equals should be accepted", manager.isSafePath("world=1/level.dat", false))
    }

    @Test
    fun testUnsafePaths() {
        val manager = WorldManagerV2(
            context = ApplicationProvider.getApplicationContext(),
            serverRoot = File("/tmp"),
            createSafetyBackup = { com.example.data.OperationResult(true, "") }
        )

        // Rejected paths
        assertFalse("Path traversal should be rejected", manager.isSafePath("../outside", false))
        assertFalse("Nested path traversal should be rejected", manager.isSafePath("folder/../../outside", false))
        assertFalse("Absolute path should be rejected", manager.isSafePath("/absolute/path", false))
        assertFalse("Windows absolute path should be rejected", manager.isSafePath("C:/outside", false))
        assertFalse("Double slash should be rejected", manager.isSafePath("folder//file", false))
        assertFalse("Current directory reference should be rejected", manager.isSafePath("folder/./file", false))
        assertFalse("Trailing slash for non-directory should be rejected", manager.isSafePath("file.txt/", false))
        assertFalse("Empty path should be rejected", manager.isSafePath("", false))
        assertFalse("Null character should be rejected", manager.isSafePath("path\u0000name", false))
    }
}
