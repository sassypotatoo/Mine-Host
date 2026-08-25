package com.example.friends

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteCommandPolicyTest {
    @Test
    fun viewerCannotSendCommands() {
        assertFalse(RemoteCommandPolicy.mayExecuteCommand(ServerRole.VIEWER, "list"))
    }

    @Test
    fun operatorCanUseAllowlistedCommandButCannotStopServer() {
        assertTrue(RemoteCommandPolicy.mayExecuteCommand(ServerRole.OPERATOR, "say hello"))
        assertFalse(RemoteCommandPolicy.mayExecuteCommand(ServerRole.OPERATOR, "stop"))
    }

    @Test
    fun commandPayloadRejectsControlCharacters() {
        val result = RemoteCommandPolicy.validateRequest(
            RemoteActionType.SEND_COMMAND,
            JSONObject().put("command", "say hello\nstop"),
        )
        assertTrue(result.isFailure)
    }
}
