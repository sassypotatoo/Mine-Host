package com.example.friends

import org.json.JSONObject

enum class ServerRole(val wireValue: String) {
    OWNER("owner"),
    ADMIN("admin"),
    OPERATOR("operator"),
    VIEWER("viewer");

    companion object {
        fun fromWire(value: String): ServerRole = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown server role: $value")
    }
}

enum class RemoteActionType(val wireValue: String, val destructive: Boolean) {
    START_SERVER("start_server", false),
    STOP_SERVER("stop_server", true),
    RESTART_SERVER("restart_server", true),
    SEND_COMMAND("send_command", false),
    VIEW_STATUS("view_status", false),
    VIEW_PLAYERS("view_players", false),
    VIEW_LOGS("view_logs", false),
}

enum class RemoteActionStatus(val wireValue: String) {
    PENDING("pending"),
    CLAIMED("claimed"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    REJECTED("rejected"),
    EXPIRED("expired"),
}

data class ServerMember(
    val serverUuid: String,
    val userId: String,
    val role: ServerRole,
    val createdAt: String?,
)

data class ServerInvitation(
    val id: String,
    val serverUuid: String,
    val invitedEmail: String,
    val role: ServerRole,
    val status: String,
    val expiresAt: String,
)

data class RemoteAction(
    val id: String,
    val serverUuid: String,
    val requestedBy: String,
    val type: RemoteActionType,
    val payload: JSONObject,
    val confirmationRequired: Boolean,
    val confirmedAt: String?,
    val status: RemoteActionStatus,
    val createdAt: String,
    val expiresAt: String,
)

data class FriendPermissionSet(
    val canStartStop: Boolean,
    val canSendCommands: Boolean,
    val canViewStatus: Boolean,
    val canViewPlayers: Boolean,
    val canViewLogs: Boolean,
    val canManageMembers: Boolean,
) {
    companion object {
        fun forRole(role: ServerRole): FriendPermissionSet = when (role) {
            ServerRole.OWNER -> FriendPermissionSet(true, true, true, true, true, true)
            ServerRole.ADMIN -> FriendPermissionSet(true, true, true, true, true, true)
            ServerRole.OPERATOR -> FriendPermissionSet(false, true, true, true, true, false)
            ServerRole.VIEWER -> FriendPermissionSet(false, false, true, true, false, false)
        }
    }
}
