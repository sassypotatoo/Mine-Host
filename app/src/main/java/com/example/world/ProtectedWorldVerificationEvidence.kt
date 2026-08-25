package com.example.world

/**
 * Evidence required before a protected imported world may leave the provisional state.
 * Network readiness is deliberately absent because it does not prove world correctness.
 */
data class ProtectedWorldVerificationEvidence(
    val expectedDimensions: Set<Int>,
    val loadedDimensions: Set<Int>,
    val selectedChunksRequested: Int,
    val selectedChunksLoaded: Int,
    val playerJoined: Boolean,
    val playerSpawnAreaLoaded: Boolean,
    val playerPositionMigrationRequired: Boolean,
    val playerPositionMigrationApplied: Boolean,
    val playerInventoryMigrationRequired: Boolean,
    val playerInventoryMigrationApplied: Boolean,
    val fatalWorldErrors: List<String>,
    val unsupportedMappings: List<String>,
    val unsafeWritesDetected: Boolean,
    val cleanRestartCompleted: Boolean,
) {
    fun failures(): List<String> = buildList {
        val missingDimensions = expectedDimensions - loadedDimensions
        if (missingDimensions.isNotEmpty()) {
            add("Dimensions did not finish loading: ${missingDimensions.sorted().joinToString()}")
        }
        if (selectedChunksRequested <= 0) add("No verification chunks were selected")
        if (selectedChunksLoaded != selectedChunksRequested) {
            add("Only $selectedChunksLoaded of $selectedChunksRequested selected chunks loaded")
        }
        if (!playerJoined) add("No approved player completed the protected join")
        if (!playerSpawnAreaLoaded) add("The approved player's spawn area was not verified")
        if (playerPositionMigrationRequired && !playerPositionMigrationApplied) {
            add("Imported player position was not applied")
        }
        if (playerInventoryMigrationRequired && !playerInventoryMigrationApplied) {
            add("Imported player inventory was not applied")
        }
        fatalWorldErrors.filter(String::isNotBlank).forEach { add("Fatal world error: ${it.trim()}") }
        unsupportedMappings.filter(String::isNotBlank).forEach { add("Unsupported mapping: ${it.trim()}") }
        if (unsafeWritesDetected) add("Unsafe world writes or chunk regeneration were detected")
        if (!cleanRestartCompleted) add("A clean stop and restart has not been verified")
    }.distinct()

    val verified: Boolean get() = failures().isEmpty()
}
