package com.example.world

data class ProtectedWorldLaunchDecision(
    val requiresProtectedLaunch: Boolean,
    val fullyVerified: Boolean,
    val reason: String?,
)

/**
 * A world leaves the protected gate only when both independent records agree and the immutable
 * original still verifies. Missing, stale, corrupt, or one-sided records always fail closed.
 */
object ProtectedWorldLaunchGate {
    fun evaluate(
        markerState: ImportedWorldVerificationState?,
        registrationExists: Boolean,
        protectionState: WorldProtectionState?,
        protectedOriginalValid: Boolean,
    ): ProtectedWorldLaunchDecision {
        val hasProtectionRecord = registrationExists || markerState != null
        if (!hasProtectionRecord) {
            return ProtectedWorldLaunchDecision(
                requiresProtectedLaunch = false,
                fullyVerified = false,
                reason = null,
            )
        }

        val fullyVerified = markerState == ImportedWorldVerificationState.VERIFIED &&
            protectionState == WorldProtectionState.COMPATIBLE &&
            protectedOriginalValid
        return if (fullyVerified) {
            ProtectedWorldLaunchDecision(
                requiresProtectedLaunch = false,
                fullyVerified = true,
                reason = null,
            )
        } else {
            val reason = when {
                markerState == null -> "Imported-world verification marker is missing"
                !registrationExists -> "Protected-world registration is missing"
                markerState != ImportedWorldVerificationState.VERIFIED ->
                    "Imported-world marker is ${markerState.name}"
                protectionState != WorldProtectionState.COMPATIBLE ->
                    "Protected-world metadata is ${protectionState?.name ?: "unreadable"}"
                !protectedOriginalValid -> "Immutable protected original failed verification"
                else -> "Protected-world verification records disagree"
            }
            ProtectedWorldLaunchDecision(
                requiresProtectedLaunch = true,
                fullyVerified = false,
                reason = reason,
            )
        }
    }
}
