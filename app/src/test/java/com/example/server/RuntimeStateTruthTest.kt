package com.example.server

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeStateTruthTest {
    @Test fun deadStoppingProcessBecomesStopped() {
        assertEquals(ServerStatus.STOPPED, RuntimeStateTruth.reconcile(ServerStatus.STOPPING, false))
    }

    @Test fun deadOnlineProcessBecomesCrashed() {
        assertEquals(ServerStatus.CRASHED, RuntimeStateTruth.reconcile(ServerStatus.ONLINE, false))
    }

    @Test fun deadStartingProcessBecomesFailed() {
        assertEquals(ServerStatus.FAILED, RuntimeStateTruth.reconcile(ServerStatus.STARTING, false))
    }

    @Test fun liveOnlineProcessRemainsOnline() {
        assertEquals(ServerStatus.ONLINE, RuntimeStateTruth.reconcile(ServerStatus.ONLINE, true))
    }

    @Test fun preparingWithoutProcessRemainsPreparing() {
        assertEquals(ServerStatus.PREPARING, RuntimeStateTruth.reconcile(ServerStatus.PREPARING, false))
    }
    @Test fun deadProvisionalWorldBecomesWorldLoadFailed() {
        assertEquals(
            ServerStatus.WORLD_LOAD_FAILED,
            RuntimeStateTruth.reconcile(ServerStatus.WORLD_PROVISIONALLY_LOADED, false),
        )
    }

    @Test fun deadVerifiedWorldBecomesCrashed() {
        assertEquals(ServerStatus.CRASHED, RuntimeStateTruth.reconcile(ServerStatus.WORLD_VERIFIED, false))
    }

    @Test fun deadNetworkReadyProcessBecomesFailed() {
        assertEquals(ServerStatus.FAILED, RuntimeStateTruth.reconcile(ServerStatus.NETWORK_READY, false))
    }

}
