package com.example.server.version

data class ResolvedEngineVersion(
    val catalogBaseId: String,
    val effectiveVersionId: String,
    val engineId: String,
    val resolvedBuildNumber: Int,
    val resolvedArtifactUrl: String,
    val artifactSize: Long,
    val sourceRepository: String,
    val sourceRevision: String?,
    val resolvedAt: Long,
    val generatorId: String,
    val generatorRevision: String
)
