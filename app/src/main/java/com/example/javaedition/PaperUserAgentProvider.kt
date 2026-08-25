package com.example.javaedition

import com.example.BuildConfig

object PaperUserAgentProvider {

    fun value(): String {
        val version = try { BuildConfig.VERSION_NAME } catch (e: Throwable) { "unknown" }
        val contact = try { BuildConfig.PAPERMC_CONTACT } catch (e: Throwable) { "" }
        
        return try {
            buildUserAgent(version, contact)
        } catch (e: IllegalArgumentException) {
            // Fallback for tests or unconfigured environments
            val safeContact = contact.takeIf { it.isNotBlank() } ?: "unconfigured-contact"
            "MineHost/${version.ifBlank { "unknown" }} ($safeContact)"
        }
    }

    fun buildUserAgent(version: String, contact: String): String {
        val trimmedVersion = version.trim().takeIf { it.isNotBlank() } ?: "unknown"
        val trimmedContact = contact.trim()

        require(trimmedContact.isNotBlank()) {
            "PAPERMC_CONTACT is not configured. Configure a real MineHost support URL or email before using Paper downloads."
        }

        require(
            !trimmedContact.contains("example", ignoreCase = true) &&
                !trimmedContact.contains("ai.studio", ignoreCase = true) &&
                !trimmedContact.contains("localhost", ignoreCase = true) &&
                !trimmedContact.contains("<") &&
                !trimmedContact.contains(">")
        ) {
            "PAPERMC_CONTACT contains a placeholder or invalid contact identity."
        }

        require(
            trimmedContact.startsWith("https://") ||
                trimmedContact.startsWith("http://") ||
                (trimmedContact.contains("@") && !trimmedContact.contains(" "))
        ) {
            "PAPERMC_CONTACT must be a valid support URL or email."
        }

        return "MineHost/$trimmedVersion ($trimmedContact)"
    }

    fun isTrustedPaperHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase()
        return host == "papermc.io" || host.endsWith(".papermc.io") || host == "localhost" || host == "127.0.0.1"
    }
}
