package com.example.friends

import android.content.Context
import com.example.BuildConfig
import com.example.auth.SupabaseAuthManager
import com.example.auth.SupabaseRestClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * RLS-backed friend access repository.
 *
 * It never sends phone file paths or file contents to Supabase. Remote access is
 * represented only as narrowly typed server actions that the owner device must
 * validate again before execution.
 */
class FriendAccessRepository(
    context: Context,
    private val authManager: SupabaseAuthManager,
    private val rest: SupabaseRestClient = SupabaseRestClient(authManager),
) {
    private val prefs = context.applicationContext.getSharedPreferences("minehost_device", Context.MODE_PRIVATE)

    fun localDeviceIdentifier(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    suspend fun registerDevice(displayName: String, publicKey: String? = null): Result<String> {
        val session = authManager.requireValidSession().getOrElse { return Result.failure(it) }
        val body = JSONObject()
            .put("user_id", session.user.id)
            .put("device_identifier", localDeviceIdentifier())
            .put("display_name", displayName.trim().ifBlank { "Android device" })
            .put("platform", "android")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("public_key", publicKey)
            .put("last_seen_at", Instant.now().toString())
        return rest.insert(
            table = "devices",
            body = body,
            upsert = true,
            onConflict = "user_id,device_identifier",
        ).mapCatching { response ->
            rest.parseFirstObject(response)?.getString("id")
                ?: throw IllegalStateException("Supabase did not return the device ID.")
        }
    }

    suspend fun registerOrUpdateServer(
        serverUuid: String,
        displayName: String,
        engineId: String,
        engineVersion: String?,
        deviceId: String?,
        remoteAccessEnabled: Boolean,
    ): Result<Unit> {
        UUID.fromString(serverUuid)
        val session = authManager.requireValidSession().getOrElse { return Result.failure(it) }
        val body = JSONObject()
            .put("server_uuid", serverUuid)
            .put("owner_id", session.user.id)
            .put("display_name", displayName)
            .put("engine_id", engineId)
            .put("engine_version", engineVersion)
            .put("device_id", deviceId)
            .put("remote_access_enabled", remoteAccessEnabled)
        return rest.insert(
            table = "minehost_servers",
            body = body,
            upsert = true,
            onConflict = "server_uuid",
        ).map { Unit }
    }

    suspend fun inviteFriend(
        serverUuid: String,
        email: String,
        role: ServerRole,
        expiresInDays: Long = 7L,
    ): Result<ServerInvitation> {
        require(role != ServerRole.OWNER) { "Ownership cannot be assigned through an invitation." }
        require(expiresInDays in 1L..30L) { "Invitation expiry must be between 1 and 30 days." }
        val normalizedEmail = email.trim().lowercase()
        require(EMAIL_REGEX.matches(normalizedEmail)) { "Enter a valid email address." }
        val session = authManager.requireValidSession().getOrElse { return Result.failure(it) }
        val body = JSONObject()
            .put("server_uuid", serverUuid)
            .put("invited_email", normalizedEmail)
            .put("role", role.wireValue)
            .put("invited_by", session.user.id)
            .put("expires_at", Instant.now().plus(expiresInDays, ChronoUnit.DAYS).toString())
        return rest.insert("server_invitations", body).mapCatching { text ->
            parseInvitation(rest.parseFirstObject(text)
                ?: throw IllegalStateException("Supabase did not return the invitation."))
        }
    }

    suspend fun acceptInvitation(invitationId: String): Result<ServerMember> {
        UUID.fromString(invitationId)
        return rest.rpc(
            function = "accept_server_invitation",
            body = JSONObject().put("p_invitation_id", invitationId),
        ).mapCatching { text ->
            parseMember(rest.parseFirstObject(text)
                ?: throw IllegalStateException("Supabase did not return membership details."))
        }
    }

    suspend fun listMembers(serverUuid: String): Result<List<ServerMember>> =
        rest.get(
            table = "server_members",
            query = mapOf(
                "select" to "server_uuid,user_id,role,created_at",
                "server_uuid" to "eq.$serverUuid",
                "order" to "created_at.asc",
            ),
        ).mapCatching { text -> rest.parseArray(text).objects().map(::parseMember) }

    suspend fun listInvitations(serverUuid: String): Result<List<ServerInvitation>> =
        rest.get(
            table = "server_invitations",
            query = mapOf(
                "select" to "id,server_uuid,invited_email,role,status,expires_at",
                "server_uuid" to "eq.$serverUuid",
                "order" to "created_at.desc",
            ),
        ).mapCatching { text -> rest.parseArray(text).objects().map(::parseInvitation) }

    suspend fun changeRole(serverUuid: String, userId: String, role: ServerRole): Result<Unit> {
        require(role != ServerRole.OWNER) { "Ownership cannot be assigned as a member role." }
        return rest.update(
            table = "server_members",
            filters = mapOf("server_uuid" to "eq.$serverUuid", "user_id" to "eq.$userId"),
            body = JSONObject().put("role", role.wireValue),
        ).map { Unit }
    }

    suspend fun removeAccess(serverUuid: String, userId: String): Result<Unit> =
        rest.delete(
            table = "server_members",
            filters = mapOf("server_uuid" to "eq.$serverUuid", "user_id" to "eq.$userId"),
        ).map { Unit }

    suspend fun revokeInvitation(invitationId: String): Result<Unit> =
        rest.update(
            table = "server_invitations",
            filters = mapOf("id" to "eq.$invitationId", "status" to "eq.pending"),
            body = JSONObject().put("status", "revoked"),
        ).map { Unit }

    suspend fun requestRemoteAction(
        serverUuid: String,
        type: RemoteActionType,
        payload: JSONObject = JSONObject(),
        destructiveActionConfirmed: Boolean = false,
    ): Result<RemoteAction> {
        if (type.destructive && !destructiveActionConfirmed) {
            return Result.failure(IllegalStateException("Confirm this destructive remote action first."))
        }
        RemoteCommandPolicy.validateRequest(type, payload).getOrElse { return Result.failure(it) }
        authManager.requireValidSession().getOrElse { return Result.failure(it) }
        val requested = rest.rpc(
            function = "request_remote_action",
            body = JSONObject()
                .put("p_server_uuid", serverUuid)
                .put("p_action_type", type.wireValue)
                .put("p_payload", payload),
        ).mapCatching { text ->
            parseRemoteAction(rest.parseFirstObject(text)
                ?: throw IllegalStateException("Supabase did not return the remote action."))
        }
        val action = requested.getOrElse { return Result.failure(it) }
        if (!type.destructive) return Result.success(action)
        return rest.rpc(
            function = "confirm_remote_action",
            body = JSONObject().put("p_action_id", action.id),
        ).mapCatching { text ->
            parseRemoteAction(rest.parseFirstObject(text)
                ?: throw IllegalStateException("Supabase did not return the confirmed remote action."))
        }
    }

    /** Owner-device query. RLS prevents unrelated users from reading the queue. */
    suspend fun listPendingActions(serverUuid: String): Result<List<RemoteAction>> =
        rest.get(
            table = "remote_actions",
            query = mapOf(
                "select" to "id,server_uuid,requested_by,action_type,payload,confirmation_required,confirmed_at,status,created_at,expires_at",
                "server_uuid" to "eq.$serverUuid",
                "status" to "eq.pending",
                "expires_at" to "gt.${Instant.now()}",
                "order" to "created_at.asc",
                "limit" to "25",
            ),
        ).mapCatching { text -> rest.parseArray(text).objects().map(::parseRemoteAction) }

    suspend fun claimAction(actionId: String): Result<RemoteAction> {
        UUID.fromString(actionId)
        return rest.rpc(
            function = "claim_remote_action",
            body = JSONObject().put("p_action_id", actionId),
        ).mapCatching { text ->
            parseRemoteAction(rest.parseFirstObject(text)
                ?: throw IllegalStateException("Supabase did not return the claimed action."))
        }
    }

    suspend fun completeAction(
        actionId: String,
        succeeded: Boolean,
        safeResult: JSONObject,
    ): Result<Unit> {
        val redacted = RemoteActionRedactor.redact(safeResult)
        return rest.rpc(
            function = "complete_remote_action",
            body = JSONObject()
                .put("p_action_id", actionId)
                .put("p_succeeded", succeeded)
                .put("p_result", redacted),
        ).map { Unit }
    }

    suspend fun appendAuditEvent(
        serverUuid: String,
        eventType: String,
        details: JSONObject,
    ): Result<Unit> {
        val session = authManager.requireValidSession().getOrElse { return Result.failure(it) }
        val safeType = eventType.trim().take(80).also {
            require(it.matches(Regex("[a-z0-9_]+"))) { "Invalid audit event type." }
        }
        return rest.insert(
            table = "server_audit_log",
            body = JSONObject()
                .put("server_uuid", serverUuid)
                .put("actor_id", session.user.id)
                .put("event_type", safeType)
                .put("details", RemoteActionRedactor.redact(details)),
        ).map { Unit }
    }

    private fun parseMember(json: JSONObject): ServerMember = ServerMember(
        serverUuid = json.getString("server_uuid"),
        userId = json.getString("user_id"),
        role = ServerRole.fromWire(json.getString("role")),
        createdAt = json.optString("created_at").takeIf { it.isNotBlank() },
    )

    private fun parseInvitation(json: JSONObject): ServerInvitation = ServerInvitation(
        id = json.getString("id"),
        serverUuid = json.getString("server_uuid"),
        invitedEmail = json.getString("invited_email"),
        role = ServerRole.fromWire(json.getString("role")),
        status = json.getString("status"),
        expiresAt = json.getString("expires_at"),
    )

    private fun parseRemoteAction(json: JSONObject): RemoteAction = RemoteAction(
        id = json.getString("id"),
        serverUuid = json.getString("server_uuid"),
        requestedBy = json.getString("requested_by"),
        type = RemoteActionType.entries.first { it.wireValue == json.getString("action_type") },
        payload = json.optJSONObject("payload") ?: JSONObject(),
        confirmationRequired = json.optBoolean("confirmation_required", false),
        confirmedAt = json.optString("confirmed_at").takeIf { it.isNotBlank() && it != "null" },
        status = RemoteActionStatus.entries.first { it.wireValue == json.getString("status") },
        createdAt = json.getString("created_at"),
        expiresAt = json.getString("expires_at"),
    )

    private fun JSONArray.objects(): List<JSONObject> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_identifier"
        private val EMAIL_REGEX = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    }
}
