package com.example.world

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BedrockRawNbtRecordTest {
    @Test fun passthroughPreservesUnknownFieldsByteForByte() {
        val bytes = byteArrayOf(
            10, 0, 0,                         // root compound, empty name
            8, 2, 0, 'i'.code.toByte(), 'd'.code.toByte(),
            5, 0, 'V'.code.toByte(), 'a'.code.toByte(), 'u'.code.toByte(), 'l'.code.toByte(), 't'.code.toByte(),
            3, 7, 0, 'm'.code.toByte(), 'y'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            'e'.code.toByte(), 'r'.code.toByte(), 'y'.code.toByte(),
            0x78, 0x56, 0x34, 0x12,
            0,
        )
        val record = BedrockRawNbtRecord.fromConcatenated(bytes)
        record.verifyLosslessRoundTrip().getOrThrow()
        assertEquals(listOf("Vault"), record.identifiers)
        assertArrayEquals(bytes, record.writeLossless())
    }

    @Test fun concatenatedRootsRetainIndependentExactSlices() {
        val first = byteArrayOf(
            10, 0, 0,
            8, 2, 0, 'i'.code.toByte(), 'd'.code.toByte(),
            5, 0, 'V'.code.toByte(), 'a'.code.toByte(), 'u'.code.toByte(), 'l'.code.toByte(), 't'.code.toByte(),
            0,
        )
        val second = byteArrayOf(
            10, 0, 0,
            8, 2, 0, 'i'.code.toByte(), 'd'.code.toByte(),
            12, 0, 'S'.code.toByte(), 'p'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(),
            'B'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), 's'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0,
        )
        val bytes = first + second
        val record = BedrockRawNbtRecord.fromConcatenated(bytes)
        record.verifyLosslessRoundTrip().getOrThrow()
        assertEquals(2, record.elements.size)
        assertEquals(listOf("Vault", "SporeBlossom"), record.identifiers)
        assertArrayEquals(first, record.elements[0].writeLossless())
        assertArrayEquals(second, record.elements[1].writeLossless())
        assertArrayEquals(bytes, record.writeLossless())
    }
}
