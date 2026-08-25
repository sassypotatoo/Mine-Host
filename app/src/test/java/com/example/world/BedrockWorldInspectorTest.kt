package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BedrockWorldInspectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val inspector = BedrockWorldInspector()

    @Test
    fun `1 Empty JSON array returns empty list`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText("[]")
        val packsFolder = tempFolder.newFolder("packs")

        val result = inspector.readPackReferences(file, packsFolder)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `2 One valid pack reference`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText(
            """
            [
              {
                "pack_id": "c001-a002-b003",
                "version": [1, 2, 0]
              }
            ]
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("packs")

        val result = inspector.readPackReferences(file, packsFolder)
        assertEquals(1, result.size)
        assertEquals("c001-a002-b003", result[0].packId)
        assertEquals("1.2.0", result[0].version)
        assertNull(result[0].subpack)
        assertFalse(result[0].filesPresent)
    }

    @Test
    fun `3 Multiple pack references`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText(
            """
            [
              { "pack_id": "p1", "version": [1, 0, 0] },
              { "pack_id": "p2", "version": [2, 1, 0] }
            ]
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("packs")

        val result = inspector.readPackReferences(file, packsFolder)
        assertEquals(2, result.size)
        assertEquals("p1", result[0].packId)
        assertEquals("p2", result[1].packId)
    }

    @Test
    fun `4 Optional subpack`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText(
            """
            [
              { "pack_id": "p1", "version": [1, 0, 0], "subpack": "high_res" },
              { "pack_id": "p2", "version": [1, 0, 0] }
            ]
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("packs")

        val result = inspector.readPackReferences(file, packsFolder)
        assertEquals(2, result.size)
        assertEquals("high_res", result[0].subpack)
        assertNull(result[1].subpack)
    }

    @Test
    fun `5 Version array conversion`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText(
            """
            [
              { "pack_id": "p1", "version": [1, 16, 220] }
            ]
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("packs")

        val result = inspector.readPackReferences(file, packsFolder)
        assertEquals("1.16.220", result[0].version)
    }

    @Test
    fun `6 Whitespace formatting`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText(
            """
            
            [
              
              {
                
                "pack_id" :   "  p_whitespace  "  ,
                "version" : [
                  1,
                  0,
                  5
                ]
              }
              
            ]
            
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("packs")

        val result = inspector.readPackReferences(file, packsFolder)
        assertEquals(1, result.size)
        assertEquals("p_whitespace", result[0].packId)
        assertEquals("1.0.5", result[0].version)
    }

    @Test
    fun `7 Missing pack_id throws exception`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText(
            """
            [
              { "version": [1, 0, 0] }
            ]
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("packs")

        try {
            inspector.readPackReferences(file, packsFolder)
            fail("Expected IllegalArgumentException for missing pack_id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Missing pack_id") == true)
        }
    }

    @Test
    fun `8 Malformed JSON throws exception`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText("[ { \"pack_id\": \"p1\", ")
        val packsFolder = tempFolder.newFolder("packs")

        try {
            inspector.readPackReferences(file, packsFolder)
            fail("Expected IllegalArgumentException for malformed JSON")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Malformed JSON") == true)
        }
    }

    @Test
    fun `9 Nested JSON data extra properties handled safely`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText(
            """
            [
              {
                "pack_id": "p_nested",
                "version": [1, 0, 0],
                "extra_meta": { "author": "dev", "tags": ["addon", "mc"] }
              }
            ]
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("packs")

        val result = inspector.readPackReferences(file, packsFolder)
        assertEquals(1, result.size)
        assertEquals("p_nested", result[0].packId)
    }

    @Test
    fun `10 Braces inside a quoted string`() {
        val file = tempFolder.newFile("packs.json")
        file.writeText(
            """
            [
              {
                "pack_id": "p_braces_{123-abc}",
                "version": [1, 0, 0],
                "subpack": "sub_{with_braces}"
              }
            ]
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("packs")

        val result = inspector.readPackReferences(file, packsFolder)
        assertEquals(1, result.size)
        assertEquals("p_braces_{123-abc}", result[0].packId)
        assertEquals("sub_{with_braces}", result[0].subpack)
    }

    @Test
    fun `11 Oversized input rejection`() {
        val file = tempFolder.newFile("packs.json")
        // Write content exceeding 1_000_000 chars
        val padding = " ".repeat(1_000_005)
        file.writeText("[$padding]")
        val packsFolder = tempFolder.newFolder("packs")

        try {
            inspector.readPackReferences(file, packsFolder)
            fail("Expected IllegalArgumentException for oversized input")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("file is too large") == true)
        }
    }

    @Test
    fun `12 Real world pack references with complex formatting no longer throw regex error`() {
        val file = tempFolder.newFile("world_behavior_packs.json")
        file.writeText(
            """
            [
              {
                "pack_id": "70339a4d-f95d-4f18-a6d1-123456789abc",
                "version": [1, 0, 10],
                "subpack": "option_{default}"
              }
            ]
            """.trimIndent()
        )
        val packsFolder = tempFolder.newFolder("behavior_packs")
        val samplePack = File(packsFolder, "70339a4d-f95d-4f18-a6d1-123456789abc")
        samplePack.mkdirs()
        File(samplePack, "manifest.json").writeText("{\"pack_id\": \"70339a4d-f95d-4f18-a6d1-123456789abc\"}")

        val result = inspector.readPackReferences(file, packsFolder)
        assertEquals(1, result.size)
        assertEquals("70339a4d-f95d-4f18-a6d1-123456789abc", result[0].packId)
        assertEquals("1.0.10", result[0].version)
        assertEquals("option_{default}", result[0].subpack)
        assertTrue(result[0].filesPresent)
    }
}
