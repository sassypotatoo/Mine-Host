package com.example.friends

import com.example.MineHostApplication
import com.example.data.ServerProfile
import com.example.data.StartServerResult
import com.example.server.ServerStatus
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Executes only narrowly typed, RLS-authorized actions on the exact server UUID. */
class RemoteAccessProcessor(private val app: MineHostApplication) {
    private val auth = app.authManager
    private val repository = app.friendAccessRepository
    private val profiles = app.profileRepository
    private val servers = app.serverManager
    private val prefs = app.getSharedPreferences("minehost_ui_prefs", android.content.Context.MODE_PRIVATE)

    suspend fun processOnce() {
        if (auth.currentSession() == null) return
        profiles.profiles.value
            .filter { prefs.getBoolean("remote_access_${it.id}", false) }
            .forEach { profile -> processServer(profile) }
    }

    private suspend fun processServer(profile: ServerProfile) {
        repository.listPendingActions(profile.id).getOrNull().orEmpty().forEach { queued ->
            val action = repository.claimAction(queued.id).getOrNull() ?: return@forEach
            val execution = runCatching { execute(profile, action) }
            val safeResult = execution.getOrElse { error ->
                JSONObject().put("message", error.message ?: "Remote action failed")
            }
            repository.completeAction(action.id, execution.isSuccess, safeResult)
            repository.appendAuditEvent(
                profile.id,
                if (execution.isSuccess) "remote_action_succeeded" else "remote_action_failed",
                JSONObject()
                    .put("action_id", action.id)
                    .put("action_type", action.type.wireValue)
                    .put("requested_by", action.requestedBy)
                    .put("processed_at", Instant.now().toString())
                    .put("result", safeResult),
            )
        }
    }

    private suspend fun execute(profile: ServerProfile, action: RemoteAction): JSONObject {
        require(action.serverUuid == profile.id) { "Action server UUID does not match the local profile" }
        if (action.type.destructive) {
            require(action.confirmationRequired && !action.confirmedAt.isNullOrBlank()) {
                "Destructive action was not confirmed"
            }
        }
        val ownerId = auth.requireValidSession().getOrElse { throw it }.user.id
        val role = if (action.requestedBy == ownerId) ServerRole.OWNER else {
            repository.listMembers(profile.id).getOrElse { throw it }
                .firstOrNull { it.userId == action.requestedBy }?.role
                ?: throw SecurityException("Requester no longer has access")
        }
        val permissions = FriendPermissionSet.forRole(role)
        return when (action.type) {
            RemoteActionType.START_SERVER -> {
                require(permissions.canStartStop) { "Role cannot start servers" }
                when (val result = servers.startServer(profile.id, profile.memoryMb)) {
                    StartServerResult.Started -> JSONObject().put("message", "Start requested")
                    is StartServerResult.OperationBlocked -> throw IllegalStateException(result.reason)
                    is StartServerResult.ValidationFailed -> throw IllegalStateException(result.reason)
                    is StartServerResult.Failed -> throw IllegalStateException(result.reason)
                }
            }
            RemoteActionType.STOP_SERVER -> {
                require(permissions.canStartStop) { "Role cannot stop servers" }
                servers.stopServer(profile.id)
                JSONObject().put("message", "Stop requested")
            }
            RemoteActionType.RESTART_SERVER -> {
                require(permissions.canStartStop) { "Role cannot restart servers" }
                require(servers.getStatus(profile.id) == ServerStatus.ONLINE) { "Server is not online" }
                servers.restartServer(profile.id)
                JSONObject().put("message", "Restart requested")
            }
            RemoteActionType.SEND_COMMAND -> {
                require(permissions.canSendCommands) { "Role cannot send commands" }
                val command = action.payload.optString("command").trim().removePrefix("/")
                require(RemoteCommandPolicy.mayExecuteCommand(role, command)) { "Command is not allowed for this role" }
                require(servers.getStatus(profile.id) == ServerStatus.ONLINE) { "Server is not online" }
                servers.sendCommand(profile.id, command)
                JSONObject().put("message", "Command accepted").put("command_root", command.substringBefore(' '))
            }
            RemoteActionType.VIEW_STATUS -> {
                require(permissions.canViewStatus) { "Role cannot view status" }
                JSONObject()
                    .put("status", servers.getStatus(profile.id).name.lowercase())
                    .put("port", servers.getRuntimePort(profile.id) ?: profile.port)
                    .put("players", servers.getPlayerNames(profile.id).size)
            }
            RemoteActionType.VIEW_PLAYERS -> {
                require(permissions.canViewPlayers) { "Role cannot view players" }
                JSONObject().put("players", JSONArray(servers.getPlayerNames(profile.id)))
            }
            RemoteActionType.VIEW_LOGS -> {
                require(permissions.canViewLogs) { "Role cannot view logs" }
                val lines = servers.getRecentLogs(profile.id, 200).map(::redactLogLine)
                JSONObject().put("logs", JSONArray(lines))
            }
        }
    }

    private fun redactLogLine(line: String): String = line
        .replace(Regex("(?i)(token|password|secret|api[_-]?key)\\s*[=:]\\s*\\S+"), "$1=[REDACTED]")
        .replace(Regex("(?i)authorization:\\s*bearer\\s+\\S+"), "Authorization: [REDACTED]")
        .take(2_000)
}
