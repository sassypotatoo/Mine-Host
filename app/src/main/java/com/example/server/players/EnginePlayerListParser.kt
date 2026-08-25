package com.example.server.players

data class PlayerLogParseResult(
    val authoritativeNames: List<String>? = null,
    val pendingExpectedCount: Int? = null,
    val joinedName: String? = null,
    val leftName: String? = null,
)

/** Parses only real console output. It never synthesizes names from player counts. */
object EnginePlayerListParser {
    private val ansi = Regex("\\u001B\\[[;\\d]*[A-Za-z]")
    private val minecraftColor = Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE)
    private val bracketPrefix = Regex("^\\s*\\[[^]]*]\\s*(?::|-)?\\s*")
    private val levelPrefix = Regex("^\\s*(?:TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\s*(?::|-)?\\s*", RegexOption.IGNORE_CASE)

    private val commonHeaders = listOf(
        Regex("""there\s+are\s+(\d+)\s*/\s*\d+\s+players?\s+online\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE),
        Regex("""there\s+are\s+(\d+)\s+of\s+\d+\s+players?\s+online\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE),
        Regex("""online\s+players?\s*(?:\(|:)?\s*(\d+)\s*/\s*\d+\)?\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE),
        Regex("""players?\s+online\s*(?:\(|:)?\s*(\d+)\s*/\s*\d+\)?\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE),
        Regex("""(?:online\s+)?players?\s*\(\s*(\d+)\s*\)\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*/\s*\d+\s+players?\s+online\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE),
    )
    private val nukkitMotHeaders = commonHeaders + listOf(
        Regex("""players?\s*:\s*(\d+)\s*/\s*\d+\s*(?::|-)\s*(.*)$""", RegexOption.IGNORE_CASE),
        Regex("""online\s*:\s*(\d+)\s+players?\s*(?::|-)\s*(.*)$""", RegexOption.IGNORE_CASE),
    )
    private val pm1eHeaders = commonHeaders + listOf(
        Regex("""online\s*:\s*(\d+)\s*/\s*\d+\s*(?::|-)\s*(.*)$""", RegexOption.IGNORE_CASE),
        Regex("""player\s+list\s*\(\s*(\d+)\s*\)\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE),
    )

    private val joinPatterns = listOf(
        Regex("""^player\s+(.+?)\s+(?:connected|joined)(?:\s+the\s+(?:game|server))?\b""", RegexOption.IGNORE_CASE),
        Regex("""^player\s+connected\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?)(?:\[[^]]+])?\s+(?:joined\s+the\s+game|joined\s+the\s+server|logged\s+in(?:\s+with\s+entity\s+id\s+\d+)?|connected\s+from\s+\S+)\b""", RegexOption.IGNORE_CASE),
    )
    private val leavePatterns = listOf(
        Regex("""^player\s+(.+?)\s+(?:disconnected|left|quit)(?:\s+the\s+(?:game|server))?\b""", RegexOption.IGNORE_CASE),
        Regex("""^player\s+disconnected\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^(.+?)(?:\[[^]]+])?\s+(?:left\s+the\s+game|left\s+the\s+server|quit\s+the\s+game|logged\s+out|disconnected)(?:\b|\s|:)""", RegexOption.IGNORE_CASE),
    )

    fun parse(engineId: String, line: String, pendingExpectedCount: Int? = null): PlayerLogParseResult {
        val payload = consolePayload(line)
        val headers = when (engineId) {
            "nukkit-mot" -> nukkitMotHeaders
            "bedrock_nukkit" -> pm1eHeaders
            "bedrock_power_nukkit", "bedrock_power_nukkit_x" -> commonHeaders
            else -> commonHeaders
        }

        headers.firstNotNullOfOrNull { it.find(payload) }?.let { match ->
            val reportedCount = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@let
            val inlineNames = parseNames(match.groupValues.getOrNull(2).orEmpty(), allowSingleSpacedName = true)
            if (reportedCount == 0) {
                return PlayerLogParseResult(authoritativeNames = emptyList())
            }
            if (inlineNames.size == reportedCount) {
                return PlayerLogParseResult(authoritativeNames = inlineNames)
            }
            return PlayerLogParseResult(pendingExpectedCount = reportedCount)
        }

        findName(joinPatterns, payload)?.let { return PlayerLogParseResult(joinedName = it) }
        findName(leavePatterns, payload)?.let { return PlayerLogParseResult(leftName = it) }

        if (pendingExpectedCount != null && pendingExpectedCount > 0) {
            val followUp = payload.replace(
                Regex("""^(?:online\s+players?|players?|player\s+list)\s*:\s*""", RegexOption.IGNORE_CASE),
                "",
            )
            val names = parseNames(followUp, allowSingleSpacedName = pendingExpectedCount == 1)
            if (names.size == pendingExpectedCount) {
                return PlayerLogParseResult(authoritativeNames = names)
            }
        }

        return PlayerLogParseResult()
    }

    private fun findName(patterns: List<Regex>, payload: String): String? {
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(payload)?.groupValues?.getOrNull(1)?.let(::cleanName)?.takeIf(::isPlayerName)
        }
    }

    private fun parseNames(value: String, allowSingleSpacedName: Boolean): List<String> {
        val cleaned = value.trim().trim('[', ']', '(', ')')
        if (cleaned.isBlank() || cleaned.equals("none", true) || cleaned.equals("no players", true)) {
            return emptyList()
        }
        val hasSeparator = cleaned.contains(',') || cleaned.contains(';')
        if (!hasSeparator && !allowSingleSpacedName && cleaned.any(Char::isWhitespace)) {
            return emptyList()
        }
        val parts = if (hasSeparator) cleaned.split(',', ';') else listOf(cleaned)
        return parts.map(::cleanName)
            .filter(::isPlayerName)
            .distinctBy { it.lowercase() }
    }

    private fun cleanName(value: String): String = value
        .substringBefore("[/")
        .substringBefore(" [/")
        .trim()
        .trimStart('-', '*', '•')
        .trim()
        .trimEnd('.', ':')

    private fun isPlayerName(value: String): Boolean {
        if (value.isBlank() || value.length > 64) return false
        if (!(value.first().isLetterOrDigit() || value.first() == '_')) return false
        if (!value.all { it.isLetterOrDigit() || it in setOf('_', ' ', '.', '-') }) return false
        val lower = value.lowercase()
        return lower !in setOf(
            "info", "warn", "warning", "error", "debug", "trace", "server", "console",
            "there are", "online players", "players online", "no players", "player list",
        )
    }

    private fun consolePayload(line: String): String {
        var value = minecraftColor.replace(ansi.replace(line, ""), "").trim()
        repeat(4) {
            val withoutBracket = bracketPrefix.replaceFirst(value, "")
            value = if (withoutBracket == value) value else withoutBracket.trim()
        }
        value = levelPrefix.replaceFirst(value, "").trim()
        value = value.trimStart(':', '-', '>', ' ')
        return value
    }
}
