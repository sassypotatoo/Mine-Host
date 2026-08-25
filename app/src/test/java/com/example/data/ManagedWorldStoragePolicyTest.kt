package com.example.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedWorldStoragePolicyTest {
    @Test
    fun coreBedrockStorageCannotBeMutatedDirectly() {
        assertTrue(ManagedWorldStoragePolicy.rejectsMutation("worlds/world"))
        assertTrue(ManagedWorldStoragePolicy.rejectsMutation("worlds/world/level.dat"))
        assertTrue(ManagedWorldStoragePolicy.rejectsMutation("worlds/world/db/000001.ldb"))
        assertTrue(ManagedWorldStoragePolicy.rejectsMutation("worlds\\world\\db\\CURRENT"))
    }

    @Test
    fun unrelatedServerFilesRemainEditable() {
        assertFalse(ManagedWorldStoragePolicy.rejectsMutation("server.properties"))
        assertFalse(ManagedWorldStoragePolicy.rejectsMutation("plugins/example.jar"))
        assertFalse(ManagedWorldStoragePolicy.rejectsMutation("worlds/world/resource_packs/example/manifest.json"))
    }
}
