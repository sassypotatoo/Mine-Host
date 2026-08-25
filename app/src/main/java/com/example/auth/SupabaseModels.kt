package com.example.auth

import org.json.JSONObject

data class SupabaseUser(
    val id: String,
    val email: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val createdAt: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("email", email)
        .put("display_name", displayName)
        .put("avatar_url", avatarUrl)
        .put("created_at", createdAt)

    companion object {
        fun fromJson(json: JSONObject): SupabaseUser {
            val metadata = json.optJSONObject("user_metadata") ?: JSONObject()
            return SupabaseUser(
                id = json.getString("id"),
                email = json.nullableString("email"),
                displayName = metadata.nullableString("full_name")
                    ?: metadata.nullableString("name")
                    ?: metadata.nullableString("user_name"),
                avatarUrl = metadata.nullableString("avatar_url")
                    ?: metadata.nullableString("picture"),
                createdAt = json.nullableString("created_at"),
            )
        }
    }
}

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val tokenType: String,
    val user: SupabaseUser,
) {
    fun isExpiringSoon(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        expiresAtEpochSeconds <= nowEpochSeconds + 90L

    fun toJson(): JSONObject = JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("expires_at", expiresAtEpochSeconds)
        .put("token_type", tokenType)
        .put("user", user.toJson())

    companion object {
        fun fromJson(json: JSONObject): SupabaseSession {
            val expiresAt = if (json.has("expires_at")) {
                json.optLong("expires_at")
            } else {
                System.currentTimeMillis() / 1000L + json.optLong("expires_in", 3600L)
            }
            return SupabaseSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresAtEpochSeconds = expiresAt,
                tokenType = json.optString("token_type", "bearer"),
                user = SupabaseUser.fromJson(json.getJSONObject("user")),
            )
        }
    }
}

sealed interface AuthState {
    data class NotConfigured(val missing: List<String>) : AuthState
    data object SignedOut : AuthState
    data class Authorizing(val provider: String = "google") : AuthState
    data class SignedIn(val session: SupabaseSession) : AuthState
    data class Error(val message: String, val recoverable: Boolean = true) : AuthState
}

private fun JSONObject.nullableString(key: String): String? =
    optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
