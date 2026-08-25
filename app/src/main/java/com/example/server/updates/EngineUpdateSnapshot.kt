package com.example.server.updates


data class EngineUpdateSnapshot(
    val schemaVersion: Int = 1,
    val detectedReleases: List<DetectedEngineRelease> = emptyList(),
    val checkState: EngineUpdateCheckState = EngineUpdateCheckState()
)
