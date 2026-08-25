package com.example.javaedition

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperResolverTest {

    @Test
    fun testDetermineRequiredJavaMajor() {
        assertEquals(17, PaperResolver.determineRequiredJavaMajor("1.17"))
        assertEquals(17, PaperResolver.determineRequiredJavaMajor("1.17.1"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.20.4"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.20.5"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.21"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.21.1"))
        assertEquals(25, PaperResolver.determineRequiredJavaMajor("26.1"))
    }
}
