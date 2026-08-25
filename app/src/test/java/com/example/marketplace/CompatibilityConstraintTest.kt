package com.example.marketplace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityConstraintTest {
    @Test
    fun acceptsBoundedVersionRange() {
        val result = CompatibilityConstraint.version("1.21.50", ">=1.21.0,<=1.21.60")
        assertTrue(result.understood)
        assertTrue(result.matches)
    }

    @Test
    fun rejectsUnsupportedConstraintSyntax() {
        val result = CompatibilityConstraint.version("1.21.50", "~1.21")
        assertFalse(result.understood)
        assertFalse(result.matches)
    }

    @Test
    fun selectsCompatiblePackagedJavaVersion() {
        assertTrue(CompatibilityConstraint.java(21, ">=18,<=21").matches)
        assertFalse(CompatibilityConstraint.java(17, ">=18").matches)
    }
}
