package com.example.world

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedWorldLaunchGateTest {
    @Test fun ordinaryWorldWithoutRecordsDoesNotEnterTheGate() {
        val decision = ProtectedWorldLaunchGate.evaluate(null, false, null, false)
        assertFalse(decision.requiresProtectedLaunch)
        assertFalse(decision.fullyVerified)
    }

    @Test fun missingMarkerCannotBypassExistingProtectedRegistration() {
        val decision = ProtectedWorldLaunchGate.evaluate(
            markerState = null,
            registrationExists = true,
            protectionState = WorldProtectionState.PARTIALLY_COMPATIBLE,
            protectedOriginalValid = true,
        )
        assertTrue(decision.requiresProtectedLaunch)
        assertFalse(decision.fullyVerified)
    }

    @Test fun verifiedMarkerAloneIsNotEnough() {
        val decision = ProtectedWorldLaunchGate.evaluate(
            markerState = ImportedWorldVerificationState.VERIFIED,
            registrationExists = false,
            protectionState = null,
            protectedOriginalValid = false,
        )
        assertTrue(decision.requiresProtectedLaunch)
    }

    @Test fun bothVerifiedRecordsAndAValidOriginalLeaveTheGate() {
        val decision = ProtectedWorldLaunchGate.evaluate(
            markerState = ImportedWorldVerificationState.VERIFIED,
            registrationExists = true,
            protectionState = WorldProtectionState.COMPATIBLE,
            protectedOriginalValid = true,
        )
        assertFalse(decision.requiresProtectedLaunch)
        assertTrue(decision.fullyVerified)
    }
}
