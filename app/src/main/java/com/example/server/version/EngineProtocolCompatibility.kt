package com.example.server.version

enum class EngineProtocolCompatibilityState {
    VERIFIED,
    VERSION_MISMATCH,
    PROTOCOL_MISMATCH,
    UNKNOWN
}

data class EngineProtocolCompatibilityResult(
    val state: EngineProtocolCompatibilityState,
    val message: String
)

/**
 * Evaluates the real RakNet advertisement against immutable catalog metadata.
 * This never marks a server incompatible merely because metadata is missing.
 */
object EngineProtocolCompatibility {
    fun evaluate(
        selectedBedrockVersion: String,
        advertisedBedrockVersion: String?,
        advertisedProtocol: Int?,
        expectedProtocols: List<Int>,
        advertisedEdition: String? = null,
    ): EngineProtocolCompatibilityResult {
        val expectedVersion = selectedBedrockVersion.trim().takeUnless { it.isBlank() || it.equals("AUTO", true) }
        val actualVersion = advertisedBedrockVersion?.trim()?.takeIf { it.isNotBlank() }
        val actualEdition = advertisedEdition?.trim()?.takeIf { it.isNotBlank() }

        if (actualEdition != null && !actualEdition.equals("MCPE", ignoreCase = true)) {
            return EngineProtocolCompatibilityResult(
                EngineProtocolCompatibilityState.PROTOCOL_MISMATCH,
                "RakNet responded with edition '$actualEdition', but a Bedrock server must advertise MCPE."
            )
        }

        if (expectedProtocols.isNotEmpty() && advertisedProtocol != null && advertisedProtocol !in expectedProtocols) {
            return EngineProtocolCompatibilityResult(
                EngineProtocolCompatibilityState.PROTOCOL_MISMATCH,
                "Engine advertised protocol $advertisedProtocol, but this catalog build expects ${expectedProtocols.joinToString()}."
            )
        }

        if (expectedVersion != null && actualVersion != null && !samePatch(expectedVersion, actualVersion)) {
            return EngineProtocolCompatibilityResult(
                EngineProtocolCompatibilityState.VERSION_MISMATCH,
                "Engine advertised Bedrock $actualVersion, but the selected build is catalogued for $expectedVersion."
            )
        }

        val hasExpectedProtocol = expectedProtocols.isNotEmpty()
        val hasExpectedVersion = expectedVersion != null
        if (!hasExpectedProtocol && !hasExpectedVersion) {
            return EngineProtocolCompatibilityResult(
                EngineProtocolCompatibilityState.UNKNOWN,
                "RakNet responded, but this catalog entry has no verified protocol or Bedrock version metadata."
            )
        }
        if (
            (hasExpectedProtocol && advertisedProtocol == null) ||
            (hasExpectedVersion && actualVersion == null)
        ) {
            return EngineProtocolCompatibilityResult(
                EngineProtocolCompatibilityState.UNKNOWN,
                "RakNet responded, but the server advertisement omitted metadata needed for verification."
            )
        }

        return EngineProtocolCompatibilityResult(
            EngineProtocolCompatibilityState.VERIFIED,
            "RakNet advertisement matches the selected engine metadata."
        )

    }

    private fun samePatch(expected: String, actual: String): Boolean {
        fun parts(value: String): List<Int> = value.split('.').mapNotNull { it.toIntOrNull() }
        val left = parts(expected)
        val right = parts(actual)
        if (left.isEmpty() || right.isEmpty()) return expected.equals(actual, true)
        return left == right
    }
}
