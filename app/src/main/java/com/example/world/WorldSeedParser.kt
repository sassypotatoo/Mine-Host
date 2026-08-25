package com.example.world

import java.security.SecureRandom

sealed interface SeedParseResult {
    data class Valid(val value: Long) : SeedParseResult
    data class Invalid(val message: String) : SeedParseResult
}

object WorldSeedFactory {
    private val secureRandom = SecureRandom()

    fun next(previous: Long? = null): Long {
        var candidate: Long
        do {
            candidate = secureRandom.nextLong()
        } while (previous != null && candidate == previous)
        return candidate
    }
}

object WorldSeedParser {
    fun parse(raw: String): SeedParseResult {
        if (raw.isEmpty()) {
            return SeedParseResult.Invalid("Seed cannot be empty")
        }
        if (raw.any { it == '\n' || it == '\r' || it == '\t' || it == '\u0000' || it == '=' || it == ';' || it.isISOControl() }) {
            return SeedParseResult.Invalid("Seed contains invalid control or injection characters")
        }
        if (raw.trim { it == ' ' }.startsWith("+")) {
            return SeedParseResult.Invalid("Leading plus sign is not allowed")
        }

        val trimmed = raw.trim { it == ' ' }
        if (trimmed.isEmpty()) {
            return SeedParseResult.Invalid("Seed cannot be empty")
        }
        if (trimmed.contains(" ")) {
            return SeedParseResult.Invalid("Seed cannot contain embedded whitespace")
        }
        if (trimmed.drop(1).contains("-")) {
            return SeedParseResult.Invalid("Multiple minus signs are not allowed")
        }
        if (trimmed == "-") {
            return SeedParseResult.Invalid("Seed must contain at least one digit")
        }

        return try {
            val value = trimmed.toLong()
            SeedParseResult.Valid(value)
        } catch (_: NumberFormatException) {
            SeedParseResult.Invalid("Seed is outside valid 64-bit signed integer range")
        }
    }
}
