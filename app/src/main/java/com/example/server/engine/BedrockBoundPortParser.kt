package com.example.server.engine

object BedrockBoundPortParser {
    private val pattern = Regex(
        """Opening server on\s+(?:\[[0-9a-fA-F:]+\]|[0-9a-fA-F:.]+):(\d+)(?:\b|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(line: String): Int? {
        val raw = pattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return raw.takeIf { it in 1..65535 }
    }
}
