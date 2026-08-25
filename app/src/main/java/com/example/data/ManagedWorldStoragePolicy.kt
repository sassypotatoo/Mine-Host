package com.example.data

import java.util.Locale

/** Core Bedrock storage must be changed through World Manager transactions, never file-by-file. */
object ManagedWorldStoragePolicy {
    fun rejectsMutation(relativePath: String): Boolean {
        val parts = relativePath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2 || !parts[0].equals("worlds", ignoreCase = true)) return false
        if (parts.size == 2) return true
        val worldEntry = parts[2].lowercase(Locale.ROOT)
        return worldEntry == "db" || worldEntry in protectedWorldEntries
    }

    private val protectedWorldEntries = setOf(
        "level.dat",
        "level.dat_old",
        "levelname.txt",
        "world_behavior_packs.json",
        "world_resource_packs.json",
        "world_icon.jpeg",
        ".minehost-engine-generated.properties",
    )
}
