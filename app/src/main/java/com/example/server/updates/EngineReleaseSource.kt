package com.example.server.updates

interface EngineReleaseSource {
    val engineId: String
    val sourceName: String
    val sourceKey: String

    suspend fun checkReleases(
        knownSourceIds: Set<String>
    ): SourceCheckResult
}

sealed class SourceCheckResult {
    data class Success(val releases: List<DetectedEngineRelease>) : SourceCheckResult()
    data class Failure(val failure: ReleaseSourceFailure) : SourceCheckResult()
}

data class ReleaseSourceFailure(
    val engineId: String,
    val sourceName: String,
    val retryable: Boolean,
    val message: String,
    val httpCode: Int? = null
)

sealed class ReleaseCheckResult {
    data class Success(
        val newReleaseCount: Int,
        val checkedSourceCount: Int
    ) : ReleaseCheckResult()

    data class PartialSuccess(
        val newReleaseCount: Int,
        val checkedSourceCount: Int,
        val failures: List<ReleaseSourceFailure>
    ) : ReleaseCheckResult()

    data class Failure(
        val failures: List<ReleaseSourceFailure>
    ) : ReleaseCheckResult()

    data object SkippedCooldown : ReleaseCheckResult()
    data object AlreadyRunning : ReleaseCheckResult()
}
