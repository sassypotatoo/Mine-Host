package com.example.server

import java.io.File

sealed interface ArtifactDownloadResult {
    data class Success(
        val file: File,
        val finalUrl: String,
        val contentLength: Long,
        val manifestMainClass: String?
    ) : ArtifactDownloadResult

    data class HttpFailure(
        val url: String,
        val statusCode: Int,
        val retryable: Boolean
    ) : ArtifactDownloadResult

    data class NetworkFailure(
        val url: String,
        val message: String,
        val retryable: Boolean
    ) : ArtifactDownloadResult

    data class ValidationFailure(
        val url: String,
        val message: String
    ) : ArtifactDownloadResult

    data object Cancelled : ArtifactDownloadResult
}
