package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CanonicalBlockStateTest {
    @Test fun canonicalIdentityPreservesTypesAndSortsProperties() {
        val first = CanonicalBlockStateCodec.fromCompound(
            linkedMapOf(
                "name" to "minecraft:test_block",
                "states" to linkedMapOf(
                    "z_flag" to 0.toByte(),
                    "a_value" to 0,
                    "direction" to "north",
                ),
                "version" to 18_168_865,
            ),
        )
        val reordered = CanonicalBlockStateCodec.fromCompound(
            linkedMapOf(
                "version" to 18_168_865,
                "states" to linkedMapOf(
                    "direction" to "north",
                    "a_value" to 0,
                    "z_flag" to 0.toByte(),
                ),
                "name" to "minecraft:test_block",
            ),
        )
        val wrongType = CanonicalBlockStateCodec.fromCompound(
            linkedMapOf(
                "name" to "minecraft:test_block",
                "states" to linkedMapOf(
                    "a_value" to 0.toByte(),
                    "direction" to "north",
                    "z_flag" to 0.toByte(),
                ),
                "version" to 18_168_865,
            ),
        )

        assertEquals(first.canonicalIdentity, reordered.canonicalIdentity)
        assertEquals(listOf("a_value", "direction", "z_flag"), first.properties.map { it.name })
        assertFalse(first.canonicalIdentity == wrongType.canonicalIdentity)
    }
}
