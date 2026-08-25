package com.example.server.players

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/** A real entry read from the selected server's persisted ban list. */
data class BannedPlayerEntry(
    val name: String,
    val reason: String? = null,
    val source: String? = null,
    val expires: String? = null,
)

data class ServerAccessListState(
    val whitelist: List<String> = emptyList(),
    val bannedPlayers: List<BannedPlayerEntry> = emptyList(),
    val whitelistFilePresent: Boolean = false,
    val bannedPlayersFilePresent: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Reads only files owned by the exact server directory. Nukkit-family engines normally persist
 * whitelist and name-ban data in white-list.txt and banned-players.json. Alternate JSON names are
 * accepted as a compatibility fallback, but no player is ever synthesized from counts or UI state.
 */
object ServerAccessListRepository {
    private const val MAX_ACCESS_LIST_BYTES = 2L * 1024L * 1024L
    private val BAN_CONTAINER_METADATA_KEYS = setOf(
        "version", "format", "updated", "updatedat", "created", "createdat", "metadata",
    )

    fun read(serverDir: File): ServerAccessListState {
        val whitelistFile = firstExisting(serverDir, "white-list.txt", "whitelist.json")
        val bannedFile = firstExisting(serverDir, "banned-players.json", "banned_players.json")
        val errors = mutableListOf<String>()

        val whitelist = whitelistFile?.let { file ->
            runCatching { readWhitelist(file) }
                .onFailure { errors += "Whitelist could not be read: ${it.message ?: "invalid file"}" }
                .getOrDefault(emptyList())
        }.orEmpty()

        val bannedPlayers = bannedFile?.let { file ->
            runCatching { readBannedPlayers(file) }
                .onFailure { errors += "Ban list could not be read: ${it.message ?: "invalid file"}" }
                .getOrDefault(emptyList())
        }.orEmpty()

        return ServerAccessListState(
            whitelist = whitelist,
            bannedPlayers = bannedPlayers,
            whitelistFilePresent = whitelistFile != null,
            bannedPlayersFilePresent = bannedFile != null,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    private fun firstExisting(serverDir: File, vararg names: String): File? {
        val canonicalRoot = serverDir.canonicalFile
        return names.asSequence()
            .map { File(canonicalRoot, it).canonicalFile }
            .firstOrNull { candidate ->
                candidate.parentFile == canonicalRoot && candidate.isFile
            }
    }

    private fun readWhitelist(file: File): List<String> {
        val text = safeRead(file)
        val trimmed = text.trimStart()
        val names = when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> parseNamesFromJson(JSONTokener(text).nextValue())
            else -> text.lineSequence().mapNotNull(::parseWhitelistLine).toList()
        }
        return distinctNames(names)
    }

    private fun parseWhitelistLine(raw: String): String? {
        val line = raw.trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return null
        val separator = line.indexOf('=')
        if (separator < 0) return cleanName(line)

        val name = cleanName(line.substring(0, separator)) ?: return null
        val enabled = line.substring(separator + 1).trim().lowercase()
        return name.takeUnless { enabled in setOf("false", "0", "off", "no") }
    }

    private fun readBannedPlayers(file: File): List<BannedPlayerEntry> {
        val root = JSONTokener(safeRead(file)).nextValue()
        val entries = mutableListOf<BannedPlayerEntry>()
        collectBans(root, fallbackName = null, output = entries)
        return entries
            .filter { cleanName(it.name) != null }
            .map { it.copy(name = requireNotNull(cleanName(it.name))) }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
    }

    private fun collectBans(value: Any?, fallbackName: String?, output: MutableList<BannedPlayerEntry>) {
        when (value) {
            is JSONArray -> repeat(value.length()) { index ->
                collectBans(value.opt(index), fallbackName = null, output = output)
            }
            is JSONObject -> {
                val directName = cleanName(value.optString("name"))
                    ?: cleanName(value.optString("player"))
                    ?: cleanName(value.optString("username"))
                    ?: cleanName(fallbackName.orEmpty())
                if (directName != null) {
                    output += BannedPlayerEntry(
                        name = requireNotNull(directName),
                        reason = optionalString(value, "reason"),
                        source = optionalString(value, "source"),
                        expires = optionalString(value, "expires") ?: optionalString(value, "expiration"),
                    )
                    return
                }

                val containerKeys = listOf("entries", "bans", "players", "values")
                var consumedContainer = false
                containerKeys.forEach { key ->
                    if (value.has(key)) {
                        consumedContainer = true
                        collectBans(value.opt(key), fallbackName = null, output = output)
                    }
                }
                if (consumedContainer) return

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    when (child) {
                        is JSONObject, is JSONArray -> collectBans(child, fallbackName = key, output = output)
                        is String -> if (key.lowercase() !in BAN_CONTAINER_METADATA_KEYS) {
                            cleanName(key)?.let { name ->
                                output += BannedPlayerEntry(
                                    name = name,
                                    reason = child.trim().takeIf { it.isNotBlank() && !it.equals("true", true) },
                                )
                            }
                        }
                        true, 1 -> cleanName(key)?.let { output += BannedPlayerEntry(it) }
                    }
                }
            }
            is String -> cleanName(value)?.let { output += BannedPlayerEntry(it) }
        }
    }

    private fun parseNamesFromJson(value: Any?): List<String> {
        val names = mutableListOf<String>()
        fun collect(item: Any?, fallback: String? = null) {
            when (item) {
                is JSONArray -> repeat(item.length()) { collect(item.opt(it)) }
                is JSONObject -> {
                    val direct = cleanName(item.optString("name"))
                        ?: cleanName(item.optString("player"))
                        ?: cleanName(item.optString("username"))
                    if (direct != null) {
                        names += direct
                    } else {
                        val keys = item.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val child = item.opt(key)
                            when (child) {
                                is JSONObject, is JSONArray -> collect(child, key)
                                true, "true", 1 -> cleanName(key)?.let(names::add)
                            }
                        }
                    }
                }
                is String -> cleanName(item)?.let(names::add)
                true, 1 -> cleanName(fallback.orEmpty())?.let(names::add)
            }
        }
        collect(value)
        return names
    }

    private fun safeRead(file: File): String {
        require(file.length() <= MAX_ACCESS_LIST_BYTES) { "file exceeds 2 MB safety limit" }
        return file.readText(Charsets.UTF_8)
    }

    private fun optionalString(json: JSONObject, key: String): String? =
        json.optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", true) }

    private fun cleanName(value: String): String? {
        val cleaned = value.trim().trim('"')
        if (cleaned.isBlank() || cleaned.length > 64 || cleaned.any(Char::isISOControl)) return null
        return cleaned
    }

    private fun distinctNames(names: List<String>): List<String> = names
        .mapNotNull(::cleanName)
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
}
