package com.example.friends

import org.json.JSONArray
import org.json.JSONObject

/**
 * Second authorization boundary on the owner phone.
 * Supabase RLS controls who may enqueue an action; this policy controls what the
 * owner device will actually send to the real server process.
 */
object RemoteCommandPolicy {
    private val operatorAllowedCommands = setOf(
        "list", "say", "tell", "msg", "kick", "ban", "ban-ip", "pardon", "pardon-ip",
        "whitelist", "time", "weather", "difficulty", "gamerule", "tp", "teleport",
        "give", "effect", "clear", "gamemode", "setworldspawn", "spawnpoint",
    )
    private val forbiddenRemotePrefixes = setOf(
        "stop", "reload", "op", "deop", "save-off", "save-on", "save-all",
        "plugins", "version", "ver", "about",
    )

    fun validateRequest(type: RemoteActionType, payload: JSONObject): Result<Unit> = runCatching {
        if (type != RemoteActionType.SEND_COMMAND) return@runCatching
        val command = normalize(payload.optString("command"))
        require(command.isNotBlank()) { "A command is required." }
        require(command.length <= 512) { "The command is too long." }
        require(command.none { it < ' ' || it == '\u007f' }) {
            "Commands may not contain control characters."
        }
        val root = command.substringBefore(' ').lowercase()
        require(root !in forbiddenRemotePrefixes) {
            "This command cannot be sent remotely. Use a dedicated confirmed action instead."
        }
    }

    fun mayExecuteCommand(role: ServerRole, rawCommand: String): Boolean {
        val command = normalize(rawCommand)
        if (command.isBlank() || command.length > 512 || command.any { it < ' ' || it == '\u007f' }) {
            return false
        }
        val root = command.substringBefore(' ').lowercase()
        if (root in forbiddenRemotePrefixes) return false
        return when (role) {
            ServerRole.OWNER, ServerRole.ADMIN -> true
            ServerRole.OPERATOR -> root in operatorAllowedCommands
            ServerRole.VIEWER -> false
        }
    }

    private fun normalize(command: String): String = command.trim().removePrefix("/").trim()
}

object RemoteActionRedactor {
    private val sensitiveKeys = setOf(
        "access_token", "refresh_token", "token", "authorization", "password", "secret",
        "api_key", "apikey", "service_role", "private_key", "file_path", "absolute_path",
    )

    fun redact(input: JSONObject): JSONObject {
        val output = JSONObject()
        input.keys().forEach { key ->
            val value = input.opt(key)
            output.put(key, when {
                sensitiveKeys.any { key.contains(it, ignoreCase = true) } -> "[REDACTED]"
                value is JSONObject -> redact(value)
                value is JSONArray -> redactArray(value)
                value is String -> value.take(8_192)
                else -> value
            })
        }
        return output
    }

    private fun redactArray(input: JSONArray): JSONArray = JSONArray().also { output ->
        for (index in 0 until input.length()) {
            val value = input.opt(index)
            output.put(when (value) {
                is JSONObject -> redact(value)
                is JSONArray -> redactArray(value)
                is String -> value.take(8_192)
                else -> value
            })
        }
    }
}
