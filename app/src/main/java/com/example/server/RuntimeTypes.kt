package com.example.server

import java.io.File

sealed class RuntimePreparationResult {
    data class Ready(
        val runtimeHome: File,
        val launcherFile: File,
        val javaMajor: Int,
        val runtimeFingerprint: String
    ) : RuntimePreparationResult()

    data class Unsupported(
        val requiredJavaMajor: Int,
        val message: String
    ) : RuntimePreparationResult()

    data class Failure(
        val stage: String,
        val message: String,
        val cause: Throwable? = null
    ) : RuntimePreparationResult()
}

sealed class RuntimeIntegrityResult {
    data class Valid(
        val runtimeHome: File,
        val javaMajor: Int,
        val runtimeFingerprint: String
    ) : RuntimeIntegrityResult()

    data class Invalid(
        val reason: String
    ) : RuntimeIntegrityResult()
}

data class RuntimeCommandResult(
    val launcherFile: File,
    val commandArgs: List<String>
)

data class JavaValidationResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val command: String,
    val runtimePath: String,
    val launcherPath: String
)
