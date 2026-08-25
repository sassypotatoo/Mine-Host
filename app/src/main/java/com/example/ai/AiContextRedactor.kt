package com.example.ai

object AiContextRedactor {
    private val secretAssignments = Regex(
        """(?i)(access[_-]?token|refresh[_-]?token|authorization|api[_-]?key|apikey|password|secret|service[_-]?role|private[_-]?key|rcon[_-]?password)\s*[:=]\s*([^\s,;]+)"""
    )
    private val bearer = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/-]+=*""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b""")
    private val androidPaths = Regex("""/(data|storage|sdcard|mnt)/[^\s"']+""")
    private val ipv4 = Regex(
        """\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?::\d{1,5})?\b"""
    )
    private val ipv6 = Regex(
        """(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])"""
    )
    private val uuid = Regex(
        """(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b"""
    )
    private val email = Regex(
        """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""
    )
    private val playerAssignment = Regex(
        """(?i)\b(player|username|name)(\s*[:=]\s*)([A-Za-z0-9_]{3,24})\b"""
    )
    private val playerLifecycle = Regex(
        """(?i)\b([A-Za-z0-9_]{3,24})(\s+(?:joined the game|joined the server|left the game|left the server|logged in|logged out|connected|disconnected))\b"""
    )

    fun redactLine(raw: String): String = raw
        .take(16_384)
        .replace(secretAssignments) { "${it.groupValues[1]}=[REDACTED]" }
        .replace(bearer, "Bearer [REDACTED]")
        .replace(jwt, "[REDACTED_JWT]")
        .replace(androidPaths, "[REDACTED_PATH]")
        .replace(ipv4, "[REDACTED_ADDRESS]")
        .replace(ipv6, "[REDACTED_ADDRESS]")
        .replace(uuid, "[REDACTED_UUID]")
        .replace(email, "[REDACTED_EMAIL]")
        .replace(playerAssignment) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED_PLAYER]" }
        .replace(playerLifecycle) { "[REDACTED_PLAYER]${it.groupValues[2]}" }

    fun redactLines(lines: List<String>, maxLines: Int = 200): List<String> =
        lines.takeLast(maxLines.coerceAtLeast(0)).map(::redactLine)
}
