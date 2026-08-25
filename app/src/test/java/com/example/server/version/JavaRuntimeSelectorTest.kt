package com.example.server.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JavaRuntimeSelectorTest {
    @Test fun mapsMinimumRequirementsToPackagedRuntime() {
        assertEquals(17, JavaRuntimeSelector.select(17))
        assertEquals(21, JavaRuntimeSelector.select(18))
        assertEquals(21, JavaRuntimeSelector.select(21))
        assertEquals(25, JavaRuntimeSelector.select(22))
        assertEquals(25, JavaRuntimeSelector.select(25))
        assertNull(JavaRuntimeSelector.select(26))
    }
}
